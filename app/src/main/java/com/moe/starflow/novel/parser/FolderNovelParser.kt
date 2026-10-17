package com.moe.starflow.novel.parser

import com.moe.starflow.mangaimport.data.ArchivedMangaReader
import com.moe.starflow.novel.model.NovelBook
import com.moe.starflow.novel.model.NovelChapterMeta
import com.moe.starflow.novel.model.NovelFormat
import com.moe.starflow.novel.translate.NovelParagraphSplitter
import com.moe.starflow.utils.FileAccessDiagnostics
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 文件夹子：**整个文件夹 = 一部小说**。
 *
 * 与漫画侧同一套心智（整个夹 = 一部作品），区别只在「一章」的粒度：
 * 漫画的子文件夹是一章，小说这里**夹内每个文本文件就是一章**。
 *
 * ### 章标题与顺序
 * - `.txt` → 一章，标题取文件名主干（`01 雾港.txt` → `01 雾港`）
 * - `.html/.htm/.xhtml` → 一章（2026-10-04 加，用户要求「所有格式都支持图片预览」）：
 *   「一堆 html + 一个 images/ 目录」是小说下载最常见的分发形态；导入时图片也一起拷进来，
 *   所以这类章节的插图能显示（见 [loadChapterSource]）
 * - `.epub` → 当作**一卷**，内部章节原样展开，标题加卷名前缀（`卷名 · 章名`）
 * - 含 HTML/TXT 章节的 `.zip` → 同样当作一卷，包内章节与插图按 ZIP 解析器读取
 *   这样「一部小说 = 若干卷 epub/zip + 若干散章 txt/html」这种下载形态也能读
 * - 排序用**自然序**（`第10章` 在 `第2章` 之后）—— 字典序会把顺序弄乱
 *
 * 章节文本允许放在章节子文件夹中，保留相对路径并按自然序排列；最多递归 6 层。
 * 整个选中目录仍是一部小说，目录内每个受支持文本文件都是一章。HTML 插图继续按
 * HTML 所在目录解析相对路径。
 */
object FolderNovelParser : NovelParser {

    private const val TAG = "FolderNovelParser"

    private const val MAX_CHAPTER_DEPTH = 6

    /** locator 里「卷内定位」的分隔符。文件名理论上可能含 `|`，但那种文件极少见。 */
    private const val INNER_SEP = "|"

    override suspend fun parse(file: File): NovelBook = withContext(Dispatchers.IO) {
        if (!file.isDirectory) throw IllegalStateException("NO_TEXT_CHAPTER")
        val rootPath = file.canonicalPath
        val children = collectChapterFiles(file, rootPath, 0)
            .sortedWith(compareBy(ArchivedMangaReader.naturalComparator()) { it.relativePath })

        val chapters = mutableListOf<NovelChapterMeta>()
        val volumes = mutableListOf<NovelBook>()
        val htmlHeader = children.firstOrNull { it.format == NovelFormat.HTML }?.file?.let { html ->
            TextEncoding.decode(html.inputStream().use { NovelReadLimits.readAtMost(it, 64 * 1024) })
        }
        for ((child, relativePath, format) in children) {
            val chapterName = displayName(relativePath)
            when (format) {
                NovelFormat.TXT, NovelFormat.HTML -> chapters.add(
                    NovelChapterMeta(
                        index = chapters.size,
                        title = chapterName,
                        locator = relativePath,
                    )
                )
                NovelFormat.EPUB, NovelFormat.ZIP_HTML ->
                    chapters.addAll(expandVolume(child, relativePath, chapterName, format, volumes))
                else -> Unit
            }
        }
        if (chapters.isEmpty()) throw IllegalStateException("NO_TEXT_CHAPTER")

        NovelBook(
            title = file.name.ifBlank { "Untitled" },
            author = volumes.firstNotNullOfOrNull { it.author } ?: htmlHeader?.let(HtmlParser::authorOf),
            // index 必须等于列表位置（跨卷展开后重新编号）
            chapters = chapters.mapIndexed { i, c -> c.copy(index = i) },
            description = volumes.firstOrNull { it.description.isNotBlank() }?.description ?: htmlHeader?.let(HtmlParser::descriptionOf).orEmpty(),
            coverBytes = volumes.firstNotNullOfOrNull { it.coverBytes },
        )
    }

    private data class ChapterFile(val file: File, val relativePath: String, val format: NovelFormat)

    private fun collectChapterFiles(directory: File, rootPath: String, depth: Int): List<ChapterFile> {
        if (depth > MAX_CHAPTER_DEPTH) throw IOException("章节目录层级超过支持深度：${directory.path}")
        val children = directory.listFiles() ?: throw IOException(
            "枚举小说章节目录失败：${directory.absolutePath}；File.listFiles() 返回 null " +
                "(${FileAccessDiagnostics.describe(directory)})"
        )
        val out = ArrayList<ChapterFile>()
        for (child in children) {
            val canonical = runCatching { child.canonicalPath }.getOrNull() ?: continue
            if (canonical != rootPath && !canonical.startsWith(rootPath + File.separator)) continue
            val relative = canonical.removePrefix(rootPath).trimStart(File.separatorChar)
                .replace(File.separatorChar, '/')
            if (child.isDirectory) {
                out += collectChapterFiles(child, rootPath, depth + 1)
            } else if (child.isFile) {
                val format = NovelFormatDetector.detect(child)
                if (format != null && format in SUPPORTED_CHAPTER_FORMATS) out += ChapterFile(child, relative, format)
            }
        }
        return out
    }

    private fun displayName(relativePath: String): String =
        relativePath.substringAfterLast('/').substringBeforeLast('.', relativePath)

    /** 把一本 epub 展开成若干章，locator 保留分卷相对路径与卷内定位。 */
    private suspend fun expandVolume(
        epub: File,
        relativePath: String,
        volumeName: String,
        format: NovelFormat,
        volumes: MutableList<NovelBook>,
    ): List<NovelChapterMeta> {
        val inner = try {
            when (format) {
                NovelFormat.EPUB -> EpubParser.parse(epub)
                NovelFormat.ZIP_HTML -> ZipHtmlParser.parse(epub)
                else -> return emptyList()
            }
        } catch (e: CancellationException) {
            // 取消（用户退出了导入 / 切走了）不是「坏卷」：吞掉它就变成「取消后照样导入成功，
            // 但少了一卷」—— 必须原样抛出去让协程正常结束
            throw e
        } catch (e: Exception) {
            // 坏卷只丢这一卷（不能让整部书导入失败 —— 用户还有别的卷能读），但**必须留日志**：
            // 静默丢卷时书照样「导入成功」，用户只会发现凭空少了几章，却无从查起
            LogCollector.w(TAG, "卷解析失败，已跳过: ${epub.name}", e)
            null
        } ?: return emptyList()
        volumes += inner
        return inner.chapters.map { c ->
            NovelChapterMeta(
                index = 0, // 由调用方统一重编号
                title = chapterTitle(c.title, volumeName, c.locator),
                locator = "$relativePath$INNER_SEP${c.locator}",
            )
        }
    }

    /** 优先显示卷内章节名；导出器把卷名重复写入章节标题时将其剥掉。 */
    private fun chapterTitle(title: String, volumeName: String, locator: String): String {
        val locatorTitle = locator.substringAfterLast('/').substringBeforeLast('.', locator)
        val original = title.trim()
        if (original.isBlank()) return locatorTitle.ifBlank { volumeName }
        val volumeBase = volumeName.substringAfterLast('/').substringBeforeLast('.', volumeName).trim()
        val withoutVolume = if (volumeBase.isNotBlank() && original.startsWith(volumeBase, ignoreCase = true)) {
            original.drop(volumeBase.length).trimStart(' ', '·', '-', '—', '_', '.', ':', '：')
        } else original
        return withoutVolume.ifBlank { locatorTitle.ifBlank { volumeName } }
    }

    override suspend fun loadChapter(file: File, locator: String): String =
        loadChapterSource(file, locator).text

    override suspend fun loadChapterText(file: File, locator: String): ChapterText = withContext(Dispatchers.IO) {
        val sep = locator.indexOf(INNER_SEP)
        if (sep >= 0) {
            val volume = File(file, locator.substring(0, sep))
            val inner = locator.substring(sep + 1)
            return@withContext when (NovelFormatDetector.detect(volume)) {
                NovelFormat.EPUB -> EpubParser.loadChapterText(volume, inner)
                else -> ChapterText(loadChapterSource(file, locator).text)
            }
        }
        ChapterText(loadChapterSource(file, locator).text)
    }

    /**
     * 正文 + 图片。
     *
     * - 夹里的 **epub / ZIP HTML 卷**：整条委托给对应解析器（包内插图照常显示）
     * - 夹里的 **html**：与单 HTML 同一条路（[HtmlParser.loadChapterSource]）——
     *   基准目录就是 html 所在目录，
     *   而导入时 `images/` 这类子目录里的图片**已经递归拷进来了**，所以插图能显示
     * - 夹里的 **txt**：纯文本（「一行一段」，与 [TxtParser] 同一套）
     */
    override suspend fun loadChapterSource(file: File, locator: String): ChapterSource =
        withContext(Dispatchers.IO) {
            val sep = locator.indexOf(INNER_SEP)
            if (sep >= 0) {
                // 卷内章：按实际容器类型交给相应解析器
                val volume = File(file, locator.substring(0, sep))
                val inner = locator.substring(sep + 1)
                return@withContext when (NovelFormatDetector.detect(volume)) {
                    NovelFormat.EPUB -> EpubParser.loadChapterSource(volume, inner)
                    NovelFormat.ZIP_HTML -> ZipHtmlParser.loadChapterSource(volume, inner)
                    else -> ChapterSource.EMPTY
                }
            }
            val target = File(file, locator)
            if (!target.isFile) return@withContext ChapterSource.EMPTY
            if (NovelFormatDetector.detect(target) == NovelFormat.HTML) {
                // 图片基准 = html 的父目录；导入保留相对目录，
                // 所以 `images/a.jpg` 与 `../` 都由 `ZipImages.resolveLocal`
                // 做「夹在基准目录内」的越界守卫。
                return@withContext HtmlParser.loadChapterSourceWithin(target, file)
            }
            // 夹内的散章都是 txt（见 NovelImporter.DIR_TEXT_EXTS）→ 与 TxtParser 同一套「一行一段」
            ChapterSource(
                NovelParagraphSplitter.linesToParagraphs(
                    TextEncoding.decode(NovelReadLimits.readFile(target))
                )
            )
        }

    private val SUPPORTED_CHAPTER_FORMATS = setOf(
        NovelFormat.TXT, NovelFormat.HTML, NovelFormat.EPUB, NovelFormat.ZIP_HTML,
    )
}
