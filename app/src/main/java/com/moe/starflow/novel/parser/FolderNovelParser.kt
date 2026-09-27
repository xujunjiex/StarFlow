package com.moe.starflow.novel.parser

import com.moe.starflow.mangaimport.data.ArchivedMangaReader
import com.moe.starflow.novel.model.NovelBook
import com.moe.starflow.novel.model.NovelChapterMeta
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 文件夹子：**整个文件夹 = 一部小说**。
 *
 * 与漫画侧同一套心智（整个夹 = 一部作品），区别只在「一章」的粒度：
 * 漫画的子文件夹是一章，小说这里**夹内每个 txt 就是一章**。
 *
 * ### 章标题与顺序
 * - `.txt` → 一章，标题取文件名主干（`01 雾港.txt` → `01 雾港`）
 * - `.epub` → 当作**一卷**，内部章节原样展开，标题加卷名前缀（`卷名 · 章名`）
 *   这样「一部小说 = 若干卷 epub + 若干散章 txt」这种下载形态也能读
 * - 排序用**自然序**（`第10章` 在 `第2章` 之后）—— 字典序会把顺序弄乱
 *
 * ⚠️ 只取**直接子文件**，不递归：递归会把「夹里套了一堆杂七杂八」的情况也吞进来变成
 * 莫名其妙的章节。要递归的话得先明确用户意图（那是多部还是单部的混合结构）。
 */
object FolderNovelParser : NovelParser {

    private const val TAG = "FolderNovelParser"

    private val TEXT_EXTS = setOf("txt")
    private val VOLUME_EXTS = setOf("epub")

    /** locator 里「卷内定位」的分隔符。文件名理论上可能含 `|`，但那种文件极少见。 */
    private const val INNER_SEP = "|"

    override suspend fun parse(file: File): NovelBook = withContext(Dispatchers.IO) {
        if (!file.isDirectory) throw IllegalStateException("NO_TEXT_CHAPTER")
        val children = file.listFiles()
            ?.filter { it.isFile }
            ?.sortedWith(compareBy(ArchivedMangaReader.naturalComparator()) { it.name })
            ?: emptyList()

        val chapters = mutableListOf<NovelChapterMeta>()
        for (child in children) {
            val ext = child.extension.lowercase(Locale.ROOT)
            when {
                ext in TEXT_EXTS -> chapters.add(
                    NovelChapterMeta(
                        index = chapters.size,
                        title = child.nameWithoutExtension.ifBlank { child.name },
                        locator = child.name,
                    )
                )
                ext in VOLUME_EXTS -> chapters.addAll(expandVolume(child))
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

    /** 把一本 epub 展开成若干章，标题前缀卷名，locator 带上「卷文件名 + 卷内定位」。 */
    private suspend fun expandVolume(epub: File): List<NovelChapterMeta> {
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
        val volumeName = epub.nameWithoutExtension
        return inner.chapters.map { c ->
            NovelChapterMeta(
                index = 0, // 由调用方统一重编号
                title = if (c.title.isBlank()) volumeName else "$volumeName · ${c.title}",
                locator = "${epub.name}$INNER_SEP${c.locator}",
            )
        }
    }

    override suspend fun loadChapter(file: File, locator: String): String = withContext(Dispatchers.IO) {
        val sep = locator.indexOf(INNER_SEP)
        if (sep >= 0) {
            // 卷内章：交给 epub 解析器按卷内 locator 取
            val volume = File(file, locator.substring(0, sep))
            val inner = locator.substring(sep + 1)
            return@withContext EpubParser.loadChapter(volume, inner)
        }
        val target = File(file, locator)
        if (!target.isFile) return@withContext ""
        TextEncoding.decode(NovelReadLimits.readFile(target))
    }
}
