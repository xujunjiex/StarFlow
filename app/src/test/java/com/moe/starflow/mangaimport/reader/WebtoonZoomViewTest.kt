package com.moe.starflow.mangaimport.reader

import android.content.Context
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 这里钉住测量口径与提交回调，避免以后改回"锁住列表去缩放单页"。
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
        zoom.viewTreeObserver.dispatchOnGlobalLayout()

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
        zoom.viewTreeObserver.dispatchOnGlobalLayout()
        zoom.scrollTo(300, 0)

        zoom.resetZoom()
        layout(zoom, 1000, 2000)

        assertEquals(1f, zoom.zoom, 0.001f)
        assertEquals(1000, child.measuredWidth)
        assertEquals("复位后横向滚动必须归零", 0, zoom.scrollX)
    }

    @Test
    fun commitReportsTheNewZoomAndAnchorOnce() {
        val (zoom, _) = host()
        layout(zoom, 1000, 2000)
        val reported = mutableListOf<Triple<Float, Int, Float>>()
        zoom.anchorProvider = { 3 to 0.25f }
        zoom.onZoomCommitted = { z, page, fraction -> reported += Triple(z, page, fraction) }

        zoom.setZoom(2f)
        layout(zoom, 1000, 2000)
        zoom.viewTreeObserver.dispatchOnGlobalLayout()

        assertEquals("一次提交只通知一次（宿主据此重绑/重渲/恢复位置）", 1, reported.size)
        assertEquals(2f, reported.single().first, 0.001f)
        assertEquals(3, reported.single().second)
        assertEquals(0.25f, reported.single().third, 0.001f)
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
