package com.moe.starflow.mangaimport.data

import androidx.room.Room
import com.moe.starflow.data.ImportedPageTranslation
import com.moe.starflow.data.TranslationHistoryDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * **页序迁移里真正动用户数据的那一步**（`dao.rekeyPages`：事务内读 → 删 → 按新下标重插）的守卫。
 *
 * 为什么单独立一份：`MangaPageOrderTest` 只覆盖纯函数 `legacyToNewPlan`，而**改数据的是这里**。
 * 映射方向写反、`deleteMangaScoped` 之后重插漏行、或主键冲突被 `OnConflictStrategy.REPLACE`
 * 静默吃掉 —— 用户看到的就是「整本书的译文挂到别的图上」，全程零报错。
 *
 * ⚠️ 2026-10 审查发现「页序迁移每次打开阅读器都重跑一次」（`ImportedManga.pageOrderVersion`
 * 根本没落盘）那个真 bug，正是因为这一层零覆盖才活到了线上。别把它删回纯函数测试。
 */
@RunWith(RobolectricTestRunner::class)
class MangaPageOrderRekeyTest {

    private var db: TranslationHistoryDatabase? = null
    private fun dao() = db!!.importedPageTranslationDao()

    private val mangaId = 7L
    private val mangaKey = "manga|1"

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

    private fun row(page: Int, tag: String) = ImportedPageTranslation(
        mangaId = mangaId, pageIndex = page,
        state = ImportedPageTranslation.STATE_SUCCESS,
        sourceText = "[1] src-$tag", translatedText = "[1] t-$tag",
        bubbleRects = null, failCode = null, failMessage = null,
        updatedAtMs = page.toLong(), mangaKey = mangaKey,
    )

    /** 置换 1,2,0：旧下标 0/1/2 的译文要整体搬到 1/2/0 去。 */
    @Test
    fun rekeyPages_movesEachRowToItsMappedIndex() = runBlocking {
        openDb()
        (0..2).forEach { dao().upsert(row(it, "p$it")) }

        dao().rekeyPages(mangaId, mangaKey, intArrayOf(1, 2, 0))

        val all = dao().forManga(mangaId, mangaKey)
        assertEquals("行数不能变（迁移只做置换、绝不删行）", 3, all.size)
        assertEquals("页号集合不变", listOf(0, 1, 2), all.map { it.pageIndex }.sorted())
        // 载荷必须**跟着自己那一行**走
        assertEquals("[1] t-p2", all.first { it.pageIndex == 0 }.translatedText)
        assertEquals("[1] t-p0", all.first { it.pageIndex == 1 }.translatedText)
        assertEquals("[1] t-p1", all.first { it.pageIndex == 2 }.translatedText)
    }

    /** 载荷的每一个字段都要跟着行走，不能只搬译文把气泡坐标落在旧行上。 */
    @Test
    fun rekeyPages_keepsEveryColumnWithItsRow() = runBlocking {
        openDb()
        dao().upsert(
            row(0, "a").copy(bubbleRects = "RECTS-A", sourceText = "SRC-A", failCode = "OCR_EMPTY")
        )
        dao().upsert(row(1, "b").copy(bubbleRects = "RECTS-B", sourceText = "SRC-B"))

        dao().rekeyPages(mangaId, mangaKey, intArrayOf(1, 0))

        val at0 = dao().get(mangaId, 0)!!
        val at1 = dao().get(mangaId, 1)!!
        assertEquals("RECTS-B", at0.bubbleRects)
        assertEquals("SRC-B", at0.sourceText)
        assertEquals("RECTS-A", at1.bubbleRects)
        assertEquals("SRC-A", at1.sourceText)
        assertEquals("OCR_EMPTY", at1.failCode)
        assertNotNull(at1)
    }

    /** 映射比页号短（文件被换过）时：越界的行**保留原页号**，既不丢也不撞主键。 */
    @Test
    fun rekeyPages_keepsOriginalIndexWhenMappingIsShorter() = runBlocking {
        openDb()
        (0..3).forEach { dao().upsert(row(it, "p$it")) }

        // 只给了前 2 个映射
        dao().rekeyPages(mangaId, mangaKey, intArrayOf(1, 0))

        val all = dao().forManga(mangaId, mangaKey)
        assertEquals("不能因为映射短就丢行", 4, all.size)
        assertEquals(listOf(0, 1, 2, 3), all.map { it.pageIndex }.sorted())
        assertEquals("0/1 被置换", "[1] t-p1", all.first { it.pageIndex == 0 }.translatedText)
        assertEquals("2/3 越界 → 原样保留，且不能撞掉别人", "[1] t-p2", all.first { it.pageIndex == 2 }.translatedText)
    }

    /** 只影响「本书 + 本指纹」：别的书、别的指纹的行一律不许被动到。 */
    @Test
    fun rekeyPages_doesNotTouchOtherBooksOrOtherKeys() = runBlocking {
        openDb()
        // ⚠️ 主键是 (mangaId, pageIndex)，没有 mangaKey —— 所以这里的页号必须错开，
        //    否则 upsert 的 REPLACE 会把上一行静默吃掉（这正是本类要防的那类问题）
        dao().upsert(row(0, "mine"))
        dao().upsert(row(0, "other-book").copy(mangaId = 99L))
        dao().upsert(row(9, "other-key").copy(mangaKey = "manga|2"))

        dao().rekeyPages(mangaId, mangaKey, intArrayOf(5))

        assertEquals("[1] t-other-book", dao().get(99L, 0)?.translatedText)
        assertEquals("别的指纹的行不许被删/被搬", "[1] t-other-key", dao().get(mangaId, 9)?.translatedText)
        assertEquals("[1] t-mine", dao().get(mangaId, 5)?.translatedText)
    }

    /**
     * **置换不是幂等的** —— 这条不变式正是 `ImportedManga.pageOrderPlan` 存在的理由。
     *
     * 如果哪天有人"优化"掉了那个记录，靠的就是这条用例说清后果：
     * 同一个置换施加两次，译文会被挪到第三张图上（不是回到原位）。
     */
    @Test
    fun applyingTheSamePlanTwiceIsNotIdentity() = runBlocking {
        openDb()
        (0..2).forEach { dao().upsert(row(it, "p$it")) }
        val plan = intArrayOf(1, 2, 0)

        dao().rekeyPages(mangaId, mangaKey, plan)
        val afterOnce = dao().forManga(mangaId, mangaKey).associate { it.pageIndex to it.translatedText }
        dao().rekeyPages(mangaId, mangaKey, plan)
        val afterTwice = dao().forManga(mangaId, mangaKey).associate { it.pageIndex to it.translatedText }

        assertEquals("[1] t-p2", afterOnce[0])
        assertNotEquals("同一个置换再施加一次必须改变结果（所以必须记着「已施加过」）", afterOnce, afterTwice)
        assertEquals("第二次的结果是再转一格", "[1] t-p1", afterTwice[0])
    }

    /**
     * 幂等哨兵依赖的不变式：`legacyToNewPlan` 是**页文件集合的纯函数** ——
     * 同样的输入永远得到同一个置换。记录下来的 plan 才有资格当"已施加过"的判据。
     */
    @Test
    fun legacyToNewPlanIsStableAcrossCalls() {
        val raw = listOf("z.jpg", "ch1/001.jpg", "ch1/002.jpg", "a.jpg")
        val ordered = listOf("z.jpg", "a.jpg", "ch1/001.jpg", "ch1/002.jpg")
        val first = MangaPageOrder.legacyToNewPlan(raw, ordered)
        val second = MangaPageOrder.legacyToNewPlan(raw, ordered)
        assertNotNull("这个样本必须真的产生置换，否则用例是空跑", first)
        assertTrue("同一输入必须得到同一个置换", first!!.contentEquals(second!!))
    }
}
