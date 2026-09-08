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
    private var longPressTriggered = false
    private var downX = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val longPressCallback = Runnable {
        longPressTriggered = true
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
                tracking = true
                longPressTriggered = false
                downX = event.x
                updateFromX(event.x)
                postDelayed(longPressCallback, 500)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (tracking) {
                    if (abs(event.x - downX) > touchSlop) {
                        removeCallbacks(longPressCallback)
                        downX = event.x
                    }
                    updateFromX(event.x)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (tracking) {
                    removeCallbacks(longPressCallback)
                    if (!longPressTriggered) {
                        updateFromX(event.x)
                        onSeek?.invoke(currentPage)
                    }
                    tracking = false
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