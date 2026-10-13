package com.moe.starflow.library

import androidx.room.*
import com.moe.starflow.data.*

@Entity(tableName = "library_book", primaryKeys = ["kind", "id"], indices = [Index(value = ["uuid"], unique = true)])
data class LibraryBook(val kind: String, val id: Long, val uuid: String, val body: String,
    val source: String, val deleting: Boolean = false, val error: String? = null)

@Entity(tableName = "library_outbox")
data class LibraryWrite(@PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val token: String, val body: String)

@Entity(tableName = "library_meta")
data class LibraryMeta(@PrimaryKey val name: String, val value: String)

@Dao
interface LibraryCatalogDao {
    @Query("SELECT * FROM library_book WHERE kind = :kind ORDER BY id") fun books(kind: String): List<LibraryBook>
    @Query("SELECT * FROM library_book WHERE kind = :kind AND id = :id") fun book(kind: String, id: Long): LibraryBook?
    @Query("SELECT * FROM library_book WHERE uuid = :uuid LIMIT 1") fun byUuid(uuid: String): LibraryBook?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(book: LibraryBook)
    @Query("DELETE FROM library_book WHERE kind = :kind AND id = :id") fun remove(kind: String, id: Long)
    @Query("SELECT * FROM library_outbox ORDER BY sequence") fun pending(): List<LibraryWrite>
    @Insert fun enqueue(write: LibraryWrite): Long
    @Query("DELETE FROM library_outbox WHERE sequence = :sequence") fun acknowledge(sequence: Long)
    @Query("SELECT value FROM library_meta WHERE name = :name") fun meta(name: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun meta(value: LibraryMeta)
    @Query("SELECT * FROM library_meta") fun allMeta(): List<LibraryMeta>
    @Query("DELETE FROM library_book") fun clearBooks()
    @Query("DELETE FROM imported_page_translation") fun clearTranslations()
    @Query("DELETE FROM imported_page_sr") fun clearResolutions()
    @Query("DELETE FROM novel_paragraph_translation") fun clearParagraphs()
    @Query("DELETE FROM library_meta WHERE name LIKE 'asset:%'") fun clearAssets()
    @Query("SELECT * FROM imported_page_translation") fun allTranslations(): List<ImportedPageTranslation>
    @Query("SELECT * FROM imported_page_sr") fun allResolutions(): List<ImportedPageSr>
    @Query("SELECT * FROM novel_paragraph_translation") fun allParagraphs(): List<NovelParagraphTranslation>
}

@Database(entities = [LibraryBook::class, LibraryWrite::class, LibraryMeta::class,
    ImportedPageTranslation::class, ImportedPageSr::class, NovelParagraphTranslation::class],
    version = 1, exportSchema = false)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun catalog(): LibraryCatalogDao
    abstract fun translations(): ImportedPageTranslationDao
    abstract fun resolutions(): ImportedPageSrDao
    abstract fun paragraphs(): NovelParagraphTranslationDao
}
