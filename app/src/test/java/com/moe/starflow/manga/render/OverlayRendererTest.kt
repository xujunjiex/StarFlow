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
     * ⚠️ 用户要求：译文替换表必须**在渲染时**套用（overlay 是后期渲染到原图上的），
     * 所以「加一条规则」不该逼用户重新翻译 —— 重新渲染当前页就行。
     *
     * 用「把整条译文替换成空串」做判据：命中后该气泡没有文字可画，白块也不会画 →
     * 输出应与输入**逐像素一致**。规则若没进渲染层，这里会画出一块白色（旧实现就是如此）。
     */
    @Test
    fun replacementRules_areAppliedAtRenderTime() {
        val green = Color.rgb(0, 255, 0)
        val bitmap = solidBitmap(200, 100, green)
        val bubble = TranslatedBubble(
            rect = Rect(10, 20, 180, 80),
            originalText = "……",
            translatedText = "...",
            backgroundColor = Color.WHITE,
            fontSize = 30f,
            direction = TextDirection.HORIZONTAL
        )

        val plain = OverlayRenderer.renderOverlay(
            original = bitmap, regions = listOf(bubble),
            fontSize = 30f, autoFit = false, bgColor = Color.WHITE
        )
        assertNotEquals("无规则时应画白块", green, plain.getPixel(95, 50))

        val ruled = OverlayRenderer.renderOverlay(
            original = bitmap, regions = listOf(bubble),
            fontSize = 30f, autoFit = false, bgColor = Color.WHITE,
            replacementRules = listOf(
                com.moe.starflow.manga.config.ReplacementRule("...", "")
            )
        )
        assertEquals("规则命中（译文变空）后不该再画任何东西", green, ruled.getPixel(95, 50))
        assertEquals("整图应与输入一致", green, ruled.getPixel(20, 30))

        bitmap.recycle()
        plain.recycle()
        ruled.recycle()
    }

    /** 替换表**只**作用于译文：三态切到「原文」时不能把用户规则套在识别原文上。 */
    @Test
    fun replacementRules_doNotTouchOriginalTextMode() {
        val green = Color.rgb(0, 255, 0)
        val bitmap = solidBitmap(200, 100, green)
        val bubble = TranslatedBubble(
            rect = Rect(10, 20, 180, 80),
            originalText = "...",
            translatedText = "...",
            backgroundColor = Color.WHITE,
            fontSize = 30f,
            direction = TextDirection.HORIZONTAL
        )

        // 原文模式 + 规则「...→空」：若规则误作用到原文，这里就什么都不画了
        val out = OverlayRenderer.renderOverlay(
            original = bitmap, regions = listOf(bubble),
            fontSize = 30f, autoFit = false, bgColor = Color.WHITE,
            useOriginalText = true,
            replacementRules = listOf(com.moe.starflow.manga.config.ReplacementRule("...", ""))
        )

        assertNotEquals(
            "原文模式必须原样画出识别到的原文（规则只作用于译文）",
            green, out.getPixel(95, 50)
        )
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

    /**
     * ⚠️ 用户要求：**横排对齐在非自动字号下也必须生效**（RT-DETR 选「横排渲染」时最明显：
     * 它的选区是宽矩形气泡框，文字通常远窄于框，对齐是唯一的水平位置控制）。
     *
     * 非自动模式的白块收缩到文字本身 —— 若白块恒居中，对齐设置在这条路径上等于失效。
     * 判据用**白块位置**（Robolectric 能栅格化矩形、画不出字形，正好够用）：
     * 沿气泡中线取白色像素区间，左/中/右三种对齐的白块中心必须依次右移。
     */
    @Test
    fun nonAuto_horizontalAlign_movesWhiteBlock() {
        val green = Color.rgb(0, 255, 0)

        fun blockCenterX(align: TextAlign): Float {
            val bmp = solidBitmap(200, 100, green)
            val bubble = TranslatedBubble(
                rect = Rect(20, 20, 180, 80),
                originalText = "",
                translatedText = "译文",          // 短文本 → 白块必然装得进气泡
                backgroundColor = Color.WHITE,
                fontSize = 12f,                  // 非自动模式：字号由用户定 → 白块收缩
                direction = TextDirection.HORIZONTAL,   // RT 选「横排渲染」时就是这个方向
            )
            val out = OverlayRenderer.renderOverlay(
                original = bmp, regions = listOf(bubble),
                fontSize = 12f, autoFit = false,
                textColor = Color.BLACK, bgColor = Color.WHITE,
                align = align, density = 1f
            )
            val y = 50
            var first = -1
            var last = -1
            for (x in 0 until out.width) {
                if (out.getPixel(x, y) == Color.WHITE) {
                    if (first < 0) first = x
                    last = x
                }
            }
            bmp.recycle()
            out.recycle()
            if (first < 0) return -1f
            return (first + last) / 2f
        }

        val left = blockCenterX(TextAlign.LEFT)
        val center = blockCenterX(TextAlign.CENTER)
        val right = blockCenterX(TextAlign.RIGHT)

        assertTrue("三种对齐都应画出白块（left=$left center=$center right=$right）", left > 0f && center > 0f && right > 0f)
        assertTrue("左对齐白块应靠左（$left < $center）", left < center - 5f)
        assertTrue("右对齐白块应靠右（$right > $center）", right > center + 5f)
        // 居中必须落在中间：顺带防住「左/右写反」也能通过的情况
        assertTrue("居中应在左右之间（$left < $center < $right）", left < center && center < right)
    }

    /**
     * ⚠️ 用户要求：**「竖排方向 / RT 渲染方向」在自动字号关闭时也必须生效**。
     *
     * 判据用白块尺寸（Robolectric 能画矩形、画不出字形）：
     * 同一段文字，**竖排白块必须显著高于横排白块** —— 竖排把字往一列里堆（越长越高），
     * 横排摊成一行（长的是宽）。方向若没进排版，两者会一模一样。
     *
     * ⚠️ **不要用"横排宽 > 高 / 竖排高 > 宽"这种绝对形状断言**：Robolectric 对 CJK 的
     * `Paint.measureText` 不准（实测 6 个汉字 ≈ 6px，横排白块反而变成 12×20 的高块），
     * 绝对形状会被这个假度量带偏。相对比较（竖 vs 横）不受影响。
     */
    @Test
    fun nonAuto_directionChangesBlockSize() {
        val green = Color.rgb(0, 255, 0)

        /** 返回白块尺寸 (w, h)；无白块返回 (0,0)。 */
        fun blockSize(direction: TextDirection): Pair<Int, Int> {
            val bmp = solidBitmap(200, 200, green)
            val bubble = TranslatedBubble(
                rect = Rect(40, 40, 160, 160),
                originalText = "",
                translatedText = "译文内容测试",       // 足够长 → 一列堆起来明显更高
                backgroundColor = Color.WHITE,
                fontSize = 14f,
                direction = direction,
            )
            val out = OverlayRenderer.renderOverlay(
                original = bmp, regions = listOf(bubble),
                fontSize = 14f, autoFit = false,          // 自动字号关闭
                textColor = Color.BLACK, bgColor = Color.WHITE, density = 1f
            )
            var minX = Int.MAX_VALUE; var maxX = -1
            var minY = Int.MAX_VALUE; var maxY = -1
            for (y in 0 until out.height) {
                for (x in 0 until out.width) {
                    if (out.getPixel(x, y) == Color.WHITE) {
                        if (x < minX) minX = x; if (x > maxX) maxX = x
                        if (y < minY) minY = y; if (y > maxY) maxY = y
                    }
                }
            }
            bmp.recycle()
            out.recycle()
            return if (maxX < 0) (0 to 0) else ((maxX - minX + 1) to (maxY - minY + 1))
        }

        val (hw, hh) = blockSize(TextDirection.HORIZONTAL)
        val (vw, vh) = blockSize(TextDirection.VERTICAL_RL)

        assertTrue("横排必须画出白块（$hw x $hh）", hw > 0 && hh > 0)
        assertTrue("竖排必须画出白块（$vw x $vh）", vw > 0 && vh > 0)
        assertTrue(
            "竖排白块应显著高于横排（竖 $vw x $vh vs 横 $hw x $hh）—— 否则方向没进排版",
            vh > hh * 2
        )
    }

    /**
     * ⚠️ 用户要求：**行间距在自动字号关闭时也必须生效**（`Manga_Auto_Font_Size` 关掉后
     * 「间距调整」里的行间距不能失效）。
     *
     * 判据：同一个两行文本（用 `\n` 强制两行 —— Robolectric 的 `Paint.breakText` 不按宽度换行），
     * 行间距 0 → 行间距 1×字号，白块**高度必须变大**。两次一样就说明间距没进排版。
     */
    @Test
    fun nonAuto_leadingRatio_growsBlockHeight() {
        val green = Color.rgb(0, 255, 0)

        fun blockHeight(leading: Float): Int {
            val bmp = solidBitmap(200, 200, green)
            val bubble = TranslatedBubble(
                rect = Rect(40, 40, 160, 160),
                originalText = "",
                translatedText = "译文\n两行",
                backgroundColor = Color.WHITE,
                fontSize = 14f,
                direction = TextDirection.HORIZONTAL,
            )
            val out = OverlayRenderer.renderOverlay(
                original = bmp, regions = listOf(bubble),
                fontSize = 14f, autoFit = false,          // 自动字号关闭
                textColor = Color.BLACK, bgColor = Color.WHITE,
                align = TextAlign.CENTER,
                leadingRatio = leading, density = 1f
            )
            var minY = Int.MAX_VALUE; var maxY = -1
            for (y in 0 until out.height) {
                for (x in 0 until out.width) {
                    if (out.getPixel(x, y) == Color.WHITE) {
                        if (y < minY) minY = y; if (y > maxY) maxY = y
                    }
                }
            }
            bmp.recycle()
            out.recycle()
            return if (maxY < 0) 0 else maxY - minY + 1
        }

        val tight = blockHeight(0f)
        val loose = blockHeight(1f)
        assertTrue("必须画出白块（$tight）", tight > 0)
        assertTrue("行间距调大后白块应变高（$tight → $loose）", loose > tight)
    }

    /**
     * 竖排方向覆盖是**纯函数**，单独钉住它的两条语义：
     * 只改写竖排（横排不动）、null = 不改。它发生在排版之前，与字号模式无关 ——
     * 所以「自动字号关闭时竖排方向失效」不可能出在这里，只可能出在排版/绘制入口。
     */
    @Test
    fun verticalDirectionOverride_onlyRewritesVertical() {
        fun bubble(dir: TextDirection) = TranslatedBubble(
            rect = Rect(0, 0, 10, 10),
            originalText = "a", translatedText = "b",
            backgroundColor = Color.WHITE, fontSize = 12f, direction = dir,
        )
        val stored = listOf(
            bubble(TextDirection.VERTICAL_RL),
            bubble(TextDirection.VERTICAL_LR),
            bubble(TextDirection.HORIZONTAL),
        )

        val toLr = OverlayRenderer.applyVerticalDirectionOverride(stored, TextDirection.VERTICAL_LR)
        assertEquals(TextDirection.VERTICAL_LR, toLr[0].direction)
        assertEquals(TextDirection.VERTICAL_LR, toLr[1].direction)
        assertEquals("横排不能被改写", TextDirection.HORIZONTAL, toLr[2].direction)

        val toRl = OverlayRenderer.applyVerticalDirectionOverride(stored, TextDirection.VERTICAL_RL)
        assertEquals(TextDirection.VERTICAL_RL, toRl[0].direction)
        assertEquals(TextDirection.VERTICAL_RL, toRl[1].direction)

        val untouched = OverlayRenderer.applyVerticalDirectionOverride(stored, null)
        assertEquals(TextDirection.VERTICAL_RL, untouched[0].direction)
        assertEquals(TextDirection.VERTICAL_LR, untouched[1].direction)
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
