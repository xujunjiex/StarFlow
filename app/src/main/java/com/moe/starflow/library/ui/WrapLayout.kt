package com.moe.starflow.library.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * 流式换行容器：子 View 按宽度自动换行，用于「标签 chips」排布。
 *
 * 为什么不用 `ChipGroup`：它按 `Chip` 的间距规则处理子项，塞 `TextView` 时换行与间距都不可控；
 * 这里只需要「从左到右铺、放不下就换行」，自己实现更稳（也不引新依赖）。
 */
class WrapLayout(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val available = if (View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
        else (width - paddingLeft - paddingRight).coerceAtLeast(0)
        var lineWidth = 0
        var lineHeight = 0
        var total = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val params = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + params.leftMargin + params.rightMargin
            val childHeight = child.measuredHeight + params.topMargin + params.bottomMargin
            if (lineWidth > 0 && lineWidth + childWidth > available) {
                total += lineHeight
                lineWidth = 0
                lineHeight = 0
            }
            lineWidth += childWidth
            if (childHeight > lineHeight) lineHeight = childHeight
        }
        total += lineHeight
        setMeasuredDimension(width, total + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = (r - l - paddingLeft - paddingRight).coerceAtLeast(0)
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            val params = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + params.leftMargin + params.rightMargin
            if (x > paddingLeft && x - paddingLeft + childWidth > available) {
                x = paddingLeft
                y += lineHeight
                lineHeight = 0
            }
            val left = x + params.leftMargin
            val top = y + params.topMargin
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
            x += childWidth
            val childHeight = child.measuredHeight + params.topMargin + params.bottomMargin
            if (childHeight > lineHeight) lineHeight = childHeight
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = MarginLayoutParams(p)

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams
}
