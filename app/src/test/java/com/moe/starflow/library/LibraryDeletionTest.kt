package com.moe.starflow.library

import androidx.room.Room
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryDeletionTest {
    @get:Rule val temp = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication()

    private fun withSession(storage: LibraryStorage, work: (LibrarySession) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(app, LibraryDatabase::class.java).allowMainThreadQueries().build()
        try { work(LibrarySession(app, storage, db, 1).also { it.prepare() }) } finally { db.close() }
    }
    private fun book(source: String, uuid: String = UUID.randomUUID().toString()) = LibraryBook("manga", 1, uuid,
        JSONObject().put("bookUuid", uuid).put("localRoot", source).put("title", "Book").toString(), source)

    @Test fun appCopiedArchiveRemovesItsWholeUuidFolder() {
        val backend = FileLibraryStorage(temp.newFolder("library"))
        withSession(backend) { session ->
            val uuid = UUID.randomUUID().toString()
            backend.mkdir("manga_import/$uuid")
            backend.writeNew("manga_import/$uuid/original.cbz").use { it.write(1) }
            backend.writeNew("manga_import/$uuid/meta.json").use { it.write(1) }
            val book = book("manga_import/$uuid/original.cbz", uuid)
            val body = JSONObject(book.body)
            LibraryDeletion.planSourceCleanup(session, book, body)
            LibraryDeletion.deleteSource(session, book, body)
            assertFalse(backend.exists("manga_import/$uuid"))
            assertTrue(backend.exists("manga_import"))
        }
    }

    @Test fun dedicatedNamedPdfFolderRemovesItsMetaAndEmptyWrapper() {
        val backend = FileLibraryStorage(temp.newFolder("library"))
        withSession(backend) { session ->
            backend.mkdir("manga_import/139話 前編")
            backend.writeNew("manga_import/139話 前編/139話 前編.pdf").use { it.write(1) }
            backend.writeNew("manga_import/139話 前編/meta.json").use { it.write(1) }
            val book = book("manga_import/139話 前編/139話 前編.pdf")
            val body = JSONObject(book.body)
            LibraryDeletion.planSourceCleanup(session, book, body)
            LibraryDeletion.deleteSource(session, book, body)
            assertFalse(backend.exists("manga_import/139話 前編"))
        }
    }

    @Test fun flatArchiveAndSharedGroupsKeepTheirParentAndOtherBooks() {
        val backend = FileLibraryStorage(temp.newFolder("library"))
        withSession(backend) { session ->
            backend.mkdir("manga_import/Group")
            for (name in listOf("one.zip", "one.meta.json", "two.zip", "meta.json"))
                backend.writeNew("manga_import/Group/$name").use { it.write(1) }
            val book = book("manga_import/Group/one.zip")
            val body = JSONObject(book.body)
            LibraryDeletion.planSourceCleanup(session, book, body)
            LibraryDeletion.deleteSource(session, book, body)
            assertEquals(setOf("two.zip", "meta.json"), backend.list("manga_import/Group").map { it.path.substringAfterLast('/') }.toSet())
            backend.writeNew("manga_import/flat.zip").use { it.write(1) }
            val flat = book("manga_import/flat.zip")
            val flatBody = JSONObject(flat.body)
            LibraryDeletion.planSourceCleanup(session, flat, flatBody)
            LibraryDeletion.deleteSource(session, flat, flatBody)
            assertTrue(backend.exists("manga_import"))
            assertTrue(backend.exists("manga_import/Group/two.zip"))
        }
    }

    @Test fun metadataDeletionFailureCanRetryAfterThePdfHasAlreadyBeenRemoved() {
        val backend = FileLibraryStorage(temp.newFolder("library"))
        var fail = true
        val storage = object : LibraryStorage by backend {
            override fun delete(path: String) {
                if (path.endsWith("meta.json") && fail) throw IOException("Provider refused metadata deletion")
                backend.delete(path)
            }
        }
        withSession(storage) { session ->
            backend.mkdir("manga_import/Book")
            backend.writeNew("manga_import/Book/book.pdf").use { it.write(1) }
            backend.writeNew("manga_import/Book/meta.json").use { it.write(1) }
            val book = book("manga_import/Book/book.pdf")
            val body = JSONObject(book.body)
            LibraryDeletion.planSourceCleanup(session, book, body)
            assertThrows(IOException::class.java) { LibraryDeletion.deleteSource(session, book, body) }
            assertFalse(backend.exists(book.source))
            assertTrue(backend.exists("manga_import/Book/meta.json"))
            fail = false
            LibraryDeletion.planSourceCleanup(session, book, body)
            LibraryDeletion.deleteSource(session, book, body)
            assertFalse(backend.exists("manga_import/Book"))
        }
    }

    @Test fun anOwnedWrapperCannotDeleteAnotherCataloguedWorkInsideIt() {
        val backend = FileLibraryStorage(temp.newFolder("library"))
        withSession(backend) { session ->
            val uuid = UUID.randomUUID().toString()
            backend.mkdir("manga_import/$uuid")
            backend.writeNew("manga_import/$uuid/one.zip").use { it.write(1) }
            backend.writeNew("manga_import/$uuid/two.zip").use { it.write(1) }
            val other = book("manga_import/$uuid/two.zip")
            session.putBook("manga", 2, JSONObject(other.body))
            val mine = book("manga_import/$uuid/one.zip", uuid)
            assertThrows(IOException::class.java) { LibraryDeletion.planSourceCleanup(session, mine, JSONObject(mine.body)) }
            assertTrue(backend.exists(mine.source))
            assertTrue(backend.exists(other.source))
        }
    }

    @Test fun absentSourceCanBeRemovedDespiteAnExistingParentBook() {
        val backend = FileLibraryStorage(temp.newFolder("missing-child"))
        withSession(backend) { session ->
            backend.mkdir("manga_import/Book")
            val parent = book("manga_import/Book")
            session.putBook("manga", 2, JSONObject(parent.body))
            val missing = book("manga_import/Book/original.pdf")
            val body = JSONObject(missing.body)
            LibraryDeletion.validateSource(session, missing)
            LibraryDeletion.planSourceCleanup(session, missing, body)
            LibraryDeletion.deleteSource(session, missing, body)
            assertTrue(backend.exists(parent.source))
            assertEquals(parent.uuid, session.database.catalog().book("manga", 2)!!.uuid)
        }
    }

    @Test fun absentCopiedSourceDoesNotDeleteAnotherBooksWrapperOnRetry() {
        val backend = FileLibraryStorage(temp.newFolder("missing-copy"))
        withSession(backend) { session ->
            val uuid = UUID.randomUUID().toString()
            val wrapper = "manga_import/$uuid"
            backend.mkdir(wrapper)
            val parent = book(wrapper)
            session.putBook("manga", 2, JSONObject(parent.body))
            val missing = book("$wrapper/original.cbz", uuid)
            // A previous failure may already have persisted the broader cleanup target.
            val body = JSONObject(missing.body).put("deleteSourceRoot", wrapper)
            LibraryDeletion.planSourceCleanup(session, missing, body)
            assertEquals(missing.source, body.getString("deleteSourceRoot"))
            LibraryDeletion.deleteSource(session, missing, body)
            assertTrue(backend.exists(wrapper))
        }
    }

    @Test fun missingDescendantAndDuplicatePathsDoNotBlockCleanup() {
        val backend = FileLibraryStorage(temp.newFolder("missing-overlaps"))
        withSession(backend) { session ->
            backend.mkdir("manga_import/Book")
            val child = book("manga_import/Book/gone.pdf")
            session.putBook("manga", 2, JSONObject(child.body))
            val parent = book("manga_import/Book")
            val body = JSONObject(parent.body)
            LibraryDeletion.planSourceCleanup(session, parent, body)
            LibraryDeletion.deleteSource(session, parent, body)
            assertFalse(backend.exists(parent.source))
            LibraryDeletion.validateSource(session, book(child.source))
        }
    }

    @Test fun actualOverlapReportsTheOtherBookAndKeepsBothSources() {
        val backend = FileLibraryStorage(temp.newFolder("live-overlap"))
        withSession(backend) { session ->
            backend.mkdir("manga_import/Book")
            backend.writeNew("manga_import/Book/original.pdf").use { it.write(1) }
            val parent = book("manga_import/Book")
            session.putBook("manga", 2, JSONObject(parent.body))
            val child = book("manga_import/Book/original.pdf")
            val error = assertThrows(IOException::class.java) { LibraryDeletion.validateSource(session, child) }
            assertTrue(error.message.orEmpty().contains(parent.source))
            assertTrue(error.message.orEmpty().contains(parent.uuid))
            assertTrue(backend.exists(child.source))
        }
    }

    @Test fun removingMissingOriginalAndSourceIndexCanStillFinishTheCatalogRemoval() = runBlocking {
        val root = temp.newFolder("missing-index")
        app.getSharedPreferences("library_locations", 0).edit().clear().putString("defaultPath", root.path).commit()
        LibrarySessions.prepare(app)
        val source = File(root, "manga_import/gone.cbz").apply { writeText("source") }
        ImportedMangaStore.add(app, ImportedManga(1, "Gone", source.path, true, coverPath = null, pageCount = 1, addedAt = 1))
        val session = LibrarySessions.current(app)
        val book = ImportedMangaStore.load(app).single()
        val index = com.moe.starflow.mangaimport.data.MangaSourceIndex(emptyList()).store(session, book.bookUuid)
        ImportedMangaStore.patch(app, book) { it.copy(sourceIndexPath = index) }
        session.flush()
        assertTrue(source.delete())
        assertTrue(session.local(index).delete())
        LibraryDeletion.start(app, "manga", listOf(book.id), mapOf(book.id to book.bookUuid))
        withTimeout(10_000) {
            while (session.database.catalog().book("manga", book.id) != null || LibraryDeletion.state.value.running) delay(10)
        }
        assertTrue(LibraryDeletion.state.value.failures.joinToString(), LibraryDeletion.state.value.failures.isEmpty())
        assertFalse(session.storage.exists(".starflow/books/${book.bookUuid}"))
    }

    @Test fun deletingStateIsProgressUntilTheJobFinishesAndInterruptedIntentsAreFailures() = runBlocking {
        val root = temp.newFolder("library")
        app.getSharedPreferences("library_locations", 0).edit().clear().putString("defaultPath", root.path).commit()
        LibrarySessions.prepare(app)
        val source = File(root, "manga_import/book.zip").apply { writeText("source") }
        ImportedMangaStore.add(app, ImportedManga(1, "Book", source.path, true, coverPath = null, pageCount = 1, addedAt = 1))
        val session = LibrarySessions.current(app)
        session.flush()
        val uuid = ImportedMangaStore.load(app).single().bookUuid
        val reader = LibrarySources.acquire(source)
        try {
            LibraryDeletion.start(app, "manga", listOf(1), mapOf(1L to uuid))
            withTimeout(10_000) { while (session.database.catalog().book("manga", 1)?.deleting != true) delay(10) }
            val deleting = ImportedMangaStore.load(app).single()
            assertTrue(deleting.deleting)
            assertNull(deleting.deletionError)
            assertTrue(source.exists())
        } finally { reader.close() }
        withTimeout(10_000) { while (LibraryDeletion.state.value.running || session.books("manga").isNotEmpty()) delay(10) }
        assertFalse(source.exists())

        val second = File(root, "manga_import/interrupted.zip").apply { writeText("source") }
        ImportedMangaStore.add(app, ImportedManga(2, "Interrupted", second.path, true, coverPath = null, pageCount = 1, addedAt = 2))
        val row = session.database.catalog().book("manga", 2)!!
        session.putBook("manga", 2, JSONObject(row.body), deleting = true)
        val failed = ImportedMangaStore.load(app).single()
        assertFalse(failed.deleting)
        assertNotNull(failed.deletionError)
    }
}
