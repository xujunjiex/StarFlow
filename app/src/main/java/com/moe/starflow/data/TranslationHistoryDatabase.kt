package com.moe.starflow.data
import com.moe.starflow.translate.widget.*

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [HistoryEntity::class, PageCacheEntity::class, TextTranslateRecord::class, ChatMessageEntity::class, ImportedPageTranslation::class],
    version = 15,
    exportSchema = false
)
abstract class TranslationHistoryDatabase : RoomDatabase() {

    abstract fun historyDao(): TranslationHistoryDao

    abstract fun textTranslateRecordDao(): TextTranslateRecordDao

    abstract fun chatMessageDao(): ChatMessageDao

    abstract fun importedPageTranslationDao(): ImportedPageTranslationDao

    companion object {
        @Volatile
        private var instance: TranslationHistoryDatabase? = null

        // 版本 2 → 3：添加 sessionId 字段
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE translation_history ADD COLUMN session_id TEXT NOT NULL DEFAULT ''")
            }
        }

        // 版本 3 → 4：page_cache 添加 cropWidth/cropHeight 字段
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE page_cache ADD COLUMN cropWidth INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE page_cache ADD COLUMN cropHeight INTEGER NOT NULL DEFAULT 0")
            }
        }

        // 版本 4 → 5：translation_history 添加 updated_at 字段
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE translation_history ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
            }
        }

        // 版本 5 → 6：无 schema 变更（版本号跳转，迁移为空）
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) { /* no schema change needed */ }
        }

        // 版本 6 → 7：无 schema 变更（版本号跳转，迁移为空）
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) { /* no schema change needed */ }
        }

        // 版本 7 → 8：无 schema 变更（版本号跳转，迁移为空）
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) { /* no schema change needed */ }
        }

        // 版本 8 → 9：translation_history 添加 original_image_path / is_retranslated
        // page_cache 添加 crop_left / crop_top / crop_right / crop_bottom
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE translation_history ADD COLUMN original_image_path TEXT")
                db.execSQL("ALTER TABLE translation_history ADD COLUMN is_retranslated INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE page_cache ADD COLUMN crop_left INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE page_cache ADD COLUMN crop_top INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE page_cache ADD COLUMN crop_right INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE page_cache ADD COLUMN crop_bottom INTEGER NOT NULL DEFAULT 0")
            }
        }

        // 版本 9 → 10：translation_history 和 page_cache 添加 pHash2/pHash3/pHash4（256-bit 扩展感知哈希）
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE translation_history ADD COLUMN pHash2 INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE translation_history ADD COLUMN pHash3 INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE translation_history ADD COLUMN pHash4 INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE page_cache ADD COLUMN pHash2 INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE page_cache ADD COLUMN pHash3 INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE page_cache ADD COLUMN pHash4 INTEGER NOT NULL DEFAULT 0")
            }
        }

        // 版本 10 → 11：修复漏加的 last_session_id 列，以及 createdAt → created_at 列名问题
        // 根因：1ec7831 添加 lastSessionId 时用了 fallbackToDestructiveMigration()（旧数据丢弃），
        // 导致后续重建 DB 时字段以当时的 Entity 为准（createdAt 而非 created_at），且从未经过迁移添加 last_session_id
        // 注意：必须幂等——先 PRAGMA 检查列是否存在，再决定操作。直接 ALTER TABLE 会在列已存在时崩溃
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val cursor = db.query("PRAGMA table_info(translation_history)")
                val columnNames = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    columnNames.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                cursor.close()

                // 在 createdAt 重命名之前先确保 last_session_id 存在
                if (!columnNames.contains("last_session_id")) {
                    db.execSQL("ALTER TABLE translation_history ADD COLUMN last_session_id TEXT NOT NULL DEFAULT ''")
                }

                if (columnNames.contains("createdAt")) {
                    // SQLite 不支持直接重命名列，需要重建表
                    db.execSQL("""
                        CREATE TABLE translation_history_new (
                            id INTEGER NOT NULL PRIMARY KEY,
                            type INTEGER NOT NULL,
                            sourceText TEXT,
                            translatedText TEXT,
                            imagePath TEXT,
                            thumbnailPath TEXT,
                            sourceLang TEXT NOT NULL,
                            targetLang TEXT NOT NULL,
                            translatorName TEXT NOT NULL,
                            pHash INTEGER NOT NULL,
                            pHash2 INTEGER NOT NULL DEFAULT 0,
                            pHash3 INTEGER NOT NULL DEFAULT 0,
                            pHash4 INTEGER NOT NULL DEFAULT 0,
                            created_at INTEGER NOT NULL DEFAULT 0,
                            session_id TEXT NOT NULL DEFAULT '',
                            last_session_id TEXT NOT NULL DEFAULT '',
                            updated_at INTEGER NOT NULL DEFAULT 0,
                            original_image_path TEXT,
                            is_retranslated INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())
                    db.execSQL("""
                        INSERT INTO translation_history_new
                        (id, type, sourceText, translatedText, imagePath, thumbnailPath,
                         sourceLang, targetLang, translatorName, pHash, pHash2, pHash3, pHash4,
                         created_at, session_id, last_session_id, updated_at, original_image_path, is_retranslated)
                        SELECT
                        id, type, sourceText, translatedText, imagePath, thumbnailPath,
                        sourceLang, targetLang, translatorName, pHash, pHash2, pHash3, pHash4,
                        createdAt, session_id, last_session_id, updated_at, original_image_path, is_retranslated
                        FROM translation_history
                    """.trimIndent())
                    db.execSQL("DROP TABLE translation_history")
                    db.execSQL("ALTER TABLE translation_history_new RENAME TO translation_history")
                    // 重建索引
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_translation_history_type_created_at ON translation_history(type, created_at)")
                }
            }
        }

        // 版本 11 → 12：添加 bubble_rects 列，存储气泡位置数据
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE translation_history ADD COLUMN bubble_rects TEXT")
            }
        }

        // 版本 12 → 13：新增 text_translate_record 表（文本翻译页最近记录）。
        // ⚠️ 纯新增、幂等，绝不 ALTER 现有表。fallbackToDestructiveMigration 已启用，
        //    不提供此迁移会导致升级用户整库删除（数据丢失）——此迁移是数据安全的第一道保障。
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS text_translate_record (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "original_text TEXT NOT NULL, translated_text TEXT NOT NULL, " +
                    "source_lang TEXT NOT NULL, target_lang TEXT NOT NULL, " +
                    "engine_name TEXT NOT NULL, created_at INTEGER NOT NULL)"
                )
            }
        }

        // 版本 13 → 14：新增 chat_message 表（AI 对话历史）。
        // ⚠️ 纯新增、幂等，绝不 ALTER 现有表。fallbackToDestructiveMigration 已启用，
        //    不提供此迁移会导致升级用户整库删除（数据丢失）。
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS chat_message (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "role INTEGER NOT NULL, content TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL)"
                )
            }
        }

        // 版本 14 → 15：新增 imported_page_translation（阅读器每页翻译记录）。
        // ⚠️ 纯新增、幂等，绝不 ALTER 现有表。
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS imported_page_translation (" +
                    "mangaId INTEGER NOT NULL, " +
                    "pageIndex INTEGER NOT NULL, " +
                    "state INTEGER NOT NULL, " +
                    "sourceText TEXT, " +
                    "translatedText TEXT, " +
                    "bubbleRects TEXT, " +
                    "failCode TEXT, " +
                    "failMessage TEXT, " +
                    "updatedAtMs INTEGER NOT NULL, " +
                    "PRIMARY KEY(mangaId, pageIndex))"
                )
            }
        }

        fun getInstance(context: Context): TranslationHistoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TranslationHistoryDatabase::class.java,
                    "translation_history.db"
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
            }
        }
    }
}
