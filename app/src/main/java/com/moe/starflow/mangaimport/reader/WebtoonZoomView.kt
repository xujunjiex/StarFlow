package com.moe.starflow.mangaimport.reader

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 连续滚动（Webtoon）的**整列缩放**容器：唯一子视图是 `webtoonList`。
 *
 * ## 为什么是"整列"而不是"页内"
 * 分页模式可以锁住翻页去缩放单页（`ZoomableImageView` + ViewPager2）；连续滚动不一样：
 * 用户放大后**还要继续上下滑**。所以这里缩放的是**列宽**：
 * - 子视图宽度 = 视口宽 × [zoom]（高度仍是一屏），页面按比例变宽；
 * - 上下滚动由列表自己处理，本容器**只**接管多点缩放与放大后的横向拖动；
 * - 页面比视口宽时左右拖动看屏幕外部分（`HorizontalScrollView` 的滚动范围，天然带钳制）。
 *
 * ## 为什么**不做** transform 预览（踩过大坑）
 * 一开始捏合期间用子视图 `scaleX/scaleY` 做实时预览、松手才提交。缩放本身是对的，
 * 但**缩小**时整列被画小，屏幕上下就露出背景（用户看到的"上下黑屏"），松手才恢复。
 * 现在改成**逐帧真换列宽**：每次 onScale 直接把 [zoom] 设成新值、`requestLayout()`，
 * 行高由宿主在 `onZoomPreview` 里按新列宽重算（不重绑、不解码，只改 layoutParams），
 * 列表永远铺满屏幕，不会有黑边；松手后才重绑那一轮（按最终宽度重新解码 + 译图预热）。
 *
 * ## 缩放中心 = 手指（横竖都是）
 * - 横向：布局完成后 `scrollX = 焦点比例 × 新内容宽 - 手指x`；
 * - 纵向：宿主按**手指下方那一页**的页内比例换算 `scrollToPositionWithOffset`。
 *
 * ## 所有权
 * - [zoom] 与横向滚动量只在本 View；行高重算、页图重绑、译图缓存、位置恢复都由宿主经
 *   [anchorProvider] / [onZoomPreview] / [onZoomCommitted] 完成（本 View 不依赖适配器或控制器）。
 * - 缩放在**离开连续滚动**或**旋转**时由宿主复位（`resetZoom()`）。
 */
class WebtoonZoomView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    /**
     * 手指点下方的阅读锚点：页号 + 页内比例 + 该页**当前**的像素高度。
     *
     * 高度要带上：本帧的缩放回调发生在布局**之前**，宿主只能按 `高度 × 缩放比` 推算新位置。
     */
    data class Anchor(val page: Int, val fraction: Float, val heightPx: Int)

    /** 当前列宽倍率（1 = 铺满屏宽）。 */
    var zoom: Float = MIN_ZOOM
        private set

    /** 取 `focusY` 手指点下方的阅读锚点（一屏可能有两页，必须按手指选页），由宿实现。 */
    var anchorProvider: ((focusY: Float) -> Anchor?)? = null

    /**
     * 缩放过程中**每一帧**回调（布局前）：宿主按新的 [zoom] 重算可见页行高（只改 layoutParams，
     * 不重绑、不解码）并 `scrollToPositionWithOffset` 把手指下方那一页滚到新位置。
     *
     * @param heightRatio 新列宽 / 当前布局的列宽
     */
    var onZoomPreview: ((zoom: Float, heightRatio: Float, focusY: Float, anchor: Anchor?) -> Unit)? = null

    /**
     * 缩放已落定（新列宽/行高已完成布局）：宿主在这里重绑可见页（按最终宽度重新解码）、
     * 预热译图、同步当前页。⚠️ 不要再改滚动位置。
     */
    var onZoomCommitted: ((zoom: Float) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var scaling = false
    private var horizontalLock = false
    private var horizontalRejected = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    /** 本次捏合的累计目标倍率（onScale 的 scaleFactor 是相对上一帧的增量）。 */
    private var liveTarget = MIN_ZOOM

    /** 本次手势是否真的改过列宽 → 松手时才需要重绑。 */
    private var liveChanged = false

    /** **当前子视图宽度**对应的倍率：布局还没跟上时它比 [zoom] 小/大，用来算本帧的缩放比。 */
    private var lastLayoutZoom = MIN_ZOOM

    /** 一次布局完成后要恢复的横向位置（焦点比例 + 手指 x）；-1 = 没有待办。 */
    private var pendingFocusFraction = -1f
    private var pendingFocusX = 0f

    /** 松手/双击后等这一轮布局结束再通知宿主重绑。 */
    private var pendingCommitNotify = false

    /** 列宽变化监听只装一次（`onFinishInflate` 在测试/代码 addView 时不会走，measure 里兜底）。 */
    private var listenerInstalled = false

    private val layoutListener = object : View.OnLayoutChangeListener {
        override fun onLayoutChange(
            v: View, left: Int, top: Int, right: Int, bottom: Int,
            oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
        ) {
            // 只看列宽变化：行高/位置变化不归这里管
            if (right - left == oldRight - oldLeft) return
            lastLayoutZoom = zoom
            if (pendingFocusFraction >= 0f) {
                val childWidth = (right - left).coerceAtLeast(1)
                val maxScroll = (childWidth - width).coerceAtLeast(0)
                val target = (pendingFocusFraction * childWidth - pendingFocusX)
                    .roundToInt().coerceIn(0, maxScroll)
                scrollTo(target, 0)
                pendingFocusFraction = -1f
            }
            if (pendingCommitNotify) {
                pendingCommitNotify = false
                onZoomCommitted?.invoke(zoom)
            }
        }
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaling = true
                liveTarget = zoom
                liveChanged = false
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                liveTarget = (liveTarget * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                applyLive(liveTarget, detector.focusX, detector.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                scaling = false
                if (liveChanged) commitWhenSettled()
            }
        },
    )

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                setZoomAt(if (zoom > 1.05f) MIN_ZOOM else DOUBLE_TAP_ZOOM, e.x, e.y)
                return true
            }
        },
    )

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = false
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        installLayoutListener(getChildAt(0))
    }

    private fun installLayoutListener(child: View?) {
        if (child == null || listenerInstalled) return
        listenerInstalled = true
        child.addOnLayoutChangeListener(layoutListener)
    }

    /** 直接设置缩放（双击/外部入口走它）；以视口中心为焦点。 */
    fun setZoom(target: Float) = setZoomAt(target, width / 2f, height / 2f)

    private fun setZoomAt(target: Float, focusX: Float, focusY: Float) {
        if (applyLive(target, focusX, focusY)) commitWhenSettled()
    }

    /** 复位到铺满屏宽（切出连续滚动/旋转时调用）。**不触发** [onZoomCommitted]。 */
    fun resetZoom() {
        if (zoom == MIN_ZOOM && scrollX == 0) return
        zoom = MIN_ZOOM
        lastLayoutZoom = MIN_ZOOM
        liveTarget = MIN_ZOOM
        liveChanged = false
        pendingFocusFraction = -1f
        pendingCommitNotify = false
        scaling = false
        horizontalLock = false
        horizontalRejected = false
        scrollTo(0, 0)
        requestLayout()
    }

    // ===== 测量：子视图宽度 = 视口宽 × zoom，高度恒为一屏 =====

    override fun measureChildWithMargins(
        child: View,
        parentWidthMeasureSpec: Int,
        widthUsed: Int,
        parentHeightMeasureSpec: Int,
        heightUsed: Int,
    ) {
        val viewportW = (MeasureSpec.getSize(parentWidthMeasureSpec) - paddingLeft - paddingRight)
            .coerceAtLeast(1)
        val viewportH = (MeasureSpec.getSize(parentHeightMeasureSpec) - paddingTop - paddingBottom)
            .coerceAtLeast(1)
        installLayoutListener(child)
        child.measure(
            MeasureSpec.makeMeasureSpec((viewportW * zoom).roundToInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(viewportH, MeasureSpec.EXACTLY),
        )
    }

    override fun measureChild(child: View, parentWidthMeasureSpec: Int, parentHeightMeasureSpec: Int) {
        measureChildWithMargins(child, parentWidthMeasureSpec, 0, parentHeightMeasureSpec, 0)
    }

    // ===== 手势 =====

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 两个检测器都吃全量事件：缩放可能在任何子视图上开始；双击也只用于缩放，不消费事件
        scaleDetector.onTouchEvent(ev)
        tapDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (scaling) return true
        if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            scaling = true
            return true
        }
        // 未放大：完全不参与，行为与没有本容器时一致
        if (zoom <= 1.001f) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                horizontalLock = false
                horizontalRejected = false
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!horizontalLock && !horizontalRejected) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy)) horizontalLock = true
                    else if (abs(dy) > touchSlop && abs(dy) >= abs(dx)) horizontalRejected = true
                }
                if (horizontalLock) lastX = ev.x
                return horizontalLock
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                lastX = ev.x
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // 竖向拖动被方向锁拒绝（horizontalRejected）后不再进这里；这里只处理横向平移
                if (!scaling && horizontalLock) {
                    val dx = lastX - ev.x
                    lastX = ev.x
                    if (dx != 0f) scrollBy(dx.roundToInt(), 0)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                horizontalLock = false
                horizontalRejected = false
                scaling = false
                // 保险：手势被取消（ScaleGestureDetector 没回调 onScaleEnd）时也要落定并重绑
                if (liveChanged) commitWhenSettled()
                return true
            }
        }
        return true
    }

    /**
     * 逐帧应用新列宽：**不做 transform**，直接改 [zoom] 触发重排，行高由宿主按新宽度重算。
     *
     * @return 是否真的改动了（倍率变化小于 [MIN_STEP] 时忽略，避免每帧抖 0.1%）
     */
    private fun applyLive(target: Float, focusX: Float, focusY: Float): Boolean {
        val newZoom = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (abs(newZoom - zoom) < MIN_STEP) return false
        val child = getChildAt(0) ?: return false
        // 锚点取不到（非 Webtoon 宿主/列表还没布局）也要能缩放，只是纵向位置不动
        val anchor = anchorProvider?.invoke(focusY)
        val oldWidth = child.width.coerceAtLeast(1)
        val focus = focusX.coerceIn(0f, width.toFloat())
        pendingFocusFraction = ((scrollX + focus) / oldWidth).coerceIn(0f, 1f)
        pendingFocusX = focus
        // ⚠️ 用「当前布局的倍率」算比例：连续 onScale 可能在同一帧里被合并，用 zoom 会漏掉中间增量
        val heightRatio = newZoom / lastLayoutZoom
        zoom = newZoom
        liveChanged = true
        onZoomPreview?.invoke(newZoom, heightRatio, focusY, anchor)
        requestLayout()
        return true
    }

    /** 松手/双击后：新列宽已经布局完就立即通知，否则等这一轮布局（不 post，避免早于 traversal）。 */
    private fun commitWhenSettled() {
        liveChanged = false
        val expected = (width * zoom).roundToInt()
        val childWidth = getChildAt(0)?.width ?: 0
        if (abs(childWidth - expected) <= 1) {
            onZoomCommitted?.invoke(zoom)
        } else {
            pendingCommitNotify = true
            requestLayout()
        }
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 3f
        const val DOUBLE_TAP_ZOOM = 2f

        /** 相邻两帧的倍率差小于它就不重排（0.1% 以内肉眼不可见，重排是浪费）。 */
        private const val MIN_STEP = 0.001f
    }
}
