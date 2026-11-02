package com.moe.starflow.library

import android.content.Context
import androidx.room.Room
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
}
