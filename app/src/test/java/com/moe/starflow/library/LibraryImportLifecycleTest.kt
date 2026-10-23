package com.moe.starflow.library

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import com.moe.starflow.mangaimport.data.MangaImporter
import com.moe.starflow.utils.LibraryScanFixtures
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryImportLifecycleTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var app: Context
    private lateinit var session: LibrarySession

    @Before fun setup() = runBlocking {
        app = RuntimeEnvironment.getApplication()
        val root = temp.newFolder("library")
        app.getSharedPreferences("library_locations", 0).edit().clear().putString("defaultPath", root.path).commit()
        LibrarySessions.prepare(app)
        session = LibrarySessions.current(app)
    }

    @Test fun coverPixelsAreBoundedAndBothInputPassesAreClosed() {
        val original = Bitmap.createBitmap(2400, 1600, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use {
            try { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray() }
            finally { original.recycle() }
        }
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val decoded = ImportCoverDecoder.decode(600) {
            opens.incrementAndGet()
            object : ByteArrayInputStream(bytes) {
                override fun close() { closes.incrementAndGet(); super.close() }
            }
        }!!
        try {
            assertEquals(600, decoded.width)
            assertEquals(400, decoded.height)
            assertTrue(decoded.allocationByteCount <= 600 * 600 * 4)
            assertEquals(2, opens.get())
            assertEquals(2, closes.get())
        } finally { decoded.recycle() }
    }

    @Test fun failedCoverDecodeStillClosesItsStream() {
        var closed = false
        // The legacy bitmap shadow otherwise invents a bitmap even for malformed image bytes.
        org.robolectric.shadows.ShadowBitmapFactory.setAllowInvalidImageData(false)
        try {
            assertNull(ImportCoverDecoder.decode(600) {
                object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
                    override fun close() { closed = true; super.close() }
                }
            })
        } finally { org.robolectric.shadows.ShadowBitmapFactory.setAllowInvalidImageData(true) }
        assertTrue(closed)
    }

    @Test fun concurrentTimestampsIncludeUncommittedImportsAndSurviveRemoval() = runBlocking {
        val future = System.currentTimeMillis() + 86_400_000
        val source = session.local("manga_import/existing.cbz").apply { writeText("source") }
        ImportedMangaStore.add(app, ImportedManga(id = 1, title = "Existing", localRoot = source.path,
            isArchive = true, coverPath = null, pageCount = 1, addedAt = future))
        session.flush()
        val allocated = List(8) { async(Dispatchers.IO) { List(25) { session.nextAddedAt("manga") } } }.awaitAll().flatten()
        assertEquals(200, allocated.toSet().size)
        assertTrue(allocated.min() > future)
        session.removeBook("manga", 1)
        assertTrue(session.nextAddedAt("manga") > allocated.max())
        assertTrue(session.nextAddedAt("novel") < future)
    }

    @Test fun abandonedImportClearsGeneratedIndexesArchivesAndCovers() = runBlocking {
        val original = temp.newFile("external.cbz")
        LibraryScanFixtures.zip(original, "001.png" to LibraryScanFixtures.png())
        val id = session.nextId("manga")
        session.beginImport("manga", id)
        val uuid = session.uuid("manga", id)
        val source = session.local("manga_import/$uuid/book.cbz").apply { parentFile!!.mkdirs(); original.copyTo(this) }
        val parsed = MangaImporter.inspectLocalSource(app, source, id, "Book")
        assertTrue(session.local(parsed.sourceIndexPath).isFile)
        assertTrue(session.recordArchive(parsed.sourceIndexPath).isFile)
        assertNotNull(parsed.coverPath)
        assertTrue(source.parentFile!!.deleteRecursively())
        session.abandonImport("manga", id)
        assertFalse(session.local(".starflow/books/$uuid").exists())
        assertFalse(session.recordArchive(".starflow/books/$uuid").exists())
        assertFalse(File(parsed.coverPath!!).exists())
        assertEquals("", session.database.catalog().meta("importing:manga:$id"))
        assertTrue(original.isFile)
    }

    @Test fun rejectedScanCleansItsArtifactsWithoutDeletingTheManualSource() = runBlocking {
        val source = session.local("manga_import/manual.cbz")
        LibraryScanFixtures.zip(source, "001.png" to LibraryScanFixtures.png())
        val id = session.nextId("manga")
        val parsed = MangaImporter.inspectLocalSource(app, source, id, "Manual")
        session.discardUncommittedArtifacts("manga", id)
        assertTrue(source.isFile)
        assertFalse(session.local(parsed.sourceIndexPath).exists())
        assertFalse(session.recordArchive(parsed.sourceIndexPath).exists())
        assertFalse(File(parsed.coverPath!!).exists())
    }

    @Test fun cleanupNeverDeletesAnAlreadyCommittedBook() = runBlocking {
        val source = session.local("manga_import/kept.cbz")
        LibraryScanFixtures.zip(source, "001.png" to LibraryScanFixtures.png())
        val id = session.nextId("manga")
        val parsed = MangaImporter.inspectLocalSource(app, source, id, "Kept")
        ImportedMangaStore.add(app, parsed)
        session.flush()
        session.discardUncommittedArtifacts("manga", id)
        assertNotNull(session.database.catalog().book("manga", id))
        assertTrue(source.isFile)
        assertTrue(session.local(parsed.sourceIndexPath).isFile)
        assertTrue(session.recordArchive(parsed.sourceIndexPath).isFile)
    }

    @Test fun failedScanPreservesTheIdentityAndArchivesOfAnExistingUuid() = runBlocking {
        val uuid = java.util.UUID.randomUUID().toString()
        val source = "manga_import/$uuid"
        session.local(source).mkdirs()
        session.local("$source/broken.cbz").writeText("invalid archive")
        val identity = ".starflow/books/$uuid/source.json"
        session.storage.writeNew(identity).use { it.write(org.json.JSONObject().put("format", 1)
            .put("kind", "manga").put("uuid", uuid).put("source", source).put("sourceName", "Previous book")
            .toString().toByteArray()) }
        val archive = session.recordArchive(".starflow/books/$uuid/previous.json")
        archive.parentFile!!.mkdirs()
        archive.writeText("retained history")
        val result = LibraryScanner.scan(app, "manga")
        assertEquals(0, result.added)
        assertTrue(result.failures.isNotEmpty())
        assertTrue(session.storage.exists(identity))
        assertEquals("retained history", archive.readText())
        assertTrue(session.local("$source/broken.cbz").isFile)
    }

    @Test fun cleanupFailureKeepsTheDurableIntentUntilRetrySucceeds() {
        val backend = FileLibraryStorage(temp.newFolder("retry-library"))
        var fail = true
        val storage = object : LibraryStorage by backend {
            override fun delete(path: String) {
                if (fail && path.startsWith(".starflow/books/")) throw IOException("Cannot remove index yet")
                backend.delete(path)
            }
        }
        val db = Room.inMemoryDatabaseBuilder(app, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val retry = LibrarySession(app, storage, db, 1)
        try {
            retry.prepare()
            retry.beginImport("manga", 1)
            val uuid = retry.uuid("manga", 1)
            val key = "importing:manga:1"
            assertThrows(IOException::class.java) { retry.abandonImport("manga", 1) }
            assertEquals("manga_import/$uuid", db.catalog().meta(key))
            fail = false
            retry.abandonImport("manga", 1)
            assertEquals("", db.catalog().meta(key))
        } finally { retry.close() }
    }
}
