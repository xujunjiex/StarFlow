package com.moe.starflow.manga.render

import com.moe.starflow.manga.types.TextAlign
import com.moe.starflow.manga.types.TextDirection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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

    @Test
    fun punctuationRenderingPreservesLayoutTextForBothColumnDirections() {
        val original = "（你好）「世界」…—〜ー，。！？︵︶"
        for (direction in listOf(TextDirection.VERTICAL_RL, TextDirection.VERTICAL_LR, TextDirection.HORIZONTAL)) {
            val layout = plan(original, Box(0f, 0f, 600f, 600f), direction)
            val before = layout.copy(lines = layout.lines.toList())
            val canvas = object : Canvas(Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)) {
                var characters = 0
                override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
                    characters += text.length
                }
            }
            VerticalTextRenderer.draw(canvas, layout, 0xFF000000.toInt())
            assertEquals(before, layout)
            assertEquals(original, layout.lines.joinToString("") { it.text })
            assertEquals("no glyph dropped during transformation", original.length, canvas.characters)
        }
    }

    /**
     * TCY：一组数字必须**一次**画完（横排、占一格），不能按字符竖着重画。
     * 判据用 drawText 的调用序列 —— Robolectric 画不出字形，但能记录调用。
     */
    @Test
    fun tcy_drawsDigitRunAsSingleHorizontalCall() {
        val layout = LayoutEngine.plan(
            PaintTextMeasurer(), "20年", Box(0f, 0f, 120f, 120f), TextDirection.VERTICAL_RL,
            requestedFontSize = 20f, autoFit = false, align = TextAlign.CENTER,
            trackingRatio = 0f, leadingRatio = 0f, minPaddingPx = 3f
        )
        val calls = mutableListOf<String>()
        val canvas = object : Canvas(Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)) {
            override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
                calls += text
            }
        }
        VerticalTextRenderer.draw(canvas, layout, 0xFF000000.toInt())
        assertEquals("数字组一次画完、其余逐字", listOf("20", "年"), calls)
    }
}
