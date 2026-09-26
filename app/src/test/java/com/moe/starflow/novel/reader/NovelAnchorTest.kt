package com.moe.starflow.novel.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读锚点的守卫。
 *
 * 起因是用户报的「翻译之后当前页面跳变」：每翻一批译文都要重排，重排后按**段号**定位
 * 会落到**这一段的第一页** —— 段一旦跨页（`NovelParagraphSplitter` 只按空行分段，
 * 长段甚至整章一段），读者就被拽回段首。锚点必须带上**段内比例**。
 */
class NovelAnchorTest {

    /** 段 0 被切成两页：`[0,100)` 在第一页，`[100,200)` 在第二页。 */
    private val splitPages = listOf(
        NovelPage(listOf(PageSegment(0, 0, 100, 0, 5))),
        NovelPage(listOf(PageSegment(0, 100, 200, 5, 10))),
    )
    private val texts = mapOf(0 to "x".repeat(200))

    /** 段首锚点 → 段首那一页（正常的"本页第一段"情况，不能被改坏）。 */
    @Test
    fun `段首锚点回到段首那一页`() {
        assertEquals(0, NovelAnchors.pageOf(splitPages, texts, NovelAnchor(0, 0f)))
    }

    /** **回归**：站在长段中段时，重排后还得在中段那一页，不能弹回段首。 */
    @Test
    fun `段中锚点回到段中那一页而不是段首`() {
        // 文本 200 字、页边界在 100：比例 × 199 落在 [0,100) 才是第一页，所以用 0.6 / 0.9
        assertEquals(1, NovelAnchors.pageOf(splitPages, texts, NovelAnchor(0, 0.6f)))
        assertEquals(1, NovelAnchors.pageOf(splitPages, texts, NovelAnchor(0, 0.9f)))
        // 比例越界（旧值/缓存里的脏值）不许跑出页表
        assertEquals(1, NovelAnchors.pageOf(splitPages, texts, NovelAnchor(0, 1.5f)))
        assertEquals(0, NovelAnchors.pageOf(splitPages, texts, NovelAnchor(0, -0.5f)))
    }

    /** 页锚点：本页第一个 segment 决定段号与比例（段首页 = 0）。 */
    @Test
    fun `页锚点取本页第一个 segment 的段内比例`() {
        assertEquals(NovelAnchor(0, 0f), NovelAnchors.ofPage(splitPages, texts, 0))
        assertEquals(NovelAnchor(0, 0.5f), NovelAnchors.ofPage(splitPages, texts, 1))
    }

    /** 原文换成译文、长度变了：比例仍然指"这一段的大致位置"，不回段首。 */
    @Test
    fun `文本变长后比例锚点仍在同一段的中后段`() {
        val longer = mapOf(0 to "y".repeat(1000))
        val pages = listOf(
            NovelPage(listOf(PageSegment(0, 0, 400, 0, 5))),
            NovelPage(listOf(PageSegment(0, 400, 1000, 5, 10))),
        )
        assertEquals(
            "50% 处重排后还落在后一页",
            1,
            NovelAnchors.pageOf(pages, longer, NovelAnchor(0, 0.5f)),
        )
    }

    /** 段被整段挪走（该段不在页表里出现）时不崩、给个合理的页。 */
    @Test
    fun `锚点段不在页表里时退化成含该段的第一页`() {
        val pages = listOf(
            NovelPage(listOf(PageSegment(3, 0, 10, 0, 2))),
            NovelPage(listOf(PageSegment(4, 0, 10, 0, 2))),
        )
        assertEquals(0, NovelAnchors.pageOf(pages, mapOf(3 to "aaaa"), NovelAnchor(3, 0.5f)))
        assertTrue("空页表不崩", NovelAnchors.pageOf(emptyList(), texts, NovelAnchor(0, 0.5f)) == 0)
    }

    /** 滚动锚点：滚过这一段多少 = 比例；拿不到高度时退回段顶。 */    @Test
    fun `滚动锚点按段内已滚过的比例算`() {
        assertEquals(NovelAnchor(7, 0f), NovelAnchors.ofScroll(7, scrolledIntoItemPx = 0, itemHeightPx = 800))
        assertEquals(NovelAnchor(7, 0.5f), NovelAnchors.ofScroll(7, scrolledIntoItemPx = 400, itemHeightPx = 800))
        assertEquals(
            "段顶在屏幕下方（首项前有留白）时按 0 算",
            NovelAnchor(7, 0f),
            NovelAnchors.ofScroll(7, scrolledIntoItemPx = -50, itemHeightPx = 800),
        )
        assertEquals(
            "高度未知时不猜",
            NovelAnchor(7, 0f),
            NovelAnchors.ofScroll(7, scrolledIntoItemPx = 100, itemHeightPx = 0),
        )
    }

    /**
     * **为什么带位重排期间必须把锚点钉住**（用户报的「翻译之后位置一直往回跑」）。
     *
     * `pageOf` 只能给到**页**（向下取整到页边界），落位后再用 `ofPage` 把"这一页的页首"
     * 当成新锚点 —— 页首永远比逻辑位置靠前，于是每重排一批就后退最多一屏，
     * 而下一批又从被取整的位置起算：**误差不抵消，逐批累积、单向后退**。
     *
     * 这条测试钉住「重取只会更靠前」这个事实；`NovelReaderActivity.carryAnchor` 就是
     * 据此在带位重排期间拒绝重取，只有用户自己导航才允许。
     */
    @Test
    fun `落位后按页首重取锚点只会往回退`() {
        // 整章一段、300 字，被切成 3 页（每页 100 字）
        val texts = mapOf(0 to "x".repeat(300))
        val pages = listOf(
            NovelPage(listOf(PageSegment(0, 0, 100, 0, 3))),
            NovelPage(listOf(PageSegment(0, 100, 200, 3, 6))),
            NovelPage(listOf(PageSegment(0, 200, 300, 6, 9))),
        )
        var anchor = NovelAnchor(0, 0.5f)          // 逻辑位置：150 字处
        val seen = mutableListOf(anchor.fraction)

        // 模拟「重排 → 落位到包含它的那一页 → 把页首当成新锚点」重复几轮
        repeat(3) {
            val page = NovelAnchors.pageOf(pages, texts, anchor)
            anchor = NovelAnchors.ofPage(pages, texts, page)
            seen += anchor.fraction
        }

        assertTrue("每轮都更靠前：$seen", seen == seen.sortedDescending())
        assertTrue("三轮之后已经退到段首附近：$seen", seen.last() <= seen.first() - 0.3f)
    }
}
