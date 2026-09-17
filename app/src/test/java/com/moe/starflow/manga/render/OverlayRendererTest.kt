package com.moe.starflow.manga.render

import com.moe.starflow.manga.types.*
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * OverlayRenderer 渲染回归测试。
 * 重点：非自动大字号时扩展 drawRect 不得侵入相邻气泡区域（intrudesOtherBubble），
 * 否则白色背景块会相互覆盖，气泡间露出原图的间隙被吃掉。
 */
@RunWith(RobolectricTestRunner::class)
class OverlayRendererTest {

    private fun solidBitmap(w: Int, h: Int, color: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }

    @Test
    fun largeFontNonAuto_overlappingBubbles_mergeWithSeparator() {
        // 原图绿色背景，两个水平相邻气泡（间隙 x∈(80,90)），大字号 + VERTICAL_LR。
        // 非自动大字号：文字超出气泡扩展后 neededRect 重叠 → 合并成一个白块，
        // 组内用记号 ◇ 分隔（不截断、白块不叠白块）。
        val green = Color.rgb(0, 255, 0)
        val bitmap = solidBitmap(200, 100, green)

        val bubbleA = TranslatedBubble(
            rect = Rect(10, 20, 80, 80),
            originalText = "あああああ",
            translatedText = "いいいいい",
            backgroundColor = Color.WHITE,
            fontSize = 40f,
            direction = TextDirection.VERTICAL_LR
        )
        val bubbleB = TranslatedBubble(
            rect = Rect(90, 20, 160, 80),
            originalText = "ううううう",
            translatedText = "えええええ",
            backgroundColor = Color.WHITE,
            fontSize = 40f,
            direction = TextDirection.VERTICAL_LR
        )

        val out = OverlayRenderer.renderOverlay(
            original = bitmap,
            regions = listOf(bubbleA, bubbleB),
            fontSize = 40f,
            autoFit = false,
            textColor = Color.BLACK,
            bgColor = Color.WHITE
        )

        // 合并白块覆盖扩展区域与两气泡之间的间隙（合并成一个白块，文字完整不截断）
        assertNotEquals("合并白块覆盖扩展区域（不截断）", green, out.getPixel(150, 50))
        assertNotEquals("相邻气泡合并成一个白块（间隙被覆盖）", green, out.getPixel(85, 50))
        bitmap.recycle()
        out.recycle()
    }

    /**
     * ⚠️ 用户要求：**非自动模式也要应用用户填的字间距/行间距**（不只是自动模式）。
     *
     * 用较小字号让白块必然收缩在气泡内，然后断言「设了字距的白块更宽」——
     * 若间距被忽略，两次结果会一模一样（旧实现正是如此）。
     */
    @Test
    fun nonAuto_spacingSettingsAffectDrawRect() {
        val green = Color.rgb(0, 255, 0)
        fun render(tracking: Float): Bitmap {
            val bmp = solidBitmap(200, 200, green)
            val bubble = TranslatedBubble(
                rect = Rect(20, 20, 180, 180),
                originalText = "ああ",
                translatedText = "いいい",   // 3 字，留出字距空间
                backgroundColor = Color.WHITE,
                fontSize = 20f,             // 小字号 → 白块收缩在气泡内
                direction = TextDirection.HORIZONTAL
            )
            val out = OverlayRenderer.renderOverlay(
                original = bmp, regions = listOf(bubble),
                fontSize = 20f, autoFit = false,
                textColor = Color.BLACK, bgColor = Color.WHITE,
                trackingRatio = tracking, density = 1f
            )
            bmp.recycle()
            return out
        }

        val noSpacing = render(0f)
        val withSpacing = render(1f)

        // 沿白块中线量白色像素宽度（白底 + 黑字；只数纯白像素避免被字形干扰）
        fun whiteRun(b: Bitmap): Int {
            val y = b.height / 2
            var count = 0
            for (x in 0 until b.width) if (b.getPixel(x, y) == Color.WHITE) count++
            return count
        }
        val w0 = whiteRun(noSpacing)
        val w1 = whiteRun(withSpacing)
        assertTrue("设了字距的白块应更宽（$w0 → $w1），否则说明间距没生效", w1 > w0)

        noSpacing.recycle()
        withSpacing.recycle()
    }

    @Test
    fun smallFontNonAuto_compactRectCenteredInsideBubble() {        // 小字号非自动：drawRect 收缩居中，气泡内左边缘应露出原图（无大片空白），
        // 且中心点附近为文字背景（白底）。这里验证不崩溃 + 尺寸正确 + 气泡外像素不变。
        val green = Color.rgb(0, 255, 0)
        val bitmap = solidBitmap(100, 100, green)

        val bubble = TranslatedBubble(
            rect = Rect(20, 20, 80, 80),
            originalText = "あ",
            translatedText = "い",
            backgroundColor = Color.WHITE,
            fontSize = 10f,
            direction = TextDirection.VERTICAL_LR
        )

        val out = OverlayRenderer.renderOverlay(
            original = bitmap,
            regions = listOf(bubble),
            fontSize = 10f,
            autoFit = false,
            textColor = Color.BLACK,
            bgColor = Color.WHITE
        )

        assertEquals(100, out.width)
        assertEquals(100, out.height)
        // 气泡外（左上角）保持原图
        assertEquals(green, out.getPixel(5, 5))
        bitmap.recycle()
        out.recycle()
    }
}
