package com.moe.starflow.manga.render

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
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
}
