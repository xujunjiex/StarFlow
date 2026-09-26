package com.moe.starflow.novel.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelBatchPlannerTest {

    // 章内段落号 0..9；「当前页」是第 3~5 段
    private val chapter = (0..9).toList()
    private val page = listOf(3, 4, 5)

    @Test
    fun `页锚点取本页第一段未翻译的段`() {
        assertEquals(3, NovelBatchPlanner.anchorOnPage(page, emptySet()))
        assertEquals(4, NovelBatchPlanner.anchorOnPage(page, setOf(0, 1, 2, 3)))
    }

    /** 本页全翻过 → 没有锚点（自动模式据此停下等翻页）。 */
    @Test
    fun `本页全翻过则没有锚点`() {
        assertNull(NovelBatchPlanner.anchorOnPage(page, setOf(3, 4, 5)))
    }

    /**
     * ⚠️ 这条是本功能最容易做错的地方：**增量向后翻的是「批」，不是「章」**。
     * 页内有未翻段 → 锚点就是它；页内翻完了 → 锚点必须是**本页之后第一段没翻的段**，
     * 既不能跳到下一章，也不能回头补本页之前的段。
     */
    @Test
    fun `增量模式页内翻完后继续在本章向后而不是跳章或回头`() {
        val translated = setOf(3, 4, 5)
        assertEquals(6, NovelBatchPlanner.anchorForMode(NovelTranslateMode.AHEAD, page, chapter, translated))
        // 自动模式相反：页内翻完就停（null），不越页
        assertNull(NovelBatchPlanner.anchorForMode(NovelTranslateMode.AUTO, page, chapter, translated))
    }

    /** 增量只向后：前面缺的段不回头补（那与「向后翻译」相反）。 */
    @Test
    fun `增量不回头补页之前的段`() {
        val translated = setOf(3, 4, 5) // 0/1/2 没翻，但都在本页之前
        assertEquals(6, NovelBatchPlanner.anchorForMode(NovelTranslateMode.AHEAD, page, chapter, translated))
    }

    /** 本页之后没有未翻的段（本章翻完）→ 停下，不去下一章。 */
    @Test
    fun `增量在本章末尾停下`() {
        val tail = listOf(7, 8, 9)
        assertEquals(
            null,
            NovelBatchPlanner.anchorForMode(NovelTranslateMode.AHEAD, tail, chapter, (0..9).toSet()),
        )
    }

    /** 手动/自动的锚点**只看当前页**：本页之前的段没翻也不管。 */
    @Test
    fun `手动与自动的锚点都只看当前页`() {
        val translated = setOf(0, 1) // 0/1 没翻完的是 2，但 2 不在本页
        assertEquals(3, NovelBatchPlanner.anchorForMode(NovelTranslateMode.MANUAL, page, chapter, translated))
        assertEquals(3, NovelBatchPlanner.anchorForMode(NovelTranslateMode.AUTO, page, chapter, translated))
    }

    /** 一批 = 从锚点向后连续 N 段。 */
    @Test
    fun `从锚点向后取一批`() {
        assertEquals(listOf(3, 4, 5), NovelBatchPlanner.nextBatch(chapter, 3, emptySet(), 3))
        assertEquals(listOf(3, 4), NovelBatchPlanner.nextBatch(chapter, 3, emptySet(), 2))
    }

    /**
     * ⚠️ 中间夹着已翻译的段要**跳过但补足数量**：否则「向后翻一批」会返回一堆已有译文的段，
     * 白花额度、用户看不到任何变化。
     */
    @Test
    fun `批内跳过已翻译的段并补足数量`() {
        assertEquals(listOf(3, 5, 6), NovelBatchPlanner.nextBatch(chapter, 3, setOf(4), 3))
    }

    /** 到章末不足一批就给剩下的 —— 章末即停，不越界。 */
    @Test
    fun `章末不足一批时只给剩下的`() {
        assertEquals(listOf(8, 9), NovelBatchPlanner.nextBatch(chapter, 8, emptySet(), 3))
        assertTrue(NovelBatchPlanner.nextBatch(chapter, 9, setOf(9), 3).isEmpty())
    }

    @Test
    fun `锚点不在本章时返回空批`() {
        assertTrue(NovelBatchPlanner.nextBatch(chapter, 99, emptySet(), 3).isEmpty())
    }

    /** 增量配额：每批减一，到 0 即耗尽，且不会变负。 */
    @Test
    fun `增量配额到零即耗尽`() {
        var q = NovelQuota.of(5)
        assertEquals(5, q.remaining)
        repeat(5) { q = q.consume() }
        assertTrue(q.exhausted)
        assertEquals(0, q.consume().remaining)
    }

    /** 设置范围外的值要被夹回区间：0 会让增量一批都不翻（用户以为功能坏了）。 */
    @Test
    fun `配额范围 2 到 10`() {
        assertEquals(2, NovelQuota.of(0).remaining)
        assertEquals(2, NovelQuota.of(1).remaining)
        assertEquals(10, NovelQuota.of(99).remaining)
        assertEquals(5, NovelQuota.of(5).remaining)
    }
}
