package com.moe.starflow.novel.parser

import com.moe.starflow.novel.model.NovelBook
import com.moe.starflow.novel.model.NovelChapterMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 单 HTML 文件：整篇 = 一章。 */
object HtmlParser : NovelParser {

    private const val LOCATOR = ""

    override suspend fun parse(file: File): NovelBook = withContext(Dispatchers.IO) {
        val raw = TextEncoding.decode(file.readBytes())
        val fallbackTitle = file.nameWithoutExtension.ifBlank { "Untitled" }
        val chapter = NovelChapterMeta(
            index = 0,
            title = HtmlTextExtractor.titleOf(raw) ?: fallbackTitle,
            locator = LOCATOR,
        )
        NovelBook(
            title = HtmlTextExtractor.titleOf(raw) ?: fallbackTitle,
            author = null,
            chapters = listOf(chapter),
        )
    }

    override suspend fun loadChapter(file: File, locator: String): String = withContext(Dispatchers.IO) {
        HtmlTextExtractor.extract(TextEncoding.decode(file.readBytes()))
    }
}
