package com.moe.starflow.library

import android.content.Context
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import com.moe.starflow.novel.data.NovelStore
import com.moe.starflow.utils.LibraryScanFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryScannerTest {
    private lateinit var app: Context
    private lateinit var root: File
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        root = File(app.cacheDir, "scan-${UUID.randomUUID()}").apply { mkdirs() }
        app.getSharedPreferences("library_locations", 0).edit().clear().putString("defaultPath", root.path).commit()
    }

    @Test fun emptyDirectoriesAreCataloguedWarnOnceAndAreDeletedWithTheirBook() = runBlocking {
        LibrarySessions.prepare(app)
        for (kind in listOf("manga", "novel")) {
            val directory = File(root, "${kind}_import/Empty book").apply { mkdirs() }
            val first = LibraryScanner.scan(app, kind)
            assertEquals(first.failures.joinToString(), 1, first.added)
            assertEquals(1, first.failures.size)
            val session = LibrarySessions.current(app)
            val book = session.books(kind).single()
            assertEquals(1, LibraryScanner.consumeFailures(session.locationId, kind)!!.size)
            repeat(2) {
                val again = LibraryScanner.scan(app, kind)
                assertEquals(0, again.added)
                assertTrue(again.failures.joinToString(), again.failures.isEmpty())
                assertEquals(book.uuid, session.books(kind).single().uuid)
                assertNull(LibraryScanner.consumeFailures(session.locationId, kind))
            }
            assertTrue(directory.isDirectory)
            if (kind == "manga") assertFalse(ImportedMangaStore.load(app).single().lost)
            else assertFalse(NovelStore.load(app).single().lost)
            LibraryDeletion.start(app, kind, listOf(book.id), mapOf(book.id to book.uuid))
            kotlinx.coroutines.withTimeout(10_000) {
                while (session.database.catalog().book(kind, book.id) != null || LibraryDeletion.state.value.running)
                    kotlinx.coroutines.delay(10)
            }
            assertFalse(directory.exists())
            assertEquals(0, LibraryScanner.scan(app, kind).added)
        }
    }

    @Test fun addingContentToAnEmptyDirectoryKeepsIdentityAndUserTitle() = runBlocking {
        LibrarySessions.prepare(app)
        for (kind in listOf("manga", "novel")) {
            val directory = File(root, "${kind}_import/Waiting").apply { mkdirs() }
            LibraryScanner.scan(app, kind)
            val original = LibrarySessions.current(app).books(kind).single()
            if (kind == "manga") {
                val book = ImportedMangaStore.load(app).single()
                ImportedMangaStore.patch(app, book) { it.copy(title = "My title", titleOrigin = "user") }
                File(directory, "001.png").writeBytes(LibraryScanFixtures.png())
            } else {
                val book = NovelStore.load(app).single()
                NovelStore.patch(app, book) { it.copy(title = "My title") }
                File(directory, "chapter.txt").writeText("Chapter body")
            }
            val scan = LibraryScanner.scan(app, kind)
            assertTrue(scan.failures.joinToString(), scan.failures.isEmpty())
            assertEquals(0, scan.added)
            assertEquals(original.uuid, LibrarySessions.current(app).books(kind).single().uuid)
            if (kind == "manga") {
                val book = ImportedMangaStore.load(app).single()
                assertEquals("My title", book.title); assertEquals(1, book.pageCount)
            } else {
                val book = NovelStore.load(app).single()
                assertEquals("My title", book.title); assertEquals(1, book.chapterCount)
            }
        }
    }

    @Test fun repeatedScanUpdatesChaptersInOneBookAndKeepsEditedMetadata() = runBlocking {
        LibrarySessions.prepare(app)
        val first = File(root, "manga_import/Group/one.cbz")
        LibraryScanFixtures.zip(first, "001.png" to LibraryScanFixtures.png(),
            "meta.json" to """{"author":"Writer","description":"A book summary"}""".toByteArray())
        assertEquals(1, LibraryScanner.scan(app, "manga").added)
        val manga = ImportedMangaStore.load(app).single()
        assertTrue(manga.description.contains("A book summary"))
        assertTrue(manga.description.contains("Writer"))
        assertEquals(0, LibraryScanner.scan(app, "manga").added)
        ImportedMangaStore.patch(app, manga) { it.copy(description = "My edited summary") }
        LibraryScanFixtures.zip(File(first.parentFile, "two.cbz"), "001.png" to LibraryScanFixtures.png())
        assertEquals(0, LibraryScanner.scan(app, "manga").added)
        assertEquals(2, ImportedMangaStore.load(app).single().pageCount)
        assertEquals("My edited summary", ImportedMangaStore.load(app).single { it.id == manga.id }.description)
        first.delete()
        LibraryScanner.scan(app, "manga")
        assertFalse(LibrarySessions.current(app).sourcePresence["manga_import/Group"]!!)
        assertEquals(1, ImportedMangaStore.load(app).size)
        assertTrue(ImportedMangaStore.load(app).single().lost)
    }

    @Test fun anExistingNestedMangaDoesNotImportItsParentAsADuplicate() = runBlocking {
        LibrarySessions.prepare(app)
        val source = File(root, "manga_import/Original title/original.cbz")
        LibraryScanFixtures.zip(source, "001.png" to LibraryScanFixtures.png())
        val manga = com.moe.starflow.mangaimport.data.MangaImporter.inspectLocalSource(app, source, 1, "Original title")
        ImportedMangaStore.add(app, manga)
        LibrarySessions.current(app).flush()
        val uuid = ImportedMangaStore.load(app).single().bookUuid
        repeat(2) {
            val scan = LibraryScanner.scan(app, "manga")
            assertTrue(scan.failures.joinToString(), scan.failures.isEmpty())
            assertEquals(0, scan.added)
            val current = ImportedMangaStore.load(app).single()
            assertEquals(uuid, current.bookUuid)
            assertEquals(source.canonicalPath, File(current.localRoot).canonicalPath)
        }
    }

    @Test fun repairsOldEmptyDescriptionsOnceAndKeepsIdentityAndReadProgress() = runBlocking {
        LibrarySessions.prepare(app)
        val folder = File(root, "manga_import/Book").apply { mkdirs() }
        File(folder, "001.png").writeBytes(LibraryScanFixtures.png())
        File(folder, "meta.json").writeText("""{"summary":"Metadata summary"}""")
        LibraryScanner.scan(app, "manga")
        val manga = ImportedMangaStore.load(app).single()
        ImportedMangaStore.patch(app, manga) { it.copy(description = "", metadataVersion = 0, lastReadPage = 1) }
        assertEquals(0, LibraryScanner.scan(app, "manga").added)
        val repaired = ImportedMangaStore.load(app).single()
        assertEquals("Metadata summary", repaired.description)
        assertEquals(manga.bookUuid, repaired.bookUuid)
        assertEquals(1, repaired.lastReadPage)
        ImportedMangaStore.patch(app, repaired) { it.copy(description = "") }
        LibraryScanner.scan(app, "manga")
        assertEquals("", ImportedMangaStore.load(app).single().description)
    }

    @Test fun mangaExcludesEpubAndNovelsReadMetadataAcrossTextFolderZipAndEpub() = runBlocking {
        LibrarySessions.prepare(app)
        LibraryScanFixtures.epub(File(root, "manga_import/novel.zip"))
        assertEquals(0, LibraryScanner.scan(app, "manga").added)
        val texts = File(root, "novel_import/Text book").apply { mkdirs() }
        File(texts, "chapter.txt").writeText("Some text")
        File(texts, "meta.json").writeText("""{"author":"Text writer","description":"Text summary"}""")
        LibraryScanFixtures.zip(File(root, "novel_import/html.zip"), "chapter.html" to "<p>Body</p>".toByteArray(),
            "meta.json" to """{"summary":"ZIP summary"}""".toByteArray())
        val epub = File(root, "novel_import/novel.epub")
        LibraryScanFixtures.zip(epub,
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>""".toByteArray(),
            "book.opf" to """<package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>EPUB book</dc:title><dc:creator>EPUB writer</dc:creator><dc:description>EPUB summary</dc:description></metadata><manifest><item id="one" href="one.xhtml"/></manifest><spine><itemref idref="one"/></spine></package>""".toByteArray(),
            "one.xhtml" to "<html><body><p>Chapter</p></body></html>".toByteArray())
        val result = LibraryScanner.scan(app, "novel")
        assertEquals(result.failures.joinToString(), 3, result.added)
        val novels = NovelStore.load(app)
        assertTrue(novels.any { it.description.contains("Text summary") && it.author == "Text writer" })
        assertTrue(novels.any { it.description.contains("ZIP summary") })
        assertTrue(novels.any { it.description.contains("EPUB summary") && it.author == "EPUB writer" })
        assertEquals(0, LibraryScanner.scan(app, "novel").added)
    }

    @Test fun standaloneHtmlAndTxtSidecarsAlsoFillDescriptions() = runBlocking {
        LibrarySessions.prepare(app)
        File(root, "novel_import/page.html").writeText("""<html><head><meta name="description" content="HTML &amp; summary"><meta name='author' content='HTML writer'></head><body><p>Body</p></body></html>""")
        File(root, "novel_import/book.txt").writeText("Chapter body")
        File(root, "novel_import/book.meta.json").writeText("""{"summary":"TXT summary"}""")
        val result = LibraryScanner.scan(app, "novel")
        assertEquals(result.failures.joinToString(), 2, result.added)
        val novels = NovelStore.load(app)
        assertTrue(novels.any { it.description.contains("HTML & summary") && it.author == "HTML writer" })
        assertTrue(novels.any { it.description == "TXT summary" })
    }

    @Test fun simultaneousMangaAndNovelRequestsBothFinish() = runBlocking {
        LibrarySessions.prepare(app)
        LibraryScanFixtures.zip(File(root, "manga_import/manga.cbz"), "001.png" to LibraryScanFixtures.png())
        File(root, "novel_import/novel.txt").writeText("Novel chapter")
        LibraryScanner.start(app, "manga")
        LibraryScanner.start(app, "novel")
        kotlinx.coroutines.withTimeout(10_000) {
            while (ImportedMangaStore.load(app).size != 1 || NovelStore.load(app).size != 1 || LibraryScanner.state.value.running)
                kotlinx.coroutines.delay(10)
        }
        assertEquals(1, ImportedMangaStore.load(app).size)
        assertEquals(1, NovelStore.load(app).size)
    }

    @Test fun oneShelfScanCannotOverwriteTheOtherShelfsFailureReport() = runBlocking {
        LibrarySessions.prepare(app)
        File(root, "manga_import/bad.zip").writeText("Not an archive")
        File(root, "novel_import/good.txt").writeText("Novel body")
        assertFalse(LibraryScanner.scan(app, "manga").failures.isEmpty())
        assertTrue(LibraryScanner.scan(app, "novel").failures.isEmpty())
        val location = LibrarySessions.current(app).locationId
        assertTrue(LibraryScanner.consumeFailures(location, "manga")!!.single().contains("bad.zip"))
        assertNull(LibraryScanner.consumeFailures(location, "manga"))
        assertNull(LibraryScanner.consumeFailures(location, "novel"))
    }

    @Test fun oldZipAndFolderBooksRecoverEmbeddedHtmlMetadata() = runBlocking {
        LibrarySessions.prepare(app)
        val html = """<html><head><meta name="description" content="Recovered summary"><meta name="author" content="Recovered writer"></head><body><p>Text</p></body></html>"""
        val folder = File(root, "novel_import/Folder").apply { mkdirs() }
        File(folder, "chapter.html").writeText(html)
        LibraryScanFixtures.zip(File(root, "novel_import/package.zip"), "chapter.html" to html.toByteArray())
        assertEquals(2, LibraryScanner.scan(app, "novel").added)
        NovelStore.load(app).forEach { book -> NovelStore.patch(app, book) {
            it.copy(description = "", author = null, metadataVersion = 0, lastReadParaIndex = 4)
        } }
        assertEquals(0, LibraryScanner.scan(app, "novel").added)
        NovelStore.load(app).forEach {
            assertTrue(it.description.contains("Recovered summary"))
            assertEquals("Recovered writer", it.author)
            assertEquals(4, it.lastReadParaIndex)
        }
    }
}
