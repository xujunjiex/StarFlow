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
        val chapters = sorted.mapIndexed { i, name ->
            NovelChapterMeta(
                index = i,
                title = name.substringAfterLast('/').substringBeforeLast('.'),
                locator = name,
            )
        }
        NovelBook(
            title = file.nameWithoutExtension.ifBlank { "Untitled" },
            author = null,
            chapters = chapters,
        )
    }

    override suspend fun loadChapter(file: File, locator: String): String = withContext(Dispatchers.IO) {
        val raw = ZipFile(file).use { zip ->
            val entry = zip.getEntry(locator) ?: return@withContext ""
            NovelReadLimits.readZipEntry(zip, entry)
        }
        val text = TextEncoding.decode(raw)
        // .txt 条目是纯文本，不能再走 HTML 提取（会把正文里的 `<` 之类当标签吃掉）
        if (locator.endsWith(".txt", ignoreCase = true)) text else HtmlTextExtractor.extract(text)
    }
}
