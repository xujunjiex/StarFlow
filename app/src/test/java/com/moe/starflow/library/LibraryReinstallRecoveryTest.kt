package com.moe.starflow.library

import android.content.Context
import androidx.room.Room
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import com.moe.starflow.novel.data.NovelStore
import com.moe.starflow.novel.model.NovelFormat
import com.moe.starflow.utils.LibraryScanFixtures
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/** A reinstall/clear-data leaves the directory identity but no local database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryReinstallRecoveryTest {
    private lateinit var app: Context
    private lateinit var root: File
    private val databases = mutableListOf<LibraryDatabase>()

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        root = File(app.cacheDir, "library-reinstall-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun cleanup() {
        databases.forEach { it.close() }
        root.deleteRecursively()
    }

    private fun open(storage: LibraryStorage): LibrarySession {
        val db = Room.inMemoryDatabaseBuilder(app, LibraryDatabase::class.java).allowMainThreadQueries().build()
        databases += db
        return LibrarySession(app, storage, db, 1)
    }

    private fun body(uuid: String, source: String = "manga_import/book") = JSONObject()
        .put("bookUuid", uuid).put("localRoot", source).put("title", "Book")

    private fun writeSourceIdentity(storage: LibraryStorage, kind: String, uuid: String, source: String): String {
        val path = ".starflow/books/$uuid/source.json"
        storage.mkdir(".starflow/books/$uuid")
        storage.writeNew(path).use {
            it.write(JSONObject().put("format", 1).put("kind", kind).put("uuid", uuid)
                .put("source", source).put("sourceName", "Restored").toString().toByteArray())
        }
        return path
    }

    @Test fun emptyLocalDatabaseReconnectsToTheExistingDirectoryIdentity() {
        val backend = FileLibraryStorage(root)
        val first = open(backend).also { it.prepare() }
        first.putBook("manga", 1, body(UUID.randomUUID().toString()))
        first.flush()
        val identity = JSONObject(backend.read(".starflow/library.json").bufferedReader().use { it.readText() })
            .getString("libraryId")

        val restored = open(backend).also { it.prepare() }

        assertEquals(identity, restored.database.catalog().meta("manifestId"))
        assertTrue(backend.exists(".starflow/library.json"))
        assertFalse(backend.list(".starflow").any { it.path.contains(".replaced-") })
        assertTrue(restored.books("manga").isEmpty())
    }

    @Test fun localRecordsPreventBlindAdoption() {
        val backend = FileLibraryStorage(root)
        val first = open(backend).also { it.prepare() }
        first.putBook("manga", 1, body(UUID.randomUUID().toString()))
        first.flush()

        val second = open(backend)
        second.putBook("manga", 1, body(UUID.randomUUID().toString()))
        assertThrows(LibraryIdentityException::class.java) { second.prepare() }
    }

    @Test fun rescanningTheReconnectedDirectoryRestoresTheAppImportedUuid() = runBlocking {
        val backend = FileLibraryStorage(root)
        open(backend).also { it.prepare() }
        val uuid = UUID.randomUUID().toString()
        backend.mkdir("manga_import/$uuid")
        backend.writeNew("manga_import/$uuid/001.png").use { it.write(LibraryScanFixtures.png()) }
        backend.mkdir(".starflow/books/$uuid")
        backend.writeNew(".starflow/books/$uuid/source.json").use {
            it.write(JSONObject().put("format", 1).put("kind", "manga").put("uuid", uuid)
                .put("source", "manga_import/$uuid").put("sourceName", "Restored").toString().toByteArray())
        }

        val restored = open(backend).also { it.prepare() }
        val result = LibraryScanner.scan(restored.context(), "manga")

        assertTrue(result.failures.joinToString(), result.failures.isEmpty())
        val book = restored.books("manga").single()
        assertEquals(uuid, book.uuid)
        assertEquals("manga_import/$uuid", book.source)
        assertEquals("Restored", JSONObject(book.body).getString("title"))
    }

    @Test fun reconnectingAnImportedTxtKeepsItsFileFormatAndChapters() = runBlocking {
        val backend = FileLibraryStorage(root)
        open(backend).prepare()
        val uuid = UUID.randomUUID().toString()
        val source = "novel_import/$uuid/book.txt"
        val text = "第一章 开始\n这里是开篇的正文内容。\n第二章 继续\n故事继续展开。"
        backend.writeNew(source).use { it.write(text.toByteArray()) }
        val identity = writeSourceIdentity(backend, "novel", uuid, source)

        val restored = open(backend).also { it.prepare() }
        val result = LibraryScanner.scan(restored.context(), "novel")

        assertTrue(result.failures.joinToString(), result.failures.isEmpty())
        val book = NovelStore.load(restored.context()).single()
        assertEquals(uuid, book.bookUuid)
        assertEquals(NovelFormat.TXT, book.format)
        assertEquals(2, book.chapterCount)
        assertEquals(source, restored.books("novel").single().source)
        assertEquals(source, JSONObject(backend.read(identity).bufferedReader().use { it.readText() }).getString("source"))
        assertEquals(text, File(book.localRoot).readText())
        assertEquals(0, LibraryScanner.scan(restored.context(), "novel").added)
    }

    @Test fun reconnectingAnImportedArchiveKeepsItsInternalChapters() = runBlocking {
        val backend = FileLibraryStorage(root)
        open(backend).prepare()
        val uuid = UUID.randomUUID().toString()
        val source = "manga_import/$uuid/book.cbz"
        LibraryScanFixtures.zip(backend.file(source),
            "Chapter 1/001.png" to LibraryScanFixtures.png(),
            "Chapter 2/001.png" to LibraryScanFixtures.png())
        writeSourceIdentity(backend, "manga", uuid, source)

        val restored = open(backend).also { it.prepare() }
        val result = LibraryScanner.scan(restored.context(), "manga")

        assertTrue(result.failures.joinToString(), result.failures.isEmpty())
        val book = ImportedMangaStore.load(restored.context()).single()
        assertEquals(uuid, book.bookUuid)
        assertTrue(book.isArchive)
        assertEquals(2, book.chapterCount)
        assertEquals(2, book.pageCount)
        assertEquals(source, restored.books("manga").single().source)
        assertEquals(0, LibraryScanner.scan(restored.context(), "manga").added)
    }

    @Test fun missingRecordedSourceDoesNotAdoptTheWrapperOrRewriteItsIdentity() = runBlocking {
        val backend = FileLibraryStorage(root)
        open(backend).prepare()
        val uuid = UUID.randomUUID().toString()
        val source = "novel_import/$uuid/missing.txt"
        backend.writeNew("novel_import/$uuid/other.txt").use { it.write("Other text".toByteArray()) }
        val identity = writeSourceIdentity(backend, "novel", uuid, source)
        val originalIdentity = backend.read(identity).use { it.readBytes() }

        val restored = open(backend).also { it.prepare() }
        val result = LibraryScanner.scan(restored.context(), "novel")

        assertEquals(0, result.added)
        assertTrue(result.failures.joinToString(), result.failures.any { it.contains(source) })
        assertTrue(restored.books("novel").isEmpty())
        assertArrayEquals(originalIdentity, backend.read(identity).use { it.readBytes() })
        assertTrue(backend.exists("novel_import/$uuid/other.txt"))
    }
}
