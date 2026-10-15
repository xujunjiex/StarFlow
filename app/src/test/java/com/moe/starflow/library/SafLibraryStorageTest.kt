package com.moe.starflow.library

import android.content.Context
import kotlinx.coroutines.async
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafLibraryStorageTest {
    private lateinit var app: Context
    private lateinit var provider: OpaqueProvider
    private lateinit var storage: SafLibraryStorage

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        provider = OpaqueProvider(File(app.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() })
        provider.attachInfo(app, ProviderInfo().apply { authority = AUTHORITY; exported = true; grantUriPermissions = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = "android.permission.MANAGE_DOCUMENTS" })
        // The shadow resolver calls the legacy query overload, which DocumentsProvider deliberately rejects.
        ShadowContentResolver.registerProviderInternal(AUTHORITY, object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?,
                selectionArgs: Array<out String>?, sortOrder: String?): Cursor = when (uri.lastPathSegment) {
                "root" -> provider.queryRoots(projection)
                "children" -> provider.queryChildDocuments(DocumentsContract.getDocumentId(uri), projection, sortOrder)
                else -> provider.queryDocument(DocumentsContract.getDocumentId(uri), projection)
            }
            override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle {
                val uri = extras!!.getParcelable<android.net.Uri>("uri")!!
                val id = DocumentsContract.getDocumentId(uri)
                return android.os.Bundle().apply {
                    when (method) {
                        "android:createDocument" -> putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(uri,
                            provider.createDocument(id, extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE)!!, extras.getString("_display_name")!!)))
                        "android:deleteDocument" -> provider.deleteDocument(id)
                        "android:isChildDocument" -> putBoolean("result", provider.isChildDocument(id,
                            DocumentsContract.getDocumentId(extras.getParcelable<android.net.Uri>("android.content.extra.TARGET_URI")!!)))
                        else -> throw UnsupportedOperationException(method)
                    }
                }
            }
            override fun openFile(uri: android.net.Uri, mode: String) = provider.openDocument(DocumentsContract.getDocumentId(uri), mode, null)
            override fun getType(uri: android.net.Uri) = "application/octet-stream"
            override fun insert(uri: android.net.Uri, values: android.content.ContentValues?) = throw UnsupportedOperationException()
            override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = throw UnsupportedOperationException()
            override fun delete(uri: android.net.Uri, selection: String?, args: Array<out String>?) = throw UnsupportedOperationException()
        }.also { it.attachInfo(app, ProviderInfo().apply { authority = AUTHORITY; exported = true; grantUriPermissions = true }) })
        storage = SafLibraryStorage(app, DocumentsContract.buildTreeDocumentUri(AUTHORITY, "token-a"))
    }

    @Test fun opaqueIdsRoundTripByActualDisplayNames() {
        storage.mkdir("manga_import/第 1 卷/chapter")
        storage.writeNew("manga_import/第 1 卷/chapter/001.txt").use { it.write("page".toByteArray()) }
        assertEquals("page", storage.read("manga_import/第 1 卷/chapter/001.txt").bufferedReader().use { it.readText() })
        assertEquals("manga_import/第 1 卷", storage.list("manga_import").single().path)
        assertTrue(DocumentsContract.getDocumentId(storage.uri("manga_import/第 1 卷/chapter/001.txt")).startsWith("opaque-"))
        storage.delete("manga_import/第 1 卷")
        assertFalse(storage.exists("manga_import/第 1 卷"))
    }

    @Test fun capacityBelongsOnlyToTheSelectedProviderRoot() {
        assertEquals(1234L, storage.availableBytes())
        val child = storage.apply { mkdir("manga_import") }.uri("manga_import")
        val selected = SafLibraryStorage(app, DocumentsContract.buildTreeDocumentUri(AUTHORITY, DocumentsContract.getDocumentId(child)))
        assertNull(selected.availableBytes())
        provider.space = null
        assertNull(storage.availableBytes())
    }

    @Test fun differentOpaqueRootsAndNestedRootsAreDistinguished() {
        val other = SafLibraryStorage(app, DocumentsContract.buildTreeDocumentUri(AUTHORITY, "token-b"))
        assertNotEquals(storage.locationId, other.locationId)
        assertFalse(storage.overlaps(other))
        storage.mkdir("child")
        val nested = SafLibraryStorage(app, DocumentsContract.buildTreeDocumentUri(AUTHORITY,
            DocumentsContract.getDocumentId(storage.uri("child"))))
        assertTrue(storage.overlaps(nested))
    }

    @Test fun switchingWaitsForReadersAndRejectsTheOldEntryEvenWithTheSameUuid() = kotlinx.coroutines.runBlocking {
        app.getSharedPreferences("library_locations", 0).edit().putString("tree", storage.tree.toString()).commit()
        val a = LibrarySessions.current(app)
        a.prepare()
        storage.mkdir("manga_import/book")
        val uuid = UUID.randomUUID().toString()
        val body = org.json.JSONObject().put("bookUuid", uuid).put("localRoot", "manga_import/book").put("title", "Same title")
        a.putBook("manga", 1, body); a.flush()
        val oldEntry = android.content.Intent().putExtra("library_location", a.locationId).putExtra("library_book_uuid", uuid)
        val reader = LibrarySources.acquire(a.local("manga_import/book"))
        val switch = async(kotlinx.coroutines.Dispatchers.Default) { LibrarySessions.select(app, null) }
        kotlinx.coroutines.delay(100)
        assertFalse(switch.isCompleted)
        reader.close()
        kotlinx.coroutines.withTimeout(10_000) { switch.await() }
        val b = LibrarySessions.current(app)
        assertNotEquals(a.locationId, b.locationId)
        b.storage.mkdir("manga_import/book")
        b.putBook("manga", 1, org.json.JSONObject(body.toString())); b.flush()
        assertThrows(java.io.IOException::class.java) { LibrarySessions.checkReaderIntent(app, oldEntry, "manga", 1) }
        var lateOperation = false
        LibrarySessions.launch(a.context()) { lateOperation = true }.join()
        assertFalse(lateOperation)
        assertEquals(1, a.books("manga").size)
        assertEquals(1, b.books("manga").size)
    }

    @Test fun permissionFailureIsNotAnEmptyDirectory() {
        provider.accessible = false
        assertThrows(Exception::class.java) { storage.list("") }
        assertThrows(Exception::class.java) { storage.exists("manga_import") }
    }

    @Test fun displayUsesReadableNameInsteadOfPercentEncodedUri() {
        storage.list("")
        assertEquals("Chosen", storage.displayPath)
        val chinese = SafLibraryStorage(app, DocumentsContract.buildTreeDocumentUri(AUTHORITY, "primary:间谍过家家"))
        assertEquals("primary:间谍过家家", chinese.displayPath)
        assertFalse(chinese.displayPath.contains("%"))
    }

    @Test fun returningToDefaultIgnoresUnrelatedNestedAppDirectories() = kotlinx.coroutines.runBlocking {
        app.getSharedPreferences("library_locations", 0).edit().putString("tree", storage.tree.toString()).commit()
        val original = LibrarySessions.current(app)
        original.prepare()
        val target = File(LibrarySessions.defaultDisplayPath(app))
        // It is outside the library sources and must never be traversed on a default switch.
        File(target, "models/unrelated/.starflow/library.json").apply { parentFile!!.mkdirs(); writeText("unrelated") }
        LibrarySessions.select(app, null)
        assertTrue(LibrarySessions.current(app).storage is FileLibraryStorage)
        assertEquals(target.path, LibrarySessions.current(app).storage.displayPath)
        assertEquals("unrelated", File(target, "models/unrelated/.starflow/library.json").readText())
    }

    @Test fun safImageFolderReadsOriginalMetadataAndSizesWithoutCopyingAllPages() = kotlinx.coroutines.runBlocking {
        app.getSharedPreferences("library_locations", 0).edit().putString("tree", storage.tree.toString()).commit()
        val session = LibrarySessions.current(app)
        session.prepare()
        session.storage.mkdir("manga_import/Book")
        val png = com.moe.starflow.utils.LibraryScanFixtures.png()
        session.storage.writeNew("manga_import/Book/001.png").use { it.write(png) }
        session.storage.writeNew("manga_import/Book/002.png").use { it.write(png) }
        session.storage.writeNew("manga_import/Book/meta.json").use { it.write("""{"summary":"SAF summary","author":"SAF author"}""".toByteArray()) }
        val result = LibraryScanner.scan(app, "manga")
        assertEquals(result.failures.joinToString(), 1, result.added)
        val manga = com.moe.starflow.mangaimport.data.ImportedMangaStore.load(app).single()
        assertTrue(manga.description.contains("SAF summary"))
        assertTrue(manga.description.contains("SAF author"))
        assertTrue(manga.sizeBytes >= png.size * 2)
        assertFalse(File(manga.localRoot, "002.png").exists())
    }

    @Test fun enumeratedDocumentsAreReadWithoutRepeatingAncestorQueries() {
        storage.mkdir("manga_import/Book")
        repeat(20) { index -> storage.writeNew("manga_import/Book/$index.txt").use { it.write("text".toByteArray()) } }
        storage.invalidateLookups()
        val nodes = storage.list("manga_import/Book")
        val queries = provider.childQueries
        nodes.forEach { assertEquals("text", storage.read(it.path).bufferedReader().use { it.readText() }) }
        assertEquals(queries, provider.childQueries)
        val removed = DocumentsContract.getDocumentId(storage.uri(nodes.first().path))
        provider.deleteDocument(removed)
        assertFalse(storage.exists(nodes.first().path))
        provider.accessible = false
        assertThrows(Exception::class.java) { storage.read(nodes.last().path).close() }
    }

    @Test fun fileImportCopiesAdjacentMetaWhenItsParentAlreadyHasALibraryGrant() = kotlinx.coroutines.runBlocking {
        app.getSharedPreferences("library_locations", 0).edit().putString("tree", storage.tree.toString()).commit()
        val session = LibrarySessions.current(app)
        session.prepare()
        storage.mkdir("manga_import/My original name")
        val zip = File(app.cacheDir, "source.cbz")
        com.moe.starflow.utils.LibraryScanFixtures.zip(zip, "001.png" to com.moe.starflow.utils.LibraryScanFixtures.png())
        storage.writeNew("manga_import/My original name/original.cbz").use { it.write(zip.readBytes()) }
        storage.writeNew("manga_import/My original name/meta.json").use { it.write("""{"series_title":"SPY×FAMILY","episode_label":"139話 前編"}""".toByteArray()) }
        val source = storage.uri("manga_import/My original name/original.cbz")
        val document = DocumentsContract.buildDocumentUri(AUTHORITY, DocumentsContract.getDocumentId(source))
        assertEquals("manga_import/My original name/original.cbz", (session.storage as SafLibraryStorage).pathForDocument(document, "manga_import"))
        val result = com.moe.starflow.mangaimport.data.MangaImporter.importArchive(session.context(), document, 12)
        assertTrue(com.moe.starflow.utils.LogCollector.getFormattedLogs(), result.description.contains("SPY×FAMILY"))
        assertTrue(File(File(result.localRoot).parentFile, "meta.json").isFile)
        assertTrue(storage.exists("manga_import/My original name/original.cbz"))
        assertTrue(storage.exists("manga_import/My original name/meta.json"))
    }

    private class OpaqueProvider(private val directory: File) : DocumentsProvider() {
        private data class Node(val id: String, val name: String, val parent: String?, val file: File, val dir: Boolean)
        private val nodes = linkedMapOf(
            "token-a" to Node("token-a", "Chosen", null, File(directory, "a").apply { mkdirs() }, true),
            "token-b" to Node("token-b", "Other", null, File(directory, "b").apply { mkdirs() }, true),
        )
        var accessible = true
        var space: Long? = 1234
        var childQueries = 0
        override fun onCreate() = true
        private fun checkAccess() { if (!accessible) throw FileNotFoundException("Permission revoked") }
        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)).apply {
            for (id in listOf("token-a", "token-b")) addRow(columnNames.map { name -> when (name) {
                DocumentsContract.Root.COLUMN_DOCUMENT_ID -> id
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES -> if (id == "token-a") space else 999999L
                else -> id
            } }.toTypedArray())
        }
        private fun documents(projection: Array<out String>?, values: List<Node>): Cursor {
            checkAccess()
            val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE)
            return MatrixCursor(columns).apply { values.forEach { node -> addRow(columns.map { name -> when (name) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> node.id
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> node.name
                DocumentsContract.Document.COLUMN_MIME_TYPE -> if (node.dir) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                DocumentsContract.Document.COLUMN_SIZE -> node.file.length()
                else -> null
            } }.toTypedArray()) } }
        }
        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = documents(projection, listOf(nodes.getValue(documentId)))
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
            childQueries++
            return documents(projection, nodes.values.filter { it.parent == parentDocumentId })
        }
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            checkAccess()
            val id = "opaque-${UUID.randomUUID()}"
            val dir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
            val file = File(nodes.getValue(parentDocumentId).file, displayName)
            if (dir) file.mkdirs() else file.createNewFile()
            nodes[id] = Node(id, displayName, parentDocumentId, file, dir)
            return id
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            checkAccess()
            return ParcelFileDescriptor.open(nodes.getValue(documentId).file, ParcelFileDescriptor.parseMode(mode))
        }
        override fun deleteDocument(documentId: String) {
            checkAccess()
            nodes.getValue(documentId).file.deleteRecursively()
            val removed = nodes.keys.filter { it == documentId || isChildDocument(documentId, it) }
            removed.forEach(nodes::remove)
        }
        override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
            var id: String? = documentId
            while (id != null) { id = nodes[id]?.parent; if (id == parentDocumentId) return true }
            return false
        }
    }
    companion object { private const val AUTHORITY = "library-test-provider" }
}
