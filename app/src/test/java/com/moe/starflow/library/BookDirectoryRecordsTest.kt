package com.moe.starflow.library

import android.content.Context
import com.moe.starflow.data.ImportedPageTranslation
import com.moe.starflow.data.ImportedPageSr
import com.moe.starflow.mangaimport.data.*
import com.moe.starflow.mangaimport.reader.ReaderPageSource
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
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookDirectoryRecordsTest {
    private lateinit var app: Context
    private lateinit var root: File
    @Before fun setup() = runBlocking {
        app = RuntimeEnvironment.getApplication()
        root = File(app.cacheDir, "directory-${UUID.randomUUID()}").apply { mkdirs() }
        app.getSharedPreferences("library_locations", 0).edit().clear().putString("defaultPath", root.path).commit()
        LibrarySessions.prepare(app)
    }
    private fun png(path: String) = File(root, "manga_import/Book/$path").apply {
        parentFile!!.mkdirs(); writeBytes(LibraryScanFixtures.png())
    }
    @Test fun folderNameOwnsBookAndMixedContainersAreReadAsChapters() = runBlocking {
        png("ch2/001.png")
        val zip = File(root, "manga_import/Book/ch10.cbz")
        LibraryScanFixtures.zip(zip, "002.png" to LibraryScanFixtures.png(), "001.png" to LibraryScanFixtures.png())
        File(zip.parentFile, "meta.json").writeText("""{"summary":"Book metadata"}""")
        val bytes = zip.readBytes()
        val result = LibraryScanner.scan(app, "manga")
        assertEquals(result.failures.joinToString(), 1, result.added)
        val book = ImportedMangaStore.load(app).single()
        assertEquals("Book", book.title)
        assertEquals(3, book.pageCount)
        assertEquals(listOf("ch2", "ch10"), book.chapters.map { it.title })
        val reader = ReaderPageSource(false, book.localRoot)
        try {
            assertEquals(3, reader.size)
            assertNotNull(reader.loadFull(0)); assertNotNull(reader.loadFull(1)); assertNotNull(reader.loadThumb(2))
            assertEquals("ch10.cbz/001.png", reader.exportKey(1))
        } finally { reader.close() }
        assertArrayEquals(bytes, zip.readBytes())
    }
    @Test fun dottedDirectoryNamesAndRootImagesKeepTheirChapterRoles() = runBlocking {
        val folder = File(root, "manga_import/Book.v2").apply { mkdirs() }
        File(folder, "001.png").writeBytes(LibraryScanFixtures.png())
        File(folder, "ch1").mkdirs()
        File(folder, "ch1/001.png").writeBytes(LibraryScanFixtures.png())
        LibraryScanner.scan(app, "manga")
        val book = ImportedMangaStore.load(app).single()
        assertEquals("Book.v2", book.title)
        assertEquals(listOf(0, 1), book.chapters.map { it.number })
    }

    @Test fun sameSizeContainerChangesInvalidateTheIndexByModificationTime() = runBlocking {
        val zip = File(root, "manga_import/Book/ch1.cbz").apply { parentFile!!.mkdirs() }
        LibraryScanFixtures.zip(zip, "001.png" to LibraryScanFixtures.png())
        val old = com.moe.starflow.mangaimport.data.MangaSourceIndex.build(zip.parentFile!!)
        val before = zip.length()
        LibraryScanFixtures.zip(zip, "002.png" to LibraryScanFixtures.png())
        assertEquals(before, zip.length())
        zip.setLastModified(old.pages.first().modifiedAt + 10_000)
        val updated = com.moe.starflow.mangaimport.data.MangaSourceIndex.build(zip.parentFile!!, old)
        assertTrue(updated.pages.any { it.entry == "002.png" && !it.missing })
        assertTrue(updated.pages.any { it.entry == "001.png" && it.missing })
    }

    @Test fun standaloneArchiveKeepsChapterRulesAndRemapsExistingRecordsWhenEntriesChange() = runBlocking {
        val zip = File(root, "manga_import/Standalone.cbz")
        LibraryScanFixtures.zip(zip, "ch2/001.png" to LibraryScanFixtures.png())
        LibraryScanner.scan(app, "manga")
        val book = ImportedMangaStore.load(app).single()
        val session = LibrarySessions.current(app)
        session.importedPageTranslationDao().upsert(ImportedPageTranslation(book.id, 0, 2,
            translatedText = "ch2 translation", mangaKey = book.translationKey))
        LibraryScanFixtures.zip(zip, "ch1/001.png" to LibraryScanFixtures.png(), "ch2/001.png" to LibraryScanFixtures.png())
        LibraryScanner.scan(app, "manga")
        val updated = ImportedMangaStore.load(app).single()
        assertEquals(book.bookUuid, updated.bookUuid)
        assertEquals(listOf("ch1", "ch2"), updated.chapters.map { it.title })
        assertEquals(1, session.importedPageTranslationDao().forManga(book.id, book.translationKey).single().pageIndex)
        ReaderPageSource(true, updated.localRoot).let { source ->
            try { assertEquals("ch2/001.png", source.key(1)); assertNotNull(source.loadFull(1)) }
            finally { source.close() }
        }
        LibraryScanFixtures.zip(zip, "ch1/001.png" to LibraryScanFixtures.png())
        LibraryScanner.scan(app, "manga")
        assertFalse(ImportedMangaStore.load(app).single().lost)
        LibraryScanner.scan(app, "manga")
        assertFalse(ImportedMangaStore.load(app).single().lost)
        LibraryScanFixtures.zip(zip, "ch1/001.png" to LibraryScanFixtures.png(), "ch2/001.png" to LibraryScanFixtures.png())
        LibraryScanner.scan(app, "manga")
        assertFalse(ImportedMangaStore.load(app).single().lost)
    }

    @Test fun addedChapterMovesTranslationResolutionAndProgressTogetherAndReplaysOnce() = runBlocking {
        png("ch2/001.png")
        LibraryScanner.scan(app, "manga")
        var book = ImportedMangaStore.load(app).single()
        val session = LibrarySessions.current(app)
        session.importedPageTranslationDao().upsert(ImportedPageTranslation(book.id, 0, 2,
            translatedText = "Saved translation", mangaKey = book.translationKey))
        val oldIndex = MangaSourceIndex.load(session, book.sourceIndexPath)!!
        session.importedPageSrDao().upsert(ImportedPageSr(book.id, 0, 2, mangaKey = book.translationKey,
            sourcePageKey = oldIndex.pages.single().key))
        png("ch1/001.png")
        assertEquals(0, LibraryScanner.scan(app, "manga").added)
        book = ImportedMangaStore.load(app).single()
        assertEquals(2, book.pageCount)
        assertEquals(0, book.lastReadPage)
        assertEquals("Saved translation", session.importedPageTranslationDao().forManga(book.id, book.translationKey).single().translatedText)
        assertEquals(1, session.importedPageTranslationDao().forManga(book.id, book.translationKey).single().pageIndex)
        assertEquals(1, session.importedPageSrDao().forManga(book.id, book.translationKey).single().pageIndex)
        LibraryScanner.scan(app, "manga")
        assertEquals(1, session.importedPageTranslationDao().forManga(book.id, book.translationKey).single().pageIndex)
    }
    @Test fun missingChapterKeepsItsIdentityAndRecordsAndReconnectsAfterRestore() = runBlocking {
        val page = png("ch1/001.png")
        png("ch2/001.png")
        LibraryScanner.scan(app, "manga")
        val original = ImportedMangaStore.load(app).single()
        val session = LibrarySessions.current(app)
        val key = MangaSourceIndex.load(session, original.sourceIndexPath)!!.pages.first().key
        page.delete()
        LibraryScanner.scan(app, "manga")
        assertFalse(ImportedMangaStore.load(app).single().lost)
        val missing = MangaSourceIndex.load(session, ImportedMangaStore.load(app).single().sourceIndexPath)!!
        assertEquals(key, missing.pages.first().key)
        assertTrue(missing.pages.first().missing)
        png("ch1/001.png")
        LibraryScanner.scan(app, "manga")
        assertFalse(ImportedMangaStore.load(app).single().lost)
        assertEquals(original.bookUuid, ImportedMangaStore.load(app).single().bookUuid)
    }
    @Test fun readerLeaseDefersOnlyThisBookAndUserTitleSurvivesRescan() = runBlocking {
        png("ch2/001.png")
        LibraryScanner.scan(app, "manga")
        val book = ImportedMangaStore.load(app).single()
        ImportedMangaStore.patch(app, book) { it.copy(title = "User title", titleOrigin = "user") }
        val reader = ReaderPageSource(false, book.localRoot)
        png("ch1/001.png")
        LibraryScanner.scan(app, "manga")
        assertEquals(1, ImportedMangaStore.load(app).single().pageCount)
        reader.close()
        kotlinx.coroutines.withTimeout(5000) {
            while (ImportedMangaStore.load(app).single().pageCount != 2) kotlinx.coroutines.delay(20)
        }
        assertEquals(2, ImportedMangaStore.load(app).single().pageCount)
        assertEquals("User title", ImportedMangaStore.load(app).single().title)
    }
    @Test fun missingOriginalExportsExactBookRecordsAndReportsMissingArtifacts() = runBlocking {
        png("ch1/001.png")
        LibraryScanner.scan(app, "manga")
        val book = ImportedMangaStore.load(app).single()
        val session = LibrarySessions.current(app)
        session.importedPageTranslationDao().upsert(ImportedPageTranslation(book.id, 0, 2,
            sourceText = "Original OCR", translatedText = "Saved translation", mangaKey = book.translationKey))
        session.importedPageSrDao().upsert(com.moe.starflow.data.ImportedPageSr(book.id, 0, 2,
            mangaKey = book.translationKey, sourcePageKey = "missing-image"))
        File(book.localRoot).deleteRecursively()
        session.sourcePresence[session.relative(File(book.localRoot))] = false
        val output = ByteArrayOutputStream()
        val ref = BookRef(session.locationId, "manga", book.bookUuid)
        BookData.writePackage(app, ref, output)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        assertTrue(entries.getValue("book-data.json").contains("Saved translation"))
        assertTrue(entries.getValue("book-data.json").contains(book.bookUuid))
        assertTrue(entries.containsKey("manifest.json"))
        val manifest = org.json.JSONObject(entries.getValue("manifest.json"))
        assertFalse(manifest.getBoolean("complete"))
        val missing = manifest.getJSONArray("missingArtifacts")
        assertTrue((0 until missing.length()).any { missing.getString(it).contains("sr/page:0") })
        assertFalse(entries.keys.any { it.startsWith("manga_import/") })
        assertFalse(BookRecordLocks.busy(ref))
    }

    @Test fun missingNovelExportsUnsavedParagraphsAndImageOcrWithoutOpeningTheSource() = runBlocking {
        val session = LibrarySessions.current(app)
        com.moe.starflow.novel.data.NovelStore.add(app, com.moe.starflow.novel.data.ImportedNovel(
            id = 1, title = "Novel", localRoot = session.local("novel_import/missing.txt").path,
            format = com.moe.starflow.novel.model.NovelFormat.TXT, chapterCount = 1, addedAt = 1))
        val book = com.moe.starflow.novel.data.NovelStore.load(app).single()
        val ref = BookRef(book.libraryLocationId, "novel", book.bookUuid)
        // Hold portable publication while capturing a newly committed local database row.
        val ioLock = LibrarySession::class.java.getDeclaredField("ioLock").apply { isAccessible = true }.get(session)
        val data = synchronized(ioLock) { runBlocking {
            session.novelParagraphTranslationDao().upsert(com.moe.starflow.data.NovelParagraphTranslation(
                book.id, book.translationKey, 0, 0, "Original paragraph", "Saved paragraph", 2,
                imagePayload = "{image OCR and rectangles}"))
            BookData.snapshot(app, ref)
        } }
        assertEquals(1, data.getJSONObject("counts").getInt("paragraph"))
        assertTrue(data.getBoolean("databaseRecordsSaved"))
        val output = ByteArrayOutputStream()
        assertTrue(BookData.writePackage(app, ref, output).isEmpty())
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            assertEquals("book-data.json", zip.nextEntry.name)
            val json = org.json.JSONObject(zip.readBytes().toString(Charsets.UTF_8))
            assertEquals("{image OCR and rectangles}", json.getJSONObject("records").getJSONArray("paragraph").getJSONObject(0).getString("imagePayload"))
            assertEquals("MISSING", json.getString("availability"))
        }
        assertFalse(File(book.localRoot).exists())
    }

    @Test fun failedAndCancelledExportsAlwaysReleaseTheirLeases() = runBlocking {
        png("ch1/001.png")
        LibraryScanner.scan(app, "manga")
        val book = ImportedMangaStore.load(app).single()
        val ref = BookRef(book.libraryLocationId, "manga", book.bookUuid)
        try {
            BookData.writePackage(app, ref, object : java.io.OutputStream() {
                override fun write(value: Int) { throw java.io.IOException("Disk full") }
            })
            fail("Write failure must propagate")
        } catch (expected: java.io.IOException) { assertEquals("Disk full", expected.message) }
        assertFalse(BookRecordLocks.busy(ref))
        try {
            BookData.writePackage(app, ref, object : java.io.OutputStream() {
                override fun write(value: Int) { throw kotlinx.coroutines.CancellationException("Cancelled") }
            })
            fail("Cancellation must propagate")
        } catch (expected: kotlinx.coroutines.CancellationException) { }
        assertFalse(BookRecordLocks.busy(ref))
    }

    @Test fun copyingSourcesDoesNotTransferRecordsAndExplicitBookExportStaysScoped() = runBlocking {
        png("ch2/001.png")
        LibraryScanner.scan(app, "manga")
        val first = ImportedMangaStore.load(app).single()
        val session = LibrarySessions.current(app)
        session.importedPageTranslationDao().upsert(ImportedPageTranslation(first.id, 0, 2,
            translatedText = "Original library record", mangaKey = first.translationKey))
        png("ch1/001.png")
        LibraryScanner.scan(app, "manga")
        session.flush()
        val copy = File(app.cacheDir, "copied-${UUID.randomUUID()}")
        root.copyRecursively(copy)
        app.getSharedPreferences("library_locations", 0).edit().putString("defaultPath", copy.path).commit()
        LibrarySessions.select(app, null)
        assertTrue(ImportedMangaStore.load(app).isEmpty())
        val second = LibrarySessions.current(app)
        assertTrue(second.database.catalog().allTranslations().isEmpty())
        LibraryScanner.scan(app, "manga")
        val restored = ImportedMangaStore.load(app).single()
        assertNotEquals(first.libraryLocationId, restored.libraryLocationId)
        second.importedPageTranslationDao().upsert(ImportedPageTranslation(restored.id, 1, 2,
            translatedText = "Other copy private record", mangaKey = restored.translationKey))
        val snapshot = BookData.snapshot(app, BookRef(first.libraryLocationId, "manga", first.bookUuid)).toString()
        assertTrue(snapshot.contains("Original library record"))
        assertFalse(snapshot.contains("Other copy private record"))
    }
}
