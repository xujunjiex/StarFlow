package com.moe.starflow.ui.viewer
import com.moe.starflow.ui.history.*
import com.moe.starflow.translate.*
import com.moe.starflow.manga.*
import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView

/**
 * 支持双指缩放、双击缩放、拖动平移的 ImageView。
 * 与 ViewPager2 配合时，缩放状态下会拦截水平滑动以平移图片。
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    /** 任意手势起点回调（ACTION_DOWN），供自动翻页做「触摸自动暂停」。可选，默认 null。 */
    var onInteraction: (() -> Unit)? = null

    /** 单击确认回调（x, y，view 坐标），供阅读器做点击分区翻页/唤出菜单。可选，默认 null。 */
    var onSingleTapConfirmed: ((Float, Float) -> Unit)? = null

    private val matrix = Matrix()
    private val savedMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    private var mode = NONE
    private var startPoint = PointF()
    private var midPoint = PointF()
    private var oldDist = 1f

    private var minScale = 1f
    private var maxScale = 5f
    private var currentScale = 1f

    private var isInitialized = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val newScale = currentScale * scaleFactor
            if (newScale in minScale..maxScale) {
                matrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                currentScale = newScale
                constrainMatrix()
                imageMatrix = matrix
            }
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale > 1.5f) {
                // 缩小到原始大小
                animateScale(currentScale, 1f, e.x, e.y)
            } else {
                // 放大到 2.5x
                animateScale(currentScale, 2.5f, e.x, e.y)
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // 放大状态不翻页：单击只在未缩放时派发（缩回初始后恢复翻页）
            if (currentScale <= 1.05f) onSingleTapConfirmed?.invoke(e.x, e.y)
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        isInitialized = false
        requestLayout()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!isInitialized && drawable != null) {
            fitCenter()
            isInitialized = true
        }
    }

    /**
     * 将图片在 View 内铺满 / 居中适配（相册式）。
     * 旋转用 imageMatrix 实现（不用 View.rotation，避免旋转后 View bounds 外溢被父容器裁切成黑边），
     * 旋转后图像始终绘制在 View 内部并按视觉外接尺寸铺满，无黑边。
     * @param imgW/imgH 旋转后视觉外接宽高（90°/270° 时互换）
     */
    private fun applyFit(imgW: Float, imgH: Float, viewW: Float, viewH: Float, degrees: Int) {
        if (imgW == 0f || imgH == 0f || viewW == 0f || viewH == 0f) return

        val rotated = degrees % 180 != 0
        val fitW = if (rotated) imgH else imgW
        val fitH = if (rotated) imgW else imgH
        // contain：按旋转后视觉外接尺寸铺满短边，长边（图片原高度方向）贴合 view，
        // 不溢出、不裁切（避免过度放大导致上下跑出屏幕）。
        val scale = minOf(viewW / fitW, viewH / fitH)

        matrix.reset()
        // 1. 绕原图中心旋转
        matrix.postRotate(degrees.toFloat(), imgW / 2f, imgH / 2f)
        // 2. 旋转后图像可能落到负坐标，平移到正象限左上角 (0,0)，得到视觉外接 (rotW, rotH) 图像
        val src = floatArrayOf(0f, 0f, imgW, 0f, imgW, imgH, 0f, imgH)
        matrix.mapPoints(src)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var i = 0
        while (i < src.size) {
            val x = src[i]; val y = src[i + 1]
            if (x < minX) minX = x; if (y < minY) minY = y
            if (x > maxX) maxX = x; if (y > maxY) maxY = y
            i += 2
        }
        matrix.postTranslate(-minX, -minY)
        // 3. 缩放到目标铺满尺寸
        matrix.postScale(scale, scale)
        // 4. 居中到 View
        val effW = (maxX - minX) * scale
        val effH = (maxY - minY) * scale
        matrix.postTranslate((viewW - effW) / 2f, (viewH - effH) / 2f)
        currentScale = 1f
        imageMatrix = matrix
    }

    /**
     * 重置旋转角度为 0（未旋转正常态），并按 fitCenter 重新铺满。
     * 三态切换 / 翻页等场景下调用，让图片回到正常方向。
     */
    fun resetRotation() {
        imgRotation = 0
        fitCenter()
    }

    /** 当前旋转角度（0/90/180/270），用 imageMatrix 旋转而非 View.rotation */
    private var imgRotation: Int = 0

    private fun fitCenter() {
        val d = drawable ?: return
        applyFit(d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat(),
            width.toFloat(), height.toFloat(), imgRotation)
    }

    /**
     * 限制矩阵，防止图片移出可视区域
     */
    /**
     * 限制矩阵，防止图片移出可视区域。
     * ⚠️ 仅适用于未旋转态（imgRotation=0）。其他角度下 MSCALE/MSKEW/MTRANS 不再代表
     * 视觉宽高/左上角，沿用未旋转公式会把图像错误回拉到屏幕外 → 禁用 constrain。
     */
    private fun constrainMatrix() {
        if (imgRotation != 0) return  // 90°/180°/270° 旋转态：不限制边界，避免错误回拉
        val d = drawable ?: return
        val imgW = d.intrinsicWidth.toFloat()
        val imgH = d.intrinsicHeight.toFloat()

        matrix.getValues(matrixValues)
        val scaleX = matrixValues[Matrix.MSCALE_X]
        val scaleY = matrixValues[Matrix.MSCALE_Y]
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]

        val scaledW = imgW * scaleX
        val scaledH = imgH * scaleY
        val viewW = width.toFloat()
        val viewH = height.toFloat()

        var dx = 0f
        var dy = 0f

        if (scaledW <= viewW) {
            // 图片比视图窄，居中
            dx = (viewW - scaledW) / 2f - transX
        } else {
            // 图片比视图宽，限制边界
            if (transX > 0) dx = -transX
            if (transX + scaledW < viewW) dx = viewW - (transX + scaledW)
        }

        if (scaledH <= viewH) {
            dy = (viewH - scaledH) / 2f - transY
        } else {
            if (transY > 0) dy = -transY
            if (transY + scaledH < viewH) dy = viewH - (transY + scaledH)
        }

        if (dx != 0f || dy != 0f) {
            matrix.postTranslate(dx, dy)
        }
    }

    private fun animateScale(from: Float, to: Float, focusX: Float, focusY: Float) {
        val animator = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val value = anim.animatedValue as Float
                val factor = value / currentScale
                matrix.postScale(factor, factor, focusX, focusY)
                currentScale = value
                constrainMatrix()
                imageMatrix = matrix
            }
        }
        animator.start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                onInteraction?.invoke()
                savedMatrix.set(matrix)
                startPoint.set(event.x, event.y)
                mode = DRAG
                // 通知父 View 不要拦截事件（不缩放时不主动 disallow，
                // 让 ViewPager2 默认能接收水平滑动；后续边界检测再恢复）
                if (currentScale > 1.05f) {
                    parent.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                oldDist = spacing(event)
                if (oldDist > 10f) {
                    savedMatrix.set(matrix)
                    midPoint(event, midPoint)
                    mode = ZOOM
                    // 进入捏合手势，必须 disallow 让 ScaleGestureDetector 处理
                    parent.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG && !scaleDetector.isInProgress) {
                    matrix.set(savedMatrix)
                    val dx = event.x - startPoint.x
                    val dy = event.y - startPoint.y
                    matrix.postTranslate(dx, dy)
                    constrainMatrix()
                    imageMatrix = matrix

                    // 决定 ViewPager2 是否接管水平滑动：
                    // - 未缩放（currentScale <= 1.05）：交给 ViewPager2（横滑距离 > 纵滑距离且 > 10px）
                    // - 已缩放：图片被放大，拖动只平移图片，**绝不翻页**——必须缩回初始才能翻页
                    if (currentScale <= 1.05f) {
                        if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 10f) {
                            parent.requestDisallowInterceptTouchEvent(false)
                        }
                    } else {
                        // 放大状态始终由图片消费拖动（平移图片），不让 ViewPager2 拦截 → 无法滑动翻页
                        parent.requestDisallowInterceptTouchEvent(true)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
            }
        }
        return true
    }

    /**
     * 判断当前图片是否在水平方向上触到边缘。
     * 用于缩放状态下滑动到边界时让 ViewPager2 接管切页。
     */
    private fun isAtHorizontalEdge(): Boolean {
        val d = drawable ?: return true
        matrix.getValues(matrixValues)
        val scaleX = matrixValues[Matrix.MSCALE_X]
        val transX = matrixValues[Matrix.MTRANS_X]
        val scaledW = d.intrinsicWidth * scaleX
        val viewW = width.toFloat()
        if (viewW <= 0f) return false
        // 右滑希望向左翻页 → 图片左边到达/超过 view 左边界 = true
        // 左滑希望向右翻页 → 图片右边到达/超过 view 右边界 = true
        val epsilon = 2f  // 浮点边界容差
        return transX >= -epsilon || (transX + scaledW) <= viewW + epsilon
    }

    /**
     * 判断是否已缩放（供外部判断手势冲突）
     */
    fun isZoomed(): Boolean = currentScale > 1.05f

    /**
     * 相册式旋转 90°：累加 imageMatrix 旋转角度（不用 View.rotation，避免旋转后 View bounds 外溢
     * 被父容器裁切成黑边），然后 applyFit 按旋转后视觉外接尺寸铺满。图像始终绘制在 View 内。
     */
    fun rotateAndFit90() {
        val d = drawable ?: return
        imgRotation = (imgRotation + 90) % 360
        applyFit(d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat(),
            width.toFloat(), height.toFloat(), imgRotation)
    }

    /**
     * 获取当前旋转角度（供外部判断是否旋转态）
     */
    fun rotationDegrees(): Float = rotation

    /**
     * 重置到原始缩放
     */
    fun resetZoom() {
        if (currentScale != 1f) {
            animateScale(currentScale, 1f, width / 2f, height / 2f)
        }
    }

    private fun spacing(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return Math.sqrt((x * x + y * y).toDouble()).toFloat()
    }

    private fun midPoint(event: MotionEvent, point: PointF) {
        if (event.pointerCount < 2) return
        point.set(
            (event.getX(0) + event.getX(1)) / 2f,
            (event.getY(0) + event.getY(1)) / 2f
        )
    }

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }
}
