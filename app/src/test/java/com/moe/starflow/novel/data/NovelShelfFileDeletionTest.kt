package com.moe.starflow.novel.data

import android.content.Context
import com.moe.starflow.novel.model.NovelFormat
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
class NovelShelfFileDeletionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        NovelStore.save(context, emptyList())
        NovelStorageDir.root(context).deleteRecursively()
    }

    private fun book(id: Long, file: File, format: NovelFormat = NovelFormat.TXT) = ImportedNovel(
        id = id, title = "Book $id", localRoot = file.path, format = format,
        chapterCount = 1, addedAt = id * 10,
    )

    @Test
    fun deletingLooseScannedSourcePreservesAnotherBooksNumberedDirectory() {
        val root = NovelStorageDir.root(context)
        val loose = File(root, "loose.txt").apply { writeText("Delete this source") }
        val otherDirectory = NovelStorageDir.bookDir(context, 2).apply { mkdirs() }
        val otherSource = File(otherDirectory, "other.txt").apply { writeText("Keep this source") }
        val scanned = book(2, loose)
        val other = book(3, otherSource)
        NovelStore.save(context, listOf(other))
        val cache = NovelStorageDir.imageCacheDir(root, scanned).apply { mkdirs() }
        File(cache, "picture").writeText("Cached image")
        val otherCache = NovelStorageDir.imageCacheDir(root, other).apply { mkdirs() }
        val cover = File(NovelImporter.placeholderCover(context, scanned.id, scanned.title))

        NovelImporter.deleteShelfBookFiles(context, scanned)

        assertFalse(loose.exists())
        assertEquals("Keep this source", otherSource.readText())
        assertFalse(cache.exists())
        assertTrue(otherCache.isDirectory)
        assertFalse(cover.exists())
    }

    @Test
    fun deletingLegacyDuplicateFolderPreservesIndexedChildSource() {
        val directory = NovelStorageDir.bookDir(context, 1).apply { mkdirs() }
        val source = File(directory, "original.txt").apply { writeText("Shared original") }
        NovelStore.add(context, book(1, source))

        NovelImporter.deleteShelfBookFiles(context, book(2, directory, NovelFormat.FOLDER))

        assertEquals("Shared original", source.readText())
    }

    @Test
    fun deletingNormalImportedBookRemovesItsSourceAndLegacyImages() {
        val directory = NovelStorageDir.bookDir(context, 1).apply { mkdirs() }
        val source = File(directory, "book.txt").apply { writeText("Book body") }
        File(directory, "images").mkdirs()

        NovelImporter.deleteShelfBookFiles(context, book(1, source))

        assertFalse(directory.exists())
    }
}
