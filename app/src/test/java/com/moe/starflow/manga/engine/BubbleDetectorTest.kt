package com.moe.starflow.manga.engine

import com.moe.starflow.manga.config.MangaModeConfig
import com.moe.starflow.manga.types.TextBlockInfo
import com.moe.starflow.manga.types.TextDirection
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BubbleDetectorTest {

    private val configLR = MangaModeConfig(enabled = true, textDirection = TextDirection.VERTICAL_LR)
    private val configRL = MangaModeConfig(enabled = true, textDirection = TextDirection.VERTICAL_RL)

    /**
     * ML Kit 的 TextBlockInfo.isVertical 永远为 null（OCRBridge.recognizeWithLocation 不设置该字段）。
     * 此时横排块（宽 > 高）必须按宽高推断为 HORIZONTAL，绝不能因为 config.textDirection 是竖排
     * 就被强行标成竖排。回归：修复前 fallback 误写为 config 判断，横排被竖排渲染。
     */
    @Test
    fun detectBubbles_withoutIsVertical_horizontalBlock_staysHorizontal() {
        val block = TextBlockInfo(
            text = "hello world",
            boundingBox = Rect(0, 0, 200, 30),  // 宽200 > 高30 → 横排
            cornerPoints = null,
            isVertical = null                     // ML Kit 场景：不提供方向
        )
        val bubbles = BubbleDetector.detectBubbles(listOf(block), configLR)
        assertEquals(1, bubbles.size)
        assertEquals(TextDirection.HORIZONTAL, bubbles[0].direction)
    }

    /**
     * 适度倾斜的横排文本（漫画常见 ≤30°）：主轴方向判定仍应判横排。
     * 回归：顺序无关主轴判定在 <45° 倾斜时保持横排。
     */
    @Test
    fun detectBubbles_withoutIsVertical_tiltedHorizontalBlock_staysHorizontal() {
        // 200×30 横排矩形绕中心旋转 20°，cornerPoints：TL,TR,BR,BL
        val block = TextBlockInfo(
            text = "tilted horizontal",
            boundingBox = Rect(1, -33, 199, 63),   // AABB 198×96，仍横宽
            cornerPoints = arrayOf(
                android.graphics.Point(11, -33),   // TL
                android.graphics.Point(199, 35),   // TR
                android.graphics.Point(189, 63),   // BR
                android.graphics.Point(1, -5)      // BL
            ),
            isVertical = null
        )
        val bubbles = BubbleDetector.detectBubbles(listOf(block), configLR)
        assertEquals(1, bubbles.size)
        assertEquals(TextDirection.HORIZONTAL, bubbles[0].direction)
    }

    /**
     * 竖排列 + **角点顺序乱序**（复现 ML Kit 实测：文档说 TL,TR,BR,BL，实际顺序不保证）。
     * 高 197 宽 28 的竖排文本块，角点按 [TL,BL,BR,TR] 排列——旧「TL,TR,BR,BL 下标」判定会
     * 把 pt0→pt1（长边=高）当宽 → 误判横排；顺序无关主轴判定必须判竖排。
     */
    @Test
    fun detectBubbles_withoutIsVertical_verticalColumn_reversedCornerOrder_isVertical() {
        val block = TextBlockInfo(
            text = "お風呂に入ってる",
            boundingBox = Rect(380, 489, 408, 686),  // 高197 宽28 → 竖排
            cornerPoints = arrayOf(
                android.graphics.Point(380, 489),   // TL
                android.graphics.Point(380, 686),   // BL（乱序：先在左下）
                android.graphics.Point(408, 686),   // BR
                android.graphics.Point(408, 489)    // TR
            ),
            isVertical = null
        )
        val bubbles = BubbleDetector.detectBubbles(listOf(block), configRL)
        assertEquals(1, bubbles.size)
        assertEquals(TextDirection.VERTICAL_RL, bubbles[0].direction)
    }

    /** cornerPoints 真实边长直接验证：竖长矩形 → 竖排。 */
    @Test
    fun detectBubbles_withoutIsVertical_verticalCornerPoints_isVertical() {
        // 30×200 竖排矩形（未旋转），AABB 也是竖长
        val block = TextBlockInfo(
            text = "縦書き",
            boundingBox = Rect(0, 0, 30, 200),
            cornerPoints = arrayOf(
                android.graphics.Point(0, 0),     // TL
                android.graphics.Point(30, 0),    // TR
                android.graphics.Point(30, 200),  // BR
                android.graphics.Point(0, 200)    // BL
            ),
            isVertical = null
        )
        val bubbles = BubbleDetector.detectBubbles(listOf(block), configRL)
        assertEquals(TextDirection.VERTICAL_RL, bubbles[0].direction)
    }
}