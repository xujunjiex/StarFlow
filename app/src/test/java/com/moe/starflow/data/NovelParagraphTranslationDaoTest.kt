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

    /**
     * `markFailed` **不能盖掉已有译文**（`AND state != 2`）。
     *
     * 触发路径是真实的：长按多选 → 重翻时 `translateExact` 会把**已有译文**的段重新送出去
     * （这正是重翻），这次若失败而 SUCCESS 行被翻成 FAILED，`loadTranslations` 只收 SUCCESS ——
     * 用户**原有的译文会静默消失**，表现是「点了重翻，译文反而没了」。
     */
    @Test
    fun `markFailed 不覆盖已有译文`() = runBlocking {
        dao.upsert(row(0, 0, state = NovelParagraphTranslation.STATE_SUCCESS, text = "原有译文"))

        dao.markFailed(NOVEL_ID, KEY, 0, SPLIT_VERSION, listOf(0), "HTTP 429", 2L)

        val r = dao.forChapter(NOVEL_ID, KEY, 0, SPLIT_VERSION).single()
        assertEquals(NovelParagraphTranslation.STATE_SUCCESS, r.state)
        assertEquals("原有译文", r.translatedText)
    }

    /** 失败要能盖在 IDLE / TRANSLATING 上 —— 否则失败状态永远显示不出来。 */
    @Test
    fun `markFailed 能标记未成功与翻译中的行`() = runBlocking {
        dao.upsert(row(0, 0, state = NovelParagraphTranslation.STATE_IDLE))
        dao.upsert(row(0, 1, state = NovelParagraphTranslation.STATE_TRANSLATING))

        dao.markFailed(NOVEL_ID, KEY, 0, SPLIT_VERSION, listOf(0, 1), "HTTP 429", 2L)

        val rows = dao.forChapter(NOVEL_ID, KEY, 0, SPLIT_VERSION)
        assertTrue(rows.all { it.state == NovelParagraphTranslation.STATE_FAILED })
        assertTrue("失败原因要原样存下来", rows.all { it.failCode == "HTTP 429" })
    }

    /** 空集合守卫：空列表不能生成 `IN ()`（SQLite 语法错 → 运行时崩）。 */
    @Test
    fun `markFailed 空列表是安全的空操作`() = runBlocking {
        dao.upsert(row(0, 0, state = NovelParagraphTranslation.STATE_IDLE))

        dao.markFailed(NOVEL_ID, KEY, 0, SPLIT_VERSION, emptyList(), "x", 2L)

        assertEquals(
            NovelParagraphTranslation.STATE_IDLE,
            dao.forChapter(NOVEL_ID, KEY, 0, SPLIT_VERSION).single().state,
        )
    }

    /**
     * `markTranslatingRows` 是 **UPDATE**：行已经由「补齐分母」建好了，
     * 而 `INSERT OR IGNORE` 对已存在的行是空操作 —— 那样只有该章第一批能被标上，
     * 第二批起「翻译中」永远写不进库（`resetStale` 于是变成空操作）。
     */
    @Test
    fun `markTranslatingRows 能把已存在的行标成翻译中`() = runBlocking {
        dao.upsert(row(0, 0, state = NovelParagraphTranslation.STATE_IDLE))

        dao.markTranslatingRows(NOVEL_ID, KEY, 0, SPLIT_VERSION, listOf(0), "引擎", 2L)

        val r = dao.forChapter(NOVEL_ID, KEY, 0, SPLIT_VERSION).single()
        assertEquals(NovelParagraphTranslation.STATE_TRANSLATING, r.state)
        assertEquals("引擎", r.translatorName)
    }

    /** 已有译文的段不能被标回「翻译中」（重翻期间也不能让译文看起来丢了）。 */
    @Test
    fun `markTranslatingRows 不动已有译文`() = runBlocking {
        dao.upsert(row(0, 0, state = NovelParagraphTranslation.STATE_SUCCESS, text = "原有译文"))

        dao.markTranslatingRows(NOVEL_ID, KEY, 0, SPLIT_VERSION, listOf(0), "引擎", 2L)

        val r = dao.forChapter(NOVEL_ID, KEY, 0, SPLIT_VERSION).single()
        assertEquals(NovelParagraphTranslation.STATE_SUCCESS, r.state)
        assertEquals("原有译文", r.translatedText)
    }

    /** 重试开始时要清掉旧的失败原因 —— 否则失败的章行会在重试期间继续挂着旧报错。 */
    @Test
    fun `markTranslatingRows 清掉旧的失败原因`() = runBlocking {
        dao.upsert(row(0, 0, state = NovelParagraphTranslation.STATE_FAILED))
        dao.markFailed(NOVEL_ID, KEY, 0, SPLIT_VERSION, listOf(0), "HTTP 429", 1L)

        dao.markTranslatingRows(NOVEL_ID, KEY, 0, SPLIT_VERSION, listOf(0), "引擎", 2L)

        val r = dao.forChapter(NOVEL_ID, KEY, 0, SPLIT_VERSION).single()
        assertEquals(NovelParagraphTranslation.STATE_TRANSLATING, r.state)
        assertNull("旧的失败原因不该继续挂着", r.failCode)
    }

    /**
     * 分段规则升级时要能清掉**非当前版本**的行。
     *
     * ⚠️ 不清的后果是静默的：主键不含 `splitVersion`，旧行占着 `(书, 章, 段号)` 那一格，
     * 新版本的行 `insertIgnore` 写不进去（分母补不齐、「翻译中」标不上），而表只增不减。
     * 调用点在 `NovelChapterTranslator.resetStale`（进入阅读器时）。
     */
    @Test
    fun `deleteOtherVersions 只删非当前版本且不碰别的书`() = runBlocking {
        dao.upsert(row(0, 0, splitVersion = 1))          // 旧版本残留
        dao.upsert(row(0, 1, splitVersion = 2))          // 当前版本
        dao.upsert(row(0, 0, key = "9999", splitVersion = 1))  // 别的书，不能动

        dao.deleteOtherVersions(NOVEL_ID, KEY, splitVersion = 2)

        assertEquals("旧版本的行必须被清掉", 0, dao.forChapter(NOVEL_ID, KEY, 0, splitVersion = 1).size)
        assertEquals("当前版本的行必须留着", 1, dao.forChapter(NOVEL_ID, KEY, 0, splitVersion = 2).size)
        assertEquals("别的书不能被牵连", 1, dao.forChapter(NOVEL_ID, "9999", 0, splitVersion = 1).size)
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
    fun `deleteForChapter 只删该章`() = runBlocking {
        dao.upsert(row(0, 0))
        dao.upsert(row(1, 0))
        dao.deleteForChapter(NOVEL_ID, KEY, 0)
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
