package com.moe.starflow.novel.parser

import com.moe.starflow.library.LibrarySources
import com.moe.starflow.novel.model.NovelBook
import com.moe.starflow.novel.model.NovelFormat
import java.io.File

/** Keeps the file-only parsers behind source leases; folder chapters and illustrations are fetched on demand. */
internal class LibraryNovelParser(private val format: NovelFormat, private val delegate: NovelParser) : NovelParser {
    override suspend fun parse(file: File): NovelBook = LibrarySources.acquire(file).use {
        delegate.parse(LibrarySources.file(file, folderTexts = format == NovelFormat.FOLDER))
    }
    private fun prepareChapter(file: File, locator: String): File {
        if (format != NovelFormat.FOLDER) return LibrarySources.file(file)
        file.mkdirs()
        LibrarySources.file(File(file, locator.substringBefore('|')))
        return file
    }
    override suspend fun loadChapter(file: File, locator: String): String = LibrarySources.acquire(file).use {
        delegate.loadChapter(prepareChapter(file, locator), locator)
    }
    override suspend fun loadChapterSource(file: File, locator: String): ChapterSource = LibrarySources.acquire(file).use {
        delegate.loadChapterSource(prepareChapter(file, locator), locator)
    }
}
