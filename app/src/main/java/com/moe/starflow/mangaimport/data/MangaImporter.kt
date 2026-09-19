package com.moe.starflow.mangaimport.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/**
 * 导入逻辑：SAF 源 → 复制进应用专属目录（getExternalFilesDir/manga_import，Android/data 下）→ 解封面 → 产出 ImportedManga。
 *
 * 「导入即复制」策略彻底解除对源文件的依赖，源文件（SAF uri）用完即弃。
 *
 * 职责边界：本对象只做**文件搬运**并回报进度，**不写清单**——入库由 [ImportManager] 统一做，
 * 这样「导入中的占位卡片」与「最终条目」由同一处编排，id 也能提前预留。
 *
 * 取消：所有复制循环每轮 `ensureActive()`；协程被取消时抛 CancellationException → 走异常分支
 * 删掉半成品目录再抛出（不会留下「复制了一半又没有条目」的垃圾）。
 */
object MangaImporter {

    private const val TAG = "MangaImporter"

    private const val COVER_WIDTH = 300

    /** 复制缓冲区（也是进度上报的字节粒度）。 */
    private const val COPY_BUFFER = 64 * 1024

    /** 进度上报节流：距上次上报不足此毫秒数就跳过（避免每 64KB 刷一次 StateFlow）。 */
    private const val REPORT_INTERVAL_MS = 80L

    fun nextId(context: Context): Long =
        (ImportedMangaStore.load(context).maxOfOrNull { it.id } ?: 0L) + 1L

    /**
     * 条目时间戳。**它同时是翻译记录的身份指纹**（[ImportedManga.translationKey]），必须唯一：
     * 漫画 id 会被复用（`nextId` = 清单最大 id + 1），删书后立刻重导就会拿到同一个 id ——
     * 若时间戳也相同，新书会被判定成"同一本书"，旧译图/旧记录会映射到新书上。
     *
     * 所以取 `max(now, 清单里最大 addedAt + 1)`：同一毫秒内连删带导也保证单调递增，
     * 代价只是一次清单读取（导入本来就要读）。
     */
    private fun freshAddedAt(context: Context): Long {
        val maxExisting = ImportedMangaStore.load(context).maxOfOrNull { it.addedAt } ?: 0L
        return maxOf(System.currentTimeMillis(), maxExisting + 1L)
    }

    private fun coverDir(context: Context): File =
        StorageDirStore.coversDir(context)

    /** 某次导入的本地目录（也是取消/失败时的清理对象）。 */
    private fun localDir(context: Context, id: Long): File =
        File(StorageDirStore.root(context), id.toString())

    // ===== 导入压缩包 zip/cbz =====

    /**
     * 导入一个 zip/cbz 压缩包（ACTION_OPEN_DOCUMENT 返回的 contentUri）。
     *
     * @param id 调用方**预留**的条目 id（并发导入时由 [ImportManager] 保证唯一）
     * @param onProgress 进度回调，在 IO 线程调用
     */
    suspend fun importArchive(
        context: Context,
        contentUri: Uri,
        id: Long = nextId(context),
        onProgress: (ImportProgress) -> Unit = {}
    ): ImportedManga = withContext(Dispatchers.IO) {
        val name = DocumentFile.fromSingleUri(context, contentUri)?.name
            ?: contentUri.lastPathSegment
            ?: context.getString(R.string.default_manga_title, id.toString())
        val title = name.substringBeforeLast('.', name)

        val manga = importArchiveToFile(context, contentUri, id, name, title, onProgress)
            ?: throw IllegalStateException("导入失败")
        LogCollector.i(TAG, "复制压缩包完成: ${manga.title} (${manga.pageCount} 页)")
        manga
    }

    private suspend fun importArchiveToFile(
        context: Context,
        contentUri: Uri,
        id: Long,
        name: String,
        title: String,
        onProgress: (ImportProgress) -> Unit
    ): ImportedManga? {
        val destDir = localDir(context, id).apply { mkdirs() }
        try {
            val archiveFile = File(destDir, name)
            // 源文件大小（可能查不到 → 0 → 进度条转不确定态）
            val totalBytes = DocumentFile.fromSingleUri(context, contentUri)?.length() ?: 0L
            onProgress(ImportProgress(ImportPhase.COPYING, totalBytes = totalBytes))

            context.contentResolver.openInputStream(contentUri)?.use { input ->
                FileOutputStream(archiveFile).use { output ->
                    var last = 0L
                    copyStream(input, output) { copied ->
                        val now = System.currentTimeMillis()
                        if (now - last >= REPORT_INTERVAL_MS) {
                            last = now
                            onProgress(
                                ImportProgress(
                                    ImportPhase.COPYING,
                                    copiedBytes = copied,
                                    totalBytes = totalBytes
                                )
                            )
                        }
                    }
                    onProgress(
                        ImportProgress(
                            ImportPhase.COPYING,
                            copiedBytes = archiveFile.length(),
                            totalBytes = totalBytes
                        )
                    )
                }
            } ?: return null

            val pageNames = listZipImageEntries(archiveFile)
            val coverPath = extractCoverFromZipFile(context, archiveFile, pageNames, id)
            return ImportedManga(
                id = id,
                title = title,
                localRoot = archiveFile.absolutePath,
                isArchive = true,
                coverPath = coverPath,
                pageCount = pageNames.size,
                addedAt = freshAddedAt(context),
                sizeBytes = archiveFile.length()
            )
        } catch (e: Exception) {
            // 半成品必须清掉：留着的话下次导入复用同一个 id，残件与新文件混在同一目录
            destDir.deleteRecursively()
            LogCollector.e(TAG, "导入压缩包失败，已清理 $destDir: ${e.message}", e)
            throw e
        }
    }

    // ===== 导入图片文件夹 =====

    /**
     * 导入一个图片文件夹（ACTION_OPEN_DOCUMENT_TREE 返回的 treeUri，**整个夹 = 一部**）。
     *
     * 两趟：先递归枚举（扫描，数出总张数）→ 按相对路径自然排序 → 再逐个复制（可取消、报进度）。
     * 排序在复制前完成，落盘顺序即阅读顺序。
     *
     * ⚠️ 夹里**没有图片**不是异常：照旧产出一条 0 页漫画（由 [ImportManager] 决定是否提示）。
     */
    suspend fun importDirectory(
        context: Context,
        treeUri: Uri,
        id: Long = nextId(context),
        onProgress: (ImportProgress) -> Unit = {}
    ): ImportedManga = withContext(Dispatchers.IO) {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("无法读取目录: $treeUri")

        importDirectoryToFile(context, rootDoc, id, onProgress)
            ?: throw IllegalStateException("导入失败")
    }

    private suspend fun importDirectoryToFile(
        context: Context,
        rootDoc: DocumentFile,
        id: Long,
        onProgress: (ImportProgress) -> Unit
    ): ImportedManga? {
        val destDir = localDir(context, id).apply { mkdirs() }
        try {
            // ===== 阶段一：扫描（枚举图片 + 排序）。总量未知 → 不确定进度 =====
            onProgress(ImportProgress(ImportPhase.SCANNING))
            val entries = mutableListOf<Pair<DocumentFile, String>>()
            collectImageDocs(rootDoc, "", entries)

            val pages = entries
                .sortedWith(compareBy(ArchivedMangaReader.naturalComparator()) { it.second })
                .map { it.second }
            val totalFiles = entries.size
            onProgress(ImportProgress(ImportPhase.COPYING, 0, totalFiles))

            // ===== 阶段二：复制（按排好的页序落盘） =====
            var total = 0L
            var done = 0
            var last = 0L
            entries.forEach { (doc, rel) ->
                coroutineContext.ensureActive()
                val target = File(destDir, rel)
                target.parentFile?.mkdirs()
                context.contentResolver.openInputStream(doc.uri)?.use { input ->
                    // 单文件内部也可取消：大页图不至于让「取消」等满一整页
                    FileOutputStream(target).use { output -> copyStream(input, output) {} }
                }
                total += target.length()
                done++
                val now = System.currentTimeMillis()
                if (now - last >= REPORT_INTERVAL_MS || done == totalFiles) {
                    last = now
                    onProgress(ImportProgress(ImportPhase.COPYING, done, totalFiles, total))
                }
            }

            val coverPath = pages.firstOrNull()
                ?.let { File(destDir, it) }
                ?.let { extractThumbnailFromFile(context, it, id) }

            return ImportedManga(
                id = id,
                title = rootDoc.name ?: context.getString(R.string.default_manga_title, id.toString()),
                localRoot = destDir.absolutePath,
                isArchive = false,
                coverPath = coverPath,
                pageCount = pages.size,
                addedAt = freshAddedAt(context),
                sizeBytes = total
            )
        } catch (e: Exception) {
            destDir.deleteRecursively()
            LogCollector.e(TAG, "导入目录失败，已清理 $destDir: ${e.message}", e)
            throw e
        }
    }

    // ===== 复制工具 =====

    /**
     * 递归枚举 DocumentFile 树里的图片，收集**相对路径**（ch1/001.jpg）。
     *
     * ⚠️ 收的是相对路径而不是文件名：分章节目录里 ch1/001.jpg 与 ch2/001.jpg 同名，只记 basename
     * 会让 File(destDir, it) 指不到文件 → 封面永远是灰底占位，排序也会把两章的同名页混在一起。
     *
     * ⚠️ 递归里每层 `ensureActive()`：`DocumentFile.listFiles()` 是逐层 IPC，大文件夹（上万条目）
     * 的扫描本身就要几十秒 —— 不检查取消的话用户点了 ✕ 仍要等扫描跑完，看起来像"取消没反应"。
     */
    private suspend fun collectImageDocs(
        doc: DocumentFile,
        prefix: String,
        out: MutableList<Pair<DocumentFile, String>>
    ) {
        doc.listFiles().forEach { child ->
            coroutineContext.ensureActive()
            if (child.isDirectory) {
                val dirName = child.name ?: return@forEach
                collectImageDocs(child, "$prefix$dirName/", out)
            } else if (child.isFile && ArchivedMangaReader.isImageFile(child.name ?: "")) {
                val name = child.name ?: return@forEach
                out.add(child to "$prefix$name")
            }
        }
    }

    /**
     * 流式复制：每轮 `ensureActive()`（取消响应）+ 累积字节回调（进度）。
     * [onBytes] 每 64KB 调一次，节流由调用方负责。
     */
    private suspend fun copyStream(
        input: InputStream,
        output: OutputStream,
        onBytes: (Long) -> Unit
    ) {
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

    // ===== zip 页枚举 =====

    private fun listZipImageEntries(archive: File): List<String> {
        val out = mutableListOf<String>()
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (!e.isDirectory && ArchivedMangaReader.isImageFile(e.name)) out.add(e.name)
            }
        }
        return ArchivedMangaReader.sortNaturally(out)
    }

    // ===== 封面提取 =====

    private fun extractCoverFromZipFile(
        context: Context,
        archive: File,
        pages: List<String>,
        id: Long
    ): String? {
        val first = pages.firstOrNull() ?: return null
        return ZipFile(archive).use { zip ->
            val entry = zip.getEntry(first) ?: return null
            zip.getInputStream(entry).use { input ->
                val bmp = BitmapFactory.decodeStream(input) ?: return null
                extractThumbnail(context, bmp, id)
            }
        }
    }

    /** 目录封面：从本地图片文件解码后落盘缩略图。 */
    private fun extractThumbnailFromFile(context: Context, src: File, id: Long): String? {
        val bmp = BitmapFactory.decodeFile(src.absolutePath) ?: return null
        return extractThumbnail(context, bmp, id)
    }

    /** 用外部图片（用户自选）替换某部漫画封面，返回新封面绝对路径（同一 id 旧封面被清理）。 */
    fun replaceCover(context: Context, mangaId: Long, imageUri: Uri): String? {
        val bmp = context.contentResolver.openInputStream(imageUri)?.use { BitmapFactory.decodeStream(it) }
            ?: return null
        return extractThumbnail(context, bmp, mangaId)
    }

    private fun extractThumbnail(context: Context, bmp: Bitmap, id: Long): String? {
        val dir = coverDir(context).apply { mkdirs() }
        // 唯一命名 covers/<id>_<ts>.jpg：避免 Glide 按路径缓存导致重导后显示旧图
        dir.listFiles()?.filter { it.name.startsWith("${id}_") }?.forEach { it.delete() }
        val ts = System.currentTimeMillis()
        val out = File(dir, "${id}_$ts.jpg")
        val scaled = Bitmap.createScaledBitmap(bmp, COVER_WIDTH, COVER_WIDTH, true)
        FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (scaled !== bmp) scaled.recycle()
        return out.absolutePath
    }
}
