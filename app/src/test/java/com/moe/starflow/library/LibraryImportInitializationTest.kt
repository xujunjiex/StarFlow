package com.moe.starflow.library

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import com.moe.starflow.mangaimport.data.ImportManager
import com.moe.starflow.novel.data.NovelImportManager
import com.moe.starflow.utils.LibraryScanFixtures
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

/** First imports race initialization, so reservations must survive restored catalog rows. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LibraryImportInitializationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun firstMangaFileImportPreservesLegacyBook() = checkFirstImport("manga", directory = false, valid = true)
    @Test fun firstNovelFileImportPreservesLegacyBook() = checkFirstImport("novel", directory = false, valid = true)
    @Test fun failedMangaFileImportPreservesLegacySource() = checkFirstImport("manga", directory = false, valid = false)
    @Test fun failedNovelFileImportPreservesLegacySource() = checkFirstImport("novel", directory = false, valid = false)
    @Test fun firstMangaDirectoryImportPreservesLegacyBook() = checkFirstImport("manga", directory = true, valid = true)
    @Test fun firstNovelDirectoryImportPreservesLegacyBook() = checkFirstImport("novel", directory = true, valid = true)
    @Test fun failedMangaDirectoryImportPreservesLegacySource() = checkFirstImport("manga", directory = true, valid = false)
    @Test fun failedNovelDirectoryImportPreservesLegacySource() = checkFirstImport("novel", directory = true, valid = false)

    private fun checkFirstImport(kind: String, directory: Boolean, valid: Boolean) = runBlocking {
        val app: Context = RuntimeEnvironment.getApplication()
        val root = temp.newFolder("library")
        app.getSharedPreferences("library_locations", 0).edit().clear().putString("defaultPath", root.path).commit()
        val legacyUuid = "00000000-0000-0000-0000-000000000001"
        val legacy = File(root, "${kind}_import/$legacyUuid/${if (kind == "manga") "old.cbz" else "old.txt"}")
            .apply { parentFile!!.mkdirs() }
        if (kind == "manga") LibraryScanFixtures.zip(legacy, "001.png" to LibraryScanFixtures.png())
        else legacy.writeText("Legacy original chapter text.")
        val originalBytes = legacy.readBytes()
        val old = JSONObject().put("id", 1).put("title", "Legacy").put("localRoot", legacy.path)
            .put("bookUuid", legacyUuid).put("coverPath", JSONObject.NULL)
            .put("translatedPath", JSONObject.NULL).put("addedAt", 100)
        if (kind == "manga") old.put("isArchive", true).put("pageCount", 1)
        else old.put("format", "TXT").put("chapterCount", 1)
        app.getSharedPreferences(if (kind == "manga") "manga_import" else "novel_shelf", 0).edit()
            .putString("imported_${if (kind == "manga") "manga" else "novel"}_list", JSONArray().put(old).toString()).commit()

        ImportManager.drainEvents()
        NovelImportManager.drainEvents()
        val session = LibrarySessions.current(app)
        if (directory) {
            val tree = directorySource(app, kind, valid)
            if (kind == "manga") ImportManager.importDirectory(app, tree)
            else NovelImportManager.importDirectory(app, tree)
        } else {
            val incoming = temp.newFile(if (kind == "manga") "Incoming.cbz" else "Incoming.txt")
            if (valid) {
                if (kind == "manga") LibraryScanFixtures.zip(incoming, "001.png" to LibraryScanFixtures.png())
                else incoming.writeText("Incoming chapter content for a separate book.")
            }
            if (kind == "manga") ImportManager.importArchives(app, listOf(Uri.fromFile(incoming)))
            else NovelImportManager.importFiles(app, listOf(Uri.fromFile(incoming)))
        }
        withTimeout(20_000) {
            while (session.books(kind).isEmpty() ||
                (if (kind == "manga") ImportManager.tasks.value.isNotEmpty() else NovelImportManager.tasks.value.isNotEmpty())) delay(10)
        }
        val events = if (kind == "manga") ImportManager.drainEvents() else NovelImportManager.drainEvents()
        assertEquals(events.toString(), if (valid) 0 else 1, events.size)
        val books = session.books(kind)
        assertEquals(if (valid) 2 else 1, books.size)
        val retained = books.single { it.id == 1L }
        assertEquals("Legacy", JSONObject(retained.body).getString("title"))
        assertEquals(legacyUuid, retained.uuid)
        assertEquals(session.relative(legacy), retained.source)
        assertArrayEquals(originalBytes, legacy.readBytes())
        if (valid) {
            val imported = books.single { it.id != 1L }
            assertNotEquals(legacyUuid, imported.uuid)
            assertTrue(session.local(imported.source).exists())
        }
        assertTrue(session.database.catalog().allMeta().none { it.name.startsWith("importing:$kind:") && it.value.isNotEmpty() })
        assertThrows(IllegalStateException::class.java) { session.beginImport(kind, 1) }
        assertArrayEquals(originalBytes, legacy.readBytes())
    }

    private fun directorySource(app: Context, kind: String, valid: Boolean): Uri {
        val tree = DocumentsContract.buildTreeDocumentUri("initial-import", "root")
        val image = kind == "manga"
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = if (DocumentsContract.getDocumentId(uri) == "root")
                DocumentsContract.Document.MIME_TYPE_DIR else if (image) "image/png" else "text/plain"
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
                val child = uri.lastPathSegment == "children"
                val columns = projection!!
                return MatrixCursor(columns).apply {
                    addRow(columns.map { column -> when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> if (child) "page" else "root"
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> if (!child) "Incoming" else if (image) "001.png" else "chapter.txt"
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> if (!child) DocumentsContract.Document.MIME_TYPE_DIR else if (image) "image/png" else "text/plain"
                        DocumentsContract.Document.COLUMN_SIZE -> 0L
                        DocumentsContract.Document.COLUMN_FLAGS -> 0
                        else -> null
                    } }.toTypedArray())
                }
            }
        }
        ShadowContentResolver.registerProviderInternal("initial-import", provider)
        if (valid) shadowOf(app.contentResolver).registerInputStream(
            DocumentsContract.buildDocumentUriUsingTree(tree, "page"),
            (if (image) LibraryScanFixtures.png() else "Incoming chapter text.".toByteArray()).inputStream(),
        )
        return tree
    }
}
