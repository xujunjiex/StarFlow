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
 * **四层信息叠在同一根条上**（2026-10 加超分那一层）：
 * ```
 *      ▓▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░░   ← 2dp 绿：已翻译页（压在粗带**中间**）
 *      ██████████░░░░░░░░░░░░░░░░░   ← 5dp 粗带：白=已读 / 灰=未读（黑白交界 = 当前阅读位置）
 *      ━━━━━━━━━━━━━━━━━━━━━━━━━━━   ← 3dp 紫：已超分页（画在粗带**下方**）
 * ```
 *
 * ## ⚠️ 为什么绿条与紫条要分居粗带两侧（而不是都压在中间）
 * 两个维度是**互相独立**的：一页可以"翻了没超"或"超了没翻"。三条线挤在同一条水平线上
 * 必然互相遮盖 —— 分居粗带**上下**之后，同一位置能同时读出四件事：
 * 读到哪 / 翻到哪 / 超到哪，且**即使分不清颜色**（色弱、截图灰度化）也能靠"在粗带上面还是下面"
 * 分辨出是"已翻译"还是"已超分"。
 *
 * 配色固定：进度条始终压在深色半透明胶囊（`bg_progress_pill`）上，不受页面背景深浅影响，
 * 因此不再有 darkBackground 分支（那个开关是历史遗留，早已是 no-op）。
 */
class ReaderProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 粗带：已读（当前页之前）。 */
    private val readPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(5f); color = 0xFFFFFFFF.toInt()
    }

    /** 粗带：未读（当前页之后）。 */
    private val unreadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(5f); color = 0x59FFFFFF
    }

    /** 细绿条：已翻译页。 */
    private val translatedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(2f); color = 0xFF34C759.toInt()
    }

    /**
     * 细紫条：**已超分**页。
     *
     * 选紫色而不是蓝/橙：绿（已翻译）与蓝（面板里的「识别中」）都已经有主，
     * 橙色是面板里的「翻译中」徽章色 —— 全都撞语义。紫在这条深色胶囊上辨识度最高，
     * 且与绿在明度上也分得开（R 通道差得远）。
     */
    private val srPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(3f); color = 0xFFA855F7.toInt()
    }

    init {
        isClickable = true
    }

    private var pageCount = 0
    private var currentPage = 0

    /** 已成功翻译的页码集合（可能不连续 —— 跳翻/预翻）。 */
    private var translatedPages: Set<Int> = emptySet()

    /** 已成功超分的页码集合（与 [translatedPages] 是**两个独立的维度**）。 */
    private var srPages: Set<Int> = emptySet()

    /** 预计算的「连续段」。⚠️ 不能在 onDraw 里算：拖动进度条时每帧都会 invalidate， */
    private var translatedRuns: List<IntRange> = emptyList()
    private var srRuns: List<IntRange> = emptyList()

    /** 拖拽/点击寻页回调（页码索引）。 */
    var onSeek: ((Int) -> Unit)? = null

    /** 长按回调（打开预览）。 */
    var onLongPress: (() -> Unit)? = null

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

        // 细绿条：压在粗带**中间** → 绿段上下会露出白/灰，同一位置同时读出"读到哪"和"翻到哪"
        drawRuns(canvas, cy, w, span, translatedRuns, translatedPaint)
        // 细紫条：画在粗带**下方**（3dp 条 + 1.5dp 间隙）→ 与绿条分居粗带两侧，互不遮盖
        drawRuns(canvas, cy + dp(5f), w, span, srRuns, srPaint)
    }

    /** 把一组「连续段」画成水平线。两种标记共用（只有 y 与 paint 不同）。 */
    private fun drawRuns(
        canvas: Canvas, cy: Float, w: Float, span: Int,
        runs: List<IntRange>, paint: Paint,
    ) {
        if (runs.isEmpty()) return
        val slot = w / span
        // 单页至少给 2dp 可见宽度（几百页时一个 slot 可能不足 1px）
        val half = (slot / 2f).coerceAtLeast(dp(1f))
        for (run in runs) {
            val x0 = (w * run.first / span - half).coerceAtLeast(0f)
            val x1 = (w * run.last / span + half).coerceAtMost(w)
            if (x1 > x0) canvas.drawLine(x0, cy, x1, cy, paint)
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
        if (pageCount != total) {
            pageCount = total
            translatedRuns = computeTranslatedRuns(translatedPages, total)
            srRuns = computeTranslatedRuns(srPages, total)
        }
        invalidate()
    }

    /** 更新「已翻译页」绿色区间。 */
    fun setTranslatedPages(pages: Set<Int>) {
        if (translatedPages == pages) return
        translatedPages = pages
        translatedRuns = computeTranslatedRuns(pages, pageCount)
        invalidate()
    }

    /**
     * 更新「已超分页」紫色区间（**独立于**已翻译：一页可以翻了没超、或超了没翻）。
     *
     * ⚠️ 传进来的必须是**已落盘成功**的页（`ReaderTranslationController.srPages()` 按记录表的
     * `STATE_SUCCESS` 取）—— 别拿"正在超分"或"有文件但是别的模型"的页充数，
     * 那样紫条会在失败后不退。
     */
    fun setSrPages(pages: Set<Int>) {
        if (srPages == pages) return
        srPages = pages
        srRuns = computeTranslatedRuns(pages, pageCount)
        invalidate()
    }

    companion object {
        /**
         * 把「已翻译页集合」压成若干连续段（供绿条绘制）。
         * 越界页过滤掉；空集合返回空表。纯函数，便于单测。
         */
        internal fun computeTranslatedRuns(pages: Set<Int>, total: Int): List<IntRange> {
            if (pages.isEmpty() || total <= 0) return emptyList()
            val sorted = pages.filter { it in 0 until total }.sorted()
            if (sorted.isEmpty()) return emptyList()
            val runs = mutableListOf<IntRange>()
            var start = sorted[0]
            var prev = sorted[0]
            for (i in 1 until sorted.size) {
                val p = sorted[i]
                if (p == prev + 1) {
                    prev = p
                    continue
                }
                runs += start..prev
                start = p
                prev = p
            }
            runs += start..prev
            return runs
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
