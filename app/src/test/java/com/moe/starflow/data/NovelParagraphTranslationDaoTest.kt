package com.moe.starflow.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelParagraphTranslationDaoTest {

    private lateinit var db: TranslationHistoryDatabase
    private lateinit var dao: NovelParagraphTranslationDao

    /** 迁移测试开的裸库 helper，tearDown 统一关掉（含它建出来的临时文件）。 */
    private val openedHelpers = mutableListOf<SupportSQLiteOpenHelper>()

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun row(
        chapter: Int,
        para: Int,
        key: String = KEY,
        state: Int = NovelParagraphTranslation.STATE_SUCCESS,
        text: String = "译$chapter-$para",
        splitVersion: Int = SPLIT_VERSION,
    ) = NovelParagraphTranslation(
        novelId = NOVEL_ID,
        novelKey = key,
        chapterIndex = chapter,
        paraIndex = para,
        sourceText = "原文$chapter-$para",
        translatedText = text,
        state = state,
        splitVersion = splitVersion,
        updatedAt = 1L,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, TranslationHistoryDatabase::class.java).build()
        dao = db.novelParagraphTranslationDao()
    }

    @After
    fun tearDown() {
        openedHelpers.forEach { runCatching { it.close() } }
        openedHelpers.clear()
        db.close()
    }

    // ===== 基本读写 =====

    @Test
    fun `upsert 后按段号升序读回`() = runBlocking {
        dao.upsert(row(1, 0))
        dao.upsert(row(0, 1))
        dao.upsert(row(0, 0))
        assertEquals(listOf(0, 1), dao.forChapter(NOVEL_ID, KEY, 0, SPLIT_VERSION).map { it.paraIndex })
    }

    @Test
    fun `同主键 upsert 覆盖而不是新增`() = runBlocking {
        dao.upsert(row(0, 0, text = "旧"))
        dao.upsert(row(0, 0, text = "新"))
        val rows = dao.forChapter(NOVEL_ID, KEY, 0, SPLIT_VERSION)
        assertEquals(1, rows.size)
        assertEquals("新", rows[0].translatedText)
    }

    @Test
    fun `upsertAll 批量写入`() = runBlocking {
        dao.upsertAll((0 until 5).map { row(0, it) })
        assertEquals(5, dao.forChapter(NOVEL_ID, KEY, 0, SPLIT_VERSION).size)
    }

    @Test
    fun `按主键取单条`() = runBlocking {
        dao.upsert(row(2, 3))
        assertEquals("译2-3", dao.get(NOVEL_ID, KEY, 2, 3)?.translatedText)
        assertNull(dao.get(NOVEL_ID, KEY, 9, 9))
    }

    // ===== 两条隔离线：身份指纹 与 splitVersion =====

    /** 删书后重导会复用同一个 bookId，靠指纹区分「同一 id 的不同书」。 */
    @Test
    fun `指纹不匹配的记录读不到`() = runBlocking {
        dao.upsert(row(0, 0, key = "1000"))
        assertEquals(0, dao.forChapter(NOVEL_ID, "2000", 0, SPLIT_VERSION).size)
        assertEquals(1, dao.forChapter(NOVEL_ID, "1000", 0, SPLIT_VERSION).size)
    }

    /** 段落切分规则一变，旧 paraIndex 整体错位且看不出错 —— 靠版本号让旧行自动失效。 */
    @Test
    fun `splitVersion 不匹配的记录被过滤`() = runBlocking {
        dao.upsert(row(0, 0, splitVersion = 0))
        assertEquals(0, dao.forChapter(NOVEL_ID, KEY, 0, splitVersion = 1).size)
        assertEquals(1, dao.forChapter(NOVEL_ID, KEY, 0, splitVersion = 0).size)
    }

    // ===== 状态机 =====

    @Test
    fun `resetTranslating 只清翻译中的行`() = runBlocking {
        dao.upsert(row(0, 0, state = NovelParagraphTranslation.STATE_TRANSLATING))
        dao.upsert(row(0, 1, state = NovelParagraphTranslation.STATE_SUCCESS))
        dao.resetTranslating(NOVEL_ID, KEY)
        val rows = dao.forChapter(NOVEL_ID, KEY, 0, SPLIT_VERSION)
        assertEquals(NovelParagraphTranslation.STATE_IDLE, rows[0].state)
        assertEquals(NovelParagraphTranslation.STATE_SUCCESS, rows[1].state)
    }

    @Test
    fun `resetTranslating 不碰别的书`() = runBlocking {
        dao.upsert(row(0, 0, key = "1000", state = NovelParagraphTranslation.STATE_TRANSLATING))
        dao.upsert(row(0, 1, key = "2000", state = NovelParagraphTranslation.STATE_TRANSLATING))
        dao.resetTranslating(NOVEL_ID, "1000")
        assertEquals(
            NovelParagraphTranslation.STATE_TRANSLATING,
            dao.forChapter(NOVEL_ID, "2000", 0, SPLIT_VERSION)[0].state,
        )
    }

    // ===== 聚合 =====

    @Test
    fun `chapterStats 聚合出各章总数与成功数`() = runBlocking {
        dao.upsert(row(0, 0))
        dao.upsert(row(0, 1))
        dao.upsert(row(0, 2, state = NovelParagraphTranslation.STATE_IDLE))
        dao.upsert(row(1, 0, state = NovelParagraphTranslation.STATE_FAILED))
        val stats = dao.chapterStats(NOVEL_ID, KEY, SPLIT_VERSION).associateBy { it.chapterIndex }
        assertEquals(3, stats[0]?.total)
        assertEquals(2, stats[0]?.success)
        assertEquals(1, stats[1]?.total)
        assertEquals(0, stats[1]?.success)
    }

    @Test
    fun `chapterStats 只统计本指纹与本版本`() = runBlocking {
        dao.upsert(row(0, 0, key = "1000"))
        dao.upsert(row(0, 1, key = "2000"))
        dao.upsert(row(0, 2, splitVersion = 99))
        assertEquals(1, dao.chapterStats(NOVEL_ID, "1000", SPLIT_VERSION).single().total)
    }

    @Test
    fun `countFor 只数本指纹`() = runBlocking {
        dao.upsert(row(0, 0))
        dao.upsert(row(0, 1, key = "2000"))
        assertEquals(1, dao.countFor(NOVEL_ID, KEY))
    }

    // ===== 删除 =====

    @Test
    fun `deleteChapter 只删该章`() = runBlocking {
        dao.upsert(row(0, 0))
        dao.upsert(row(1, 0))
        dao.deleteChapter(NOVEL_ID, KEY, 0)
        assertEquals(0, dao.forChapter(NOVEL_ID, KEY, 0, SPLIT_VERSION).size)
        assertEquals(1, dao.forChapter(NOVEL_ID, KEY, 1, SPLIT_VERSION).size)
    }

    /** 删除必须按 (id, key) 成对：id 会被复用，只按 id 删会误伤复用同 id 的新书。 */
    @Test
    fun `deleteForNovelScoped 不误伤同 id 的另一本书`() = runBlocking {
        dao.upsert(row(0, 0, key = "1000"))
        dao.upsert(row(0, 1, key = "2000"))
        dao.deleteForNovelScoped(NOVEL_ID, "1000")
        assertNull(dao.get(NOVEL_ID, "1000", 0, 0))
        assertEquals(1, dao.forChapter(NOVEL_ID, "2000", 0, SPLIT_VERSION).size)
    }

    @Test
    fun `allNovelIds 去重`() = runBlocking {
        dao.upsert(row(0, 0))
        dao.upsert(row(0, 1))
        assertEquals(listOf(NOVEL_ID), dao.allNovelIds())
    }

    // ===== 迁移 17 → 18 =====

    /**
     * 迁移 SQL 必须与 Entity 声明**完全一致**：不一致时升级用户一打开库就抛
     * `IllegalStateException`（Room 的 schema 校验），而全新安装的用户完全遇不到 ——
     * 典型的「只有老用户炸」。
     *
     * 比的是「用迁移建出来的表」与「Room 按 Entity 建出来的表」的列定义集合。
     */
    @Test
    fun `迁移建出的表与 Entity 声明一致`() {
        val fromEntity = columnsOf(db.openHelper.writableDatabase, TABLE)
        val fromMigration = columnsOf(migratedDb(), TABLE)
        assertTrue("夹具没建出表，断言无意义", fromEntity.isNotEmpty())
        assertEquals("迁移建出的列与 Entity 不一致", fromEntity, fromMigration)
    }

    /** 主键必须是四列复合：少一列会让不同书/不同章的同段号互相覆盖。 */
    @Test
    fun `迁移建出的表是四列复合主键`() {
        val pks = columnsOf(migratedDb(), TABLE)
            .filter { (it[4] as Int) > 0 }
            .sortedBy { it[4] as Int }
            .map { it[0] as String }
        assertEquals(listOf("novelId", "novelKey", "chapterIndex", "paraIndex"), pks)
    }

    @Test
    fun `迁移是幂等的 重复执行不报错`() {
        val raw = rawMigratedDb()
        try {
            TranslationHistoryDatabase.MIGRATION_17_18.migrate(raw)
            assertTrue(columnsOf(raw, TABLE).isNotEmpty())
        } finally {
            raw.close()
        }
    }

    /** 用迁移脚本建库并返回可查询的 SupportSQLiteDatabase。 */
    private fun migratedDb(): SupportSQLiteDatabase = rawMigratedDb()

    private fun rawMigratedDb(): SupportSQLiteDatabase {
        val file = File(ctx.cacheDir, "mig_${System.nanoTime()}.db")
        file.delete()
        val h = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(file.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(17) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        val support = h.writableDatabase
        openedHelpers += h
        TranslationHistoryDatabase.MIGRATION_17_18.migrate(support)
        return support
    }

    private companion object {
        const val NOVEL_ID = 1L
        const val KEY = "1000"
        const val SPLIT_VERSION = 1
        const val TABLE = "novel_paragraph_translation"

        /** 列定义集合：(名字, 类型, 非空, 默认值, 主键序号)。 */
        fun columnsOf(db: SupportSQLiteDatabase, table: String): Set<List<Any?>> {
            val out = mutableSetOf<List<Any?>>()
            db.query("PRAGMA table_info($table)").use { c ->
                while (c.moveToNext()) {
                    out.add(
                        listOf(
                            c.getString(c.getColumnIndexOrThrow("name")),
                            c.getString(c.getColumnIndexOrThrow("type")),
                            c.getInt(c.getColumnIndexOrThrow("notnull")),
                            c.getString(c.getColumnIndexOrThrow("dflt_value")),
                            c.getInt(c.getColumnIndexOrThrow("pk")),
                        )
                    )
                }
            }
            return out
        }
    }
}
