package com.moe.starflow.mangaimport.reader

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.widget.FrameLayout
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

// ===== 从 Kototoro ComposeReaderPageAnimation.calculatePageCurlGeometry 逐行移植的折叠几何 =====

internal data class CurlGeometryPoints(
    val topCurl: PointF,
    val bottomCurl: PointF,
    val frontPoly: List<PointF>,
    val backPoly: List<PointF>,
    val angleRad: Float,
)

internal fun calculateCurlGeometry(
    width: Float,
    height: Float,
    progress: Float,
    startFraction: Float,
    isReversed: Boolean,
): CurlGeometryPoints {
    val edge = calculateCurlEdge(width, height, progress, startFraction, isReversed)
    val topIntersection = lineLineIntersection(
        PointF(0f, 0f), PointF(width, 0f), edge.top, edge.bottom,
    ) ?: edge.top
    val bottomIntersection = lineLineIntersection(
        PointF(0f, height), PointF(width, height), edge.top, edge.bottom,
    ) ?: edge.bottom
    val topCurl = if (isReversed) {
        PointF(min(width, topIntersection.x), 0f)
    } else {
        PointF(max(0f, topIntersection.x), 0f)
    }
    val bottomCurl = if (isReversed) {
        PointF(min(width, bottomIntersection.x), height)
    } else {
        PointF(max(0f, bottomIntersection.x), height)
    }
    val frontPoly = if (isReversed) {
        listOf(
            PointF(width, 0f), topCurl, bottomCurl, PointF(width, height),
        )
    } else {
        listOf(
            PointF(0f, 0f), topCurl, bottomCurl, PointF(0f, height),
        )
    }
    val backPoly = calculateCurlBackPath(width, height, topCurl, bottomCurl, isReversed)
    val angle = PI.toFloat() - atan2(
        bottomCurl.y - topCurl.y,
        bottomCurl.x - topCurl.x,
    ) * 2f
    return CurlGeometryPoints(topCurl, bottomCurl, frontPoly, backPoly, angle)
}

private data class CurlEdge(val top: PointF, val bottom: PointF)

private fun calculateCurlEdge(
    width: Float,
    height: Float,
    progress: Float,
    startFraction: Float,
    isReversed: Boolean,
): CurlEdge {
    val start = CurlEdge(PointF(width, 0f), PointF(width, height))
    val middle = CurlEdge(PointF(width, height / 2f), PointF(width / 2f, height))
    val end = CurlEdge(PointF(0f, 0f), PointF(0f, height))
    val normalized = progress.coerceIn(0f, 1f)
    val bottomEdge = if (normalized <= 1f / 3f) {
        lerpEdge(start, middle, normalized * 3f)
    } else {
        lerpEdge(middle, end, (normalized - 1f / 3f) * 1.5f)
    }
    val topIntersection = lineLineIntersection(
        PointF(0f, 0f), PointF(width, 0f), bottomEdge.top, bottomEdge.bottom,
    ) ?: bottomEdge.top
    val bottomIntersection = lineLineIntersection(
        PointF(0f, height), PointF(width, height), bottomEdge.top, bottomEdge.bottom,
    ) ?: bottomEdge.bottom
    val centerX = lerp(width, 0f, normalized)
    val cornerBias = startFraction.coerceIn(0f, 1f) * 2f - 1f
    val halfSlope = (topIntersection.x - bottomIntersection.x) / 2f * cornerBias
    val edge = CurlEdge(
        top = PointF(centerX + halfSlope, 0f),
        bottom = PointF(centerX - halfSlope, height),
    )
    return if (isReversed) {
        CurlEdge(
            top = PointF(width - edge.top.x, edge.top.y),
            bottom = PointF(width - edge.bottom.x, edge.bottom.y),
        )
    } else {
        edge
    }
}

private fun lerpEdge(start: CurlEdge, end: CurlEdge, fraction: Float): CurlEdge = CurlEdge(
    top = PointF(lerp(start.top.x, end.top.x, fraction), lerp(start.top.y, end.top.y, fraction)),
    bottom = PointF(lerp(start.bottom.x, end.bottom.x, fraction), lerp(start.bottom.y, end.bottom.y, fraction)),
)

private fun lerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

private fun calculateCurlBackPath(
    width: Float,
    height: Float,
    topCurl: PointF,
    bottomCurl: PointF,
    isReversed: Boolean,
): List<PointF> {
    val path = mutableListOf<PointF>()
    if (isReversed) {
        if (topCurl.x > 0f) {
            path += topCurl
            path += PointF(0f, topCurl.y)
        } else {
            val i = lineLineIntersection(topCurl, bottomCurl, PointF(0f, 0f), PointF(0f, height)) ?: PointF(0f, 0f)
            path += i; path += i
        }
        if (bottomCurl.x > 0f) {
            path += PointF(0f, height)
            path += bottomCurl
        } else {
            val i = lineLineIntersection(topCurl, bottomCurl, PointF(0f, 0f), PointF(0f, height)) ?: PointF(0f, height)
            path += i; path += i
        }
    } else {
        if (topCurl.x < width) {
            path += topCurl
            path += PointF(width, topCurl.y)
        } else {
            val i = lineLineIntersection(topCurl, bottomCurl, PointF(width, 0f), PointF(width, height)) ?: PointF(width, 0f)
            path += i; path += i
        }
        if (bottomCurl.x < width) {
            path += PointF(width, height)
            path += bottomCurl
        } else {
            val i = lineLineIntersection(topCurl, bottomCurl, PointF(width, 0f), PointF(width, height)) ?: PointF(width, height)
            path += i; path += i
        }
    }
    return path
}

private fun lineLineIntersection(
    line1a: PointF, line1b: PointF, line2a: PointF, line2b: PointF,
): PointF? {
    val denominator = (line1a.x - line1b.x) * (line2a.y - line2b.y) -
        (line1a.y - line1b.y) * (line2a.x - line2b.x)
    if (denominator == 0f) return null
    val x1 = (line1a.x * line1b.y - line1a.y * line1b.x) * (line2a.x - line2b.x)
    val x2 = (line1a.x - line1b.x) * (line2a.x * line2b.y - line2a.y * line2b.x)
    val y1 = (line1a.x * line1b.y - line1a.y * line1b.x) * (line2a.y - line2b.y)
    val y2 = (line1a.y - line1b.y) * (line2a.x * line2b.y - line2a.y * line2b.x)
    return PointF((x1 - x2) / denominator, (y1 - y2) / denominator)
}

// ===== 自绘阅读页：Kototoro composeReaderPageCurl 的折叠绘制 =====

class CurlPageView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var foldProgress = 0f
    private var foldStartFraction = 0.85f
    private var isVertical = false
    private var isReversed = false

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
    }

    fun setFold(progress: Float, start: Float, vertical: Boolean, reversed: Boolean) {
        foldProgress = progress.coerceIn(0f, 1f)
        foldStartFraction = start
        isVertical = vertical
        isReversed = reversed
        invalidate()
    }

    fun clearFold() = setFold(0f, 0.85f, isVertical, isReversed)

    override fun dispatchDraw(canvas: Canvas) {
        if (foldProgress <= 0f) {
            super.dispatchDraw(canvas)
            return
        }
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) { super.dispatchDraw(canvas); return }

        val cw = if (isVertical) h else w
        val ch = if (isVertical) w else h
        val map: (PointF) -> PointF = { if (isVertical) PointF(it.y, it.x) else it }
        val geo = calculateCurlGeometry(
            width = cw, height = ch,
            progress = foldProgress,
            startFraction = foldStartFraction,
            isReversed = isReversed,
        )
        val front = geo.frontPoly.map(map).toPath()
        val back = geo.backPoly.map(map).toPath()
        val pivot = map(geo.bottomCurl)
        val topV = map(geo.topCurl)

        // 1) 前端（未翻起部分）：裁到 frontPoly 后正常画
        canvas.save()
        canvas.clipPath(front)
        super.dispatchDraw(canvas)
        canvas.restore()

        // 2) 背页（翻起部分）：绕折线底端先 scale 后 rotate（Kototoro 顺序）→ 镜像出纸背。
        //    ⚠️ 不再叠加深色/白色蒙版：白底漫画的空白区（上下页边）会因此闪灰，去 tint 只留镜像内容。
        canvas.save()
        val m = Matrix()
        val deg = Math.toDegrees(geo.angleRad.toDouble()).toFloat()
        if (isVertical) {
            m.setRotate(-deg, pivot.x, pivot.y)
            m.postScale(1f, -1f, pivot.x, pivot.y)
        } else {
            m.setRotate(deg, pivot.x, pivot.y)
            m.postScale(-1f, 1f, pivot.x, pivot.y)
        }
        canvas.concat(m)
        canvas.save()
        canvas.clipPath(back)
        super.dispatchDraw(canvas)
        canvas.restore()
        canvas.restore()

        // 3) 折痕线：薄细边缘线，非蒙版
        linePaint.color = Color.argb((30 + foldProgress * 50).toInt().coerceAtMost(120), 0, 0, 0)
        canvas.drawLine(topV.x, topV.y, pivot.x, pivot.y, linePaint)
    }
}

private fun List<PointF>.toPath(): Path = Path().apply {
    forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    close()
}