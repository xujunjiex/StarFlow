package com.moe.starflow.novel.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「翻到上一章末页」时锚点的回归守卫。
 *
 * 背景（真实 bug）：在章首页点「上一页」→ `gotoChapter(index - 1, atLastPage = true)`
 * → `loadChapter(index, atLastPage = true)`，而**这条路不传 anchor**，于是 `anchor` 是
 * 签名默认值 `NovelAnchor(0)`；页号却取 `pages.lastIndex`。
 *
 * 早先无条件 `carryAnchor = anchor` 把 `NovelAnchor(0)` 钉住了，紧接着的
 * `snapPagerToAnchor` 按它算出 `want = 0`、`cur = lastIndex` → 立刻 `setCurrentItem(0)`：
 * 「上一页」到的是上一章的**首页**；同时 `persistProgress` 把 `lastReadParaIndex` 落成 0，
 * 下次打开从章首开始。
 *
 * 修法是钉**实际落位那一页**的锚点。这两条断言就是它的判据。
 */
class NovelAnchorsLastPageTest {

    private fun pages(count: Int) = (0 until count).map { i ->
        NovelPage(listOf(PageSegment(i, 0, 10, 0, 1)))
    }

    private val texts = (0 until 4).associateWith { "字".repeat(10) }

    /** 末页的锚点换算回来必须还是末页 —— 否则重排（译文到达 / 改字号）会把读者弹回章首。 */
    @Test
    fun `末页锚点换算回来仍是末页`() {
        val p = pages(4)
        val last = NovelAnchors.ofPage(p, texts, p.lastIndex)

        assertEquals("末页第一段的段号", 3, last.paraIndex)
        assertEquals(3, NovelAnchors.pageOf(p, texts, last))
    }

    /** 反面：钉默认锚点就会算出首页 —— 这正是那个 bug 的机制，写下来防止改回去。 */
    @Test
    fun `默认锚点算出来的是首页`() {
        val p = pages(4)
        assertEquals(0, NovelAnchors.pageOf(p, texts, NovelAnchor(0)))
        assertEquals(0, NovelAnchors.ofPage(p, texts, 0).paraIndex)
    }

    /** 只有一页的章：末页就是首页，两者一致（不要因为"没差别"而把修法写复杂）。 */
    @Test
    fun `单页章两者一致`() {
        val p = pages(1)
        assertEquals(0, NovelAnchors.pageOf(p, texts, NovelAnchors.ofPage(p, texts, p.lastIndex)))
        assertEquals(0, NovelAnchors.pageOf(p, texts, NovelAnchor(0)))
    }
}
