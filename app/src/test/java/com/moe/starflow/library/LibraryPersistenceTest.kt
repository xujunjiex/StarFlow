package com.moe.starflow.library

import android.content.Context
import androidx.room.Room
import com.moe.starflow.data.ImportedPageTranslation
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryPersistenceTest {
    private lateinit var app: Context
    private lateinit var root: File
    private val databases = mutableListOf<LibraryDatabase>()
    private val sessions = mutableListOf<LibrarySession>()
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        root = File(app.cacheDir, "library-test-${UUID.randomUUID()}").apply { mkdirs() }
    }
    @After fun cleanup() { sessions.forEach { it.close() }; databases.forEach { it.close() }; root.deleteRecursively() }
    private fun session(storage: LibraryStorage): LibrarySession {
        val db = Room.inMemoryDatabaseBuilder(app, LibraryDatabase::class.java).allowMainThreadQueries().build()
        databases += db
        return LibrarySession(app, storage, db, 1).also { sessions += it; it.prepare() }
    }
    private fun body(uuid: String, source: String = "manga_import/book") = JSONObject()
        .put("bookUuid", uuid).put("localRoot", source).put("title", "Same title")

    @Test fun recordsAndReadingProgressDoNotWriteOrQueueDirectoryDatabaseCopies() = runBlocking {
        val backend = FileLibraryStorage(root)
        val a = session(backend)
        val uuid = UUID.randomUUID().toString()
        a.putBook("manga", 1, body(uuid)); a.flush()
        val before = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.toSet()
        repeat(20) { page ->
            a.importedPageTranslationDao().upsert(ImportedPageTranslation(1, page, 2,
                translatedText = "Saved $page", mangaKey = "${a.locationId}:manga:$uuid"))
            a.putBook("manga", 1, body(uuid).put("lastReadPage", page))
        }
        assertEquals(20, a.database.catalog().allTranslations().size)
        assertEquals(0, a.database.catalog().pendingCount())
        assertEquals(before, root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.toSet())
        assertFalse(backend.exists(".starflow/catalog"))
        assertFalse(backend.exists(".starflow/books/$uuid/records"))
        val identity = JSONObject(backend.read(".starflow/books/$uuid/source.json").bufferedReader().use { it.readText() })
        assertFalse(identity.has("title")); assertFalse(identity.has("lastReadPage"))
    }

    @Test fun recordUpdatesRemainLocalWhileDirectoryAccessFails() = runBlocking {
        val backend = FileLibraryStorage(root)
        var inaccessible = false
        val guarded = object : LibraryStorage by backend {
            override fun read(path: String): java.io.InputStream {
                if (inaccessible) throw IOException("Permission revoked")
                return backend.read(path)
            }
        }
        backend.mkdir("manga_import/book")
        val a = session(guarded)
        val uuid = UUID.randomUUID().toString()
        a.putBook("manga", 1, body(uuid)); a.flush()
        inaccessible = true
        assertThrows(IOException::class.java) { a.flush() }
        a.importedPageTranslationDao().upsert(ImportedPageTranslation(1, 0, 2,
            translatedText = "Stored offline", mangaKey = "${a.locationId}:manga:$uuid"))
        a.putBook("manga", 1, body(uuid).put("title", "Local title"))
        assertEquals("Stored offline", a.database.catalog().allTranslations().single().translatedText)
        assertEquals("Local title", JSONObject(a.books("manga").single().body).getString("title"))
        assertEquals(0, a.database.catalog().pendingCount())
    }

    @Test fun copyingTheDirectoryDoesNotCopyAppRecordsAndSameUuidStaysIsolated() = runBlocking {
        val a = session(FileLibraryStorage(File(root, "a").apply { mkdirs() }))
        val uuid = UUID.randomUUID().toString()
        a.putBook("manga", 1, body(uuid)); a.flush()
        a.importedPageTranslationDao().upsert(ImportedPageTranslation(1, 0, 2,
            translatedText = "A", mangaKey = "${a.locationId}:manga:$uuid"))
        val clone = File(root, "copy")
        (a.storage as FileLibraryStorage).root.copyRecursively(clone)
        val b = session(FileLibraryStorage(clone))
        assertTrue(b.books("manga").isEmpty())
        assertTrue(b.database.catalog().allTranslations().isEmpty())
        b.putBook("manga", 1, body(uuid)); b.flush()
        b.importedPageTranslationDao().upsert(ImportedPageTranslation(1, 0, 2,
            translatedText = "B", mangaKey = "${b.locationId}:manga:$uuid"))
        assertEquals("A", a.database.catalog().allTranslations().single().translatedText)
        assertEquals("B", b.database.catalog().allTranslations().single().translatedText)
        assertNotEquals(a.locationId, b.locationId)
    }

    @Test fun replacedIdentityStopsFileWritesWithoutDiscardingLocalRecords() {
        val backend = FileLibraryStorage(root)
        val a = session(backend)
        val uuid = UUID.randomUUID().toString()
        a.putBook("manga", 1, body(uuid)); a.flush()
        File(root, ".starflow/library.json").writeText(JSONObject().put("format", 1)
            .put("libraryId", UUID.randomUUID().toString()).toString())
        assertThrows(IOException::class.java) { a.flush() }
        assertEquals(uuid, a.books("manga").single().uuid)
        assertTrue(a.saveState.value.error!!.contains("身份已更换"))
    }

    @Test fun legacyDirectoryJournalIsImportedAndVerifiedBeforeItsRemoval() {
        val backend = FileLibraryStorage(root)
        listOf("manga_import", "novel_import", ".starflow/catalog").forEach(backend::mkdir)
        backend.writeNew(".starflow/library.json").use { it.write(JSONObject().put("format", 1)
            .put("libraryId", UUID.randomUUID().toString()).toString().toByteArray()) }
        val uuid = UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()
        val payload = ".starflow/catalog/$token.json"
        val bytes = JSONObject().put("component", "book").put("kind", "manga").put("id", 1)
            .put("book", body(uuid).put("title", "Legacy book")).put("token", token).toString().toByteArray()
        backend.writeNew(payload).use { it.write(bytes) }
        backend.writeNew(".starflow/catalog/00000000000000000001-$token.commit.json").use { it.write(JSONObject()
            .put("sequence", 1).put("token", token).put("parent", "").put("payload", payload)
            .put("digest", LibraryCodec.digest(bytes)).toString().toByteArray()) }
        val a = session(backend)
        assertEquals("Legacy book", JSONObject(a.books("manga").single().body).getString("title"))
        assertArrayEquals(bytes, a.recordArchive(payload).readBytes())
        assertFalse(backend.exists(".starflow/catalog"))
        a.flush()
        assertEquals(1, a.books("manga").size)
    }

    @Test fun deletingOrRemovedWorkRejectsLateTranslationWrites() = runBlocking {
        val a = session(FileLibraryStorage(root))
        val uuid = UUID.randomUUID().toString()
        val key = "${a.locationId}:manga:$uuid"
        a.putBook("manga", 1, body(uuid)); a.flush()
        a.putBook("manga", 1, body(uuid), deleting = true); a.flush()
        val row = ImportedPageTranslation(1, 0, 2, mangaKey = key)
        try { a.importedPageTranslationDao().upsert(row); fail("Late row accepted") } catch (_: IllegalStateException) { }
        a.finishRemoval("manga", 1)
        try { a.importedPageTranslationDao().upsert(row); fail("Removed row accepted") } catch (_: IllegalStateException) { }
        assertTrue(a.database.catalog().allTranslations().isEmpty())
    }

    @Test fun incompatibleOrDamagedMetadataCannotInitializeAnEmptyLibrary() {
        val backend = FileLibraryStorage(root)
        backend.mkdir(".starflow")
        assertThrows(IOException::class.java) { session(backend) }
        backend.writeNew(".starflow/library.json").use { it.write("{\"format\":999,\"libraryId\":\"x\"}".toByteArray()) }
        assertThrows(IOException::class.java) { session(backend) }
        assertFalse(backend.exists(".starflow/catalog"))
    }

    @Test fun relativePathsRejectTraversalAndSymlinkBoundary() {
        val backend = FileLibraryStorage(root)
        assertThrows(IllegalArgumentException::class.java) { backend.file("../outside") }
        assertThrows(IllegalArgumentException::class.java) { backend.file("manga_import/../outside") }
        assertThrows(IllegalArgumentException::class.java) { backend.file("/outside") }
    }

    @Test fun interruptedGeneratedAssetRetainsOldRevisionAndCanRetry() {
        val backend = FileLibraryStorage(root)
        backend.mkdir("manga_import/book")
        var broken = false
        val flaky = object : LibraryStorage by backend {
            override fun writeNew(path: String, mime: String): OutputStream {
                val out = backend.writeNew(path, mime)
                if (!broken || !path.contains(".revision.json")) return out
                return object : OutputStream() {
                    override fun write(b: Int) { out.write(b); throw IOException("Provider write interrupted") }
                    override fun close() { out.close() }
                }
            }
        }
        val a = session(flaky)
        val uuid = UUID.randomUUID().toString()
        a.putBook("manga", 1, body(uuid)); a.flush()
        val path = ".starflow/books/$uuid/sr/page.json"
        val logical = a.local(path).apply { parentFile!!.mkdirs(); writeText("old result") }
        a.publishArtifact(logical)
        val old = a.assetPath(path)
        val pending = a.local(".starflow/books/$uuid/sr/pending.tmp").apply { writeText("new result") }
        broken = true
        assertThrows(IOException::class.java) { a.publishRevision(logical, pending) }
        assertEquals(old, a.assetPath(path))
        assertEquals("old result", backend.read(old).bufferedReader().use { it.readText() })
        assertFalse(a.database.catalog().pending().isEmpty())
        broken = false
        a.flush()
        assertNotEquals(old, a.assetPath(path))
        assertEquals("new result", backend.read(a.assetPath(path)).bufferedReader().use { it.readText() })
    }

    @Test fun committedReplacementRetriesOldAssetCleanupAfterProviderFailure() {
        val backend = FileLibraryStorage(root)
        var cannotDelete = false
        val flaky = object : LibraryStorage by backend {
            override fun delete(path: String) {
                if (cannotDelete && path.endsWith("old.webp")) throw IOException("Provider refuses deletion")
                backend.delete(path)
            }
        }
        val a = session(flaky)
        val uuid = UUID.randomUUID().toString()
        backend.mkdir("manga_import/book")
        a.putBook("manga", 1, body(uuid)); a.flush()
        val old = a.local(".starflow/books/$uuid/sr/old.webp").apply { parentFile!!.mkdirs(); writeText("old image") }
        a.publishArtifact(old)
        val logical = a.local(".starflow/books/$uuid/sr/page.json")
        val pending = File(a.workingRoot, "replace.tmp").apply { writeText("new manifest") }
        cannotDelete = true
        assertThrows(IOException::class.java) { a.publishRevision(logical, pending, listOf(old)) }
        assertEquals("new manifest", backend.read(a.assetPath(a.relative(logical))).bufferedReader().use { it.readText() })
        assertTrue(backend.exists(a.relative(old)))
        assertNotNull(a.saveState.value.error)
        cannotDelete = false
        a.flush()
        assertFalse(backend.exists(a.relative(old)))
        assertNull(a.saveState.value.error)
    }

    @Test fun failedOwnerDefersItsNextOperationButOtherOwnersSaveAndRetrySucceeds() {
        val backend = FileLibraryStorage(root)
        val aUuid = UUID.randomUUID().toString()
        val bUuid = UUID.randomUUID().toString()
        val cUuid = UUID.randomUUID().toString()
        var failA = true
        val attempted = mutableListOf<String>()
        val flaky = object : LibraryStorage by backend {
            override fun writeNew(path: String, mime: String): OutputStream {
                attempted += path
                if (failA && path == ".starflow/books/$aUuid/source.json") throw IOException("A denied")
                return backend.writeNew(path, mime)
            }
        }
        val a = session(flaky)
        listOf("a", "b", "c").forEach { backend.mkdir("manga_import/$it") }
        a.withStorageLock {
            a.putBook("manga", 1, body(aUuid, "manga_import/a"))
            val revision = ".starflow/books/$aUuid/sr/next.webp"
            a.local(revision).apply { parentFile!!.mkdirs(); writeText("next result") }
            a.enqueue(JSONObject().put("component", "asset").put("path", ".starflow/books/$aUuid/sr/page.webp").put("revision", revision))
            a.putBook("manga", 2, body(bUuid, "manga_import/b"))
            a.putBook("manga", 3, body(cUuid, "manga_import/c"))
            val failure = assertThrows(LibraryFileWriteException::class.java) { a.flush() }
            assertEquals(setOf(aUuid), failure.result.failures.map { it.uuid }.toSet())
            assertTrue(backend.exists(".starflow/books/$bUuid/source.json"))
            assertTrue(backend.exists(".starflow/books/$cUuid/source.json"))
            assertFalse(attempted.contains(revision))
            assertEquals(2, a.database.catalog().pendingCount())
            val items = a.flushPending().items
            assertTrue(items.any { it.uuid == aUuid && it.status == LibraryFileStatus.DEFERRED && it.component == "asset" })
            // Publishing B is judged against B; A remains pending.
            val bArtifact = a.local(".starflow/books/$bUuid/sr/b.webp").apply { parentFile!!.mkdirs(); writeText("B result") }
            a.publishArtifact(bArtifact)
            assertEquals("B result", backend.read(a.relative(bArtifact)).bufferedReader().use { it.readText() })
            failA = false
            a.flush()
            assertTrue(backend.exists(revision))
            assertEquals(0, a.database.catalog().pendingCount())
            assertNull(a.saveState.value.error)
        }
    }

    @Test fun metadataFailureIsIndependentOfOrdinaryFileFailureAndLaterClears() {
        val backend = FileLibraryStorage(root)
        val uuid = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val revision = UUID.randomUUID().toString()
        var broken = false
        val flaky = object : LibraryStorage by backend {
            override fun writeNew(path: String, mime: String): OutputStream {
                if (broken && (path.contains(".starflow-description-") || path == ".starflow/books/$other/source.json"))
                    throw IOException("Provider denied")
                return backend.writeNew(path, mime)
            }
        }
        val a = session(flaky)
        backend.mkdir("manga_import/a"); backend.mkdir("manga_import/b")
        a.withStorageLock {
            a.putBook("manga", 1, body(uuid, "manga_import/a").put("descriptionRevision", revision).put("descriptionOverride", "User text"))
            a.flush()
            broken = true
            MangaDescriptions.enqueue(a, uuid, revision)
            a.putBook("manga", 2, body(other, "manga_import/b"))
            val artifact = a.local(".starflow/books/$uuid/sr/new.webp").apply { parentFile!!.mkdirs(); writeText("result") }
            // Metadata does not block a normal file for the same book.
            a.publishArtifact(artifact)
            assertTrue(backend.exists(a.relative(artifact)))
            assertFalse(a.saveState.value.metadataOnlyError)
            assertEquals(setOf("metadata", "book"), a.fileIssues.map { it.component }.toSet())
            assertNotNull(a.database.catalog().meta("metadataError:$uuid"))
            // Clear only the ordinary failure: pure metadata keeps the existing nonthrowing contract.
            a.database.catalog().pending().filter { JSONObject(it.body).optString("component") == "book" }
                .forEach { a.database.catalog().acknowledge(it.sequence) }
            a.flush()
            assertTrue(a.saveState.value.metadataOnlyError)
            broken = false
            a.flush()
            assertEquals(0, a.database.catalog().pendingCount())
            assertNull(a.saveState.value.error)
            assertTrue(backend.exists("manga_import/a/starflow.json"))
        }
    }

    @Test fun cleanupFailuresAreIsolatedAndAnExportProtectsOldRevisions() {
        val backend = FileLibraryStorage(root)
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        var failDelete = true
        val flaky = object : LibraryStorage by backend {
            override fun delete(path: String) {
                if (failDelete && path.contains(first) && path.endsWith("old.webp")) throw IOException("Keep first")
                backend.delete(path)
            }
        }
        val a = session(flaky)
        a.withStorageLock {
            listOf(first, second).forEachIndexed { index, uuid ->
                backend.mkdir("manga_import/$index")
                a.putBook("manga", index + 1L, body(uuid, "manga_import/$index")); a.flush()
                backend.writeNew(".starflow/books/$uuid/sr/old.webp").use { it.write(1) }
            }
            listOf(first, second).forEach { uuid ->
                a.database.catalog().meta(LibraryMeta("cleanup:.starflow/books/$uuid/sr/old.webp", "pending"))
            }
            val lease = BookRecordLocks.acquire(BookRef(a.locationId, "manga", second))
            try {
                assertThrows(LibraryFileWriteException::class.java) { a.flush() }
                assertTrue(backend.exists(".starflow/books/$second/sr/old.webp"))
            } finally { lease.close() }
            val result = a.flushPending()
            assertEquals(first, result.failures.single().uuid)
            assertFalse(backend.exists(".starflow/books/$second/sr/old.webp"))
            failDelete = false
            a.flush()
            assertFalse(backend.exists(".starflow/books/$first/sr/old.webp"))
        }
    }

}
