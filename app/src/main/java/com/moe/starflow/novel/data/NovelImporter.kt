package com.moe.starflow.novel.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.moe.starflow.mangaimport.data.ArchivedMangaReader
import com.moe.starflow.mangaimport.data.ImportPhase
import com.moe.starflow.mangaimport.data.ImportProgress
import com.moe.starflow.novel.model.NovelFormat
import com.moe.starflow.novel.parser.NovelFormatDetector
import com.moe.starflow.novel.parser.NovelParsers
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * 小说导入：SAF 源 → 复制进 `novel_import/<id>/` → 解析 → 封面 → [ImportedNovel]。
 *
 * 职责边界与 `MangaImporter` 一致：**只做搬运 + 解析**，不写清单（入库由
 * [NovelImportManager] 统一做，这样占位卡片与最终条目由同一处编排，id 也能提前预留）。
 *
 * 取消：复制循环每轮 `ensureActive()`；协程被取消时抛 CancellationException → 走异常分支
 * 删掉半成品目录再抛出，不留「复制了一半又没有条目」的垃圾。
 */
object NovelImporter {

    private const val TAG = "NovelImporter"
    private const val COVER_SIZE = 300
    private const val COPY_BUFFER = 64 * 1024
    private const val REPORT_INTERVAL_MS = 80L

    /** 目录导入时认可的文件扩展名（先按扩展名粗筛，避免对每个文件都去试解压）。 */
    private val DIR_IMPORT_EXTS = setOf("txt", "epub", "html", "htm", "xhtml", "zip")

    fun nextId(context: Context): Long =
        (NovelStore.load(context).maxOfOrNull { it.id } ?: 0L) + 1L

    /**
     * 条目时间戳。**同时是译文身份指纹**（[ImportedNovel.translationKey]），必须单调递增：
     * 书籍 id = 清单最大 id + 1，删书后立刻重导就会拿到同一个 id —— 若时间戳也相同，
     * 新书会被判定成「同一本书」，旧译文会映射到新书上。
     *
     * 所以取 `max(now, 清单里最大 addedAt + 1)`：同一毫秒内连删带导也保证单调递增，
     * 代价只是一次清单读取（导入本来就要读）。
     */
    private fun freshAddedAt(context: Context): Long {
        val maxExisting = NovelStore.load(context).maxOfOrNull { it.addedAt } ?: 0L
        return maxOf(System.currentTimeMillis(), maxExisting + 1L)
    }

    /**
     * 导入单个文件。
     *
     * @param id 调用方**预留**的 id（并发导入时由 [NovelImportManager] 保证唯一）
     * @throws NovelWrongFormatException 该文件不是受支持的文本格式
     */
    suspend fun importFile(
        context: Context,
        uri: Uri,
        id: Long = nextId(context),
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportedNovel = withContext(Dispatchers.IO) {
        val displayName = runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull()
            ?: fileNameOf(uri)
            ?: "book_$id"
        val fallbackTitle = displayName.substringBeforeLast('.', displayName).ifBlank { "book_$id" }
        importOne(context, uri, id, displayName, fallbackTitle, onProgress)
    }

    /**
     * 从 uri 里抠出文件名。
     *
     * ⚠️ 不能直接用 `lastPathSegment`：它对 `content://…/primary:Download/book.txt` 会给出
     * `primary:Download/book.txt`，对 Windows 上的 `file:///C:\dir\book.txt`（反斜杠不是 URI
     * 分隔符）更会给出**整条绝对路径** —— 拿它当目标文件名会拼出 `<目录>/C:\...\book.txt`
     * 这种非法路径，复制直接失败。
     */
    private fun fileNameOf(uri: Uri): String? =
        uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() }

    /**
     * 导入一个文件夹：**夹内每个文本文件 = 一部书**（与漫画的「整个夹 = 一部」不同）。
     *
     * 只取**直接子文件**，不递归、不认子目录 —— 一堆子目录里各放一本的情况交给用户逐个导入，
     * 递归会让「夹里放了一堆杂七杂八」的目录产生一堆莫名其妙的书。
     *
     * 非文本文件（图片等）静默跳过；一个都没有时返回空表，由调用方发
     * `Failed(NO_TEXT_CHAPTER)` 提示，不静默。
     *
     * @param nextId **由调用方提供的 id 分配器**（[NovelImportManager] 传自己的 `reserveIds`）。
     *   不在这里自己递增 id：夹内文件数要扫描后才知道，先从一个起始值硬数会让并发导入
     *   撞进同一段 id 区间。让上层统一分配，唯一性由它保证。
     */
    suspend fun importDirectory(
        context: Context,
        treeUri: Uri,
        nextId: () -> Long,
        onProgress: (ImportProgress) -> Unit = {},
    ): List<ImportedNovel> = withContext(Dispatchers.IO) {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("无法读取目录: $treeUri")
        importCandidates(context, candidateDocs(rootDoc), nextId, onProgress)
    }

    /**
     * 目录里可导入的文本文件（按自然序）。
     *
     * 只调一次 `listFiles()`：它是逐层 IPC，SAF 上很贵；在循环里为每个名字再捞一遍
     * 会变成 O(n²) 次 IPC，几百个文件的目录直接卡死。
     */
    internal fun candidateDocs(rootDoc: DocumentFile): List<Pair<String, Uri>> {
        val docs = rootDoc.listFiles().filter { it.isFile }
        val byName = docs.groupBy { it.name }
        return sortImportableNames(docs.mapNotNull { it.name }).mapNotNull { name ->
            byName[name]?.firstOrNull()?.let { name to it.uri }
        }
    }

    /** 文件名是否是本次目录导入认的文本类型。 */
    internal fun isImportableFileName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in DIR_IMPORT_EXTS

    /** 过滤掉非文本文件并按**自然序**排列（字典序会把 `10.txt` 排到 `2.txt` 前面）。 */
    internal fun sortImportableNames(names: List<String>): List<String> =
        ArchivedMangaReader.sortNaturally(names.filter { isImportableFileName(it) })

    /**
     * 逐个导入：**每个候选文件 = 一部书**，id 由 [nextId] 分配。
     *
     * 拆成独立函数是为了能脱离 SAF 单测「每个文件一部书 / id 由调用方分配 / 坏文件跳过」
     * 这几条规则 —— `DocumentFile.fromTreeUri` 需要真实 DocumentsProvider，单测里给不出。
     */
    internal suspend fun importCandidates(
        context: Context,
        candidates: List<Pair<String, Uri>>,
        nextId: () -> Long,
        onProgress: (ImportProgress) -> Unit = {},
    ): List<ImportedNovel> {
        val out = mutableListOf<ImportedNovel>()
        try {
            for ((name, uri) in candidates) {
                coroutineContext.ensureActive()
                val id = nextId()
                val title = name.substringBeforeLast('.', name).ifBlank { "book_$id" }
                try {
                    out.add(importOne(context, uri, id, name, title, onProgress))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 单个文件坏了不该让整夹导入失败：跳过它，其余照常导入
                    LogCollector.w(TAG, "目录内文件导入失败，跳过: $name", e)
                }
            }
        } catch (e: Exception) {
            // ⚠️ 中途取消/失败时，**已经导入成功但还没入库**的那几本会变成孤儿目录：
            // 调用方是在本函数返回后才逐条 NovelStore.add 的。必须在这里把它们删掉。
            out.forEach { deleteBookFiles(context, it.id) }
            throw e
        }
        return out
    }

    private suspend fun importOne(
        context: Context,
        uri: Uri,
        id: Long,
        displayName: String,
        fallbackTitle: String,
        onProgress: (ImportProgress) -> Unit,
    ): ImportedNovel {
        val destDir = NovelStorageDir.bookDir(context, id).apply { mkdirs() }
        try {
            // ===== 复制 =====
            val dest = File(destDir, displayName)
            val totalBytes = runCatching { DocumentFile.fromSingleUri(context, uri)?.length() }.getOrNull() ?: 0L
            onProgress(ImportProgress(ImportPhase.COPYING, totalBytes = totalBytes))
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    var last = 0L
                    copyStream(input, output) { copied ->
                        val now = System.currentTimeMillis()
                        if (now - last >= REPORT_INTERVAL_MS) {
                            last = now
                            onProgress(
                                ImportProgress(ImportPhase.COPYING, copiedBytes = copied, totalBytes = totalBytes)
                            )
                        }
                    }
                }
            } ?: throw java.io.FileNotFoundException("无法打开: $uri")
            coroutineContext.ensureActive()

            // ===== 解析 =====
            onProgress(ImportProgress(ImportPhase.SCANNING))
            val format = NovelFormatDetector.detect(dest)
                ?: throw NovelWrongFormatException(isManga = looksLikeMangaArchive(dest))
            val book = NovelParsers.forFormat(format).parse(dest)
            if (book.chapters.isEmpty()) throw IllegalStateException(ERROR_NO_TEXT_CHAPTER)

            // ===== 封面 =====
            val coverPath = book.coverBytes?.let { decodeCover(context, it, id) }
                ?: placeholderCover(context, id, book.title.ifBlank { fallbackTitle })

            return ImportedNovel(
                id = id,
                title = book.title.ifBlank { fallbackTitle },
                author = book.author,
                localRoot = dest.absolutePath,
                format = format,
                coverPath = coverPath,
                chapterCount = book.chapters.size,
                addedAt = freshAddedAt(context),
                sizeBytes = dest.length(),
            )
        } catch (e: Exception) {
            // 半成品必须清掉：留着的话下次导入复用同一个 id，残件与新文件混在同一目录
            deleteBookFiles(context, id)
            LogCollector.e(TAG, "小说导入失败，已清理 $destDir: ${e.message}", e)
            throw e
        }
    }

    /**
     * 删除某部小说的本地文件与封面（取消 / 失败 / 导入中途中止时的清理）。
     *
     * 收敛成一处：三个调用点各写一遍迟早会漏掉封面那一半，留下孤儿缩略图。
     */
    fun deleteBookFiles(context: Context, id: Long) {
        runCatching { NovelStorageDir.bookDir(context, id).deleteRecursively() }
            .onFailure { LogCollector.w(TAG, "删除书籍目录失败 id=$id", it) }
        runCatching {
            // ⚠️ 只清 `novel_` 前缀的 —— 同 id 的**漫画**封面（无前缀）不能误删
            NovelStorageDir.coversDir(context).listFiles()
                ?.filter { it.name.startsWith("novel_${id}_") }
                ?.forEach { it.delete() }
        }.onFailure { LogCollector.w(TAG, "删除封面失败 id=$id", it) }
    }

    /** 判「这是不是漫画包」，用于给出对得上的提示（「请到漫画书架导入」 vs 「格式不支持」）。 */
    private fun looksLikeMangaArchive(file: File): Boolean = runCatching {
        ArchivedMangaReader.listImageFilesInArchive(file).isNotEmpty()
    }.getOrDefault(false)

    /**
     * 流式复制：每轮 `ensureActive()`（取消响应）+ 累积字节回调（进度）。
     */
    private suspend fun copyStream(input: InputStream, output: OutputStream, onBytes: (Long) -> Unit) {
        val buf = ByteArray(COPY_BUFFER)
        var copied = 0L
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            copied += n
            onBytes(copied)
        }
    }

    // ===== 封面 =====

    private fun decodeCover(context: Context, bytes: ByteArray, id: Long): String? = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { saveCover(context, it, id) }
    } catch (e: Exception) {
        null
    }

    /**
     * 没有内嵌封面时用**书名首字**生成占位封面。
     *
     * 不做的话书架上一片灰底，几十本书认不出哪本是哪本 —— 而书名首字足够区分绝大多数情况。
     */
    fun placeholderCover(context: Context, id: Long, title: String): String {
        val bmp = Bitmap.createBitmap(COVER_SIZE, COVER_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(PLACEHOLDER_BG)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = COVER_SIZE * 0.42f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val ch = title.trim().take(1).ifEmpty { "?" }
        val fm = paint.fontMetrics
        canvas.drawText(ch, COVER_SIZE / 2f, COVER_SIZE / 2f - (fm.ascent + fm.descent) / 2f, paint)
        return saveCover(context, bmp, id)
    }

    /** 用外部图片（用户自选）替换某部小说封面，返回新封面绝对路径。 */
    fun replaceCover(context: Context, novelId: Long, imageUri: Uri): String? {
        val bmp = context.contentResolver.openInputStream(imageUri)?.use { BitmapFactory.decodeStream(it) }
            ?: return null
        return runCatching { saveCover(context, bmp, novelId) }.getOrNull()
    }

    private fun saveCover(context: Context, bmp: Bitmap, id: Long): String {
        val dir = NovelStorageDir.coversDir(context).apply { mkdirs() }
        cleanCover(context, id)
        // 唯一命名 `novel_<id>_<ts>.jpg`：避免 Glide 按路径缓存导致重导后仍显示旧封面
        val out = File(dir, "novel_${id}_${System.currentTimeMillis()}.jpg")
        val scaled = if (bmp.width == COVER_SIZE && bmp.height == COVER_SIZE) bmp
        else Bitmap.createScaledBitmap(bmp, COVER_SIZE, COVER_SIZE, true)
        FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (scaled !== bmp) {
            scaled.recycle()
            bmp.recycle()
        }
        return out.absolutePath
    }

    /** ⚠️ 只清 `novel_` 前缀的 —— 同 id 的漫画封面（无前缀）不能误删。 */
    private fun cleanCover(context: Context, id: Long) {
        runCatching {
            NovelStorageDir.coversDir(context).listFiles()
                ?.filter { it.name.startsWith("novel_${id}_") }
                ?.forEach { it.delete() }
        }
    }

    private const val PLACEHOLDER_BG = 0xFF4A5A6A.toInt()

    /** 与 `NovelImportManager` 的失败归类对齐的常量（避免两边字符串各写一份而漂移）。 */
    internal const val ERROR_NO_TEXT_CHAPTER = "NO_TEXT_CHAPTER"
    internal const val ERROR_ENCRYPTED = "ENCRYPTED"
}
