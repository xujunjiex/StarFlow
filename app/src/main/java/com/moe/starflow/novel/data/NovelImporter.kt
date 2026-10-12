package com.moe.starflow.novel.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.DocumentsContract
import com.moe.starflow.mangaimport.data.ArchivedMangaReader
import com.moe.starflow.mangaimport.data.ImportPhase
import com.moe.starflow.mangaimport.data.ImportProgress
import com.moe.starflow.novel.model.NovelFormat
import com.moe.starflow.novel.parser.NovelFormatDetector
import com.moe.starflow.novel.parser.FolderNovelParser
import com.moe.starflow.novel.parser.NovelParsers
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.SafDocumentMetadata
import com.moe.starflow.utils.SafDocumentTreeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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

    /**
     * 文件夹子认可的**文本**扩展名。
     *
     * txt / epub 与 koto 的小说文件夹导入一致（「内部 TXT 文件会作为章节导入；
     * 每个 EPUB 文件会作为一个分卷导入」）。
     *
     * ⚠️ **html 系列是 2026-10-04 加的**（用户要求「所有支持的格式都支持图片预览」）：
     * 「一个夹里一堆 html + 一个 images/ 目录」是小说下载最常见的分发形态之一，
     * 之前那种夹子会报「未找到可阅读的文本章节」。夹里的 zip 仍不读
     * （避免把「随手丢在同一个夹里」的无关文件当成章节）。
     */
    private val DIR_TEXT_EXTS = setOf("txt", "epub", "html", "htm", "xhtml")

    /** 夹内 html 的扩展名（决定「要不要连图片一起拷」，见 [importDirectory]）。 */
    private val DIR_HTML_EXTS = setOf("html", "htm", "xhtml")

    /**
     * 夹内**会递归拷贝**的图片扩展名。
     *
     * 只认这些：html 引用的插图基本都在其列，而别的东西（.db/.json/字体）不该被搬进书目录。
     * `.svg` 也收 —— `ZipImages` 会往里找一层真正的位图（EPUB3 封面那种写法）。
     */
    private val DIR_IMAGE_EXTS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "heic", "svg",
    )

    /**
     * 递归搬图片的两个上限：夹里若有上万张图（用户误把漫画夹当小说导入），
     * 不封顶会把整本漫画复制进来。命中上限只**停止继续拷**并留日志，已拷的照常用。
     */
    internal const val MAX_DIR_IMAGES = 2000
    internal const val MAX_DIR_IMAGE_BYTES = 400L * 1024 * 1024

    /** 递归深度上限（正常小说夹不会超过两三层）。 */
    private const val MAX_DIR_DEPTH = 6

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
        // ⚠️ 必须过一道 safeSegment：DISPLAY_NAME 由 DocumentsProvider 给，**不受我们控制**，
        // 带 `/` 或 `\` 时直接拿去拼路径会把写入目标挪出 `novel_import/<id>/`
        // （见 safeSegment 注释）。
        val metadata = SafDocumentMetadata.query(context, uri, TAG)
        val displayName = safeSegment(metadata.displayName ?: fileNameOf(uri), "book_$id")
        val fallbackTitle = displayName.substringBeforeLast('.', displayName).ifBlank { "book_$id" }
        importOne(context, uri, id, displayName, fallbackTitle, metadata.sizeBytes ?: 0L, onProgress)
    }

    /** Build an index entry for content already placed in app-owned storage; never copies or deletes it. */
    internal suspend fun inspectLocalSource(
        context: Context,
        source: File,
        id: Long,
        fallbackTitle: String,
    ): ImportedNovel = withContext(Dispatchers.IO) {
        val format = NovelFormatDetector.detect(source)
            ?: throw NovelWrongFormatException(isManga = false)
        val book = NovelParsers.forFormat(format).parse(source)
        if (book.chapters.isEmpty()) throw IllegalStateException(ERROR_NO_TEXT_CHAPTER)
        val title = if (format == NovelFormat.FOLDER) fallbackTitle else book.title.ifBlank { fallbackTitle }
        val coverPath = book.coverBytes?.let { decodeCover(context, it, id) }
            ?: placeholderCover(context, id, title)
        ImportedNovel(
            id = id,
            title = title,
            author = book.author,
            localRoot = source.canonicalPath,
            format = format,
            coverPath = coverPath,
            chapterCount = book.chapters.size,
            addedAt = freshAddedAt(context),
            sizeBytes = if (source.isDirectory) {
                source.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else source.length(),
        )
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
     * 把 SAF 给的显示名收敛成**单个路径段**。
     *
     * ⚠️ 为什么必须收敛：`DISPLAY_NAME` 来自 DocumentsProvider，**不是**可信输入。实现粗糙或恶意
     * 的 provider 完全可以返回 `../evil.txt`、`a/b.txt` 这类名字，直接 `File(destDir, name)` 会
     * 把写入目标挪出 `novel_import/<id>/` —— 越界后能覆盖**别的书**的目录、`covers/`，
     * 甚至应用自己的日志文件（都在同一个 app 私有目录下）。
     *
     * 所以只取最后一个 `/`（或 `\`）之后的部分；为空则退回调用方给的生成名
     * （宁可叫 `book_<id>`，也不能用一个来路不明的名字去写盘）。
     */
    private fun safeSegment(name: String?, fallback: String): String {
        val seg = name.orEmpty().substringAfterLast('/').substringAfterLast('\\').trim()
        return seg.ifBlank { fallback }
    }

    /**
     * 目标文件必须落在 [destDir] **内部**（规范路径比较，`..` / `.` 也拦得住）。
     *
     * 这是纵深防御的第二道：名字已经收敛成单段（见 [safeSegment]），但 provider 的花样不受我们
     * 控制，越界一律**抛异常**而不是静默写到别处 —— 静默写出去的后果是覆盖别人的数据，
     * 而抛异常会被上层 catch 掉（`importOne` / `importDirectory` 都会清掉半成品目录再抛出）。
     */
    private fun resolveInside(destDir: File, name: String): File {
        val dir = destDir.canonicalFile
        val target = File(dir, name).canonicalFile
        if (target.parentFile?.path != dir.path) throw IllegalStateException(ERROR_UNSAFE_NAME)
        return target
    }

    /**
     * 导入一个文件夹：**整个夹 = 一部小说**，夹内每个 txt / html 是一章（见 `FolderNovelParser`）。
     *
     * 与漫画侧同一套心智（整个夹 = 一部作品）—— 上一版按「每个文件一部书」实现，
     * 那种语义在「一本小说被拆成 300 个 txt」时会长出 300 本书。
     *
     * ### 拷什么（2026-10-04 起）
     * - **文本**：直接子文件里的 txt / epub / html / htm / xhtml（不递归 —— 递归会把
     *   「夹里套了一堆杂七杂八」的情况也吞进来变成莫名其妙的章节）
     * - **图片**：**仅当夹里有 html 时**才递归拷贝（`images/xxx.jpg` 这种子目录是常态）。
     *   txt / epub 不需要外部图片（epub 的图在包内），所以纯 txt 夹不会被塞进一堆图。
     *   ⚠️ 两个上限见 [MAX_DIR_IMAGES] / [MAX_DIR_IMAGE_BYTES]，命中只停拷 + 留日志。
     *
     * 单张图拷失败**不让整次导入失败**：少一张插图是小事，整本书导不进来才是大事 ——
     * 但每张失败都留日志（否则用户只看到"图没了"却查不出原因）。
     */
    suspend fun importDirectory(
        context: Context,
        treeUri: Uri,
        id: Long = nextId(context),
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportedNovel = withContext(Dispatchers.IO) {
        val rootId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: SecurityException) {
            throw NovelImportIoException(
                NovelImportIoException.Kind.DIRECTORY_ACCESS,
                "系统拒绝读取 SAF 小说目录标识：$treeUri",
                e,
            )
        } catch (e: Exception) {
            throw NovelImportIoException(
                NovelImportIoException.Kind.DIRECTORY_SCAN,
                "无法从 SAF URI 解析小说目录标识：$treeUri",
                e,
            )
        }
        // ACTION_OPEN_DOCUMENT_TREE returns a tree URI. Query the selected root document itself;
        // several providers support child enumeration on the tree URI but not metadata queries on it.
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val rootName = SafDocumentMetadata.query(context, rootUri, TAG).displayName
        val fallbackTitle = rootName?.takeIf { it.isNotBlank() } ?: "book_$id"
        val destDir = NovelStorageDir.bookDir(context, id)

        try {
            if (!destDir.isDirectory && !destDir.mkdirs()) {
                throw NovelImportIoException(
                    NovelImportIoException.Kind.DESTINATION_WRITE,
                    "无法创建应用小说目录：${destDir.absolutePath}",
                )
            }
            val textDocs = collectTextDocs(context, treeUri, rootId)
            if (textDocs.isEmpty()) throw IllegalStateException(ERROR_NO_TEXT_CHAPTER)
            // 夹里有 html 才收图片（见 KDoc）；`rootId` 用来把 documentId 折成相对路径
            val hasHtml = textDocs.any { isDirHtmlEntry(safName(it)) }
            val imageDocs = if (hasHtml) collectImageDocs(context, treeUri, rootId) else emptyList()
            val total = textDocs.size + imageDocs.size

            onProgress(ImportProgress(ImportPhase.COPYING, 0, total))
            var done = 0
            for (doc in textDocs) {
                coroutineContext.ensureActive()
                val relativePath = relativePathOf(rootId, doc) ?: continue
                val target = resolveInsideTree(destDir, relativePath)
                    ?: throw IOException("小说章节路径不安全：$relativePath")
                openSafSource(context, doc.uri).use { input ->
                    openNovelDestination(target).use { output -> copyStream(input, output) {} }
                }
                done++
                onProgress(ImportProgress(ImportPhase.COPYING, done, total))
            }
            var copiedBytes = 0L
            for ((rel, sourceUri) in imageDocs) {
                coroutineContext.ensureActive()
                if (copiedBytes >= MAX_DIR_IMAGE_BYTES) {
                    LogCollector.w(TAG, "夹内图片超过 ${MAX_DIR_IMAGE_BYTES / (1024 * 1024)}MB，余下不再拷贝")
                    break
                }
                val target = resolveInsideTree(destDir, rel)
                if (target == null) {
                    LogCollector.w(TAG, "夹内图片路径不安全，已跳过: $rel")
                    continue
                }
                runCatching {
                    openSafSource(context, sourceUri).use { input ->
                        openNovelDestination(target).use { output -> copyStream(input, output) {} }
                    }
                    copiedBytes += target.length()
                }.onFailure { LogCollector.w(TAG, "夹内图片拷贝失败，已跳过: $rel", it) }
                done++
                onProgress(ImportProgress(ImportPhase.COPYING, done, total))
            }
            coroutineContext.ensureActive()

            onProgress(ImportProgress(ImportPhase.SCANNING))
            val book = FolderNovelParser.parse(destDir)

            ImportedNovel(
                id = id,
                title = book.title.ifBlank { fallbackTitle },
                author = null,
                localRoot = destDir.absolutePath,
                format = NovelFormat.FOLDER,
                coverPath = placeholderCover(context, id, book.title.ifBlank { fallbackTitle }),
                chapterCount = book.chapters.size,
                addedAt = freshAddedAt(context),
                sizeBytes = destDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            )
        } catch (e: Exception) {
            // 半成品必须清掉：留着的话下次导入复用同一个 id，残件与新文件混在同一目录
            deleteBookFiles(context, id)
            LogCollector.e(TAG, "文件夹导入失败，已清理 $destDir: ${e.message}", e)
            throw e
        }
    }

    /** 这一条名字是不是文件夹导入认可的**文本**条目（纯函数，可单测）。 */
    internal fun isDirTextEntry(name: String?): Boolean = extOf(name) in DIR_TEXT_EXTS

    /** 这一条名字是不是夹内 **html**（决定要不要连图片一起拷）。 */
    internal fun isDirHtmlEntry(name: String?): Boolean = extOf(name) in DIR_HTML_EXTS

    /** 这一条名字是不是会**递归拷贝**的图片条目。 */
    internal fun isDirImageEntry(name: String?): Boolean = extOf(name) in DIR_IMAGE_EXTS

    private fun extOf(name: String?): String =
        name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()

    /**
     * 递归收集夹内图片（只收 [DIR_IMAGE_EXTS]，深度封顶 [MAX_DIR_DEPTH]，张数封顶
     * [MAX_DIR_IMAGES]），返回 `相对路径 -> 文档`。
     *
     * ⚠️ 相对路径由 **documentId** 折出来（`primary:Books/novel/images/a.jpg` 去掉树根前缀），
     * 而不是靠 provider 返回的单段显示名拼 —— 后者只有文件名，`images/` 这层就丢了，
     * 于是 html 里的 `images/a.jpg` 永远解析不到。
     */
    private suspend fun collectImageDocs(
        context: Context,
        treeUri: Uri,
        rootId: String,
    ): List<Pair<String, Uri>> {
        val out = ArrayList<Pair<String, Uri>>()
        suspend fun walk(parentDocumentId: String, depth: Int) {
            if (depth > MAX_DIR_DEPTH) return
            for (child in queryChildren(context, treeUri, parentDocumentId)) {
                coroutineContext.ensureActive()
                if (out.size >= MAX_DIR_IMAGES) {
                    LogCollector.w(TAG, "夹内图片超过 $MAX_DIR_IMAGES 张，余下不再拷贝")
                    return
                }
                val name = safName(child)
                if (child.isDirectory || (child.mimeType == null && name.substringAfterLast('.', "").isEmpty())) {
                    walk(child.documentId, depth + 1)
                } else if (isDirImageEntry(name) || imageExtension(child.mimeType) != null) {
                    val rel = relativePathOf(rootId, child) ?: continue
                    val targetRel = if (isDirImageEntry(rel)) rel else {
                        val ext = imageExtension(child.mimeType) ?: continue
                        val parent = rel.substringBeforeLast('/', "")
                        listOf(parent.takeIf { it.isNotEmpty() }, "image_${out.size + 1}.$ext")
                            .filterNotNull().joinToString("/")
                    }
                    out += targetRel to child.uri
                }
            }
        }
        walk(rootId, 0)
        return out
    }

    /**
     * 收集小说目录根及章节子目录里的文本/EPUB 文件，保留相对路径，最多递归 [MAX_DIR_DEPTH] 层。
     * 相对路径既用于安全复制，也作为阅读器 locator，保证章节分类目录不会被拍平。
     */
    private suspend fun collectTextDocs(
        context: Context,
        treeUri: Uri,
        rootId: String,
    ): List<SafDocumentTreeReader.Child> {
        val out = ArrayList<SafDocumentTreeReader.Child>()
        suspend fun walk(parentDocumentId: String, depth: Int) {
            if (depth > MAX_DIR_DEPTH) return
            for (child in queryChildren(context, treeUri, parentDocumentId)) {
                coroutineContext.ensureActive()
                val name = safName(child)
                val extensionless = name.substringAfterLast('.', "").isEmpty()
                if (child.isDirectory || (child.mimeType == null && extensionless)) {
                    walk(child.documentId, depth + 1)
                } else if (isDirTextEntry(name)) {
                    out += child
                }
            }
        }
        walk(rootId, 0)
        return out.sortedWith(
            compareBy(ArchivedMangaReader.naturalComparator()) { relativePathOf(rootId, it) ?: safName(it) }
        )
    }

    private fun queryChildren(context: Context, treeUri: Uri, documentId: String): List<SafDocumentTreeReader.Child> =
        try {
            SafDocumentTreeReader.listChildren(context, treeUri, documentId, TAG)
        } catch (e: SecurityException) {
            throw NovelImportIoException(
                NovelImportIoException.Kind.DIRECTORY_ACCESS,
                "DocumentsProvider 拒绝枚举小说目录：documentId=$documentId, uri=$treeUri",
                e,
            )
        } catch (e: Exception) {
            throw NovelImportIoException(
                NovelImportIoException.Kind.DIRECTORY_SCAN,
                "查询 SAF 小说目录子项失败：documentId=$documentId, uri=$treeUri",
                e,
            )
        }

    private fun safName(child: SafDocumentTreeReader.Child): String =
        child.displayName ?: child.documentId.substringAfterLast('/').substringAfterLast(':')

    private fun imageExtension(mimeType: String?): String? = when (mimeType?.lowercase(Locale.ROOT)) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/bmp" -> "bmp"
        "image/avif" -> "avif"
        "image/heic", "image/heif" -> "heic"
        "image/svg+xml" -> "svg"
        else -> null
    }

    /**
     * 文档 → **相对树根**的路径（拿不到 / 不在树内返回 null）。
     *
     * `DocumentsContract.getDocumentId` 对 `content://…/document/primary%3ABooks%2Fn%2Fimages%2Fa.jpg`
     * 给出 `primary:Books/n/images/a.jpg`，而树根是 `primary:Books/n` → 相对路径 `images/a.jpg`。
     */
    private fun relativePathOf(rootId: String, doc: SafDocumentTreeReader.Child): String? {
        val id = runCatching { DocumentsContract.getDocumentId(doc.uri) }.getOrNull() ?: return null
        if (!id.startsWith("$rootId/")) return null
        return sanitizeRelPath(id.removePrefix("$rootId/"))
    }

    /**
     * 相对路径消毒（纯函数，可单测）：只留安全的普通路径段，其余一律返回 null。
     *
     * ⚠️ **这是安全边界**：路径来自 DocumentsProvider（不受我们控制）。放行 `..`、绝对路径、
     * 带盘符/冒号的段，就能把写入目标挪出 `novel_import/<id>/`（覆盖别的书、封面、日志）。
     * 与 [safeSegment] 同一套思路，只是这里允许多层子目录。
     */
    internal fun sanitizeRelPath(rel: String?): String? {
        val raw = rel?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > MAX_REL_PATH_CHARS) return null
        val segs = raw.split('/', '\\')
        val out = ArrayList<String>(segs.size)
        for (seg in segs) {
            when {
                seg.isEmpty() || seg == "." -> Unit
                seg == ".." -> return null
                // 冒号 = Windows 盘符 / SAF 的 `primary:` 前缀；控制字符与尾点/尾空格在部分文件系统上非法
                seg.contains(':') -> return null
                seg.any { it.isISOControl() } -> return null
                seg.length > MAX_REL_SEGMENT_CHARS -> return null
                else -> out += seg
            }
        }
        if (out.isEmpty() || out.size > MAX_DIR_DEPTH + 1) return null
        return out.joinToString("/")
    }

    private const val MAX_REL_PATH_CHARS = 400
    private const val MAX_REL_SEGMENT_CHARS = 120

    /**
     * 相对路径 → 书目录内的目标文件（必要时建父目录）。
     *
     * 与 [resolveInside] 同一条纵深防御：规范路径必须**严格落在 [destDir] 内**，否则返回 null
     * （调用方跳过并留日志，而不是静默写到别处）。
     */
    private fun resolveInsideTree(destDir: File, rel: String): File? {
        val dir = destDir.canonicalFile
        val target = File(dir, rel).canonicalFile
        if (target.path == dir.path) return null
        if (!target.path.startsWith(dir.path + File.separator)) return null
        target.parentFile?.mkdirs()
        return target
    }

    private suspend fun importOne(
        context: Context,
        uri: Uri,
        id: Long,
        displayName: String,
        fallbackTitle: String,
        totalBytes: Long,
        onProgress: (ImportProgress) -> Unit,
    ): ImportedNovel {
        val destDir = NovelStorageDir.bookDir(context, id)
        try {
            if (!destDir.isDirectory && !destDir.mkdirs()) {
                throw NovelImportIoException(
                    NovelImportIoException.Kind.DESTINATION_WRITE,
                    "无法创建应用小说目录：${destDir.absolutePath}",
                )
            }
            // ===== 复制 =====
            val dest = resolveInside(destDir, displayName)
            onProgress(ImportProgress(ImportPhase.COPYING, totalBytes = totalBytes))
            openSafSource(context, uri).use { input ->
                openNovelDestination(dest).use { output ->
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
            }
            coroutineContext.ensureActive()

            // ⚠️ 空文件必须在**格式判定之前**单独报出来：0 字节的 .txt 照样能通过格式判定
            // （按扩展名判成 TXT）、解析出 0 章，最后落到 ERROR_NO_TEXT_CHAPTER ——
            // 用户看到的是「未找到可阅读的文本章节」，而 EMPTY（「空文件」）永远不可达。
            // 判据取**复制后的字节数**：源 provider 报的 length() 不可信（可能是 0 或 -1）。
            if (dest.length() == 0L) throw IllegalStateException(ERROR_EMPTY)

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

    /** Remove a directly scanned app-owned source when the user deletes that shelf entry. */
    internal fun deleteScannedSource(context: Context, id: Long, localRoot: String) {
        val source = File(localRoot).canonicalFile
        val appRoot = (context.getExternalFilesDir(null) ?: context.filesDir).canonicalFile
        val bookDir = NovelStorageDir.bookDir(context, id).canonicalFile
        val sourcePath = source.path
        val inAppFiles = sourcePath.startsWith(appRoot.path + File.separator)
        val inImportedBookDir = sourcePath == bookDir.path ||
            sourcePath.startsWith(bookDir.path + File.separator)
        if (!inAppFiles || inImportedBookDir) return
        if (source.exists() && !source.deleteRecursively()) {
            throw IOException("无法删除自动扫描的小说来源：${source.absolutePath}")
        }
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

    private fun openSafSource(context: Context, uri: Uri): InputStream {
        val stream = try {
            context.contentResolver.openInputStream(uri)
                ?: throw NovelImportIoException(
                    NovelImportIoException.Kind.SOURCE_OPEN,
                    "SAF 文件提供器为所选 URI 返回空数据流：$uri",
                )
        } catch (e: NovelImportIoException) {
            throw e
        } catch (e: SecurityException) {
            throw NovelImportIoException(
                NovelImportIoException.Kind.SOURCE_OPEN,
                "打开 SAF 来源时系统拒绝访问：$uri",
                e,
            )
        } catch (e: Exception) {
            throw NovelImportIoException(
                NovelImportIoException.Kind.SOURCE_OPEN,
                "SAF 文件提供器无法打开来源：$uri",
                e,
            )
        }
        return object : java.io.FilterInputStream(stream) {
            override fun read(): Int = try {
                super.read()
            } catch (e: java.io.IOException) {
                throw NovelImportIoException(
                    NovelImportIoException.Kind.SOURCE_READ,
                    "读取 SAF 来源数据失败：$uri",
                    e,
                )
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
                super.read(buffer, offset, length)
            } catch (e: java.io.IOException) {
                throw NovelImportIoException(
                    NovelImportIoException.Kind.SOURCE_READ,
                    "读取 SAF 来源数据失败：$uri",
                    e,
                )
            }

            override fun close() {
                try {
                    super.close()
                } catch (e: java.io.IOException) {
                    throw NovelImportIoException(
                        NovelImportIoException.Kind.SOURCE_READ,
                        "关闭 SAF 来源数据流失败：$uri",
                        e,
                    )
                }
            }
        }
    }

    private fun openNovelDestination(file: File): OutputStream {
        val stream = try {
            FileOutputStream(file)
        } catch (e: Exception) {
            throw NovelImportIoException(
                NovelImportIoException.Kind.DESTINATION_WRITE,
                "无法创建应用小说文件：${file.absolutePath}",
                e,
            )
        }
        return object : java.io.FilterOutputStream(stream) {
            override fun write(value: Int) {
                try {
                    out.write(value)
                } catch (e: java.io.IOException) {
                    throw NovelImportIoException(
                        NovelImportIoException.Kind.DESTINATION_WRITE,
                        "写入应用小说文件失败：${file.absolutePath}",
                        e,
                    )
                }
            }

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                try {
                    out.write(buffer, offset, length)
                } catch (e: java.io.IOException) {
                    throw NovelImportIoException(
                        NovelImportIoException.Kind.DESTINATION_WRITE,
                        "写入应用小说文件失败：${file.absolutePath}",
                        e,
                    )
                }
            }

            override fun flush() {
                try {
                    out.flush()
                } catch (e: java.io.IOException) {
                    throw NovelImportIoException(
                        NovelImportIoException.Kind.DESTINATION_WRITE,
                        "刷新应用小说文件失败：${file.absolutePath}",
                        e,
                    )
                }
            }

            override fun close() {
                try {
                    out.close()
                } catch (e: java.io.IOException) {
                    throw NovelImportIoException(
                        NovelImportIoException.Kind.DESTINATION_WRITE,
                        "关闭应用小说文件失败：${file.absolutePath}",
                        e,
                    )
                }
            }
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

    /**
     * 0 字节的源文件。⚠️ 必须真的有人抛它，否则 `NovelImportFailureReason.EMPTY`
     * （「空文件」）与其文案就是死代码（见 [importOne] 里的判空）。
     */
    internal const val ERROR_EMPTY = "EMPTY"

    /** 显示名越出目标目录（[resolveInside] 的第二道防线）。 */
    internal const val ERROR_UNSAFE_NAME = "UNSAFE_NAME"
}

internal class NovelImportIoException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : java.io.IOException(message, cause) {
    enum class Kind { SOURCE_OPEN, SOURCE_READ, DESTINATION_WRITE, DIRECTORY_SCAN, DIRECTORY_ACCESS }
}
