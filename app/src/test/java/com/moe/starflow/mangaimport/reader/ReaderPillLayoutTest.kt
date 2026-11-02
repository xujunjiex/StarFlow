package com.moe.starflow.mangaimport.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶部胶囊宽度预算（0.12.3 真机反馈：章节名过长遮挡左右按钮）。
 *
 * 口径：两侧必须让出「圆钮 40dp + 外侧 10dp + 间隙 14dp」，胶囊自身还有 12dp×2 内边距；
 * 章名只吃剩余宽度，页码不参与省略。
 */
class ReaderPillLayoutTest {

    @Test
    fun `1080px phone reserves both sides for the round buttons`() {
        // 360dp 宽 @3x：每侧 64dp = 192px，内边距 72px
        assertEquals(624, ReaderPillLayout.maxPillWidthPx(1080, 3f))
    }

    @Test
    fun `capsule never overlaps the buttons`() {
        val density = 3f
        val screen = 1080
        val pill = ReaderPillLayout.maxPillWidthPx(screen, density)
        // 胶囊左右各留 12dp 内边距（包含在 maxPill 内），外侧按钮 + 间隙合计每侧 64dp
        val outer = (2 * (ReaderPillLayout.BUTTON_WIDTH_DP + ReaderPillLayout.BUTTON_MARGIN_DP +
            ReaderPillLayout.BUTTON_GAP_DP) * density).toInt()
        assertTrue("胶囊 + 两侧让位必须装得进屏幕", pill + outer <= screen)
        assertTrue("至少留下 14dp 间隙", screen - pill - outer >= (14 * density).toInt())
    }

    @Test
    fun `title budget subtracts the page counter and paddings`() {
        assertEquals(462f, ReaderPillLayout.titleBudgetPx(624, 90f, 36, 36), 0.01f)
    }

    @Test
    fun `extreme narrow screens keep a minimum pill width`() {
        // 200px @3x 连两侧让位都不够 → 收敛到 96dp 下限，而不是负数
        assertEquals((96 * 3), ReaderPillLayout.maxPillWidthPx(200, 3f))
        assertTrue(ReaderPillLayout.titleBudgetPx(288, 500f, 36, 36) >= 1f)
    }
}
