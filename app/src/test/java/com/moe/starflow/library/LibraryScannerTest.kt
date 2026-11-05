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

    @Test fun repeatedScanAddsPagesInsideAnIndexedGroupWithoutDuplicatingTheBook() = runBlocking {
        LibrarySessions.prepare(app)
        val first = File(root, "manga_import/Group/one.cbz")
        LibraryScanFixtures.zip(first, "001.png" to LibraryScanFixtures.png(),
            "meta.json" to """{"author":"Writer","description":"A book summary"}""".toByteArray())
        assertEquals(1, LibraryScanner.scan(app, "manga").added)
        val manga = ImportedMangaStore.load(app).single()
        assertTrue(manga.description.contains("A book summary"))
        assertEquals("A book summary", manga.description)
        assertEquals(0, LibraryScanner.scan(app, "manga").added)
        ImportedMangaStore.patch(app, manga) { it.copy(description = "My edited summary") }
        LibraryScanFixtures.zip(File(first.parentFile, "two.cbz"), "001.png" to LibraryScanFixtures.png())
        assertEquals(0, LibraryScanner.scan(app, "manga").added)
        val updated = ImportedMangaStore.load(app).single()
        assertEquals(manga.bookUuid, updated.bookUuid)
        assertEquals(2, updated.pageCount)
        assertEquals("My edited summary", updated.description)
        first.delete()
        LibraryScanner.scan(app, "manga")
        assertTrue(LibrarySessions.current(app).sourcePresence["manga_import/Group"]!!)
        assertEquals(1, ImportedMangaStore.load(app).single().missingPages)
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
    @Test fun badPackageBetweenGoodBooksIsIsolatedAndEmptyFoldersAreNotErrors() = runBlocking {
        LibrarySessions.prepare(app)
        LibraryScanFixtures.zip(File(root, "manga_import/a.cbz"), "1.png" to LibraryScanFixtures.png())
        File(root, "manga_import/b.zip").writeText("broken archive")
        LibraryScanFixtures.zip(File(root, "manga_import/c.cbz"), "1.png" to LibraryScanFixtures.png())
        File(root, "manga_import/empty").mkdirs()
        val result = LibraryScanner.scan(app, "manga")
        assertEquals(result.failures.joinToString(), 3, result.added)
        assertEquals(1, result.issues.count { it.scope == LibraryIssueScope.BOOK && it.outcome == LibraryBookOutcome.NOT_ADDED })
        assertEquals(1, result.issues.count { it.scope == LibraryIssueScope.NOTICE })
        assertFalse(result.needsRecovery)
        assertEquals(1, result.failures.size)
        assertEquals(3, ImportedMangaStore.load(app).size)
        assertEquals(0, LibraryScanner.scan(app, "manga").issues.count { it.scope == LibraryIssueScope.NOTICE })
    }

    @Test fun invalidSourceIdentityIsPreservedAndNeverCollectedAsAUuidShell() = runBlocking {
        LibrarySessions.prepare(app)
        val session = LibrarySessions.current(app)
        listOf("{broken", "mismatch").forEach { contents ->
            val uuid = UUID.randomUUID().toString()
            val folder = File(root, "manga_import/$uuid").apply { mkdirs() }
            File(folder, "1.png").writeBytes(LibraryScanFixtures.png())
            val path = ".starflow/books/$uuid/source.json"
            val original = if (contents == "mismatch") org.json.JSONObject().put("format", 1)
                .put("uuid", UUID.randomUUID().toString()).put("kind", "novel").put("source", "novel_import/wrong").toString()
                else contents
            File(root, path).apply { parentFile!!.mkdirs(); writeText(original) }
            val result = LibraryScanner.scan(app, "manga")
            assertEquals(0, result.added)
            assertTrue(result.issues.any { it.scope == LibraryIssueScope.BOOK && it.path == "manga_import/$uuid" })
            if (contents == "mismatch") assertTrue(result.failures.any { it.contains("expected=") && it.contains("actual=") })
            assertEquals(original, File(root, path).readText())
        }
        assertTrue(session.scanBooks("manga").isEmpty())
    }

    @Test fun oldPendingFailureDoesNotBlockNewBooksAndIsReportedAsFilesPending() = runBlocking {
        LibrarySessions.prepare(app)
        val session = LibrarySessions.current(app)
        val uuid = UUID.randomUUID().toString()
        // An impossible old destination provides a stable file failure without changing the root.
        File(root, ".starflow/books/$uuid").apply { parentFile!!.mkdirs(); writeText("conflict") }
        File(root, "manga_import/old").mkdirs()
        session.putBook("manga", 900, org.json.JSONObject().put("bookUuid", uuid)
            .put("title", "Old book").put("localRoot", "manga_import/old"))
        LibraryScanFixtures.zip(File(root, "manga_import/new.cbz"), "1.png" to LibraryScanFixtures.png())
        val result = LibraryScanner.scan(app, "manga")
        assertEquals(result.failures.joinToString(), 1, result.added)
        assertTrue(result.issues.any { it.scope == LibraryIssueScope.FILES && it.uuid == uuid && it.outcome == LibraryBookOutcome.FILES_PENDING })
        assertFalse(result.issues.any { it.scope == LibraryIssueScope.LIBRARY })
        assertEquals(2, session.scanBooks("manga").size)
    }

    @Test fun libraryIdentityFailuresStopDiscovery() = runBlocking {
        LibrarySessions.prepare(app)
        val session = LibrarySessions.current(app)
        LibraryScanFixtures.zip(File(root, "manga_import/good.cbz"), "1.png" to LibraryScanFixtures.png())
        File(root, ".starflow/library.json").writeText("{broken")
        val result = LibraryScanner.scan(app, "manga")
        assertEquals(0, result.added)
        assertEquals(LibraryIssueScope.LIBRARY, result.issues.single().scope)
        assertEquals(LibraryErrorKind.IDENTITY.name, result.issues.single().reason)
        assertTrue(session.scanBooks("manga").isEmpty())
    }

    @Test fun databaseReadFailureIsFatalAndCannotBecomeAnEmptyDiscoveryCatalog() = runBlocking {
        val backend = FileLibraryStorage(root)
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val session = LibrarySession(app, object : LibraryStorage by backend {}, db, 1)
        try {
            session.prepare()
            db.openHelper.writableDatabase.execSQL("DROP TABLE library_book")
            val result = LibraryScanner.scan(session.context(), "manga")
            assertEquals(0, result.added)
            assertEquals(LibraryIssueScope.LIBRARY, result.issues.single().scope)
            assertEquals(app.getString(com.moe.starflow.R.string.library_stage_catalog), result.issues.single().stage)
            assertEquals(LibraryErrorKind.DATABASE.name, result.issues.single().reason)
        } finally { session.close() }
    }

    @Test fun rootEnumerationFailureStopsScanningAndCancellationIsNotReportedAsABadBook() = runBlocking {
        val backend = FileLibraryStorage(root)
        var failEnumeration = false
        var cancel = false
        val guarded = object : LibraryStorage by backend {
            override fun list(path: String): List<LibraryNode> {
                if (path == "manga_import" && failEnumeration) {
                    if (cancel) throw kotlinx.coroutines.CancellationException("Stopped scan")
                    throw java.io.IOException("Root enumeration denied", android.database.sqlite.SQLiteException("Provider backend unavailable"))
                }
                return backend.list(path)
            }
        }
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val session = LibrarySession(app, guarded, db, 1)
        try {
            session.prepare()
            failEnumeration = true
            val result = LibraryScanner.scan(session.context(), "manga")
            assertEquals(0, result.added)
            assertEquals(LibraryIssueScope.LIBRARY, result.issues.single().scope)
            assertEquals(app.getString(com.moe.starflow.R.string.library_stage_enumerate), result.issues.single().stage)
            assertEquals(LibraryErrorKind.ACCESS.name, result.issues.single().reason)
            cancel = true
            try { LibraryScanner.scan(session.context(), "manga"); fail("Cancellation swallowed") }
            catch (_: kotlinx.coroutines.CancellationException) { }
            assertFalse(LibraryScanner.state.value.running)
        } finally { session.close() }
    }

    @Test fun damagedExistingPayloadDoesNotPreventDiscoveringAnotherBook() = runBlocking {
        LibrarySessions.prepare(app)
        val session = LibrarySessions.current(app)
        val uuid = UUID.randomUUID().toString()
        session.database.catalog().put(LibraryBook("manga", 900, uuid, "{broken", "manga_import/old", false))
        LibraryScanFixtures.zip(File(root, "manga_import/new.cbz"), "1.png" to LibraryScanFixtures.png())
        val result = LibraryScanner.scan(app, "manga")
        assertEquals(result.failures.joinToString(), 1, result.added)
        assertTrue(result.issues.any { it.scope == LibraryIssueScope.BOOK && it.uuid == uuid })
        assertFalse(result.needsRecovery)
        assertEquals("{broken", session.database.catalog().byUuid(uuid)!!.body)
    }

    @Test fun initialLegacyMigrationRemainsStrictAndRetainsItsRetryMarker() = runBlocking {
        val session = LibrarySessions.current(app)
        session.prepare() // Do not complete the one-time legacy migration yet.
        val uuid = UUID.randomUUID().toString()
        File(root, ".starflow/books/$uuid").writeText("conflict")
        session.putBook("manga", 900, org.json.JSONObject().put("bookUuid", uuid)
            .put("title", "Retained").put("localRoot", "manga_import/old"))
        val result = LibraryScanner.scan(app, "manga")
        assertEquals(0, result.added)
        assertEquals(app.getString(com.moe.starflow.R.string.library_stage_migrate), result.issues.single().stage)
        assertEquals(LibraryIssueScope.LIBRARY, result.issues.single().scope)
        assertNull(session.database.catalog().meta("legacyMigrated"))
        assertNotNull(session.database.catalog().byUuid(uuid))
        assertTrue(session.database.catalog().pendingCount() > 0)
    }

}
