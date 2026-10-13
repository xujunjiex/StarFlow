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

    @Test fun replacedManifestNeverReceivesTheOriginalPendingData() {
        val backend = FileLibraryStorage(root)
        val a = session(backend)
        val uuid = UUID.randomUUID().toString()
        backend.mkdir("manga_import/book")
        a.putBook("manga", 1, body(uuid)); a.flush()
        File(root, ".starflow/library.json").writeText(JSONObject().put("format", 1)
            .put("libraryId", UUID.randomUUID().toString()).toString())
        a.putBook("manga", 1, body(uuid).put("title", "Pending"))
        assertThrows(IOException::class.java) { a.flush() }
        assertEquals(1, a.database.catalog().pending().size)
        assertTrue(a.saveState.value.error!!.contains("身份已更换"))
    }

    @Test fun copiedUuidAndNumericIdHaveIndependentDatabasesAndRuntimeKeys() = runBlocking {
        val a = session(FileLibraryStorage(File(root, "a").apply { mkdirs() }))
        val uuid = UUID.randomUUID().toString()
        a.putBook("manga", 1, body(uuid)); a.flush()
        a.importedPageTranslationDao().upsert(ImportedPageTranslation(1, 0, 2, translatedText = "A",
            mangaKey = "${a.locationId}:manga:$uuid"))
        a.flush()
        val clone = File(root, "copy")
        (a.storage as FileLibraryStorage).root.copyRecursively(clone)
        val b = session(FileLibraryStorage(clone))
        assertNotEquals(a.locationId, b.locationId)
        assertEquals("A", b.importedPageTranslationDao().forManga(1, "${b.locationId}:manga:$uuid").single().translatedText)
        assertTrue(b.importedPageTranslationDao().forManga(1, "${a.locationId}:manga:$uuid").isEmpty())
        b.importedPageTranslationDao().upsert(ImportedPageTranslation(1, 0, 2, translatedText = "B",
            mangaKey = "${b.locationId}:manga:$uuid")); b.flush()
        assertEquals("A", a.importedPageTranslationDao().forManga(1, "${a.locationId}:manga:$uuid").single().translatedText)
    }

    @Test fun truncatedCommitKeepsPreviousStateAndLocalOutboxForRetry() {
        val backend = FileLibraryStorage(root)
        var fail = false
        val flaky = object : LibraryStorage by backend {
            override fun writeNew(path: String, mime: String): OutputStream {
                val out = backend.writeNew(path, mime)
                if (!fail || !path.endsWith(".commit.json")) return out
                return object : OutputStream() {
                    override fun write(b: Int) { out.write(b); throw IOException("No space in library") }
                    override fun close() { out.close() }
                }
            }
        }
        backend.mkdir("manga_import/book")
        val a = session(flaky)
        val uuid = UUID.randomUUID().toString()
        a.putBook("manga", 1, body(uuid)); a.flush()
        fail = true
        a.putBook("manga", 1, body(uuid).put("title", "Updated"))
        assertThrows(IOException::class.java) { a.flush() }
        assertEquals(1, a.database.catalog().pending().size)
        val b = session(backend)
        assertEquals("Same title", JSONObject(b.books("manga").single().body).getString("title"))
        fail = false
        a.flush()
        assertTrue(a.database.catalog().pending().isEmpty())
        val restored = session(backend)
        assertEquals("Updated", JSONObject(restored.books("manga").single().body).getString("title"))
    }

    @Test fun aSecondWriterCannotOverwriteLocalPendingChanges() {
        val backend = FileLibraryStorage(root)
        val a = session(backend)
        val uuid = UUID.randomUUID().toString()
        a.putBook("manga", 1, body(uuid)); a.flush()
        val b = session(backend)
        b.putBook("manga", 1, body(uuid).put("title", "Other writer")); b.flush()
        a.putBook("manga", 1, body(uuid).put("title", "Local pending"))
        assertThrows(IOException::class.java) { a.flush() }
        assertFalse(a.database.catalog().pending().isEmpty())
        assertEquals("Local pending", JSONObject(a.books("manga").single().body).getString("title"))
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

    @Test fun deletionRefusesBoundaryAndOverlappingSources() {
        val a = session(FileLibraryStorage(root))
        val uuid = UUID.randomUUID().toString()
        val outside = LibraryBook("manga", 1, uuid, body(uuid).toString(), "novel_import/book")
        assertThrows(IOException::class.java) { LibraryDeletion.validateSource(a, outside) }
        a.putBook("manga", 2, body(UUID.randomUUID().toString(), "manga_import/book/child")); a.flush()
        val parent = LibraryBook("manga", 1, uuid, body(uuid).toString(), "manga_import/book")
        assertThrows(IOException::class.java) { LibraryDeletion.validateSource(a, parent) }
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

    @Test fun inaccessibleRootDoesNotEraseCatalogOrPendingChanges() {
        val backend = FileLibraryStorage(root)
        var inaccessible = false
        val guarded = object : LibraryStorage by backend {
            override fun list(path: String): List<LibraryNode> {
                if (inaccessible) throw IOException("Permission revoked")
                return backend.list(path)
            }
        }
        backend.mkdir("manga_import/book")
        val a = session(guarded)
        val uuid = UUID.randomUUID().toString()
        a.putBook("manga", 1, body(uuid)); a.flush()
        inaccessible = true
        a.putBook("manga", 1, body(uuid).put("title", "Pending title"))
        assertThrows(IOException::class.java) { a.flush() }
        assertEquals(1, a.books("manga").size)
        assertFalse(a.database.catalog().pending().isEmpty())
        inaccessible = false
        a.flush()
        assertEquals("Pending title", JSONObject(session(backend).books("manga").single().body).getString("title"))
    }

    @Test fun checkpointAndFallbackBothRestoreCompleteState() {
        val backend = FileLibraryStorage(root)
        val a = session(backend)
        val uuid = UUID.randomUUID().toString()
        repeat(100) { index -> a.putBook("manga", 1, body(uuid).put("title", "Revision $index")); a.flush() }
        val snapshot = backend.list(".starflow/catalog").single { it.path.endsWith(".snapshot.json") }
        assertEquals("Revision 99", JSONObject(session(backend).books("manga").single().body).getString("title"))
        backend.file(snapshot.path).writeText("truncated")
        assertEquals("Revision 99", JSONObject(session(backend).books("manga").single().body).getString("title"))
    }

    @Test fun compactionRemovesDeletedRecordsWithoutLosingOtherBooks() = runBlocking {
        val backend = FileLibraryStorage(root)
        val a = session(backend)
        val uuid = UUID.randomUUID().toString()
        val remaining = UUID.randomUUID().toString()
        a.putBook("manga", 1, body(uuid).put("title", "Private deleted title")); a.flush()
        a.putBook("manga", 2, body(remaining, "manga_import/remaining")); a.flush()
        a.importedPageTranslationDao().upsert(ImportedPageTranslation(1, 0, 2, translatedText = "Private deleted text",
            mangaKey = "${a.locationId}:manga:$uuid")); a.flush()
        a.putBook("manga", 1, body(uuid), deleting = true); a.flush()
        a.importedPageTranslationDao().deleteMangaScoped(1, "${a.locationId}:manga:$uuid"); a.flush()
        backend.delete(".starflow/books/$uuid")
        a.finishRemoval("manga", 1)
        a.compact()
        assertThrows(IOException::class.java) { a.putBook("manga", 1, body(uuid)) }
        val restored = session(backend)
        assertEquals(listOf(2L), restored.books("manga").map { it.id })
        assertTrue(restored.database.catalog().allTranslations().isEmpty())
        backend.list(".starflow/catalog").filter { it.path.endsWith(".json") }.forEach { node ->
            val text = backend.read(node.path).bufferedReader().use { it.readText() }
            assertFalse(text.contains("Private deleted"))
        }
        a.putBook("manga", 2, body(remaining, "manga_import/remaining").put("title", "After checkpoint")); a.flush()
        a.compact()
        assertEquals("After checkpoint", JSONObject(session(backend).books("manga").single().body).getString("title"))
    }
}
