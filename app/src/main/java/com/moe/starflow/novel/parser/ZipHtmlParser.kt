package com.moe.starflow.novel.parser

import com.moe.starflow.mangaimport.data.ArchivedMangaReader
import com.moe.starflow.novel.model.NovelBook
import com.moe.starflow.novel.model.NovelChapterMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * ZIP 小说包：包内每个 html/xhtml/txt 条目 = 一章。
 *
 * ⚠️ 排序必须用**自然序**（`第2章` 排在 `第10章` 前面），不能用字典序 —— 字典序会给出
 * `1, 10, 11, 2, 3...` 的错乱阅读顺序，用户读到一半发现章节跳了。复用
 * [ArchivedMangaReader.sortNaturally]，与漫画侧同一套比较规则。
 */
object ZipHtmlParser : NovelParser {

    private val TEXT_EXTS = setOf("html", "htm", "xhtml", "txt")

    override suspend fun parse(file: File): NovelBook = withContext(Dispatchers.IO) {
        var description = ""
        var author: String? = null
        val entries = ZipFile(file).use { zip ->
            // 条目数先封顶，再开始迭代（见 NovelReadLimits）
            NovelReadLimits.checkEntryCount(zip)
            val out = mutableListOf<String>()
            val e = zip.entries()
            while (e.hasMoreElements()) {
                val entry = e.nextElement()
                if (entry.isDirectory) continue
                val ext = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext in TEXT_EXTS) out.add(entry.name)
            }
            out
        }
        val sorted = ArchivedMangaReader.sortNaturally(entries)
        // A package may keep its book metadata in the first HTML document's head.
        sorted.firstOrNull { !it.endsWith(".txt", true) }?.let { name ->
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(name) ?: return@use
                val head = zip.getInputStream(entry).use { NovelReadLimits.readAtMost(it, 64 * 1024) }
                val raw = TextEncoding.decode(head)
                description = HtmlParser.descriptionOf(raw)
                author = HtmlParser.authorOf(raw)
            }
        }
        val chapters = sorted.mapIndexed { i, name ->
            NovelChapterMeta(
                index = i,
                title = name.substringAfterLast('/').substringBeforeLast('.'),
                locator = name,
            )
        }
        NovelBook(
            title = file.nameWithoutExtension.ifBlank { "Untitled" },
            author = author,
            chapters = chapters,
            description = description,
        )
    }

    override suspend fun loadChapter(file: File, locator: String): String =
        loadChapterSource(file, locator).text

    /**
     * 正文 + 章内插图。
     *
     * `.txt` 条目是纯文本，不能再走 HTML 提取（会把正文里的 `<` 之类当标签吃掉）——
     * 也因此**它没有图片**，直接返回纯文本。
     */
    override suspend fun loadChapterSource(file: File, locator: String): ChapterSource =
        withContext(Dispatchers.IO) {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(locator) ?: return@withContext ChapterSource.EMPTY
                val budget = NovelReadLimits.ZipBudget()
                val text = TextEncoding.decode(budget.readEntry(zip, entry))
                if (locator.endsWith(".txt", ignoreCase = true)) {
                    ChapterSource(text)
                } else {
                    val ex = HtmlTextExtractor.extractWithImages(text)
                    // ⚠️ 基准目录带结尾 `/`（见 [ZipImages.baseDirOf]）
                    val base = ZipImages.baseDirOf(locator)
                    ChapterSource(ex.text, ZipImages.read(zip, base, ex.imageSources, budget))
                }
            }
        }
}
