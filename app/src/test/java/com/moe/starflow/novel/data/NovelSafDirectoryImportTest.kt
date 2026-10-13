package com.moe.starflow.novel.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import com.moe.starflow.novel.parser.FolderNovelParser
import com.moe.starflow.utils.LibraryScanFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelSafDirectoryImportTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private data class Document(val id: String, val name: String, val mime: String, val parent: String?)

    private class TreeProvider(private val documents: List<Document>) : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = documents.singleOrNull { it.id == DocumentsContract.getDocumentId(uri) }?.mime
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection!!.map { it }.toTypedArray()
            val id = DocumentsContract.getDocumentId(uri)
            val rows = if (uri.lastPathSegment == "children") documents.filter { it.parent == id }
                else documents.filter { it.id == id }
            return MatrixCursor(columns).apply {
                rows.forEach { doc ->
                    addRow(columns.map { column ->
                        when (column) {
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> doc.id
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> doc.name
                            DocumentsContract.Document.COLUMN_MIME_TYPE -> doc.mime
                            DocumentsContract.Document.COLUMN_SIZE -> 0L
                            else -> null
                        }
                    }.toTypedArray())
                }
            }
        }
    }

    @Before
    fun setUp() {
        NovelStore.save(context, emptyList())
        NovelStorageDir.root(context).deleteRecursively()
    }

    @Test
    fun opaqueDocumentIdsPreserveNestedChaptersAndRelativeImages() = runBlocking {
        val directoryMime = DocumentsContract.Document.MIME_TYPE_DIR
        val documents = listOf(
            Document("root-1", "Book", directoryMime, null),
            Document("folder-9", "Volume2", directoryMime, "root-1"),
            Document("image-folder", "images", directoryMime, "folder-9"),
            Document("chapter-1", "01.txt", "text/plain", "root-1"),
            Document("chapter-2", "01.html", "text/html", "folder-9"),
            Document("image-7", "picture.png", "image/png", "image-folder"),
        )
        val treeUri = DocumentsContract.buildTreeDocumentUri("opaque-novel", "root-1")
        ShadowContentResolver.registerProviderInternal("opaque-novel", TreeProvider(documents))
        val png = LibraryScanFixtures.png()
        val bodies = mapOf(
            "chapter-1" to "Root chapter body".toByteArray(),
            "chapter-2" to """<p>Nested chapter body</p><img src="images/picture.png"/>""".toByteArray(),
            "image-7" to png,
        )
        bodies.forEach { (id, bytes) ->
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
            Shadows.shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
        }

        val book = NovelImporter.importDirectory(context, treeUri, 1)
        val folder = File(book.localRoot)
        val chapters = FolderNovelParser.parse(folder).chapters

        assertEquals(listOf("01.txt", "Volume2/01.html"), chapters.map { it.locator })
        assertEquals("Root chapter body", FolderNovelParser.loadChapter(folder, chapters[0].locator))
        val nested = FolderNovelParser.loadChapterSource(folder, chapters[1].locator)
        assertTrue(nested.text.contains("Nested chapter body"))
        assertArrayEquals(png, nested.images.single())
        assertArrayEquals(png, File(folder, "Volume2/images/picture.png").readBytes())
    }

    @Test
    fun all2001IllustrationsAreCopiedWithoutTheOldLimit() = runBlocking {
        val images = (0..2000).map { Document("image-$it", "$it.png", "image/png", "root") }
        val documents = listOf(Document("root", "Large book", DocumentsContract.Document.MIME_TYPE_DIR, null),
            Document("chapter", "chapter.html", "text/html", "root")) + images
        val tree = DocumentsContract.buildTreeDocumentUri("large-novel", "root")
        ShadowContentResolver.registerProviderInternal("large-novel", TreeProvider(documents))
        val resolver = Shadows.shadowOf(context.contentResolver)
        resolver.registerInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, "chapter"),
            "<p>Text</p><img src='2000.png'/>".byteInputStream())
        val png = LibraryScanFixtures.png()
        images.forEach { resolver.registerInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, it.id), png.inputStream()) }
        val book = NovelImporter.importDirectory(context, tree, 2)
        assertEquals(2001, File(book.localRoot).listFiles()!!.count { it.extension == "png" })
        assertArrayEquals(png, File(book.localRoot, "2000.png").readBytes())
    }

    @Test
    fun failedIllustrationCopyFailsTheWholeImportAndCleansThePartialCopy() = runBlocking {
        val documents = listOf(Document("root", "Failed book", DocumentsContract.Document.MIME_TYPE_DIR, null),
            Document("chapter", "chapter.html", "text/html", "root"), Document("image", "image.png", "image/png", "root"))
        val tree = DocumentsContract.buildTreeDocumentUri("failed-novel", "root")
        ShadowContentResolver.registerProviderInternal("failed-novel", TreeProvider(documents))
        val resolver = Shadows.shadowOf(context.contentResolver)
        resolver.registerInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, "chapter"), "<p>Text</p>".byteInputStream())
        resolver.registerInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, "image"), object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("Image source read failed")
        })
        val failure = runCatching { NovelImporter.importDirectory(context, tree, 3) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("image.png"))
        assertTrue(NovelStorageDir.root(context).walkTopDown().none { it.isFile && it.name == "chapter.html" })
        assertEquals(3, documents.size) // The provider's source tree was not modified.
    }

    @Test
    fun chapterCanResolveSiblingImagesWithinTheBookButCannotEscapeIt() = runBlocking {
        val root = File(context.cacheDir, "relative-image-book").apply { mkdirs() }
        val chapter = File(root, "chapters/one.html").apply { parentFile!!.mkdirs(); writeText("<p>Text</p><img src='../images/page.png'/>") }
        val image = File(root, "images/page.png").apply { parentFile!!.mkdirs(); writeBytes(LibraryScanFixtures.png()) }
        assertArrayEquals(image.readBytes(), FolderNovelParser.loadChapterSource(root, "chapters/one.html").images.single())
        chapter.writeText("<p>Text</p><img src='../../outside.png'/>")
        assertTrue(FolderNovelParser.loadChapterSource(root, "chapters/one.html").images.all { it == null })
    }

    @Test
    fun unsafeProviderNameCannotEscapeTheBookDirectory() = runBlocking {
        val documents = listOf(
            Document("root", "Book", DocumentsContract.Document.MIME_TYPE_DIR, null),
            Document("file", "../../outside.txt", "text/plain", "root"),
        )
        val treeUri = DocumentsContract.buildTreeDocumentUri("unsafe-novel", "root")
        ShadowContentResolver.registerProviderInternal("unsafe-novel", TreeProvider(documents))
        Shadows.shadowOf(context.contentResolver).registerInputStream(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, "file"), "Safe body".byteInputStream(),
        )

        val book = NovelImporter.importDirectory(context, treeUri, 1)

        assertEquals("Safe body", File(book.localRoot, "outside.txt").readText())
        assertFalse(File(NovelStorageDir.root(context), "outside.txt").exists())
    }
}
