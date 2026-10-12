package com.moe.starflow.novel.parser

import com.moe.starflow.mangaimport.data.ArchivedMangaReader
import com.moe.starflow.novel.model.NovelBook
import com.moe.starflow.novel.model.NovelChapterMeta
import com.moe.starflow.novel.translate.NovelParagraphSplitter
import com.moe.starflow.utils.FileAccessDiagnostics
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

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
 *   这样「一部小说 = 若干卷 epub + 若干散章 txt」这种下载形态也能读
 * - 排序用**自然序**（`第10章` 在 `第2章` 之后）—— 字典序会把顺序弄乱
 *
 * 章节文本允许放在章节子文件夹中，保留相对路径并按自然序排列；最多递归 6 层。
 * 整个选中目录仍是一部小说，目录内每个受支持文本文件都是一章。HTML 插图继续按
 * HTML 所在目录解析相对路径。
 */
object FolderNovelParser : NovelParser {

    private const val TAG = "FolderNovelParser"

    private val TEXT_EXTS = setOf("txt")
    private val HTML_EXTS = setOf("html", "htm", "xhtml")
    private val VOLUME_EXTS = setOf("epub")
    private const val MAX_CHAPTER_DEPTH = 6

    /** locator 里「卷内定位」的分隔符。文件名理论上可能含 `|`，但那种文件极少见。 */
    private const val INNER_SEP = "|"

    override suspend fun parse(file: File): NovelBook = withContext(Dispatchers.IO) {
        if (!file.isDirectory) throw IllegalStateException("NO_TEXT_CHAPTER")
        val rootPath = file.canonicalPath
        val children = collectChapterFiles(file, rootPath, 0)
            .sortedWith(compareBy(ArchivedMangaReader.naturalComparator()) { it.relativePath })

        val chapters = mutableListOf<NovelChapterMeta>()
        for ((child, relativePath) in children) {
            val ext = child.extension.lowercase(Locale.ROOT)
            val chapterName = displayName(relativePath)
            when {
                ext in TEXT_EXTS || ext in HTML_EXTS -> chapters.add(
                    NovelChapterMeta(
                        index = chapters.size,
                        title = chapterName,
                        locator = relativePath,
                    )
                )
                ext in VOLUME_EXTS -> chapters.addAll(expandVolume(child, relativePath, chapterName))
            }
        }
        if (chapters.isEmpty()) throw IllegalStateException("NO_TEXT_CHAPTER")

        NovelBook(
            title = file.name.ifBlank { "Untitled" },
            author = null,
            // index 必须等于列表位置（跨卷展开后重新编号）
            chapters = chapters.mapIndexed { i, c -> c.copy(index = i) },
        )
    }

    private data class ChapterFile(val file: File, val relativePath: String)

    private fun collectChapterFiles(directory: File, rootPath: String, depth: Int): List<ChapterFile> {
        if (depth > MAX_CHAPTER_DEPTH) return emptyList()
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
            } else if (child.isFile && isChapterFile(child)) {
                out += ChapterFile(child, relative)
            }
        }
        return out
    }

    private fun isChapterFile(file: File): Boolean {
        val ext = file.extension.lowercase(Locale.ROOT)
        return ext in TEXT_EXTS || ext in HTML_EXTS || ext in VOLUME_EXTS
    }

    private fun displayName(relativePath: String): String {
        val base = relativePath.substringAfterLast('/').substringBeforeLast('.', relativePath)
        val folder = relativePath.substringBeforeLast('/', "")
        return if (folder.isBlank()) base else "$folder · $base"
    }

    /** 把一本 epub 展开成若干章，locator 保留分卷相对路径与卷内定位。 */
    private suspend fun expandVolume(
        epub: File,
        relativePath: String,
        volumeName: String,
    ): List<NovelChapterMeta> {
        val inner = try {
            EpubParser.parse(epub)
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
        return inner.chapters.map { c ->
            NovelChapterMeta(
                index = 0, // 由调用方统一重编号
                title = if (c.title.isBlank()) volumeName else "$volumeName · ${c.title}",
                locator = "$relativePath$INNER_SEP${c.locator}",
            )
        }
    }

    override suspend fun loadChapter(file: File, locator: String): String =
        loadChapterSource(file, locator).text

    /**
     * 正文 + 图片。
     *
     * - 夹里的 **epub 卷**：整条委托给 [EpubParser.loadChapterSource]（卷内插图照常显示）
     * - 夹里的 **html**：与单 HTML 同一条路（[HtmlParser.loadChapterSource]）——
     *   基准目录就是 html 所在目录，
     *   而导入时 `images/` 这类子目录里的图片**已经递归拷进来了**，所以插图能显示
     * - 夹里的 **txt**：纯文本（「一行一段」，与 [TxtParser] 同一套）
     */
    override suspend fun loadChapterSource(file: File, locator: String): ChapterSource =
        withContext(Dispatchers.IO) {
            val sep = locator.indexOf(INNER_SEP)
            if (sep >= 0) {
                // 卷内章：交给 epub 解析器按卷内 locator 取
                val volume = File(file, locator.substring(0, sep))
                val inner = locator.substring(sep + 1)
                return@withContext EpubParser.loadChapterSource(volume, inner)
            }
            val target = File(file, locator)
            if (!target.isFile) return@withContext ChapterSource.EMPTY
            if (target.extension.lowercase(Locale.ROOT) in HTML_EXTS) {
                // 图片基准 = html 的父目录；导入保留相对目录，
                // 所以 `images/a.jpg` 与 `../` 都由 `ZipImages.resolveLocal`
                // 做「夹在基准目录内」的越界守卫。
                return@withContext HtmlParser.loadChapterSource(target, "")
            }
            // 夹内的散章都是 txt（见 NovelImporter.DIR_TEXT_EXTS）→ 与 TxtParser 同一套「一行一段」
            ChapterSource(
                NovelParagraphSplitter.linesToParagraphs(
                    TextEncoding.decode(NovelReadLimits.readFile(target))
                )
            )
        }
}
