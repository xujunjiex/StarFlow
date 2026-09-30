package com.moe.starflow.sr

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 大图超分前的压缩计划（用户口径 2026-10：「对较大尺寸的图片进行超分，短边压缩对齐到 1080p
 * 再超分……减轻超分模型的压力，同时可以快速通过超分模型放大尺寸」）。
 *
 * ⚠️ Robolectric 的 Canvas **不真正栅格化**（见 `manga/CLAUDE.md` 的同名条目），
 * 所以这里只断言**尺寸与所有权**，不断言像素内容 —— 像素质量只能真机看。
 * 尺寸恰恰是这块功能唯一需要正确的东西（错一步就是 OOM 或白算）。
 */
@RunWith(RobolectricTestRunner::class)
class SrDownscaleTest {

    private fun bmp(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test
    fun smallPageNeedsNoDownscale() {
        assertNull("短边 912、0.58MP → 两个约束都满足，走原路（普通漫画页零行为变化）",
            SrDownscale.plan(632, 912))
        assertNull("短边正好 1080、1.75MP 也吃得下（引擎上限 2.5MP）", SrDownscale.plan(1080, 1620))
        assertNull("非法尺寸不压", SrDownscale.plan(0, 100))
        assertNull("非法尺寸不压（负）", SrDownscale.plan(-5, 100))
    }

    /**
     * ⚠️ 这条是**实测出来的设计依据**：`NcnnSrEngine.maxInputPixels = 10MP / scale²`（2x → 2.5MP）。
     * 2000x3000 = 6MP **今天就已经超限**，引擎直接判「图太大」——用户看到的"图太大无法超分"就是它。
     */
    @Test
    fun aPageThatWouldBlowTheEngineCapGetsCompressed() {
        val p = SrDownscale.plan(2000, 3000)!!
        assertEquals("短边（宽）对齐到 1080", 1080, p.width)
        assertEquals("长边等比缩放", 1620, p.height)
        assertTrue(
            "压缩后必须落进引擎的 2.5MP 输入上限，否则超分照样出不来",
            p.width.toLong() * p.height <= SrDownscale.DEFAULT_MAX_FEED_PIXELS,
        )
        // 横过来：按高对齐
        val q = SrDownscale.plan(3000, 2000)!!
        assertEquals(1620, q.width)
        assertEquals(1080, q.height)
    }

    /** 短边本来就 ≤ 1080、但像素数仍然超上限的长条：**要按上限继续缩**，不能只看短边。 */
    @Test
    fun pixelCapStillAppliesWhenTheShortSideIsAlreadySmall() {
        val p = SrDownscale.plan(1080, 2592)!!          // 2.8MP > 2.5MP
        assertTrue(
            "只对齐短边是不够的：2.8MP 仍然超引擎上限",
            p.width.toLong() * p.height <= SrDownscale.DEFAULT_MAX_FEED_PIXELS,
        )
        assertTrue("长宽比要保住", p.width < 1080 && p.height < 2592)
    }

    /** Real-ESRGAN 是 4x，上限只有 0.625MP —— 同一个 plan 要按引擎上限给出不同的结果。 */
    @Test
    fun thePlanFollowsTheEngineSpecificCap() {
        val p = SrDownscale.plan(2000, 3000, 625_000L)!!
        assertTrue(
            "4x 引擎的 0.625MP 上限也要满足（否则 Real-ESRGAN 永远报图太大）",
            p.width.toLong() * p.height <= 625_000L,
        )
        assertTrue("比 2x 引擎压得更狠", p.width < 1080)
    }

    @Test
    fun extremeAspectIsStillBroughtUnderThePixelCap() {
        // 20000x300 的短边只有 300（不触发"短边 1080"那一条），但 6MP 超了引擎的 2.5MP 上限 ——
        // 所以**仍然要压**，只是压的依据换成像素上限。压出来还是 66:1 的长条，但吃得下。
        val p = SrDownscale.plan(20000, 300)!!
        assertTrue(
            "超过引擎上限就必须压（哪怕短边很小）",
            p.width.toLong() * p.height <= SrDownscale.DEFAULT_MAX_FEED_PIXELS,
        )
        assertTrue("长宽比要保住（不能被压成正方形）", p.width > p.height * 50)
    }

    @Test
    fun clampOnlyKicksInWhenOutputExceedsTheOriginalPixelCount() {
        // 原图 2000x3000 = 6.0MP；产物 2160x3240 = 7.0MP → 必须收敛
        assertTrue(SrDownscale.needsClamp(2160, 3240, 2000, 3000))
        // 产物比原图小/相等 → 不收敛（不做无谓的一次缩放）
        assertFalse(SrDownscale.needsClamp(1999, 2999, 2000, 3000))
        assertFalse(SrDownscale.needsClamp(2000, 3000, 2000, 3000))
        // 原图很小、产物很大 → 收敛
        assertTrue(SrDownscale.needsClamp(4000, 6000, 1000, 1500))
    }

    @Test
    fun applyProducesExactlyThePlannedSizeAndNeverReturnsTheSource() {
        val src = bmp(2000, 3000)
        val plan = SrDownscale.plan(2000, 3000)!!
        val out = SrDownscale.apply(src, plan)
        assertEquals(plan.width, out.width)
        assertEquals(plan.height, out.height)
        assertTrue("压过就必须是另一张图（调用方靠 !== 决定要不要回收源图）", out !== src)
        assertFalse("结果不能是被回收的", out.isRecycled)
    }

    /** 分步路径（倍率 > 2 时先减半）也要落到计划尺寸，且中间产物不能泄漏成"已回收的结果"。 */
    @Test
    fun applyHandlesAMultiStepDownscale() {
        val src = bmp(4000, 3000)
        val plan = SrDownscale.plan(4000, 3000)!!
        assertEquals(1440, plan.width)
        assertEquals(1080, plan.height)
        val out = SrDownscale.apply(src, plan)
        assertEquals(plan.width, out.width)
        assertEquals(plan.height, out.height)
        assertFalse(out.isRecycled)
    }

    @Test
    fun clampBringsTheProductUnderTheOriginalPixelCount() {
        val out = bmp(2160, 3240)
        val clamped = SrDownscale.clampToOriginalPixels(out, 2000, 3000)
        assertNotNull("超过原图像素数时必须收敛", clamped)
        assertTrue(
            "收敛后像素数必须 ≤ 原图（用户口径：体积不能超过原来的像素和大小）",
            clamped!!.width.toLong() * clamped.height <= 2000L * 3000L,
        )
        assertTrue(clamped.width > 0 && clamped.height > 0)
    }

    @Test
    fun clampReturnsNullWhenNothingToDo() {
        assertNull("产物本来就比原图小 → 不收敛（少一次缩放）", SrDownscale.clampToOriginalPixels(bmp(100, 150), 2000, 3000))
        assertNull("相等也不收敛", SrDownscale.clampToOriginalPixels(bmp(2000, 3000), 2000, 3000))
    }
}
