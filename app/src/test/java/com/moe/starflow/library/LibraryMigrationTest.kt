package com.moe.starflow.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.moe.starflow.mangaimport.data.ImportedMangaStore
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
    @Test fun migrationActivatesOldPagesAndCanBackfillAnAlreadyArchivedBook() = runBlocking {
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
        LibrarySessions.prepare(app)
        val restored = session.database.catalog().allTranslations().sortedBy { it.pageIndex }
        assertEquals(listOf("new first page", "old second page"), restored.map { it.translatedText })
        assertEquals(1, ImportedMangaStore.load(app).first().lastReadPage)
        assertEquals(books.map { it.bookUuid }, ImportedMangaStore.load(app).map { it.bookUuid })
        assertEquals(0, LibraryScanner.scan(app, "manga").added)
        SQLiteDatabase.openDatabase(history.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(2, db.rawQuery("SELECT * FROM imported_page_translation", null).use { it.count })
        }
    }
}
