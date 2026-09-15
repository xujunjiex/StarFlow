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
 * 阅读器薄进度条（Koto 风格）：细长进度条 + 拖拽/点击寻页 + 长按预览。
 *
 * **三色双层**（同一根条上叠加两层，宽度不同 → 两个信息互不遮挡）：
 * ```
 *   5dp  ████████████░░░░░░░░░░░░   ← 粗带：白=已读 / 灰=未读（黑白交界 = 当前阅读位置）
 *   2dp     ▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░░   ← 细绿条：已翻译页（两端 = 翻译边界）
 * ```
 * 绿条比粗带窄，绿段上下会露出白/灰 —— 因此**同一位置能同时读出"读到哪"和"翻到哪"**，
 * 跳翻时绿色可以散落在白色或灰色区域中。
 */
class ReaderProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 粗带：已读（当前页之前）。 */
    private val readPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(5f) }

    /** 粗带：未读（当前页之后）。 */
    private val unreadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(5f) }

    /** 细绿条：已翻译页。 */
    private val translatedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(2f) }

    init {
        isClickable = true
        updateColors()
    }

    private fun updateColors() {
        // 进度条始终压在深色半透明胶囊（bg_progress_pill）上，因此三色固定即可，
        // 不受页面背景深浅影响。darkBackground 仅用于保留外部既有调用。
        readPaint.color = 0xFFFFFFFF.toInt()
        unreadPaint.color = 0x59FFFFFF
        translatedPaint.color = 0xFF34C759.toInt()
    }

    private var pageCount = 0
    private var currentPage = 0

    /** 已成功翻译的页码集合（可能不连续 —— 跳翻/预翻）。 */
    private var translatedPages: Set<Int> = emptySet()

    /** 拖拽/点击寻页回调（页码索引）。 */
    var onSeek: ((Int) -> Unit)? = null

    /** 长按回调（打开预览）。 */
    var onLongPress: (() -> Unit)? = null

    /** 是否深色背景（历史字段，配色现已固定，见 [updateColors]）。 */
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
        if (pageCount <= 0 || width <= 0) return
        val cy = height / 2f
        val w = width.toFloat()
        val span = (pageCount - 1).coerceAtLeast(1)
        val splitX = w * (currentPage.toFloat() / span)

        // 底层粗带：白（已读）/ 灰（未读）
        canvas.drawLine(0f, cy, splitX, cy, readPaint)
        canvas.drawLine(splitX, cy, w, cy, unreadPaint)

        // 上层细绿条：逐「连续段」画，避免几百页时逐页画成 1px 看不见
        drawTranslatedRuns(canvas, cy, w, span)
    }

    private fun drawTranslatedRuns(canvas: Canvas, cy: Float, w: Float, span: Int) {
        if (translatedPages.isEmpty()) return
        val slot = w / span
        // 单页至少给 2dp 可见宽度（几百页时一个 slot 可能不足 1px）
        val half = (slot / 2f).coerceAtLeast(dp(1f))
        val sorted = translatedPages.filter { it in 0 until pageCount }.sorted()
        if (sorted.isEmpty()) return

        var runStart = sorted[0]
        var prev = sorted[0]
        for (i in 1 until sorted.size) {
            val p = sorted[i]
            if (p == prev + 1) {
                prev = p
                continue
            }
            drawRun(canvas, cy, w, span, runStart, prev, half)
            runStart = p
            prev = p
        }
        drawRun(canvas, cy, w, span, runStart, prev, half)
    }

    private fun drawRun(canvas: Canvas, cy: Float, w: Float, span: Int, a: Int, b: Int, half: Float) {
        val x0 = (w * a / span - half).coerceAtLeast(0f)
        val x1 = (w * b / span + half).coerceAtMost(w)
        if (x1 <= x0) return
        canvas.drawLine(x0, cy, x1, cy, translatedPaint)
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
                    updateFromX(event.x)
                } else if (tracking && !dragging && abs(event.x - downX) > touchSlop) {
                    dragging = true
                    removeCallbacks(longPressCallback)
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

    /** 更新「已翻译页」绿色区间。 */
    fun setTranslatedPages(pages: Set<Int>) {
        if (translatedPages == pages) return
        translatedPages = pages
        invalidate()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
