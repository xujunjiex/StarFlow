package com.moe.starflow.mangaimport.reader

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
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
 * ## 手势
 * - 双指捏合：[MIN_ZOOM]–[MAX_ZOOM]；捏合期间用子视图 `scaleX/scaleY` 实时预览，
 *   **松手才提交**（提交要重排列表 → 重绑页 → 按新宽度重新解码，不能每帧做）。
 * - 双击：1× ↔ [DOUBLE_TAP_ZOOM]。
 * - 单指：竖向拖动一律不拦截（交给列表滚动）；放大后横向拖动才由本容器接管，
 *   未放大时本容器完全不参与，行为与没有它时一致。
 *
 * ## 预览与提交的口径必须一致
 * 横向：以手指焦点为锚（提交后按同一焦点比例恢复 `scrollX`）。
 * 纵向：**恒以视口顶部为锚** —— 提交后宿主按「页号 + 页内比例」把阅读位置恢复到视口顶部，
 * 若预览按手指纵向锚定，松手那一下会整体跳一段（用户看到的就是"缩放完跳页"）。
 *
 * ## 所有权
 * - [zoom] 与横向滚动量只在本 View；译图缓存、页图重绑、阅读位置恢复都由宿主经
 *   [anchorProvider] / [onZoomCommitted] 完成（本 View 不依赖适配器或控制器）。
 * - 缩放在**离开连续滚动**或**旋转**时由宿主复位（`resetZoom()`）。
 */
class WebtoonZoomView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    /** 当前已提交的缩放倍率（1 = 铺满屏宽）。 */
    var zoom: Float = MIN_ZOOM
        private set

    /**
     * 提交前取阅读锚点（页号 + 页内比例），由宿主用列表的 LayoutManager 实现。
     * 为空时这次缩放不恢复位置（锚点 -1）。
     */
    var anchorProvider: (() -> Pair<Int, Float>)? = null

    /**
     * 缩放已提交、列表已按新宽度重排：宿主在这里重绑可见页 / 重渲译图 / 恢复阅读锚点。
     * `anchorPage < 0` 表示没有锚点（未注入 provider 或取不到）。
     */
    var onZoomCommitted: ((zoom: Float, anchorPage: Int, anchorFraction: Float) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var scalePreview = MIN_ZOOM
    private var scaling = false
    private var horizontalLock = false
    private var horizontalRejected = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f

    /** 最近一次捏合的横向焦点（视口坐标）：提交后按它恢复左右位置。 */
    private var lastFocusX = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaling = true
                scalePreview = zoom
                lastFocusX = detector.focusX
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scalePreview = (scalePreview * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                lastFocusX = detector.focusX
                previewScale(scalePreview, detector.focusX)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                commit(scalePreview, lastFocusX)
            }
        },
    )

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                commit(if (zoom > 1.05f) MIN_ZOOM else DOUBLE_TAP_ZOOM, e.x)
                return true
            }
        },
    )

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = false
    }

    /** 直接设置缩放（双击/外部入口走它）；会触发一次提交。 */
    fun setZoom(target: Float) = commit(target, width / 2f)

    /** 复位到铺满屏宽（切出连续滚动/旋转时调用）。**不触发** [onZoomCommitted]。 */
    fun resetZoom() {
        if (zoom == MIN_ZOOM && scrollX == 0) return
        zoom = MIN_ZOOM
        scaling = false
        horizontalLock = false
        horizontalRejected = false
        clearPreview()
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
                // 保险：捏合没真正开始就抬手时，别把状态留在"缩放中"
                scaling = false
                clearPreview()
                return true
            }
        }
        return true
    }

    private fun previewScale(scale: Float, focusX: Float) {
        val child = getChildAt(0) ?: return
        val factor = (scale / zoom).coerceAtLeast(0.01f)
        // 横向锚在手指处（子视图坐标 = 视口坐标 + 已横向滚动量）；纵向锚在视口顶部，见类注释
        child.pivotX = focusX + scrollX
        child.pivotY = 0f
        child.scaleX = factor
        child.scaleY = factor
    }

    private fun clearPreview() {
        val child = getChildAt(0) ?: return
        child.scaleX = 1f
        child.scaleY = 1f
        child.pivotX = 0f
        child.pivotY = 0f
    }

    /**
     * 提交缩放：记录横向焦点比例与阅读锚点 → 改 [zoom] → 重排 → 布局完成后恢复横向位置并通知宿主。
     *
     * ⚠️ 必须等**布局跑完**再恢复/通知：`post` 会早于 traversal 执行，读到的是旧宽度。
     */
    private fun commit(target: Float, focusX: Float) {
        val newZoom = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val anchor = anchorProvider?.invoke()
        scaling = false
        clearPreview()
        if (abs(newZoom - zoom) < 0.01f) return
        val oldChildWidth = getChildAt(0)?.width?.coerceAtLeast(1) ?: 1
        val focusFraction = ((scrollX + focusX.coerceIn(0f, width.toFloat())) / oldChildWidth)
            .coerceIn(0f, 1f)
        zoom = newZoom
        requestLayout()
        afterNextLayout {
            val childWidth = getChildAt(0)?.width?.coerceAtLeast(1) ?: return@afterNextLayout
            val maxScroll = (childWidth - width).coerceAtLeast(0)
            val targetScroll = (focusFraction * childWidth - width / 2f).roundToInt().coerceIn(0, maxScroll)
            scrollTo(targetScroll, 0)
            onZoomCommitted?.invoke(newZoom, anchor?.first ?: -1, anchor?.second ?: 0f)
        }
    }

    /** 下一次全局布局结束后执行（未附着时退回 `post`）。 */
    private fun afterNextLayout(action: () -> Unit) {
        val observer = viewTreeObserver
        if (!observer.isAlive) {
            post(action)
            return
        }
        observer.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (observer.isAlive) observer.removeOnGlobalLayoutListener(this)
                action()
            }
        })
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 3f
        const val DOUBLE_TAP_ZOOM = 2f
    }
}
