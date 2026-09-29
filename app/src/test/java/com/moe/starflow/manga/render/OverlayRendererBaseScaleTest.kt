package com.moe.starflow.manga.render

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `OverlayRenderer.renderOverlay(baseScale = …)` 的输出尺寸契约（超分底图的地基）。
 *
 * ⚠️ **只能断言尺寸，不能断言像素**：Robolectric 的 Canvas **既不兑现 `canvas.scale()`，
 * 也不兑现 `drawBitmap(src, srcRect, dstRect, paint)` 的目标矩形**（见 `manga/CLAUDE.md`）。
 * 但尺寸恰好就是这条契约的全部内容 —— 底图 2x 时"输出有多大"决定了坐标映射对不对。
 *
 * 三行真值（`spaceW = original.width / baseScale`，`outW = spaceW * renderScale`）：
 *
 * | 底图 | baseScale | renderScale | 输出 | 含义 |
 * |---|---|---|---|---|
 * | 1x 原图 | 1 | 2 | 2×源图 | 今天的行为（文字超采样） |
 * | **2x 超分图** | **2** | **2** | **= 底图本身** | **超分底图 1:1 零重采样** |
 * | 2x 超分图 | 2 | 1 | = 源图 | 超分图降回源尺寸渲染 |
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayRendererBaseScaleTest {

    private fun src(w: Int = 400, h: Int = 600): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test
    fun originalBase_renderScale2_isTwiceSource() {
        val out = OverlayRenderer.renderOverlay(
            original = src(), regions = emptyList(), renderScale = 2f, baseScale = 1f
        )
        assertEquals(800, out.width)
        assertEquals(1200, out.height)
    }

    @Test
    fun srBase_renderScale2_isExactlyTheBaseItself() {
        // 底图是精确 2x（800x1200），renderScale 也是 2 → 输出必须**正好等于底图尺寸**，
        // 也就是底图 1:1 落上去、零重采样。这条破了 = 超分图会被二次重采样（白掉画质）。
        val out = OverlayRenderer.renderOverlay(
            original = src(800, 1200), regions = emptyList(), renderScale = 2f, baseScale = 2f
        )
        assertEquals(800, out.width)
        assertEquals(1200, out.height)
    }

    @Test
    fun srBase_renderScale1_isSourceSize() {
        val out = OverlayRenderer.renderOverlay(
            original = src(800, 1200), regions = emptyList(), renderScale = 1f, baseScale = 2f
        )
        assertEquals(400, out.width)
        assertEquals(600, out.height)
    }

    @Test
    fun baseScaleDefaultsToIdentity_noRegression() {
        val a = OverlayRenderer.renderOverlay(original = src(), regions = emptyList(), renderScale = 1f)
        assertEquals(400, a.width)
        assertEquals(600, a.height)
    }

    // ---------- 画布几何（纯函数，锁住"叠加层会被放大多少"） ----------

    /**
     * ⚠️ **`fillBase == false` 绝不等于"不用缩放叠加层"**。
     *
     * 超分底图那一路（`baseScale == renderScale == 2`）`outW` 恰好等于底图宽 → 走"直接 copy 底图"
     * 分支，但叠加层仍然必须按 `scale = 2` 放大。早先的实现把 `canvas.scale()` 放进了
     * `if (需要铺底图)` 里 → 这一路漏掉缩放：底图铺满整张，译文却缩在左上角 1/4、位置全错。
     * Robolectric 不兑现 `canvas.scale`，所以这条只能靠几何数据钉住。
     */
    @Test
    fun srBase_scaleIsStillAppliedEvenThoughBaseIsNotRedrawn() {
        val g = solveOverlayCanvas(bitmapW = 800, bitmapH = 1200, baseScale = 2f, renderScale = 2f)
        assertEquals("坐标空间 = 底图 / baseScale", 400, g.spaceW)
        assertEquals(600, g.spaceH)
        assertEquals("输出 = 坐标空间 × renderScale", 800, g.outW)
        assertEquals(1200, g.outH)
        assertFalse("输出正好等于底图 → 不需要重画底图（1:1 零重采样）", g.fillBase)
        assertEquals("但叠加层**仍要**放大 2 倍", 2f, g.scale, 0.0001f)
    }

    @Test
    fun originalBase_needsBaseRedrawAndScale() {
        val g = solveOverlayCanvas(bitmapW = 400, bitmapH = 600, baseScale = 1f, renderScale = 2f)
        assertEquals(400, g.spaceW)
        assertEquals(800, g.outW)
        assertTrue("底图比输出小 → 必须按目标矩形铺满", g.fillBase)
        assertEquals(2f, g.scale, 0.0001f)
    }

    @Test
    fun identityCase_neitherRedrawNorScale() {
        val g = solveOverlayCanvas(bitmapW = 400, bitmapH = 600, baseScale = 1f, renderScale = 1f)
        assertEquals(400, g.outW)
        assertEquals(600, g.outH)
        assertFalse(g.fillBase)
        assertEquals("倍率 1 = 恒等变换", 1f, g.scale, 0.0001f)
    }

    @Test
    fun renderScaleIsClampedToMax() {
        // ×2 是 4 倍像素，防 OOM —— 传 5 也只能到 2
        val g = solveOverlayCanvas(bitmapW = 400, bitmapH = 600, baseScale = 1f, renderScale = 5f)
        assertEquals(2f, g.scale, 0.0001f)
        assertEquals(800, g.outW)
    }

    @Test
    fun srBaseWithRenderScale1_shrinksToSourceSize() {
        val g = solveOverlayCanvas(bitmapW = 800, bitmapH = 1200, baseScale = 2f, renderScale = 1f)
        assertEquals(400, g.outW)
        assertEquals(600, g.outH)
        assertTrue("2x 底图要缩到源尺寸 → 得重画", g.fillBase)
        assertEquals(1f, g.scale, 0.0001f)
    }
}
