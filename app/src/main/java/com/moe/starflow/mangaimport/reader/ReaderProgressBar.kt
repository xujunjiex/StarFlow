package com.moe.starflow.mangaimport.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 阅读器薄进度条（Koto 风格）：
 * - 细长进度条（深色轨道 + 主题蓝进度）
 * - 拖拽/点击可寻页
 * - **长按 → 打开页面预览**（自定义 View 完全掌控触摸，比 SeekBar 可靠）
 */
class ReaderProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(3f) }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(3f) }

    init {
        isClickable = true
        updateColors()
    }

    private fun updateColors() {
        if (darkBackground) {
            trackPaint.color = 0x66FFFFFF.toInt()
            progressPaint.color = 0xFF55AEEA.toInt()
        } else {
            trackPaint.color = 0x33000000
            progressPaint.color = 0xFF3A92C6.toInt()
        }
    }

    private var pageCount = 0
    private var currentPage = 0

    /** 拖拽/点击寻页回调（页码索引）。 */
    var onSeek: ((Int) -> Unit)? = null

    /** 长按回调（打开预览）。 */
    var onLongPress: (() -> Unit)? = null

    /** 是否深色背景（决定轨道/手柄取反色，保证可见）。 */
    var darkBackground = true
        set(value) {
            if (field != value) {
                field = value
                updateColors()
                invalidate()
            }
        }

    private var tracking = false
    private var dragging = false
    private var longPressTriggered = false
    private var downX = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val longPressCallback = Runnable {
        longPressTriggered = true
        // 长按反馈动画：进度条轻微脉冲（变粗+闪烁后还原）
        animate().scaleX(1.12f).scaleY(1.5f).alpha(0.7f).setDuration(90L)
            .withEndAction {
                animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(90L).start()
            }.start()
        onLongPress?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        canvas.drawLine(0f, cy, width.toFloat(), cy, trackPaint)
        if (pageCount > 1) {
            val endX = width * (currentPage.toFloat() / (pageCount - 1))
            canvas.drawLine(0f, cy, endX, cy, progressPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 按下不移动滑块：避免长按预览时进度条被误移位
                tracking = true
                dragging = false
                longPressTriggered = false
                downX = event.x
                postDelayed(longPressCallback, 500)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (tracking && !longPressTriggered && dragging) {
                    // 拖动：跟随手指实时移动
                    updateFromX(event.x)
                } else if (tracking && !dragging && abs(event.x - downX) > touchSlop) {
                    // 越过 slop → 判定为拖动，取消长按
                    dragging = true
                    removeCallbacks(longPressCallback)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (tracking) {
                    removeCallbacks(longPressCallback)
                    if (!longPressTriggered) {
                        // 点击（或拖动结束）→ 按当前位置寻页
                        updateFromX(event.x)
                        onSeek?.invoke(currentPage)
                    }
                    tracking = false
                    dragging = false
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromX(x: Float) {
        if (pageCount <= 1) {
            currentPage = 0
            invalidate()
            return
        }
        val fraction = (x / width).coerceIn(0f, 1f)
        currentPage = (fraction * (pageCount - 1)).roundToInt()
        invalidate()
    }

    /** 更新页码（不影响触摸）。 */
    fun setPage(current: Int, total: Int) {
        currentPage = current.coerceIn(0, (total - 1).coerceAtLeast(0))
        pageCount = total
        invalidate()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}