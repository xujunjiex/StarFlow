package com.moe.starflow.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.moe.starflow.mangaimport.data.ImportedMangaStore
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
    @Test fun migrationIsRetryableAndNeverActivatesArchivedRowsOrDeletesOriginals() = runBlocking {
        val app: Context = RuntimeEnvironment.getApplication()
        val source = File(app.getExternalFilesDir(null), "manga_import/legacy-pages").apply { mkdirs() }
        File(source, "001.png").writeBytes(LibraryScanFixtures.png())
        val old = JSONObject().put("id", 1).put("title", "Legacy").put("localRoot", source.path)
            .put("isArchive", false).put("pageCount", 1).put("addedAt", 100).put("lastReadPage", 9)
            .put("coverPath", JSONObject.NULL).put("translatedPath", JSONObject.NULL)
        val raw = JSONArray().put(old).put(JSONObject(old.toString()).put("id", 2)).toString()
        app.getSharedPreferences("manga_import", 0).edit().putString("imported_manga_list", raw).commit()
        val history = app.getDatabasePath("translation_history.db").apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(history, null).use { db ->
            db.execSQL("CREATE TABLE imported_page_translation (mangaId INTEGER, mangaKey TEXT, translatedText TEXT)")
            db.execSQL("INSERT INTO imported_page_translation VALUES (1, '100', 'inactive legacy result')")
        }
        LibrarySessions.prepare(app)
        val session = LibrarySessions.current(app)
        val books = ImportedMangaStore.load(app)
        assertEquals(2, books.size)
        assertEquals(2, books.map { it.bookUuid }.toSet().size)
        assertEquals(2, books.map { it.localRoot }.toSet().size)
        assertTrue(books.all { it.lastReadPage == 0 })
        assertTrue(session.database.catalog().allTranslations().isEmpty())
        assertTrue(File(source, "001.png").isFile)
        assertEquals(raw, app.getSharedPreferences("manga_import", 0).getString("imported_manga_list", null))
        val archive = session.recordArchive(".starflow/legacy/${books.first().bookUuid}/records.json").readText()
        assertTrue(archive.contains("inactive legacy result"))
        assertFalse(JSONObject(archive).getBoolean("valid"))
        LibrarySessions.prepare(app)
        assertEquals(books.map { it.bookUuid }, ImportedMangaStore.load(app).map { it.bookUuid })
        assertEquals(0, LibraryScanner.scan(app, "manga").added)
        SQLiteDatabase.openDatabase(history.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(1, db.rawQuery("SELECT * FROM imported_page_translation", null).use { it.count })
        }
    }
}
