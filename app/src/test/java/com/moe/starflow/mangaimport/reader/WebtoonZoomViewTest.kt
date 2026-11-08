package com.moe.starflow.mangaimport.reader

import android.content.Context
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 连续滚动「整列缩放」的行为守卫（[WebtoonZoomView]）。
 *
 * 用户口径：放大后**还要能上下滑**，所以缩放的是**列宽**而不是单页：
 * 子视图宽度 = 视口宽 × zoom，高度恒为一屏（内部仍由 RecyclerView 滚动）。
 *
 * 另一条硬约束（用户报过「每次缩放松手瞬间漫画轻微位移/闪一下」）：
 * 预览 transform 必须保留到**新宽度被应用的同一轮测量**再撤，且「重绑 + 预滚位置」
 * 必须发生在布局**之前** —— 否则中间会有一帧画在旧宽度/旧位置上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebtoonZoomViewTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun host(): Pair<WebtoonZoomView, View> {
        val zoom = WebtoonZoomView(ctx)
        val child = View(ctx)
        zoom.addView(child, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return zoom to child
    }

    private fun layout(view: WebtoonZoomView, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    @Test
    fun atRestFillsTheViewportAndCannotScrollSideways() {
        val (zoom, child) = host()
        layout(zoom, 1000, 2000)

        assertEquals("默认未放大", 1f, zoom.zoom, 0.001f)
        assertEquals("未放大时子视图 = 视口宽", 1000, child.measuredWidth)
        assertEquals("列表高度恒为一屏", 2000, child.measuredHeight)
        assertFalse("未放大时没有横向滚动", zoom.canScrollHorizontally(1))
        assertFalse("未放大时没有横向滚动", zoom.canScrollHorizontally(-1))
    }

    @Test
    fun zoomWidensTheWholeColumnButKeepsTheListOneScreenTall() {
        val (zoom, child) = host()
        layout(zoom, 1000, 2000)

        zoom.setZoom(2f)
        layout(zoom, 1000, 2000)

        assertEquals(2f, zoom.zoom, 0.001f)
        assertEquals("整列按比例变宽", 2000, child.measuredWidth)
        assertEquals("⚠️ 高度必须还是一屏：上下滚动由列表自己处理", 2000, child.measuredHeight)
        assertTrue("放大后可左右拖动看屏幕外部分", zoom.canScrollHorizontally(1))
    }

    @Test
    fun resetZoomReturnsToTheViewportWidthAndScrollsBack() {
        val (zoom, child) = host()
        layout(zoom, 1000, 2000)
        zoom.setZoom(2.5f)
        layout(zoom, 1000, 2000)
        zoom.scrollTo(300, 0)

        zoom.resetZoom()
        layout(zoom, 1000, 2000)

        assertEquals(1f, zoom.zoom, 0.001f)
        assertEquals(1000, child.measuredWidth)
        assertEquals("复位后横向滚动必须归零", 0, zoom.scrollX)
    }

    /**
     * 回归（用户报的"松手瞬间位移/闪一下"）：提交时**不能**先撤预览。
     *
     * 预览画的是「旧宽度 × 缩放比」，提交换的是「新宽度」。撤得太早、而新宽度还没应用，
     * 中间那一帧就会退回旧宽度 → 肉眼看到整页缩一下/挪一下。
     */
    @Test
    fun previewIsKeptUntilTheNewWidthIsApplied() {
        val (zoom, child) = host()
        layout(zoom, 1000, 2000)
        // 模拟捏合过程中的预览：子视图按 1.5 倍绘制
        child.scaleX = 1.5f
        child.scaleY = 1.5f

        zoom.setZoom(2f)
        assertEquals("提交的同一帧里不能先撤预览", 1.5f, child.scaleX, 0.001f)
        assertEquals(1.5f, child.scaleY, 0.001f)

        layout(zoom, 1000, 2000)
        assertEquals("新宽度生效后必须撤掉预览", 1f, child.scaleX, 0.001f)
        assertEquals(1f, child.scaleY, 0.001f)
    }

    /**
     * 回归：宿主必须在**布局前**收到提交（重绑可见页 + 按手指预滚阅读位置），
     * 布局完成后只做译图预热/当前页同步，不能再改滚动位置。
     *
     * 用户口径：缩放中心必须是**手指**（横竖都是），所以锚点要按手指位置取，
     * 且把焦点 y 一起交给宿主（否则纵向只能退回"以视口顶部为中心"）。
     */
    @Test
    fun willCommitRunsBeforeRelayoutAndCommittedRunsAfter() {
        val (zoom, child) = host()
        layout(zoom, 1000, 2000)

        val will = mutableListOf<WillCommit>()
        val done = mutableListOf<Pair<Float, WebtoonZoomView.Anchor?>>()
        var widthAtWill = -1
        var widthAtDone = -1
        var anchorFocusY = -1f
        zoom.anchorProvider = { focusY ->
            anchorFocusY = focusY
            WebtoonZoomView.Anchor(3, 0.25f, 4000)
        }
        zoom.onZoomWillCommit = { z, ratio, focusY, anchor ->
            widthAtWill = child.width
            will += WillCommit(z, ratio, focusY, anchor)
        }
        zoom.onZoomCommitted = { z, anchor ->
            widthAtDone = child.width
            done += z to anchor
        }

        zoom.setZoom(2f)

        assertEquals("布局前必须通知一次", 1, will.size)
        assertEquals(2f, will.single().zoom, 0.001f)
        assertEquals("缩放比 = 新列宽 / 旧列宽（宿主用它推算新阅读位置）", 2f, will.single().heightRatio, 0.001f)
        assertEquals("焦点 y 必须传给宿主（纵向按手指缩放）", 1000f, will.single().focusY, 0.001f)
        assertEquals("锚点要按手指取", 1000f, anchorFocusY, 0.001f)
        assertEquals(1000, widthAtWill)
        assertNotNull("锚点要带页高（布局前推算位置用）", will.single().anchor)
        assertEquals(3, will.single().anchor!!.page)
        assertEquals(0.25f, will.single().anchor!!.fraction, 0.001f)
        assertEquals(4000, will.single().anchor!!.heightPx)
        assertEquals("新宽度生效前不能提前走 committed 分支", 0, done.size)

        layout(zoom, 1000, 2000)

        assertEquals("新布局完成后通知一次", 1, done.size)
        assertEquals(2f, done.single().first, 0.001f)
        assertEquals("committed 时子视图已经是新宽度", 2000, widthAtDone)
    }

    private data class WillCommit(
        val zoom: Float,
        val heightRatio: Float,
        val focusY: Float,
        val anchor: WebtoonZoomView.Anchor?,
    )

    @Test
    fun panIsClampedToTheZoomedContent() {
        val (zoom, _) = host()
        layout(zoom, 1000, 2000)
        zoom.setZoom(2f)
        layout(zoom, 1000, 2000)

        zoom.scrollTo(10_000, 0)
        assertEquals("横向滚动不能超出放大后的内容", 1000, zoom.scrollX)

        zoom.scrollTo(-50, 0)
        assertEquals(0, zoom.scrollX)
    }

    @Test
    fun zoomIsClampedToTheSupportedRange() {
        val (zoom, _) = host()
        layout(zoom, 1000, 2000)

        zoom.setZoom(99f)
        layout(zoom, 1000, 2000)
        assertEquals(WebtoonZoomView.MAX_ZOOM, zoom.zoom, 0.001f)

        zoom.setZoom(0.1f)
        layout(zoom, 1000, 2000)
        assertEquals(WebtoonZoomView.MIN_ZOOM, zoom.zoom, 0.001f)
    }
}
