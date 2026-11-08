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
 * ## 缩放中心 = 手指（横竖都是）
 * 预览用子视图 `scaleX/scaleY`，pivot 就是手指点；提交时也必须按同一个手指点还原位置，
 * 否则松手会跳（用户报过「缩放中心一直在屏幕靠上」和「松手瞬间轻微位移」这两类问题）：
 * - 横向：`scrollX = 焦点比例 × 新内容宽 - 手指x`；
 * - 纵向：纵向滚动由 RecyclerView 管，宿主按**手指下方那一页**的页内比例换算
 *   `scrollToPositionWithOffset(页, 手指y - 页内比例 × 旧行高 × 缩放比)`。
 *
 * ## 放手那一帧必须"无缝"
 * 预览画的是「旧宽度 × 缩放比」，提交换的是「新宽度」。两者只要不落在同一帧，
 * 中间就会有一帧画在旧宽度上 → 看起来就是"松手闪一下/挪一下"。所以：
 * 1. **绝不先清预览**：把预览留到应用新宽度的**同一轮布局**里（`measureChildWithMargins` 里清），
 *    绘制发生在布局之后，因此那一帧直接就是"新宽度 + 无 transform"，视觉没有任何变化；
 * 2. **重绑与阅读位置也要提前**：`onZoomWillCommit` 在 `requestLayout()` 之前通知宿主，
 *    宿主在同一次布局里重绑页（钉新行高）并把列表预滚到手指下方那一页。
 *
 * ## 所有权
 * - [zoom] 与横向滚动量只在本 View；译图缓存、页图重绑、阅读位置恢复都由宿主经
 *   [anchorProvider] / [onZoomWillCommit] / [onZoomCommitted] 完成（本 View 不依赖适配器或控制器）。
 * - 缩放在**离开连续滚动**或**旋转**时由宿主复位（`resetZoom()`）。
 */
class WebtoonZoomView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    /**
     * 手指点下方的阅读锚点：页号 + 页内比例 + 该页**缩放前**的像素高度。
     *
     * 高度要带上：提交发生在布局**之前**，宿主只能按 `高度 × 缩放比` 推算新位置，
     * 等布局跑完再问新高度就已经晚一帧了（那一帧就是用户看到的位移）。
     */
    data class Anchor(val page: Int, val fraction: Float, val heightPx: Int)

    /** 当前已提交的缩放倍率（1 = 铺满屏宽）。 */
    var zoom: Float = MIN_ZOOM
        private set

    /** 取 `focusY` 手指点下方的阅读锚点（一屏可能有两页，必须按手指选页），由宿实现。 */
    var anchorProvider: ((focusY: Float) -> Anchor?)? = null

    /**
     * **布局之前**通知宿主：按新的列宽重绑页（重新钉行高）并把列表滚到手指下方那一页的
     * `fraction × heightPx × heightRatio` 处。必须与新的子视图宽度落在同一次布局里。
     *
     * @param heightRatio 新列宽 / 旧列宽（行高与列宽等比，所以位置也按它换算）
     */
    var onZoomWillCommit: ((zoom: Float, heightRatio: Float, focusY: Float, anchor: Anchor?) -> Unit)? = null

    /**
     * 缩放已提交、新列宽/新行高已经布局完成（仍在绘制之前）：宿主在这里预热译图、
     * 同步当前页/进度条。不要再改动列表滚动位置（会晚一帧）。
     */
    var onZoomCommitted: ((zoom: Float, anchor: Anchor?) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var scalePreview = MIN_ZOOM
    private var scaling = false
    private var horizontalLock = false
    private var horizontalRejected = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f

    /** 最近一次捏合的焦点（视口坐标）：提交后按它恢复横竖位置，缩放中心才跟手指。 */
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    /**
     * 预览 transform 还没撤（等下一次测量时撤）。
     *
     * ⚠️ 见类注释：提交后**不能**立刻 `clearPreview()`，否则新宽度还没应用，
     * 这一帧会画成旧宽度的内容（松手闪一下）。
     */
    private var pendingTransformClear = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaling = true
                scalePreview = zoom
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scalePreview = (scalePreview * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                previewScale(scalePreview, detector.focusX, detector.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                commit(scalePreview, lastFocusX, lastFocusY)
            }
        },
    )

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                commit(if (zoom > 1.05f) MIN_ZOOM else DOUBLE_TAP_ZOOM, e.x, e.y)
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
    fun setZoom(target: Float) = commit(target, width / 2f, height / 2f)

    /** 复位到铺满屏宽（切出连续滚动/旋转时调用）。**不触发** [onZoomCommitted]。 */
    fun resetZoom() {
        if (zoom == MIN_ZOOM && scrollX == 0) return
        zoom = MIN_ZOOM
        scaling = false
        horizontalLock = false
        horizontalRejected = false
        pendingTransformClear = false
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
        // ⚠️ 只在**这一轮**（已提交新宽度）撤预览 transform：本帧绘制发生在布局之后，
        //    所以看到的是"新宽度 + 无 transform"，与松手前的预览画面完全一致。
        if (pendingTransformClear) {
            pendingTransformClear = false
            clearPreview()
        }
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
                // ⚠️ 已经提交、正等新宽度布局时**不撤预览**（由 measureChildWithMargins 撤）
                if (!pendingTransformClear) clearPreview()
                return true
            }
        }
        return true
    }

    /** 预览：以手指点为 pivot（横竖都跟手指），画出来的缩放中心就是手指所在处。 */
    private fun previewScale(scale: Float, focusX: Float, focusY: Float) {
        val child = getChildAt(0) ?: return
        val factor = (scale / zoom).coerceAtLeast(0.01f)
        // 子视图坐标 = 视口坐标 + 已横向滚动量；纵向列表本身不滚动（滚动在 RecyclerView 内部）
        child.pivotX = focusX + scrollX
        child.pivotY = focusY
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
     * 提交缩放：改 [zoom] → **先**让宿主在新宽度下重绑/按手指预滚 → 重排 →
     * 新布局完成（绘制前）时撤预览并恢复横向位置。
     */
    private fun commit(target: Float, focusX: Float, focusY: Float) {
        val newZoom = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        scaling = false
        if (abs(newZoom - zoom) < 0.01f) {
            clearPreview()
            return
        }
        val heightRatio = newZoom / zoom
        val anchor = anchorProvider?.invoke(focusY)
        val oldChildWidth = getChildAt(0)?.width?.coerceAtLeast(1) ?: 1
        val focus = focusX.coerceIn(0f, width.toFloat())
        val focusFraction = ((scrollX + focus) / oldChildWidth).coerceIn(0f, 1f)
        zoom = newZoom
        pendingTransformClear = true
        // ① 布局前：宿主按新列宽重绑（钉新行高）+ 按手指预滚 → 与换宽同一次布局
        onZoomWillCommit?.invoke(newZoom, heightRatio, focusY, anchor)
        requestLayout()
        // ② 新宽度/新行高落定、仍在绘制之前：恢复横向位置并通知宿主
        afterChildLayoutChange {
            pendingTransformClear = false
            clearPreview()
            val childWidth = getChildAt(0)?.width?.coerceAtLeast(1) ?: return@afterChildLayoutChange
            val maxScroll = (childWidth - width).coerceAtLeast(0)
            // 同一个手指位置 × 新内容宽 - 手指x：手指下的内容保持不动
            val targetScroll = (focusFraction * childWidth - focus).roundToInt().coerceIn(0, maxScroll)
            scrollTo(targetScroll, 0)
            onZoomCommitted?.invoke(newZoom, anchor)
        }
    }

    /**
     * 等子视图下一次**宽度变化**的布局结束时执行（回调在 `layout()` 里、绘制之前）。
     *
     * 提交时列宽必然变化（缩放下限 0.01 倍 → 屏宽级以上至少几个像素），所以不会漏触发。
     */
    private fun afterChildLayoutChange(action: () -> Unit) {
        val child = getChildAt(0)
        if (child == null) {
            post(action)
            return
        }
        child.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
            ) {
                if (right - left == oldRight - oldLeft) return
                v.removeOnLayoutChangeListener(this)
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
