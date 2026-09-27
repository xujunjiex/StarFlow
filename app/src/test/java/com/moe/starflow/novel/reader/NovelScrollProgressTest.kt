package com.moe.starflow.novel.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动模式进度条的坐标守卫。
 *
 * 起因是一条用户报的 bug：**章节文字太多时滚动模式底部的进度条失效**。
 * 根因是位置当时按「段序号 / 段总数」算，而一个几万字的章节可能整章只有**一段**
 * （`NovelParagraphSplitter` 只按空行分段），于是那个比例恒为 0/1 —— 进度条永远不动。
 * 这里把新口径钉死：**位置按像素比例，与段落怎么切无关**；绿条按段序号的等分切片。
 */
class NovelScrollProgressTest {

    /** 滚到一半就是一半 —— 这条与"章节被切成几段"完全无关（函数也不收段数）。 */
    @Test
    fun `位置按像素比例而不是段序号`() {
        val steps = NovelScrollProgress.STEPS
        assertEquals(0, NovelScrollProgress.stepOf(offset = 0, range = 10_000, extent = 1_000))
        val half = NovelScrollProgress.stepOf(offset = 4_500, range = 10_000, extent = 1_000)
        assertTrue("滚到一半 ≈ 中间格（实际 $half）", kotlin.math.abs(half - (steps - 1) / 2) <= 1)
        assertEquals(
            "滚到底 = 最后一格",
            steps - 1,
            NovelScrollProgress.stepOf(offset = 9_000, range = 10_000, extent = 1_000),
        )
    }

    /** 内容不足一屏 → 没得滚，恒为起点（不能除出 NaN / 负数）。 */
    @Test
    fun `内容不足一屏时停在起点`() {
        assertEquals(0, NovelScrollProgress.stepOf(offset = 0, range = 800, extent = 1_000))
        assertEquals(0, NovelScrollProgress.stepOf(offset = 500, range = 800, extent = 1_000))
        assertEquals(0, NovelScrollProgress.stepOf(offset = 0, range = 0, extent = 0))
    }

    /** 越界输入（RecyclerView 估算值会抖）必须被钳住，不能画出界。 */
    @Test
    fun `越界输入被钳制`() {
        assertEquals(0, NovelScrollProgress.stepOf(offset = -100, range = 10_000, extent = 1_000))
        assertEquals(
            NovelScrollProgress.STEPS - 1,
            NovelScrollProgress.stepOf(offset = 99_999, range = 10_000, extent = 1_000),
        )
    }

    /** 拖拽回调 → 滚动目标的换算：两端精确，中间单调。 */
    @Test
    fun `格到比例的换算是整段`() {
        val steps = NovelScrollProgress.STEPS
        assertEquals(0f, NovelScrollProgress.fractionOfStep(0), 1e-6f)
        assertEquals(1f, NovelScrollProgress.fractionOfStep(steps - 1), 1e-6f)
        assertEquals(0.5f, NovelScrollProgress.fractionOfStep(steps / 2), 0.001f)
        assertEquals("越界钳到端点", 1f, NovelScrollProgress.fractionOfStep(steps + 10), 1e-6f)
    }

    /**
     * **回归**：整章只有一段且已翻译 → 绿条铺满。
     *
     * 这是「超长单段章节」的极端情形：按点映射的话只会画出一格，用户看到的是
     * "翻完了却基本没变绿"。
     */
    @Test
    fun `整章一段且已翻译时绿条铺满`() {
        val steps = NovelScrollProgress.stepsOfItems(setOf(0), itemCount = 1)
        assertEquals("一段 = 一整片", NovelScrollProgress.STEPS, steps.size)
        assertEquals(0, steps.min())
        assertEquals(NovelScrollProgress.STEPS - 1, steps.max())
    }

    /** 一段都没翻 → 没有绿条（空集合不能变成"整条绿"）。 */
    @Test
    fun `没有译文时绿条为空`() {
        assertTrue(NovelScrollProgress.stepsOfItems(emptySet(), itemCount = 100).isEmpty())
        assertTrue(NovelScrollProgress.stepsOfItems(setOf(0), itemCount = 0).isEmpty())
    }

    /** 段数多于格数时切片会塌成 0 宽 → 退化成至少一格，不能出现"翻了却不亮"的空洞。 */
    @Test
    fun `段数多于格数时每一段都还落在格上`() {
        for (i in listOf(0, 1, 2_500, 4_999)) {
            val r = NovelScrollProgress.sliceOf(i, itemCount = 5_000)
            assertTrue("第 $i 段不能映射成空格", !r.isEmpty())
            assertTrue("第 $i 段越界：$r", r.first in 0 until NovelScrollProgress.STEPS)
            assertTrue("第 $i 段越界：$r", r.last in 0 until NovelScrollProgress.STEPS)
        }
        assertEquals(
            "末段至少落在最后一格",
            NovelScrollProgress.STEPS - 1,
            NovelScrollProgress.sliceOf(4_999, itemCount = 5_000).last,
        )
    }

    /** 段数少于格数：连续翻过的段合并成连片，中间没翻的留空。 */
    @Test
    fun `连续翻译的段在绿条上连片`() {
        val steps = NovelScrollProgress.stepsOfItems(setOf(0, 1, 2, 3, 4), itemCount = 10)
        assertEquals(NovelScrollProgress.STEPS / 2, steps.size)
        assertEquals(0, steps.min())
        assertEquals(NovelScrollProgress.STEPS / 2 - 1, steps.max())
    }
}
