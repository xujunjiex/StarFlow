package com.moe.starflow.mangaimport.data

import android.content.Context
import com.moe.starflow.novel.data.NovelLibraryScanner
import com.moe.starflow.novel.data.NovelStore
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
class MangaLibraryScannerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.getExternalFilesDir(null)!!.listFiles()?.forEach { it.deleteRecursively() }
        runBlocking { com.moe.starflow.library.LibrarySessions.prepare(context) }
    }

    @Test
    fun epubWithImagesIsOnlyAddedToNovelShelfEvenWhenRenamedZip() = runBlocking {
        val files = StorageDirStore.root(context)
        val epub = File(com.moe.starflow.novel.data.NovelStorageDir.root(context), "novel.epub")
        val renamedEpub = File(com.moe.starflow.novel.data.NovelStorageDir.root(context), "renamed.zip")
        LibraryScanFixtures.epub(epub)
        LibraryScanFixtures.epub(renamedEpub)
        val manga = File(files, "manga.cbz")
        LibraryScanFixtures.zip(manga, "001.png" to LibraryScanFixtures.png())

        // EPUB content in the manga folder must also be excluded.
        epub.copyTo(File(files, "novel.zip"))
        val result = MangaLibraryScanner.scan(context)

        assertEquals(1, result.added)
        assertTrue(result.failures.isEmpty())
        assertEquals(manga.canonicalPath, File(ImportedMangaStore.load(context).single().localRoot).canonicalPath)
        assertEquals(2, NovelLibraryScanner.scan(context).added)
        assertEquals(setOf(epub.canonicalPath, renamedEpub.canonicalPath), NovelStore.load(context).map { File(it.localRoot).canonicalPath }.toSet())
        assertTrue(epub.exists() && renamedEpub.exists())
        assertEquals(0, MangaLibraryScanner.scan(context).added)
    }

    @Test
    fun chapterImagesAreScannedInNaturalOrder() = runBlocking {
        val directory = File(StorageDirStore.root(context), "Manga")
        File(directory, "Chapter2").mkdirs()
        File(directory, "Chapter2/10.png").writeBytes(LibraryScanFixtures.png())
        File(directory, "Chapter2/2.png").writeBytes(LibraryScanFixtures.png())

        assertEquals(1, MangaLibraryScanner.scan(context).added)
        val manga = ImportedMangaStore.load(context).single()
        assertEquals(2, manga.pageCount)
        assertEquals(listOf("Chapter2/2.png", "Chapter2/10.png"), ArchivedMangaReader.listImageFilesInDir(File(manga.localRoot)))
    }
}
