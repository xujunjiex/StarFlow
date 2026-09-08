package com.moe.starflow.mangaimport.reader

import android.view.View
import androidx.viewpager2.widget.ViewPager2

/** 翻页动画 2「高级」：景深式（前页滑出缩放+淡出，后页滑入+回缩）。 */
class DepthTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        page.alpha = when {
            position <= 0f -> 1f + position
            position < 1f -> 1f - position
            else -> 0f
        }
        page.translationX = position * -page.width * 0.3f
        page.scaleX = if (position <= 0f) 1f else (1f - position * 0.2f).coerceAtLeast(0f)
        page.scaleY = page.scaleX
        page.cameraDistance = 8000f * page.resources.displayMetrics.density
    }
}

/** 翻页动画 3「仿真」：模拟翻页（绕左边缘 Y 轴旋转，透视角）。 */
class PageTurnTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        page.cameraDistance = 8000f * page.resources.displayMetrics.density
        page.pivotX = 0f
        page.pivotY = page.height / 2f
        page.rotationY = position * 85f
        page.alpha = (1f - kotlin.math.abs(position) * 0.6f).coerceAtLeast(0f)
    }
}