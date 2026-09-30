package com.moe.starflow.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 超分逐页记录表（`imported_page_sr`，DB v18 → v19）。
 *
 * 两层守卫：
 * 1. **DAO 行为**：指纹过滤 / 区间删除 / 「超分中」复位 —— 都是"错了也不报错、只是数据不对"的那类。
 * 2. **迁移与实体逐列一致**：`MIGRATION_18_19` 手写的建表语句必须与 Room 由 [ImportedPageSr]
 *    生成的 schema **逐列相同**（列名/类型/NOT NULL/主键），多一个 `DEFAULT` 就会让**升级用户**
 *    一打开库即抛 `IllegalStateException`，而全新安装的用户完全遇不到 —— 只能靠这条守住。
 */
@RunWith(RobolectricTestRunner::class)
class ImportedPageSrDaoTest {

    private var db: TranslationHistoryDatabase? = null
    private fun dao() = db!!.importedPageSrDao()

    private val mangaId = 42L
    private val mangaKey = "book|1000"

    @After
    fun tearDown() {
        db?.close()
        db = null
    }

    private fun openDb() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), TranslationHistoryDatabase::class.java
        ).build()
    }

    private fun row(page: Int, state: Int = ImportedPageSr.STATE_SUCCESS, key: String? = mangaKey) =
        ImportedPageSr(
            mangaId = mangaId, pageIndex = page, state = state,
            modelName = "SR_W2X_UP7_ANIME_M1", srcWidth = 1080, srcHeight = 1620, srcBytes = 200_000L,
            outWidth = 1080, outHeight = 1620, outBytes = 150_000L,
            startedAtMs = 1_000L, finishedAtMs = 2_000L, updatedAtMs = 2_000L, mangaKey = key,
        )

    // ─────────────── DAO 行为 ───────────────

    @Test
    fun forMangaReturnsRowsInPageOrder() = runBlocking {
        openDb()
        listOf(5, 1, 3).forEach { dao().upsert(row(it)) }
        assertEquals(listOf(1, 3, 5), dao().forManga(mangaId, mangaKey).map { it.pageIndex })
    }

    /** 指纹不匹配的行一律不采用（漫画 id 会被复用 —— 与译文侧同一条硬约束）。 */
    @Test
    fun rowsFromAnotherBookAreInvisible() = runBlocking {
        openDb()
        dao().upsert(row(0, key = "book|9999"))     // 删书重导后复用同一 id 的旧书
        dao().upsert(row(1, key = mangaKey))
        val rows = dao().forManga(mangaId, mangaKey)
        assertEquals("只该看到属于这本书的那一行", listOf(1), rows.map { it.pageIndex })
    }

    @Test
    fun deletePageRangeOnlyTouchesTheRange() = runBlocking {
        openDb()
        (0..5).forEach { dao().upsert(row(it)) }
        dao().deletePageRange(mangaId, mangaKey, 2, 4)
        assertEquals(listOf(0, 1, 5), dao().forManga(mangaId, mangaKey).map { it.pageIndex })
    }

    @Test
    fun deletePageRangeDoesNotTouchAnotherBookWithTheSameId() = runBlocking {
        openDb()
        dao().upsert(row(1, key = "book|9999"))
        dao().upsert(row(2, key = mangaKey))
        dao().deletePageRange(mangaId, mangaKey, 0, 9)
        assertEquals("别人的行一行都不能动", 1, dao().forManga(mangaId, "book|9999").size)
    }

    /** 残留的「超分中」必须能一键回到「未超分」，否则那一页从此既没结果也再不会被重挑。 */
    @Test
    fun resetRunningOnlyRevertsRunningRows() = runBlocking {
        openDb()
        dao().upsert(row(0, state = ImportedPageSr.STATE_RUNNING))
        dao().upsert(row(1, state = ImportedPageSr.STATE_SUCCESS))
        dao().upsert(row(2, state = ImportedPageSr.STATE_FAILED))
        dao().resetRunning(mangaId, mangaKey)

        val byPage = dao().forManga(mangaId, mangaKey).associateBy { it.pageIndex }
        assertEquals(ImportedPageSr.STATE_IDLE, byPage.getValue(0).state)
        assertEquals("成功的不许被回退", ImportedPageSr.STATE_SUCCESS, byPage.getValue(1).state)
        assertEquals("失败的也不许被回退", ImportedPageSr.STATE_FAILED, byPage.getValue(2).state)
    }

    @Test
    fun rekeyPagesMovesRowsAndKeepsThePayload() = runBlocking {
        openDb()
        (0..2).forEach { dao().upsert(row(it).copy(updatedAtMs = it.toLong())) }
        // 旧 0→1、1→2、2→0
        dao().rekeyPages(mangaId, mangaKey, intArrayOf(1, 2, 0))
        val rows = dao().forManga(mangaId, mangaKey)
        assertEquals(listOf(0, 1, 2), rows.map { it.pageIndex })
        assertEquals("载荷要跟着行走", 2L, rows.first { it.pageIndex == 0 }.updatedAtMs)
        assertEquals(0L, rows.first { it.pageIndex == 1 }.updatedAtMs)
    }

    @Test
    fun deleteMangaScopedRemovesNullOrMatchingRows() = runBlocking {
        openDb()
        dao().upsert(row(0, key = null))
        dao().upsert(row(1, key = mangaKey))
        dao().upsert(row(2, key = "book|9999"))
        dao().deleteMangaScoped(mangaId, mangaKey)
        assertEquals(listOf(2), dao().forManga(mangaId, "book|9999").map { it.pageIndex })
        assertTrue("本指纹的行清空了", dao().forManga(mangaId, mangaKey).isEmpty())
    }

    // ─────────────── 迁移与实体逐列一致 ───────────────

    private data class Col(val name: String, val type: String, val notNull: Boolean, val pk: Int)

    private fun columnsOf(db: SupportSQLiteDatabase, table: String): List<Col> {
        val out = ArrayList<Col>()
        db.query("PRAGMA table_info(`$table`)").use { c ->
            while (c.moveToNext()) {
                out += Col(
                    name = c.getString(c.getColumnIndexOrThrow("name")),
                    type = c.getString(c.getColumnIndexOrThrow("type")).uppercase(),
                    notNull = c.getInt(c.getColumnIndexOrThrow("notnull")) == 1,
                    pk = c.getInt(c.getColumnIndexOrThrow("pk")),
                )
            }
        }
        return out.sortedBy { it.name }
    }

    /** 空库 + 只跑迁移语句的另一个库，用来取"迁移期望的样子" */
    private fun scratchDb(): SupportSQLiteDatabase {
        val ctx = RuntimeEnvironment.getApplication()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(null)                      // in-memory
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        return helper.writableDatabase
    }

    @Test
    fun migrationTableDefinitionMatchesTheEntityExactly() = runBlocking {
        openDb()
        // Room 由 ImportedPageSr 生成的真实 schema
        val actual = columnsOf(db!!.openHelper.writableDatabase, "imported_page_sr")

        // 迁移手写语句建出来的表
        val scratch = scratchDb()
        TranslationHistoryDatabase.MIGRATION_18_19.migrate(scratch)
        val expected = columnsOf(scratch, "imported_page_sr")

        assertEquals(
            "迁移建的表与实体 schema 不一致 —— 升级用户一打开库就会抛 IllegalStateException" +
                "（全新安装完全遇不到）。逐列对比：",
            expected, actual,
        )
        assertEquals("列数也要对上", actual.size, actual.map { it.name }.distinct().size)
        assertNull(
            "不许出现 DEFAULT（Kotlin 默认值是语言层的，Room 建表语句里没有 DEFAULT 子句）",
            run {
                var dflt: String? = null
                scratch.query("PRAGMA table_info(`imported_page_sr`)").use { c ->
                    while (c.moveToNext() && dflt == null) {
                        val v = c.getString(c.getColumnIndexOrThrow("dflt_value"))
                        if (v != null) dflt = v
                    }
                }
                dflt
            },
        )
    }
}
