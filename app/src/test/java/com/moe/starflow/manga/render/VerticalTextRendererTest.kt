package com.moe.starflow.manga.render

import com.moe.starflow.manga.types.TextAlign
import com.moe.starflow.manga.types.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * **真实 Paint** 下的排版烟测。
 *
 * 细粒度的断行/分列不变量在 [LayoutEngineTest]（纯 JVM + 假 measurer，可精确控制断行）。
 * 这里用真实 `Paint.breakText` 只锁两件事：整套链路不崩、真实字体度量下依然不越界。
 *
 * ⚠️ Robolectric 的 `Paint.breakText` 不按宽度换行，因此多行用例必须靠 `\n` 构造。
 */
@RunWith(RobolectricTestRunner::class)
class VerticalTextRendererTest {

    private fun plan(text: String, region: Box, dir: TextDirection, font: Float = 40f) =
        LayoutEngine.plan(
            measurer = PaintTextMeasurer(),
            text = text, region = region, direction = dir,
            requestedFontSize = font, autoFit = true,
            align = TextAlign.CENTER,
            trackingRatio = LayoutEngine.TRACKING_MAX_RATIO,
            leadingRatio = LayoutEngine.LEADING_MAX_RATIO,
            minPaddingPx = 3f
        )

    @Test
    fun multiLine_doesNotOverflow_andFillsHeight() {
        val region = Box(0f, 0f, 100f, 200f)
        val layout = plan("AAA\nBBB\nCCC", region, TextDirection.HORIZONTAL)

        assertEquals("3 行", 3, layout.lines.size)
        assertTrue("内容块不越出高", layout.totalHeight <= 200f + 0.01f)
        assertTrue("内容块不越出宽", layout.totalWidth <= 100f + 0.01f)
        // 三行铺满：中间行的行距被拉伸
        val gap = layout.lines[1].baseline - layout.lines[0].baseline
        assertTrue("行距被拉伸填高", gap > layout.fontSize)
    }

    @Test
    fun newline_paragraphsPreserved() {
        val region = Box(0f, 0f, 300f, 200f)
        val layout = plan("AA\nBB", region, TextDirection.HORIZONTAL)
        assertEquals(listOf("AA", "BB"), layout.lines.map { it.text })
    }

    @Test
    fun verticalText_allCharsLaidOut_noOverflow() {
        val region = Box(0f, 0f, 80f, 200f)
        val text = "一二三四五六七八九十"
        val layout = plan(text, region, TextDirection.VERTICAL_RL)
        assertEquals("竖排字符不截断", text, layout.lines.joinToString("") { it.text })
        assertTrue("竖排不越出宽", layout.totalWidth <= 80f + 0.01f)
        assertTrue("竖排不越出高", layout.totalHeight <= 200f + 0.01f)
    }

    @Test
    fun degenerateRegion_returnsEmptyLayout() {
        val layout = plan("AA", Box(0f, 0f, 0f, 0f), TextDirection.HORIZONTAL)
        assertTrue("退化区域返回空排版", layout.isEmpty)
    }
}
