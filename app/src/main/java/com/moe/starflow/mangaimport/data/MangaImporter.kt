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

    /** rar/7z 解压出来的图片目录名（在 `manga_import/<id>/` 下）。 */
    const val EXTRACT_DIR = "pages"

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

    // ===== 导入压缩包 zip/cbz/cbr/7z =====

    /**
     * 导入一个压缩包（ACTION_OPEN_DOCUMENT 返回的 contentUri）。
     *
     * 分两条链路，按**内容魔数**判定（不看扩展名，见 [ArchiveTypes]）：
     * - **zip/cbz**：原样留存，`ZipFile` 随机读（当前实现，最快的路径）
     * - **rar/cbr/7z**：导入时用 libarchive **解压成目录**，之后与文件夹导入走同一条阅读链路
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
        val name = safeSegment(
            DocumentFile.fromSingleUri(context, contentUri)?.name
                ?: contentUri.lastPathSegment
                ?: ""
        ) ?: context.getString(R.string.default_manga_title, id.toString())
        val title = name.substringBeforeLast('.', name)

        val manga = importArchiveToFile(context, contentUri, id, name, title, onProgress)
            ?: throw IllegalStateException("导入失败")
        LogCollector.i(TAG, "复制压缩包完成: ${manga.title} (${manga.pageCount} 页, ${manga.chapterCount} 章)")
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
            // ⚠️ 文件名同样不可信（SAF 显示名可能带 `../`/分隔符）→ 只取最后一段
            val archiveFile = File(destDir, safeSegment(name) ?: "archive")
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

            return when (ArchiveTypes.detect(archiveFile)) {
                ArchiveKind.ZIP -> importZipArchive(context, archiveFile, id, title)
                ArchiveKind.RAR, ArchiveKind.SEVEN_ZIP ->
                    importExtractedArchive(context, archiveFile, destDir, id, title, onProgress)
                ArchiveKind.UNKNOWN -> throw IllegalArgumentException("不支持的压缩包格式: $name")
            }
        } catch (e: Exception) {
            // 半成品必须清掉：留着的话下次导入复用同一个 id，残件与新文件混在同一目录
            destDir.deleteRecursively()
            LogCollector.e(TAG, "导入压缩包失败，已清理 $destDir: ${e.message}", e)
            throw e
        }
    }

    /** zip/cbz：保留原包，页序/章节交给 [MangaChapterSplitter]（与阅读侧同源）。 */
    private fun importZipArchive(
        context: Context,
        archiveFile: File,
        id: Long,
        title: String
    ): ImportedManga {
        val split = MangaChapterSplitter.split(listZipImageEntries(archiveFile))
        val coverPath = extractCoverFromZipFile(context, archiveFile, split.keys, id)
        return ImportedManga(
            id = id,
            title = title,
            localRoot = archiveFile.absolutePath,
            isArchive = true,
            coverPath = coverPath,
            pageCount = split.keys.size,
            addedAt = freshAddedAt(context),
            sizeBytes = archiveFile.length(),
            // 包内的 ComicInfo.xml / meta.json → 自动填简介（只填这一次，之后用户可手改）
            description = ComicMetadataParser.descriptionOf(context, readZipMetadataTexts(archiveFile)),
            chapters = split.chapters
        )
    }

    /**
     * rar/cbr/7z：用 libarchive 解压成 `manga_import/<id>/pages/` 目录，之后按**文件夹导入**处理。
     *
     * ⚠️ 解压成功后**删掉原压缩包副本**：rar/7z 没有中央目录、随机读一页要顺序扫整包，
     * 留着读取体验极差；而漫画本来就是「导入即复制」，解压结果才是真正要读的东西。
     * 不删的话同一本书要占两份空间（几百 MB 级）。
     */
    private suspend fun importExtractedArchive(
        context: Context,
        archiveFile: File,
        destDir: File,
        id: Long,
        title: String,
        onProgress: (ImportProgress) -> Unit
    ): ImportedManga {
        val extractDir = File(destDir, EXTRACT_DIR)
        val extracted = LibArchiveExtractor.extract(
            archive = archiveFile,
            destDir = extractDir,
            // 图片之外**顺带把元数据文件也解出来**（ComicInfo.xml / meta.json，都是几 KB）：
            // rar/7z 没法像 zip 那样随手读一个条目，解出来再读是最省事、也最省 CPU 的做法。
            // 解出来后**留在 pages/ 里不删** —— 阅读侧只枚举图片，不受影响；留着还能让用户
            // 在 Android/data 下看到原始元数据
            filter = { ArchivedMangaReader.isImageFile(it) || ComicMetadataParser.isMetadataEntry(it) },
            onProgress = { consumed, total ->
                // ⚠️ 参数按**名字**给：ImportProgress 的前两位是「文件数」(Int)，
                // 顺序传 Long 会被推断成文件数 → 编译不过 / 语义错
                onProgress(
                    ImportProgress(ImportPhase.COPYING, copiedBytes = consumed, totalBytes = total)
                )
            }
        )
        archiveFile.delete()
        // 解压完重新枚举（保留子目录结构 → 分章白拿）
        // ⚠️ 包内没有图片**不是异常**：照旧产出一条 0 页漫画，由 ImportManager 决定提示
        // （与 zip 链路一致 —— 那里也是 pageCount=0 走 NoImages 事件）
        val split = MangaChapterSplitter.split(ArchivedMangaReader.listImageFilesInDir(extractDir))
        if (split.keys.isEmpty()) {
            LogCollector.w(TAG, "压缩包里没有图片: ${archiveFile.name} (extracted=$extracted)")
        }
        val coverPath = split.keys.firstOrNull()
            ?.let { File(extractDir, it) }
            ?.let { extractThumbnailFromFile(context, it, id) }
        val size = extractDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return ImportedManga(
            id = id,
            title = title,
            localRoot = extractDir.absolutePath,
            isArchive = false,
            coverPath = coverPath,
            pageCount = split.keys.size,
            addedAt = freshAddedAt(context),
            sizeBytes = size,
            // 解出来的 ComicInfo.xml / meta.json → 自动填简介（与 zip 链路同一条规则）
            description = ComicMetadataParser.descriptionOf(context, readDirMetadataTexts(extractDir)),
            chapters = split.chapters
        )
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

            // ⚠️ 章节切分在这里就定下来：它同时产出**权威页序**（按章分组、章内自然排序），
            // 落盘顺序即阅读顺序 —— 阅读侧 `ReaderPageSource` 用同一个切分器，两边不会错位。
            val split = MangaChapterSplitter.split(entries.map { it.second })
            val pageOrder = split.keys.withIndex().associate { (i, key) -> key to i }
            val ordered = entries.sortedBy { pageOrder[it.second] ?: Int.MAX_VALUE }
            val totalFiles = ordered.size
            onProgress(ImportProgress(ImportPhase.COPYING, 0, totalFiles))

            // ===== 阶段二：复制（按排好的页序落盘） =====
            var total = 0L
            var done = 0
            var last = 0L
            ordered.forEach { (doc, rel) ->
                coroutineContext.ensureActive()
                val target = File(destDir, rel)
                // ⚠️ 纵深防御：哪怕上游漏了消毒，也**绝不允许**写到这本书自己的目录之外
                // （越界会覆盖 models/、covers/、别人书的页图）。
                if (!target.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
                    LogCollector.w(TAG, "跳过越界目标: $rel")
                    return@forEach
                }
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

            // 封面取**阅读顺序的第一页**：有第0章时就是第0章的第一张，没有则第1章的第一张
            // （用户口径：「封面选择从第0章找，没有图片再去第一章找」）
            val coverPath = split.keys.firstOrNull()
                ?.let { File(destDir, it) }
                ?.let { extractThumbnailFromFile(context, it, id) }

            return ImportedManga(
                id = id,
                title = rootDoc.name ?: context.getString(R.string.default_manga_title, id.toString()),
                localRoot = destDir.absolutePath,
                isArchive = false,
                coverPath = coverPath,
                pageCount = split.keys.size,
                addedAt = freshAddedAt(context),
                sizeBytes = total,
                chapters = split.chapters
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
                val dirName = safeSegment(child.name ?: return@forEach) ?: return@forEach
                collectImageDocs(child, "$prefix$dirName/", out)
            } else if (child.isFile && ArchivedMangaReader.isImageFile(child.name ?: "")) {
                val name = safeSegment(child.name ?: return@forEach) ?: return@forEach
                out.add(child to "$prefix$name")
            }
        }
    }

    /**
     * SAF（DocumentsProvider）给的**显示名是不可信输入**：恶意/故障的 provider 可以返回
     * `../../models/x.gguf` 这类名字，直接拼成相对路径就会写到 `manga_import/<id>/` **之外**
     * （覆盖 models/、covers/、别人书的页图）。只保留最后一段文件名，并挡掉 `.`/`..`/空白/控制字符。
     *
     * @return 安全的路径片段；名字完全不可用时返回 null（调用方跳过这一项）
     */
    private fun safeSegment(raw: String): String? {
        val last = raw.replace('\\', '/').substringAfterLast('/').trim()
        if (last.isEmpty() || last == "." || last == "..") return null
        val cleaned = last.filter { it.code >= 0x20 }
        return cleaned.ifEmpty { null }
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

    // ===== 元数据（ComicInfo.xml / meta.json → 简介） =====

    /**
     * 读 zip 内的元数据条目文本。
     *
     * ⚠️ 按 **basename** 匹配而不是硬编码路径：不同工具写的位置不一样
     * （根目录 / 书名目录里 / 与图片同层），大小写也不统一（`ComicInfo.xml` / `comicinfo.xml`）。
     * 读取失败、条目太大、不是文本，一律**当没有元数据**（只影响简介，绝不影响导入成败）。
     */
    internal fun readZipMetadataTexts(archive: File): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory || !ComicMetadataParser.isMetadataEntry(e.name)) continue
                    val text = zip.getInputStream(e).use { readTextCapped(it, e.size) } ?: continue
                    out.add(e.name to text)
                }
            }
        } catch (ex: Exception) {
            LogCollector.w(TAG, "读取压缩包元数据失败（忽略）: ${ex.message}")
        }
        return out
    }

    /** 读目录里的元数据条目文本（rar/7z 解压出来的 `pages/`，见 [importExtractedArchive]）。 */
    internal fun readDirMetadataTexts(dir: File): List<Pair<String, String>> {
        if (!dir.isDirectory) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        dir.walkTopDown().forEach { f ->
            if (!f.isFile || !ComicMetadataParser.isMetadataEntry(f.name)) return@forEach
            val text = try {
                f.inputStream().use { readTextCapped(it, f.length()) }
            } catch (ex: Exception) {
                null
            } ?: return@forEach
            out.add(f.relativeTo(dir).path.replace('\\', '/') to text)
        }
        return out
    }

    /**
     * 按上限读文本（UTF-8）。超过 [ComicMetadataParser.MAX_METADATA_BYTES] 返回 null ——
     * 有条目自称 `meta.json` 其实是几百 MB 的东西时不能整个读进内存。
     */
    private fun readTextCapped(input: InputStream, expectedSize: Long): String? {
        if (expectedSize > ComicMetadataParser.MAX_METADATA_BYTES) return null
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > ComicMetadataParser.MAX_METADATA_BYTES) return null
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
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
