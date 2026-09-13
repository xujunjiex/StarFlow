package com.moe.starflow.mangaimport.reader

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import kotlin.math.round

/**
 * 翻页动画共享状态（锚点/导航进度）。
 */
internal class ReaderAnimationState {
    /** 折线起始位置比例：横=手指纵向比例，竖=手指横向比例（Koto 默认 0.85）。 */
    var foldStartFraction = 0.85f
    /** 锚点页索引：正在被翻离的一页。 */
    var anchorPage = 0
    /** 导航进度 = (滚动标记 - 锚点)，∈[-1,1]。 */
    var navigationProgress = 0f
    /** 是否回翻。 */
    var isBackward = false
}

private fun axisSize(page: View, isVertical: Boolean): Float =
    if (isVertical) page.height.toFloat() else page.width.toFloat()

private fun pageIndex(page: View): Int = (page.tag as? Int) ?: Int.MIN_VALUE

private fun setAxisTranslation(page: View, value: Float, isVertical: Boolean) {
    if (isVertical) {
        page.translationX = 0f
        page.translationY = value
    } else {
        page.translationX = value
        page.translationY = 0f
    }
}

private fun resetPageTransform(page: View, isVertical: Boolean) {
    page.alpha = 1f
    page.pivotX = page.width / 2f
    page.pivotY = page.height / 2f
    page.rotationX = 0f
    page.rotationY = 0f
    page.rotation = 0f
    setAxisTranslation(page, 0f, isVertical)
    page.translationZ = 0f
}

/**
 * 把页面中心钉到可视区中心：量实际布局位置抵消，LTR/RTL/竖排通用（符号无关）。
 */
private fun pinToViewCenter(page: View, isVertical: Boolean) {
    val rv = page.parent as? View
    if (rv == null) {
        setAxisTranslation(page, 0f, isVertical)
        return
    }
    val axisSize = if (isVertical) page.height.toFloat() else page.width.toFloat()
    val viewport = if (isVertical) rv.height.toFloat() else rv.width.toFloat()
    val pageCenter = if (isVertical) page.top + axisSize / 2f else page.left + axisSize / 2f
    setAxisTranslation(page, viewport / 2f - pageCenter, isVertical)
}

private fun foldAlpha(fold: Float): Float =
    if (fold <= 0.92f) 1f else (1f - (fold - 0.92f) / 0.08f).coerceAtLeast(0f)

/** 页面停在整数槽位（静止/吸附）时复位；alpha 恒 1（邻居在屏外不显示，但点跳后立即可见→无黑闪）。 */
private fun settleAtSlot(page: View, pos: Float, isVertical: Boolean): Boolean {
    if (abs(pos - round(pos)) >= 0.001f) return false
    resetPageTransform(page, isVertical)
    (page as? CurlPageView)?.clearFold()
    return true
}

/**
 * 翻页动画 1「无」：绝对无动画的直接切换。转场中间帧只把「当前锚点页」钉在可视区中心
 * （量测式抵消滚动位移），其余页隐藏——点击/滑动的拖拽与吸附过程视觉完全静止，无任何
 * 滑动/翻页动作，页面只在吸附到整数槽位那一刻瞬间切换（无动画的真正语义）。
 */
internal class NoneTransformer(
    private val isVertical: Boolean,
    private val state: ReaderAnimationState,
) : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        if (settleAtSlot(page, position, isVertical)) return
        if (pageIndex(page) == state.anchorPage) {
            // 当前页：钉在可视区中心，滚动过程中保持不动
            page.alpha = 1f
            pinToViewCenter(page, isVertical)
        } else {
            // 目标/残影页：隐藏，避免中间帧重叠或滑动感
            page.alpha = 0f
            setAxisTranslation(page, 0f, isVertical)
        }
    }
}

/**
 * 翻页动画 2「高级」（封面叠放）：复刻 Kototoro resolveCoverPageTransform。
 */
internal class CoverTransformer(
    private val isVertical: Boolean,
    private val isReversed: Boolean,
    private val state: ReaderAnimationState,
) : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        if (settleAtSlot(page, position, isVertical)) return
        val progress = state.navigationProgress
        if (abs(progress) < 0.001f) {
            resetPageTransform(page, isVertical)
            return
        }
        val size = axisSize(page, isVertical)
        val idx = pageIndex(page)
        val anchor = state.anchorPage
        val isFwd = progress > 0f
        val dir = if (isReversed) -1f else 1f
        val incoming = if (isFwd) anchor + 1 else anchor - 1
        val kotoOffset = (anchor - idx).toFloat() + progress
        page.alpha = 1f
        when {
            idx == anchor && isFwd -> { page.translationZ = 1f; setAxisTranslation(page, 0f, isVertical) }
            idx == anchor && !isFwd -> { page.translationZ = 0f; setAxisTranslation(page, kotoOffset * dir * size, isVertical) }
            idx == incoming && !isFwd -> { page.translationZ = 1f; setAxisTranslation(page, 0f, isVertical) }
            idx == incoming -> { page.translationZ = 0f; setAxisTranslation(page, kotoOffset * dir * size, isVertical) }
            else -> { page.translationZ = -1f; setAxisTranslation(page, 0f, isVertical) }
        }
    }
}

/**
 * 翻页动画 3「仿真」（页脚卷曲）：复刻 Kototoro resolveSimulationPageTransform（镜像背页、倾斜折痕）。
 */
internal class SimulationTransformer(
    private val isVertical: Boolean,
    private val isReversed: Boolean,
    private val state: ReaderAnimationState,
) : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        if (settleAtSlot(page, position, isVertical)) return
        val pos = position
        pinToViewCenter(page, isVertical)

        val idx = pageIndex(page)
        val isTurning = idx == state.anchorPage
        val fold = if (isTurning) abs(pos).coerceIn(0f, 1f) else 0f

        page.alpha = if (pos in -1f..1f) foldAlpha(fold) else 0f
        page.translationZ = if (isTurning) 1f else 0f

        val effReversed = if (isVertical) state.isBackward else (isReversed != state.isBackward)

        (page as? CurlPageView)?.setFold(
            progress = fold,
            start = state.foldStartFraction,
            vertical = isVertical,
            reversed = effReversed,
        )
    }
}