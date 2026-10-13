package com.moe.starflow.library

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryOpenHelperFactoryTest {
    @Test fun corruptionCallbackPreservesTheDatabaseAndPendingRows() {
        val app = RuntimeEnvironment.getApplication()
        val file = app.getDatabasePath("outbox-corruption-test.db")
        val callback = LibraryOpenHelperFactory.PreservingCallback(object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) { db.execSQL("CREATE TABLE pending (value TEXT)") }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        })
        val helper = LibraryOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(app)
            .name(file.path).callback(callback).build())
        helper.use {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO pending VALUES ('unsaved')")
            assertThrows(IllegalStateException::class.java) { callback.onCorruption(db) }
            assertTrue(file.isFile)
            assertEquals("unsaved", db.query("SELECT value FROM pending").use { cursor -> cursor.moveToFirst(); cursor.getString(0) })
        }
    }
}
