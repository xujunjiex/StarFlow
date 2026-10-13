package com.moe.starflow.novel.data

import android.content.Context
import com.moe.starflow.novel.model.NovelFormat
import com.moe.starflow.novel.parser.FolderNovelParser
import com.moe.starflow.utils.LibraryScanFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelLibraryScannerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.getExternalFilesDir(null)!!.listFiles()?.forEach { it.deleteRecursively() }
        runBlocking { com.moe.starflow.library.LibrarySessions.prepare(context) }
    }

    @Test
    fun indexedEpubWithLegacyImageCacheIsNotImportedAsFolder() = runBlocking {
        val file = File(NovelStorageDir.bookDir(context, 1), "book.epub")
        LibraryScanFixtures.epub(file)
        File(file.parentFile, "images").mkdirs()
        val original = ImportedNovel(
            id = 1, title = "Original", localRoot = file.path, format = NovelFormat.EPUB,
            chapterCount = 1, addedAt = 10,
        )
        NovelStore.add(context, original)

        val result = NovelLibraryScanner.scan(context)

        assertEquals(0, result.added)
        assertTrue(result.failures.isEmpty())
        assertEquals("Original", NovelStore.load(context).single().title)
        assertEquals(original.localRoot, NovelStore.load(context).single().localRoot)
    }

    @Test
    fun scannedChapterFolderAndManagedFilesRemainDistinct() = runBlocking {
        val root = NovelStorageDir.root(context)
        val folder = File(root, "Chapters")
        File(folder, "Volume2").mkdirs()
        File(folder, "01.txt").writeText("Root chapter body")
        File(folder, "Volume2/02.txt").writeText("Nested chapter body")
        File(root, "sr_ncnn_benchmark.txt").writeText("Benchmark output")
        val cached = File(root, ".image_cache/7_10").apply { mkdirs() }
        File(cached, "ignored.txt").writeText("Cache is not a novel")

        val result = NovelLibraryScanner.scan(context)
        val book = NovelStore.load(context).single()

        assertEquals(1, result.added)
        assertTrue(result.failures.isEmpty())
        assertEquals(NovelFormat.FOLDER, book.format)
        val chapters = FolderNovelParser.parse(File(book.localRoot)).chapters
        assertEquals(listOf("01.txt", "Volume2/02.txt"), chapters.map { it.locator })
        assertEquals("Nested chapter body", FolderNovelParser.loadChapter(folder, chapters[1].locator))
        assertEquals(0, NovelLibraryScanner.scan(context).added)
        assertEquals(listOf(book), NovelStore.load(context))
    }
}
