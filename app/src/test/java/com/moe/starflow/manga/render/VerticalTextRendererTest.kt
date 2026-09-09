package com.moe.starflow.manga.render

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 横排自动填充排版（computeHorizontalFillLayout）纯函数测试。
 * 核心保证：译文尽量填满 region，但绝不越界。
 */
@RunWith(RobolectricTestRunner::class)
class VerticalTextRendererTest {

    @Test
    fun multiLine_doesNotOverflowBottom_andFillsHeight() {
        val region = Rect(0, 0, 100, 200)
        // 3 段 → 3 行；glyphHeight=3×40=120，剩 80 → 行距拉伸填满高（不越界）
        val text = "AAA\nBBB\nCCC"
        val layout = VerticalTextRenderer.computeHorizontalFillLayout(text, region, 40f)

        assertEquals("3 行", 3, layout.lines.size)
        assertEquals(layout.lines.size, layout.baselines.size)
        // 不越界：最后一行基线 ≤ region.bottom
        assertTrue("最后一行不越界", layout.baselines.last() <= 200f + 0.01f)
        // 行距被拉伸 :相邻基线间距 > 字形高
        assertTrue("行距拉伸填高", layout.baselines[1] - layout.baselines[0] > 40f + 1f)
        // 内容块不高于顶
        val blockTop = layout.baselines.first() - layout.fontSize
        assertTrue("首行不高于顶", blockTop >= -0.01f)
        // 填高：内容覆盖 ≥90% 高
        val blockBottom = layout.baselines.last()
        assertTrue("块高覆盖 ≥90%", blockBottom - blockTop >= 200f * 0.9f)
    }

    @Test
    fun singleLine_fillsWidthWithLetterSpacing_notOverflow() {
        val region = Rect(0, 0, 200, 40)
        val layout = VerticalTextRenderer.computeHorizontalFillLayout("AB", region, 30f)

        assertEquals(1, layout.lines.size)
        assertTrue("横向富余产生字距", layout.letterSpacing > 0f)
        val spacedWidth = layout.lineWidths[0] + layout.letterSpacing * (layout.lines[0].length - 1)
        assertTrue("字距铺满不越界", spacedWidth <= 200f + 0.01f)
        // 垂直居中：topPad=(40-30)/2=5 → baseline = 5+30 = 35
        assertEquals(35f, layout.baselines[0], 0.5f)
    }

    @Test
    fun singleLine_inTallRegion_verticallyCentered() {
        val region = Rect(0, 0, 200, 100)
        val layout = VerticalTextRenderer.computeHorizontalFillLayout("AB", region, 30f)

        assertEquals(1, layout.lines.size)
        // topPad=(100-30)/2=35 → baseline = 35+30 = 65
        assertEquals(65f, layout.baselines[0], 0.5f)
    }

    @Test
    fun newline_paragraphsPreserved() {
        val region = Rect(0, 0, 300, 200)
        val layout = VerticalTextRenderer.computeHorizontalFillLayout("AA\nBB", region, 40f)
        assertEquals(listOf("AA", "BB"), layout.lines)
    }

    @Test
    fun degenerateRegion_returnsEmptyLayout() {
        val layout = VerticalTextRenderer.computeHorizontalFillLayout("AA", Rect(0, 0, 0, 0), 20f)
        assertTrue(layout.lines.isEmpty())
    }
}