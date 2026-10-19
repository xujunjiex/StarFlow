package com.moe.starflow.library

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import com.moe.starflow.mangaimport.reader.ReaderPageSource
import com.moe.starflow.data.ImportedPageTranslation
import com.moe.starflow.data.TranslationCacheUtils
import com.moe.starflow.data.TranslationHistoryDatabase
import com.moe.starflow.novel.data.NovelStore
import com.moe.starflow.utils.LibraryScanFixtures
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryMigrationTest {
    @Test fun migrationActivatesCompatibleRecordsAndPreservesEditsInArchiveOnlyInstallations() = runBlocking {
        val app: Context = RuntimeEnvironment.getApplication()
        val source = File(app.getExternalFilesDir(null), "manga_import/legacy-pages").apply { mkdirs() }
        File(source, "001.png").writeBytes(LibraryScanFixtures.png())
        File(source, "002.png").writeBytes(LibraryScanFixtures.png())
        val old = JSONObject().put("id", 1).put("title", "Legacy").put("localRoot", source.path)
            .put("isArchive", false).put("pageCount", 2).put("addedAt", 100).put("lastReadPage", 1)
            .put("coverPath", JSONObject.NULL).put("translatedPath", JSONObject.NULL)
        val raw = JSONArray().put(old).put(JSONObject(old.toString()).put("id", 2)).toString()
        app.getSharedPreferences("manga_import", 0).edit().putString("imported_manga_list", raw).commit()
        val novelSource = File(app.getExternalFilesDir(null), "novel_import/legacy.txt").apply {
            parentFile!!.mkdirs(); writeText("Alpha\nBeta")
        }
        val novel = JSONObject().put("id", 3).put("title", "Old novel").put("localRoot", novelSource.path)
            .put("format", "TXT").put("chapterCount", 1).put("addedAt", 200)
            .put("lastReadChapter", 0).put("lastReadParaIndex", 1).put("lastReadPage", 2).put("lastReadAt", 99)
        app.getSharedPreferences("novel_shelf", 0).edit()
            .putString("imported_novel_list", JSONArray().put(novel).toString()).commit()
        val history = app.getDatabasePath("translation_history.db").apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(history, null).use { db ->
            db.execSQL("CREATE TABLE imported_page_translation (mangaId INTEGER, mangaKey TEXT, pageIndex INTEGER, state INTEGER, translatedText TEXT)")
            db.execSQL("INSERT INTO imported_page_translation VALUES (1, '100', 0, 2, 'old first page')")
            db.execSQL("INSERT INTO imported_page_translation VALUES (1, '100', 1, 2, 'old second page')")
            db.execSQL("CREATE TABLE imported_page_sr (mangaId INTEGER, mangaKey TEXT, pageIndex INTEGER, state INTEGER, modelName TEXT)")
            db.execSQL("INSERT INTO imported_page_sr VALUES (1, '100', 0, 2, 'old model')")
            db.execSQL("CREATE TABLE novel_paragraph_translation (novelId INTEGER, novelKey TEXT, chapterIndex INTEGER, paraIndex INTEGER, sourceText TEXT, translatedText TEXT, state INTEGER, splitVersion INTEGER)")
            db.execSQL("INSERT INTO novel_paragraph_translation VALUES (3, '200', 0, 1, 'Beta', '译文', 2, 3)")
            db.execSQL("INSERT INTO novel_paragraph_translation VALUES (3, '200', 0, 2, 'Older split', '旧切分', 2, 2)")
        }
        LibrarySessions.prepare(app)
        val session = LibrarySessions.current(app)
        val books = ImportedMangaStore.load(app)
        assertEquals(2, books.size)
        assertEquals(2, books.map { it.bookUuid }.toSet().size)
        assertEquals(2, books.map { it.localRoot }.toSet().size)
        assertTrue(books.all { it.lastReadPage == 1 })
        assertEquals(2, session.database.catalog().allTranslations().size)
        assertEquals("old first page", session.database.catalog().allTranslations().first { it.pageIndex == 0 }.translatedText)
        assertEquals("old model", session.database.catalog().allResolutions().single().modelName)
        assertEquals("译文", session.database.catalog().allParagraphs().single().translatedText)
        assertEquals(1, NovelStore.load(app).single().lastReadParaIndex)
        assertEquals(99, NovelStore.load(app).single().lastReadAt)
        assertTrue(session.recordArchive(".starflow/legacy/${NovelStore.load(app).single().bookUuid}/records.json")
            .readText().contains("旧切分"))
        assertTrue(File(source, "001.png").isFile)
        assertEquals(raw, app.getSharedPreferences("manga_import", 0).getString("imported_manga_list", null))
        val archive = session.recordArchive(".starflow/legacy/${books.first().bookUuid}/records.json").readText()
        assertTrue(archive.contains("old first page"))
        assertFalse(JSONObject(archive).getBoolean("valid"))
        session.database.catalog().clearTranslations()
        val key = "${session.locationId}:manga:${books.first().bookUuid}"
        session.database.catalog().insertLegacyTranslations(listOf(
            com.moe.starflow.data.ImportedPageTranslation(books.first().id, 0, 2,
                translatedText = "new first page", mangaKey = key)))
        val previous = session.database.catalog().book("manga", books.first().id)!!
        session.database.catalog().put(previous.copy(body = JSONObject(previous.body)
            .put("lastReadPage", 0).put("pageOrderVersion", 1).toString()))
        session.database.catalog().meta(LibraryMeta("legacyActive:${books.first().bookUuid}", ""))
        session.database.catalog().meta(LibraryMeta("legacyActivation:${books.first().bookUuid}", ""))
        LibrarySessions.prepare(app)
        val restored = session.database.catalog().allTranslations().sortedBy { it.pageIndex }
        assertEquals(listOf("new first page"), restored.map { it.translatedText })
        assertEquals(0, ImportedMangaStore.load(app).first().lastReadPage)
        assertEquals(books.map { it.bookUuid }, ImportedMangaStore.load(app).map { it.bookUuid })
        assertEquals(0, LibraryScanner.scan(app, "manga").added)
        SQLiteDatabase.openDatabase(history.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(2, db.rawQuery("SELECT * FROM imported_page_translation", null).use { it.count })
        }
    }

    // Schema from v0.11.2 (56cbcd0), DB v17. No novel/SR/OCR-model columns.
    private fun releaseDatabase(app: Context, block: (SQLiteDatabase) -> Unit) {
        val file = app.getDatabasePath("translation_history.db").apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val schema = checkNotNull(javaClass.getResourceAsStream("/library/release-v0.11.2.sql"))
                .bufferedReader().use { it.readText() }
            schema.split(';').map(String::trim).filter(String::isNotEmpty).forEach(db::execSQL)
            db.version = 17
            block(db)
        }
    }

    private val releaseRects = """[{"l":1,"t":2,"r":7,"b":10,"fs":18.0,"dir":"VERTICAL_RL"}]"""

    private fun releaseRow(db: SQLiteDatabase, id: Long, stamp: Long, page: Int, key: String = stamp.toString()) {
        db.insertOrThrow("imported_page_translation", null, ContentValues().apply {
            put("mangaId", id); put("pageIndex", page); put("state", 2)
            put("sourceText", "[1] source-$page"); put("translatedText", "[1] translation-$page")
            put("bubbleRects", releaseRects); put("updatedAtMs", 123456L); put("mangaKey", key)
            put("translatorName", "Release translator"); put("sourceLang", "ja"); put("targetLang", "zh")
        })
    }

    private fun releaseBook(id: Long, root: File, count: Int = 3, page: Int = 2, archive: Boolean = false) = JSONObject()
        .put("id", id).put("title", "Renamed $id").put("localRoot", root.path).put("isArchive", archive)
        .put("coverPath", JSONObject.NULL).put("pageCount", count).put("addedAt", id * 100)
        .put("sizeBytes", 1000).put("description", "Release description")
        .put("translatedPath", JSONObject.NULL).put("lastReadPage", page)

    private fun saveReleaseShelf(app: Context, vararg books: JSONObject) = app.getSharedPreferences("manga_import", 0)
        .edit().putString("imported_manga_list", JSONArray(books.toList()).toString()).commit()

    private fun releaseFolder(app: Context, name: String): File = File(app.getExternalFilesDir(null), "manga_import/$name").apply {
        for (path in listOf("chapter1/001.png", "chapter1/002.png", "z.png"))
            File(this, path).apply { parentFile!!.mkdirs(); writeBytes(LibraryScanFixtures.png()) }
    }

    @Test fun releaseV17DirectoryAndZipPreservePayloadIdentityAndRealPagePermutation() = runBlocking {
        val app: Context = RuntimeEnvironment.getApplication()
        val folder = releaseFolder(app, "1")
        // Release ignored this container. The new reader includes it before chapter1's images.
        LibraryScanFixtures.zip(File(folder, "chapter0/extra.zip"), "new.png" to LibraryScanFixtures.png())
        val zip = File(app.getExternalFilesDir(null), "manga_import/2/original.cbz")
        LibraryScanFixtures.zip(zip, *listOf("z.png", "chapter1/002.png", "chapter1/001.png")
            .map { it to LibraryScanFixtures.png() }.toTypedArray())
        val cover = File(app.getExternalFilesDir(null), "covers/1.jpg").apply {
            parentFile!!.mkdirs(); writeBytes(LibraryScanFixtures.png())
        }
        saveReleaseShelf(app, releaseBook(1, folder, page = 0).put("coverPath", cover.path),
            releaseBook(2, zip, archive = true))
        releaseDatabase(app) { db ->
            for (id in 1L..2L) for (page in 0..2)
                releaseRow(db, id, id * 100, page, if (id == 1L) "Before rename|100" else "200")
            releaseRow(db, 1, 999, 99, "Deleted book|999") // Reused numeric id cannot acquire another book's row.
        }
        val prefs = app.getSharedPreferences("manga_import", 0).getString("imported_manga_list", null)
        LibrarySessions.prepare(app)
        val session = LibrarySessions.current(app)
        val beforeScan = session.database.catalog().allTranslations()
        assertEquals(6, beforeScan.size)
        val legacyPaths = listOf("chapter1/001.png", "chapter1/002.png", "z.png")
        for (book in ImportedMangaStore.load(app)) {
            val rows = session.database.catalog().bookTranslations(book.id, book.translationKey)
            assertEquals(3, rows.size)
            val source = ReaderPageSource(book.isArchive, book.localRoot)
            try {
                for (row in rows) {
                    val oldPage = row.translatedText!!.substringAfterLast('-').toInt()
                    assertEquals(legacyPaths[oldPage], source.exportKey(row.pageIndex))
                    assertEquals("[1] source-$oldPage", row.sourceText)
                    assertEquals(releaseRects, row.bubbleRects)
                    assertEquals("Release translator", row.translatorName)
                    assertEquals("ja", row.sourceLang); assertEquals("zh", row.targetLang)
                    assertEquals(123456L, row.updatedAtMs); assertNull(row.ocrModel)
                    val bubbles = TranslationCacheUtils.rebuildBubblesFromCache(
                        TranslationCacheUtils.parseIndexedTextList(row.sourceText),
                        TranslationCacheUtils.parseIndexedTextList(row.translatedText), row.bubbleRects, 16f, 0)
                    assertEquals("translation-$oldPage", bubbles.single().translatedText)
                    assertEquals(1, bubbles.single().rect.left)
                }
                assertEquals(if (book.id == 1L) "chapter1/001.png" else "z.png", source.exportKey(book.lastReadPage))
            } finally { source.close() }
            assertEquals("Release description", book.description)
            assertEquals("Renamed ${book.id}", book.title)
            assertEquals(1, book.pageOrderVersion)
            if (book.id == 1L) assertTrue(File(book.coverPath!!).readBytes().contentEquals(cover.readBytes()))
        }
        assertTrue(session.recordArchive(".starflow/legacy/unassigned/records.json").readText().contains("Deleted book|999"))
        assertEquals(0, LibraryScanner.scan(app, "manga").added)
        LibrarySessions.prepare(app)
        assertEquals(beforeScan, session.database.catalog().allTranslations())
        assertEquals(prefs, app.getSharedPreferences("manga_import", 0).getString("imported_manga_list", null))
        SQLiteDatabase.openDatabase(app.getDatabasePath("translation_history.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(17, db.version)
            assertEquals(7, db.rawQuery("SELECT * FROM imported_page_translation", null).use { it.count })
        }
    }

    @Test fun deferredActivationNeverRestoresClearedPagesOrChapters() = runBlocking {
        val app: Context = RuntimeEnvironment.getApplication()
        val folders = (1..2).map { releaseFolder(app, it.toString()) }
        folders.forEach { File(it, "z.png").delete() }
        saveReleaseShelf(app, *folders.mapIndexed { i, root -> releaseBook((i + 1).toLong(), root) }.toTypedArray())
        releaseDatabase(app) { db -> for (id in 1L..2L) for (page in 0..2) releaseRow(db, id, id * 100, page) }
        LibrarySessions.prepare(app)
        val session = LibrarySessions.current(app)
        assertTrue(session.database.catalog().allTranslations().isEmpty())
        for (book in ImportedMangaStore.load(app)) {
            val dao = session.importedPageTranslationDao()
            dao.upsert(ImportedPageTranslation(book.id, 0, 2, translatedText = "New", mangaKey = book.translationKey))
            if (book.id == 1L) dao.deletePage(book.id, book.translationKey, 0)
            else dao.deletePageRange(book.id, book.translationKey, 0, 1)
            File(book.localRoot, "z.png").writeBytes(LibraryScanFixtures.png())
        }
        LibrarySessions.prepare(app)
        assertTrue(session.database.catalog().allTranslations().isEmpty())
        for (book in ImportedMangaStore.load(app))
            assertTrue(session.recordArchive(".starflow/legacy/${book.bookUuid}/records.json").isFile)
    }

    @Test fun releaseV17GlobalDatabaseUpgradesWithoutLosingHistoryThenMigratesReaderRecords() = runBlocking {
        val app: Context = RuntimeEnvironment.getApplication()
        saveReleaseShelf(app, releaseBook(1, releaseFolder(app, "1")))
        releaseDatabase(app) { db ->
            releaseRow(db, 1, 100, 0)
            db.execSQL("INSERT INTO translation_history (id,type,sourceText,translatedText,sourceLang,targetLang,translatorName,pHash,pHash2,pHash3,pHash4) VALUES (1,0,'Old source','Old history','ja','zh','Release translator',1,2,3,4)")
            db.execSQL("INSERT INTO page_cache (historyId,pHash,pHash2,pHash3,pHash4,mode,lastAccessedAt,createdAt,cropWidth,cropHeight) VALUES (1,1,2,3,4,0,100,99,20,30)")
            db.execSQL("INSERT INTO text_translate_record (original_text,translated_text,source_lang,target_lang,engine_name,created_at) VALUES ('Old text','Old translation','ja','zh','Release translator',99)")
            db.execSQL("INSERT INTO chat_message (role,content,created_at) VALUES (0,'Old chat',99)")
        }
        // No destructive fallback: Room must validate the real migration chain's resulting schema.
        val upgraded = Room.databaseBuilder(app, TranslationHistoryDatabase::class.java, "translation_history.db")
            .addMigrations(TranslationHistoryDatabase.MIGRATION_17_18, TranslationHistoryDatabase.MIGRATION_18_19,
                TranslationHistoryDatabase.MIGRATION_19_20, TranslationHistoryDatabase.MIGRATION_20_21,
                TranslationHistoryDatabase.MIGRATION_21_22, TranslationHistoryDatabase.MIGRATION_22_23,
                TranslationHistoryDatabase.MIGRATION_23_24)
            .allowMainThreadQueries().build()
        try {
            val db = upgraded.openHelper.writableDatabase
            assertEquals(24, db.version)
            assertEquals("Old history", db.query("SELECT translatedText FROM translation_history WHERE id=1").use {
                assertTrue(it.moveToFirst()); it.getString(0)
            })
            for (table in listOf("page_cache", "text_translate_record", "chat_message"))
                assertEquals(1, db.query("SELECT * FROM $table").use { it.count })
            assertEquals("[1] translation-0", upgraded.importedPageTranslationDao().forManga(1, "100").single().translatedText)
        } finally { upgraded.close() }
        LibrarySessions.prepare(app)
        val book = ImportedMangaStore.load(app).single()
        val row = LibrarySessions.current(app).importedPageTranslationDao().forManga(book.id, book.translationKey).single()
        assertEquals("[1] translation-0", row.translatedText)
        assertEquals(1, row.pageIndex)
    }

    @Test fun deferredActivationKeepsUserProgressAtFirstPage() = runBlocking {
        val app: Context = RuntimeEnvironment.getApplication()
        val folder = releaseFolder(app, "1")
        File(folder, "z.png").delete()
        saveReleaseShelf(app, releaseBook(1, folder))
        releaseDatabase(app) { db -> for (page in 0..2) releaseRow(db, 1, 100, page) }
        LibrarySessions.prepare(app)
        val session = LibrarySessions.current(app)
        val book = ImportedMangaStore.load(app).single()
        ImportedMangaStore.patch(app, book) { it.copy(lastReadPage = 0, pageOrderVersion = 1) }
        File(folder, "z.png").writeBytes(LibraryScanFixtures.png())
        LibrarySessions.prepare(app)
        assertEquals(0, ImportedMangaStore.load(app).single().lastReadPage)
        assertEquals(3, session.database.catalog().allTranslations().size)
    }

    @Test fun failedActivationRollsBackRowsAndCompletionThenRetriesWithoutOverwritingNewRows() = runBlocking {
        val app: Context = RuntimeEnvironment.getApplication()
        val folder = releaseFolder(app, "1")
        saveReleaseShelf(app, releaseBook(1, folder))
        releaseDatabase(app) { db -> for (page in 0..2) releaseRow(db, 1, 100, page) }
        val session = LibrarySessions.current(app)
        session.prepare()
        session.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_legacy BEFORE INSERT ON imported_page_translation WHEN NEW.pageIndex=2 BEGIN SELECT RAISE(ABORT, 'test interruption'); END")
        LibraryMigration.migrate(app, session)
        assertTrue(session.database.catalog().allTranslations().isEmpty())
        val book = ImportedMangaStore.load(app).single()
        assertNull(session.database.catalog().meta("legacyActive:${book.bookUuid}"))
        assertEquals("pending", session.database.catalog().meta("legacyActivation:${book.bookUuid}"))
        session.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_legacy")
        session.importedPageTranslationDao().upsert(ImportedPageTranslation(book.id, 1, 2,
            translatedText = "New translation", mangaKey = book.translationKey))
        LibrarySessions.prepare(app)
        val rows = session.database.catalog().bookTranslations(book.id, book.translationKey)
        assertEquals(3, rows.size)
        assertEquals("New translation", rows.single { it.pageIndex == 1 }.translatedText)
        assertEquals("1", session.database.catalog().meta("legacyActive:${book.bookUuid}"))
        LibrarySessions.prepare(app)
        assertEquals(rows, session.database.catalog().bookTranslations(book.id, book.translationKey))
    }
}
