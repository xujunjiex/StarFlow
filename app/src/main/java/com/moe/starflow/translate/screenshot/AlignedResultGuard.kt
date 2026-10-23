package com.moe.starflow.translate.screenshot

import android.view.View
import android.view.ViewTreeObserver
import com.moe.starflow.utils.DisplaySize

/** 在绘制前检查物理像素与原点，不允许 FIT_XY 用缩放掩盖几何错误。 */
class AlignedResultGuard(
    private val view: View,
    private val mapping: ScreenshotMapping,
    private val onInvalid: () -> Unit
) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    private var invalid = false

    init {
        view.viewTreeObserver.addOnPreDrawListener(this)
        view.addOnAttachStateChangeListener(this)
    }

    override fun onPreDraw(): Boolean {
        if (invalid) return false
        val origin = IntArray(2)
        view.getLocationOnScreen(origin)
        if (DisplaySize.geometry(view.context) == mapping.display &&
            mapping.matchesWindow(view.width, view.height, origin[0], origin[1])) return true
        invalid = true
        com.moe.starflow.utils.LogCollector.w("ResultGeometry", "拒绝错位绘制: expected=$mapping actual=${view.width}x${view.height}@(${origin[0]},${origin[1]}) display=${DisplaySize.geometry(view.context)}")
        // 不在 WMS 正在遍历的调用栈中移除窗口。
        view.post { onInvalid() }
        return false
    }

    override fun onViewAttachedToWindow(v: View) = Unit
    override fun onViewDetachedFromWindow(v: View) {
        if (v.viewTreeObserver.isAlive) v.viewTreeObserver.removeOnPreDrawListener(this)
        v.removeOnAttachStateChangeListener(this)
    }
}
