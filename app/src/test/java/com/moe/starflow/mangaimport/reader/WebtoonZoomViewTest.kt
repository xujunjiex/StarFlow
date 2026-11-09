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
 * 两条硬约束（都是用户报过的）：
 * 1. **不做 transform 预览**：捏合期间直接用新列宽逐帧重排。用 `scaleX/scaleY` 预览时，
 *    缩小会让整列被画小、屏幕上下露出背景（"上下黑屏"），松手才恢复。
 * 2. **缩放中心 = 手指**：每一帧都按 `focusY` 取锚点、按当前布局的倍率算比例。
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

    private class Preview(
        val zoom: Float,
        val heightRatio: Float,
        val focusY: Float,
        val anchor: WebtoonZoomView.Anchor?,
    )

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

    /**
     * 回归（用户报的「缩小的时候上下黑屏，松手才显示」）：捏合必须**直接改列宽**，
     * 不能用 `scaleX/scaleY` 预览 —— transform 缩小时整列被画小，屏幕上下就是背景。
     */
    @Test
    fun liveZoomWidensTheColumnWithoutAnyTransform() {
        val (zoom, child) = host()
        layout(zoom, 1000, 2000)
        val previews = mutableListOf<Preview>()
        var committed = 0
        zoom.anchorProvider = { WebtoonZoomView.Anchor(0, 0f, 2000) }
        zoom.onZoomPreview = { z, ratio, focusY, anchor -> previews += Preview(z, ratio, focusY, anchor) }
        zoom.onZoomCommitted = { committed++ }

        zoom.setZoom(2f)

        assertEquals("布局前必须先算好新行高/位置", 1, previews.size)
        assertEquals(2f, previews.single().zoom, 0.001f)
        assertEquals("倍率按当前布局算", 2f, previews.single().heightRatio, 0.001f)
        assertEquals("焦点 y 必须传给宿主（纵向按手指缩放）", 1000f, previews.single().focusY, 0.001f)
        assertNotNull(previews.single().anchor)
        assertEquals("缩放过程中不能等布局完再通知", 0, committed)

        layout(zoom, 1000, 2000)

        assertEquals("整列按比例变宽", 2000, child.measuredWidth)
        assertEquals("⚠️ 高度必须还是一屏：上下滚动由列表自己处理", 2000, child.measuredHeight)
        assertEquals("整个缩放过程不使用 transform（否则会有黑边）", 1f, child.scaleX, 0.001f)
        assertEquals(1f, child.scaleY, 0.001f)
        assertTrue("放大后可左右拖动看屏幕外部分", zoom.canScrollHorizontally(1))
        assertEquals("焦点在正中 → 布局后横向居中对齐", 500, zoom.scrollX)
        assertEquals("布局完成后通知一次（宿主据此重绑/预热）", 1, committed)
    }

    @Test
    fun heightRatioIsMeasuredFromTheCurrentLayoutNotFromTheTarget() {
        val (zoom, _) = host()
        layout(zoom, 1000, 2000)
        zoom.anchorProvider = { WebtoonZoomView.Anchor(0, 0f, 2000) }
        zoom.setZoom(2f)
        layout(zoom, 1000, 2000)

        var ratio = -1f
        var target = -1f
        zoom.onZoomPreview = { z, r, _, _ -> target = z; ratio = r }

        zoom.setZoom(1.5f)

        assertEquals(1.5f, target, 0.001f)
        assertEquals("连续缩放时要相对「当前布局的倍率」，否则会漏掉同一帧里的增量", 0.75f, ratio, 0.001f)
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
