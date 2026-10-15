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
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        root = File(app.cacheDir, "library-test-${UUID.randomUUID()}").apply { mkdirs() }
    }
    @After fun cleanup() { databases.forEach { it.close() }; root.deleteRecursively() }
    private fun session(storage: LibraryStorage): LibrarySession {
        val db = Room.inMemoryDatabaseBuilder(app, LibraryDatabase::class.java).allowMainThreadQueries().build()
        databases += db
        return LibrarySession(app, storage, db, 1).also { it.prepare() }
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

}
