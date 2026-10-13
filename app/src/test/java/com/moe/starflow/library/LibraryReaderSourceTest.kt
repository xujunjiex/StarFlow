package com.moe.starflow.library

import android.content.Context
import android.net.Uri
import com.moe.starflow.mangaimport.reader.ReaderPageSource
import com.moe.starflow.novel.data.NovelImporter
import com.moe.starflow.novel.data.NovelStore
import com.moe.starflow.utils.LibraryScanFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryReaderSourceTest {
    @Test fun zipAndFolderSharePageOrderAndStreamsWithoutWholeExtraction() = runBlocking {
        val app: Context = RuntimeEnvironment.getApplication()
        LibrarySessions.prepare(app)
        val owner = LibrarySessions.current(app)
        val zip = owner.local("manga_import/book.cbz")
        val png = LibraryScanFixtures.png()
        LibraryScanFixtures.zip(zip, "chapter2/2.png" to png, "chapter1/1.png" to png)
        val folder = owner.local("manga_import/folder")
        listOf("chapter1/1.png", "chapter2/2.png").forEach { File(folder, it).apply { parentFile!!.mkdirs(); writeBytes(png) } }
        val compressed = ReaderPageSource(true, zip.path)
        val loose = ReaderPageSource(false, folder.path)
        assertEquals(loose.orderedPageKeys(), compressed.orderedPageKeys())
        assertTrue(LibrarySources.hasReaders(zip))
        assertArrayEquals(png, compressed.openEntry(0)!!.use { it.readBytes() })
        assertArrayEquals(png, loose.openEntry(0)!!.use { it.readBytes() })
        compressed.close(); loose.close()
        assertFalse(LibrarySources.hasReaders(zip))
        assertNull(compressed.openEntry(0))
        assertTrue(zip.isFile)
        assertFalse(owner.local("manga_import/pages").exists())
    }

    @Test fun repeatedManualImportsCreateIndependentSourcesAndProgress() = runBlocking {
        val app: Context = RuntimeEnvironment.getApplication()
        LibrarySessions.prepare(app)
        val source = File(app.cacheDir, "book.txt").apply { writeText("Chapter 1\nBody paragraph") }
        val first = NovelImporter.importFile(app, Uri.fromFile(source), 1)
        NovelStore.add(app, first)
        val second = NovelImporter.importFile(app, Uri.fromFile(source), 2)
        NovelStore.add(app, second)
        val saved = NovelStore.load(app)
        assertEquals(2, saved.map { it.bookUuid }.toSet().size)
        assertNotEquals(saved[0].localRoot, saved[1].localRoot)
        NovelStore.patch(app, saved[0]) { it.copy(lastReadParaIndex = 5) }
        assertEquals(0, NovelStore.load(app).first { it.id == 2L }.lastReadParaIndex)
        File(saved[0].localRoot).delete()
        assertTrue(File(saved[1].localRoot).isFile)
        assertTrue(source.isFile)
    }
}
