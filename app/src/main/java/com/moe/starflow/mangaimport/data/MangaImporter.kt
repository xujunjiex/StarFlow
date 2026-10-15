package com.moe.starflow.mangaimport.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.SafDocumentMetadata
import com.moe.starflow.utils.SafDocumentTreeReader
import com.moe.starflow.R
import com.moe.starflow.library.BookMetadataReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/**
 * 导入逻辑：SAF 源 → 复制进当前书库的 manga_import/<UUID>/ → 解封面 → 产出 ImportedManga。
 *
 * 「导入即复制」策略彻底解除对源文件的依赖，源文件（SAF uri）用完即弃。
 *
 * 职责边界：本对象只做**文件搬运**并回报进度，**不写清单**——入库由 [ImportManager] 统一做，
 * 导入进度与最终入库由同一处编排，id 也能提前预留。
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

    fun nextId(context: Context): Long = com.moe.starflow.library.LibrarySessions.current(context).nextId("manga")

    /** Import timestamp; processing identity uses the library UUID reference. */
    private fun freshAddedAt(context: Context): Long {
        val maxExisting = ImportedMangaStore.load(context).maxOfOrNull { it.addedAt } ?: 0L
        return maxOf(System.currentTimeMillis(), maxExisting + 1L)
    }

    private fun coverDir(context: Context): File =
        StorageDirStore.coversDir(context)

    /** Parse an already-present app file/folder without copying or deleting the user's source. */
    internal suspend fun inspectLocalSource(
        context: Context,
        source: File,
        id: Long,
        title: String,
        coverPathOverride: String? = null,
        sourceNode: com.moe.starflow.library.LibraryNode? = null,
    ): ImportedManga = withContext(Dispatchers.IO) {
        if (source.isDirectory) {
            return@withContext inspectImageDirectory(context, source, id, title, coverPathOverride)
        }
        when (ArchiveTypes.detect(source)) {
            ArchiveKind.ZIP -> importZipArchive(context, source, id, title, coverPathOverride)
            ArchiveKind.PDF -> importPdf(context, source, id, title, coverPathOverride)
            ArchiveKind.RAR, ArchiveKind.SEVEN_ZIP ->
                inspectRawArchive(context, source, id, title, coverPathOverride)
            ArchiveKind.UNKNOWN -> throw IllegalArgumentException("不支持的漫画文件内容：${source.name}")
        }.let { indexContainer(context, it, sourceNode) }
    }

    private fun indexContainer(context: Context, manga: ImportedManga, sourceNode: com.moe.starflow.library.LibraryNode? = null): ImportedManga {
        val file = File(manga.localRoot)
        val session = com.moe.starflow.library.LibrarySessions.current(context)
        val index = MangaSourceIndex.build(file.parentFile!!, onlyPath = file.name, sourceNode = sourceNode)
        return manga.copy(sourceIndexPath = index.store(session, session.uuid("manga", manga.id)), sourceName = manga.title)
    }

    private fun inspectImageDirectory(
        context: Context,
        dir: File,
        id: Long,
        title: String,
        coverPathOverride: String?,
    ): ImportedManga {
        val index = MangaSourceIndex.build(dir)
        val session = com.moe.starflow.library.LibrarySessions.current(context)
        val indexPath = index.store(session, session.uuid("manga", id))
        val cover = coverPathOverride ?: com.moe.starflow.mangaimport.reader.ReaderPageSource(false, dir.path, sourceIndex = index).let { source ->
            try { source.loadFull(0)?.let { bitmap ->
                try { val coverFile = File(coverDir(context), "${id}_${System.nanoTime()}.jpg")
                    coverFile.parentFile!!.mkdirs()
                    val thumb = android.graphics.Bitmap.createScaledBitmap(bitmap, COVER_WIDTH.coerceAtMost(bitmap.width),
                        (bitmap.height.toLong() * COVER_WIDTH.coerceAtMost(bitmap.width) / bitmap.width).toInt().coerceAtLeast(1), true)
                    try { coverFile.outputStream().use { thumb.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) } }
                    finally { if (thumb !== bitmap) thumb.recycle() }
                    coverFile.path
                } finally { bitmap.recycle() }
            } } finally { source.close() }
        }
        return ImportedManga(id = id, title = title, localRoot = dir.canonicalPath, isArchive = false,
            coverPath = cover, pageCount = index.pages.size, addedAt = freshAddedAt(context),
            sizeBytes = index.pages.distinctBy { it.path }.sumOf { it.bytes.coerceAtLeast(0) },
            description = ComicMetadataParser.descriptionOf(context, BookMetadataReader.directoryTexts(dir) + index.pages.distinctBy { it.path }
                .filter { MangaSourceIndex.supported(it.path) }.flatMap { BookMetadataReader.sourceTexts(File(dir, it.path)) }),
            metadataVersion = BookMetadataReader.VERSION, chapters = index.chapters,
            sourceIndexPath = indexPath, sourceName = title)
    }

    private fun inspectRawArchive(
        context: Context, source: File, id: Long, title: String, coverPathOverride: String? = null,
    ): ImportedManga {
        LibraryArchiveReader.acquire(source).use { lease ->
            val reader = lease.reader
            val split = MangaChapterSplitter.split(reader.entries.filter(ArchivedMangaReader::isImageFile))
            val cover = coverPathOverride ?: split.keys.firstOrNull()?.let {
                reader.openEntry(it).use { input -> android.graphics.BitmapFactory.decodeStream(input) }
            }?.let { bitmap -> try { extractThumbnail(context, bitmap, id) } finally { bitmap.recycle() } }
            val metadata = reader.entries.filter(ComicMetadataParser::isMetadataEntry).mapNotNull { name ->
                runCatching { reader.openEntry(name, ComicMetadataParser.MAX_METADATA_BYTES.toLong()).use {
                    readTextCapped(it, -1)?.let { text -> name to text }
                } }.getOrNull()
            }
            return ImportedManga(id = id, title = title, localRoot = source.path, isArchive = true,
                coverPath = cover, pageCount = split.keys.size, addedAt = freshAddedAt(context),
                sizeBytes = source.length(), chapters = split.chapters,
                description = ComicMetadataParser.descriptionOf(context, metadata + BookMetadataReader.sidecarTexts(source)), metadataVersion = BookMetadataReader.VERSION)
        }
    }

    /** 某次导入的本地目录（也是取消/失败时的清理对象）。 */
    private fun localDir(context: Context, id: Long): File =
        try {
            StorageDirStore.bookDir(context, id)
        } catch (e: SecurityException) {
            throw ImportOperationException(
                ImportFailureReason.DESTINATION_WRITE_FAILED,
                "系统拒绝访问应用漫画存储根目录",
                e
            )
        }

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
        // SAF 的 name 查询只是显示名提示；有些 provider 的 metadata query 会抛异常，
        // 但 openInputStream 仍可读。查询失败时用 URI 路径名兜底，不要提前误报读取失败。
        val metadata = SafDocumentMetadata.query(context, contentUri, TAG)
        val name = safeSegment(metadata.displayName ?: SafDocumentMetadata.fallbackName(contentUri).orEmpty())
            ?: context.getString(R.string.default_manga_title, id.toString())
        val title = name.substringBeforeLast('.', name)

        val manga = importArchiveToFile(context, contentUri, id, name, title, metadata.sizeBytes ?: 0L, onProgress)
        LogCollector.i(TAG, "复制压缩包完成: ${manga.title} (${manga.pageCount} 页, ${manga.chapterCount} 章)")
        manga
    }

    private suspend fun importArchiveToFile(
        context: Context,
        contentUri: Uri,
        id: Long,
        name: String,
        title: String,
        totalBytes: Long,
        onProgress: (ImportProgress) -> Unit
    ): ImportedManga {
        val destDir = localDir(context, id)
        try {
            ensureDestinationDirectory(destDir)
            // ⚠️ 文件名同样不可信（SAF 显示名可能带 `../`/分隔符）→ 只取最后一段
            val archiveFile = File(destDir, safeSegment(name) ?: "archive")
            // 长度只用于进度条；metadata query 不可用时改走不确定进度，不能因此拒绝可读文件。
            onProgress(ImportProgress(ImportPhase.COPYING, totalBytes = totalBytes))

            openSourceInput(context, contentUri).use { input ->
                openDestinationOutput(archiveFile).use { output ->
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
            }

            BookMetadataReader.copyAuthorizedSidecars(context, contentUri, destDir, "manga")
            return when (ArchiveTypes.detect(archiveFile)) {
                ArchiveKind.ZIP -> importZipArchive(context, archiveFile, id, title)
                ArchiveKind.RAR, ArchiveKind.SEVEN_ZIP ->
                    inspectRawArchive(context, archiveFile, id, title)
                // ⚠️ PDF **不转图**：原文件就是页源，页图按需栅格化（见 importPdf 的注释）
                ArchiveKind.PDF -> importPdf(context, archiveFile, id, title)
                ArchiveKind.UNKNOWN -> throw IllegalArgumentException("不支持的压缩包格式: $name")
            }.let { indexContainer(context, it) }
        } catch (e: Exception) {
            // 半成品必须清掉：留着的话下次导入复用同一个 id，残件与新文件混在同一目录
            destDir.deleteRecursively()
            LogCollector.e(TAG, "导入压缩包失败，已清理 $destDir: ${e.message}", e)
            throw e
        }
    }

    // ===== 导入 PDF（漫画扫描件 / 电子版） =====

    /**
     * PDF：**保留原文件、不做任何转换**，页图在阅读/翻译/导出时按需栅格化。
     *
     * ## 分页在哪（这是 PDF 与压缩包最大的差别）
     * PDF 的页是文件内部的 **page tree**（每页一个对象），与"有没有文本层"无关 ——
     * 扫描 PDF 只是每页内容是一整张位图，页边界照样由 page tree 给出。
     * 所以页数由 `PdfRenderer.pageCount` **权威给出**，不需要解析文件名、也不需要文本层。
     * 章节同理：framework 不暴露 PDF 目录（outline），整本 = 1 章（与单章漫画同一条路）。
     *
     * ## 为什么不在这里转成图片（与 rar/7z 的处理刻意不同）
     * - rar/7z 解压是因为它们**没有中央目录**，随机读一页要顺序扫整包；
     *   PDF 恰好相反：页码索引 + 随机渲染是 pdfium 的原生能力
     * - 导入即转图 = 存储 ×2~3（原 PDF + 几百张页图）、导入从秒级变分钟级，
     *   而且把分辨率**焊死在导入那一刻**（以后想提高 OCR 分辨率就得整本重导）
     * - 译文本来就不落图（只存气泡 + 文本，显示时实时渲染），PDF 的"按需栅格化"与这套架构同构
     *
     * @throws PdfImportException 打不开（加密 / 损坏）——由 [ImportManager] 转成用户提示
     */
    private fun importPdf(
        context: Context,
        pdfFile: File,
        id: Long,
        title: String,
        coverPathOverride: String? = null,
    ): ImportedManga {
        val renderer = PdfPageRenderer(pdfFile)
        try {
            val pageCount = renderer.pageCount
            if (pageCount <= 0) {
                val reason = when (renderer.openError) {
                    PdfOpenError.ENCRYPTED -> ImportFailureReason.PDF_ENCRYPTED
                    PdfOpenError.IO -> ImportFailureReason.UNREADABLE
                    else -> ImportFailureReason.PDF_INVALID
                }
                throw PdfImportException(reason, "PDF 打不开（${renderer.openError}）: ${pdfFile.name}")
            }
            // 封面 = 阅读顺序的第一页（PDF 第 1 页），与 zip/目录同一条口径
            val coverPath = coverPathOverride ?: renderer.renderCanonical(0)
                ?.let { bmp -> try { extractThumbnail(context, bmp, id) } finally { bmp.recycle() } }
            LogCollector.i(TAG, "导入 PDF 完成: $title（$pageCount 页，按需栅格化）")
            return ImportedManga(
                id = id,
                title = title,
                localRoot = pdfFile.absolutePath,
                isArchive = false,
                isPdf = true,
                coverPath = coverPath,
                pageCount = pageCount,
                addedAt = freshAddedAt(context),
                sizeBytes = pdfFile.length(),
                description = ComicMetadataParser.descriptionOf(context, BookMetadataReader.sourceTexts(pdfFile)),
                metadataVersion = BookMetadataReader.VERSION,
                // 整本一章：章标题留空 → 显示「第1章」（与单章漫画一致的显示规则）
                chapters = listOf(
                    MangaChapter(number = 1, title = "", startPage = 0, pageCount = pageCount)
                ),
            )
        } finally {
            // ⚠️ 导入只是探页数 + 渲一张封面，用完必须关：FD 是有限资源，
            //    阅读器那边会自己再开一份（`ReaderPageSource` 持有）
            renderer.close()
        }
    }

    /** zip/cbz：保留原包，页序/章节交给 [MangaChapterSplitter]（与阅读侧同源）。 */
    private fun importZipArchive(
        context: Context,
        archiveFile: File,
        id: Long,
        title: String,
        coverPathOverride: String? = null,
    ): ImportedManga {
        val split = MangaChapterSplitter.split(listZipImageEntries(archiveFile))
        val coverPath = coverPathOverride ?: extractCoverFromZipFile(context, archiveFile, split.keys, id)
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
            description = ComicMetadataParser.descriptionOf(context, BookMetadataReader.sourceTexts(archiveFile)),
            metadataVersion = BookMetadataReader.VERSION,
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
        (com.moe.starflow.library.LibrarySessions.current(context).storage as? com.moe.starflow.library.SafLibraryStorage)
            ?.requireImportOutsideDestination(treeUri, "manga")
        val rootDocumentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: SecurityException) {
            throw ImportOperationException(
                ImportFailureReason.DIRECTORY_ACCESS_DENIED,
                "读取 SAF 目录标识时系统拒绝访问：$treeUri",
                e
            )
        } catch (e: Exception) {
            throw ImportOperationException(
                ImportFailureReason.DIRECTORY_SCAN_FAILED,
                "无法从所选 SAF URI 解析目录标识：$treeUri",
                e
            )
        }.takeIf { it.isNotBlank() } ?: throw ImportOperationException(
            ImportFailureReason.DIRECTORY_INVALID,
            "所选 SAF URI 没有目录标识：$treeUri",
        )

        // ACTION_OPEN_DOCUMENT_TREE returns a tree URI. Query metadata on the concrete root
        // document URI; some DocumentsProviders reject metadata queries against the tree URI.
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId)
        val title = SafDocumentMetadata.query(context, rootUri, TAG).displayName
            ?.let(::safeSegment)
            ?: context.getString(R.string.default_manga_title, id.toString())
        importDirectoryToFile(context, treeUri, rootDocumentId, title, id, onProgress)
    }

    private suspend fun importDirectoryToFile(
        context: Context,
        treeUri: Uri,
        rootDocumentId: String,
        title: String,
        id: Long,
        onProgress: (ImportProgress) -> Unit
    ): ImportedManga {
        val destDir = localDir(context, id)
        try {
            ensureDestinationDirectory(destDir)
            // ===== 阶段一：扫描（枚举图片 + 排序）。总量未知 → 不确定进度 =====
            onProgress(ImportProgress(ImportPhase.SCANNING))
            val entries = mutableListOf<Pair<Uri, String>>()
            val metadataEntries = mutableListOf<Pair<Uri, String>>()
            collectImageDocs(context, treeUri, rootDocumentId, "", entries, metadataEntries)

            // ⚠️ 章节切分在这里就定下来：它同时产出**权威页序**（按章分组、章内自然排序），
            // 落盘顺序即阅读顺序 —— 阅读侧 `ReaderPageSource` 用同一个切分器，两边不会错位。
            if (entries.map { it.second }.toSet().size != entries.size) throw IOException("来源包含重复名称，无法完整复制漫画")
            val split = MangaChapterSplitter.split(entries.map { it.second })
            val pageOrder = split.keys.withIndex().associate { (i, key) -> key to i }
            val ordered = entries.sortedBy { pageOrder[it.second] ?: Int.MAX_VALUE }
            val totalFiles = ordered.size
            onProgress(ImportProgress(ImportPhase.COPYING, 0, totalFiles))

            // ===== 阶段二：复制（按排好的页序落盘） =====
            var total = 0L
            var done = 0
            var last = 0L
            ordered.forEach { (sourceUri, rel) ->
                coroutineContext.ensureActive()
                val target = File(destDir, rel)
                // ⚠️ 纵深防御：哪怕上游漏了消毒，也**绝不允许**写到这本书自己的目录之外
                // （越界会覆盖 models/、covers/、别人书的页图）。
                val staysInsideImportDir = try {
                    target.canonicalPath.startsWith(destDir.canonicalPath + File.separator)
                } catch (e: SecurityException) {
                    throw ImportOperationException(
                        ImportFailureReason.DESTINATION_WRITE_FAILED,
                        "无法校验应用漫画文件的目标路径：${target.absolutePath}",
                        e
                    )
                } catch (e: IOException) {
                    throw ImportOperationException(
                        ImportFailureReason.DESTINATION_WRITE_FAILED,
                        "无法解析应用漫画文件的目标路径：${target.absolutePath}",
                        e
                    )
                }
                if (!staysInsideImportDir) {
                    LogCollector.w(TAG, "跳过越界目标: $rel")
                    return@forEach
                }
                openSourceInput(context, sourceUri).use { input ->
                    // 单文件内部也可取消：大页图不至于让「取消」等满一整页
                    openDestinationOutput(target).use { output -> copyStream(input, output) {} }
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

            // Keep optional metadata with the copied source so later readers/scans can see it.
            val metadataTexts = metadataEntries.mapNotNull { (uri, relative) ->
                coroutineContext.ensureActive()
                try {
                    val text = openSourceInput(context, uri).use { BookMetadataReader.readText(it) } ?: return@mapNotNull null
                    val target = File(destDir, relative).canonicalFile
                    require(target.path.startsWith(destDir.canonicalPath + File.separator))
                    openDestinationOutput(target).use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    relative to text
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { LogCollector.w(TAG, "读取目录元数据失败：$relative", e); null }
            }

            return inspectImageDirectory(context, destDir, id, title, coverPath)

        } catch (e: Exception) {
            destDir.deleteRecursively()
            LogCollector.e(TAG, "导入目录失败，已清理 $destDir: ${e.message}", e)
            throw e
        }
    }

    // ===== 复制工具 =====

    /**
     * 递归枚举 SAF 子项查询结果里的图片，收集**相对路径**（ch1/001.jpg）。
     *
     * ⚠️ 收的是相对路径而不是文件名：分章节目录里 ch1/001.jpg 与 ch2/001.jpg 同名，只记 basename
     * 会让 File(destDir, it) 指不到文件 → 封面永远是灰底占位，排序也会把两章的同名页混在一起。
     *
     * ⚠️ 递归里每层 `ensureActive()`：DocumentsProvider 子项查询是逐层 IPC，大文件夹（上万条目）
     * 的扫描本身就要几十秒 —— 不检查取消的话用户点了 ✕ 仍要等扫描跑完，看起来像"取消没反应"。
     */
    private suspend fun collectImageDocs(
        context: Context,
        treeUri: Uri,
        documentId: String,
        prefix: String,
        out: MutableList<Pair<Uri, String>>,
        metadata: MutableList<Pair<Uri, String>>,
    ) {
        try {
            val children = SafDocumentTreeReader.listChildren(context, treeUri, documentId, TAG)
            children.forEachIndexed { index, child ->
                coroutineContext.ensureActive()
                val providerName = child.displayName ?: throw IOException("提供器未返回来源名称：${child.uri}")
                val imageExtension = imageExtension(child.mimeType)
                val isImage = ArchivedMangaReader.isImageFile(providerName) ||
                    (imageExtension != null && child.mimeType?.startsWith("image/") == true)
                val isDirectory = child.isDirectory ||
                    (child.mimeType == null && !isImage && providerName.substringAfterLast('.', "").isEmpty())
                if (isDirectory) {
                    if (com.moe.starflow.library.LibrarySessions.current(context).database.catalog().allMeta()
                        .any { it.name.startsWith("importing:manga:") && it.value.substringAfterLast('/') == providerName })
                        throw IOException("导入来源包含本次目标目录，已停止递归复制：$providerName")
                    val dirName = safeSegment(providerName) ?: "folder_${index + 1}"
                    collectImageDocs(context, treeUri, child.documentId, "$prefix$dirName/", out, metadata)
                } else if (isImage || MangaSourceIndex.supported(providerName)) {
                    val suggestedName = if (ArchivedMangaReader.isImageFile(providerName) || MangaSourceIndex.supported(providerName)) providerName
                    else "image_${(index + 1).toString().padStart(5, '0')}.$imageExtension"
                    val name = safeSegment(suggestedName) ?: return@forEachIndexed
                    out.add(child.uri to "$prefix$name")
                } else if (ComicMetadataParser.isMetadataEntry(providerName)) {
                    val name = safeSegment(providerName) ?: return@forEachIndexed
                    metadata.add(child.uri to "$prefix$name")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImportOperationException) {
            throw e
        } catch (e: SecurityException) {
            throw ImportOperationException(
                ImportFailureReason.DIRECTORY_ACCESS_DENIED,
                "扫描所选目录时 SAF 拒绝访问",
                e
            )
        } catch (e: Exception) {
            throw ImportOperationException(
                ImportFailureReason.DIRECTORY_SCAN_FAILED,
                "扫描所选目录失败",
                e
            )
        }
    }

    private fun imageExtension(mimeType: String?): String? = when (mimeType?.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/bmp" -> "bmp"
        else -> null
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

    /** 读取 SAF 来源时保留「打开来源」和「读取来源」两个不同失败阶段。 */
    private fun openSourceInput(context: Context, uri: Uri): InputStream {
        val input = try {
            context.contentResolver.openInputStream(uri)
                ?: throw ImportOperationException(
                    ImportFailureReason.SOURCE_OPEN_FAILED,
                    "ContentResolver.openInputStream 返回 null"
                )
        } catch (e: ImportOperationException) {
            throw e
        } catch (e: SecurityException) {
            throw ImportOperationException(
                ImportFailureReason.SOURCE_ACCESS_DENIED,
                "打开 SAF 来源时系统拒绝访问",
                e
            )
        } catch (e: FileNotFoundException) {
            throw ImportOperationException(
                ImportFailureReason.SOURCE_OPEN_FAILED,
                "SAF 文件提供器未能打开所选 URI",
                e
            )
        } catch (e: IOException) {
            throw ImportOperationException(
                ImportFailureReason.SOURCE_OPEN_FAILED,
                "打开 SAF 来源时发生 I/O 错误",
                e
            )
        }

        return object : FilterInputStream(input) {
            override fun read(): Int = try {
                super.read()
            } catch (e: IOException) {
                throw ImportOperationException(
                    ImportFailureReason.SOURCE_READ_FAILED,
                    "从 SAF 来源读取数据时发生 I/O 错误",
                    e
                )
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
                super.read(buffer, offset, length)
            } catch (e: IOException) {
                throw ImportOperationException(
                    ImportFailureReason.SOURCE_READ_FAILED,
                    "从 SAF 来源读取数据时发生 I/O 错误",
                    e
                )
            }

            override fun close() {
                try {
                    super.close()
                } catch (e: IOException) {
                    throw ImportOperationException(
                        ImportFailureReason.SOURCE_READ_FAILED,
                        "关闭 SAF 来源数据流时发生 I/O 错误",
                        e
                    )
                }
            }
        }
    }

    /** 目标目录/文件不可写时明确报告应用目标存储故障，不冒充 SAF 权限问题。 */
    private fun ensureDestinationDirectory(dir: File) {
        try {
            com.moe.starflow.utils.FileAccessDiagnostics.ensureDirectory(dir)
        } catch (e: ImportOperationException) {
            throw e
        } catch (e: IOException) {
            throw ImportOperationException(ImportFailureReason.DESTINATION_WRITE_FAILED, e.message.orEmpty(), e)
        } catch (e: SecurityException) {
            throw ImportOperationException(
                ImportFailureReason.DESTINATION_WRITE_FAILED,
                "创建应用漫画存储目录时被系统拒绝：${dir.absolutePath}",
                e
            )
        }
    }

    private fun openDestinationOutput(file: File): OutputStream {
        file.parentFile?.let(::ensureDestinationDirectory)
        val output = try {
            FileOutputStream(file)
        } catch (e: IOException) {
            throw ImportOperationException(
                ImportFailureReason.DESTINATION_WRITE_FAILED,
                "无法创建应用漫画文件：${file.absolutePath}",
                e
            )
        } catch (e: SecurityException) {
            throw ImportOperationException(
                ImportFailureReason.DESTINATION_WRITE_FAILED,
                "系统拒绝创建应用漫画文件：${file.absolutePath}",
                e
            )
        }

        return object : FilterOutputStream(output) {
            override fun write(value: Int) {
                try {
                    out.write(value)
                } catch (e: IOException) {
                    throw ImportOperationException(
                        ImportFailureReason.DESTINATION_WRITE_FAILED,
                        "写入应用漫画文件失败：${file.absolutePath}",
                        e
                    )
                }
            }

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                try {
                    out.write(buffer, offset, length)
                } catch (e: IOException) {
                    throw ImportOperationException(
                        ImportFailureReason.DESTINATION_WRITE_FAILED,
                        "写入应用漫画文件失败：${file.absolutePath}",
                        e
                    )
                }
            }

            override fun flush() {
                try {
                    out.flush()
                } catch (e: IOException) {
                    throw ImportOperationException(
                        ImportFailureReason.DESTINATION_WRITE_FAILED,
                        "刷新应用漫画文件失败：${file.absolutePath}",
                        e
                    )
                }
            }

            override fun close() {
                try {
                    out.close()
                } catch (e: IOException) {
                    throw ImportOperationException(
                        ImportFailureReason.DESTINATION_WRITE_FAILED,
                        "关闭应用漫画文件失败：${file.absolutePath}",
                        e
                    )
                }
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

    /** 读取图片文件夹内的元数据文本。 */
    internal fun readDirMetadataTexts(dir: File): List<Pair<String, String>> = BookMetadataReader.directoryTexts(dir)

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
        val out = File(dir, "${id}_${java.util.UUID.randomUUID()}.jpg")
        val scaled = Bitmap.createScaledBitmap(bmp, COVER_WIDTH, COVER_WIDTH, true)
        try {
            FileOutputStream(out).use { stream ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)) throw IOException("封面压缩失败：${out.path}")
                stream.fd.sync()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(out.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("封面写入不完整：${out.path}")
        } catch (e: Exception) { out.delete(); throw e }
        if (scaled !== bmp) scaled.recycle()
        return out.absolutePath
    }
}
