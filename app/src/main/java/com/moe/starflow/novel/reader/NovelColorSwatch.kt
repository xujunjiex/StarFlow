package com.moe.starflow.novel.reader

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Small color sample; honors both layout dimensions, including non-square preview strips. */
class NovelColorSwatch @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var color: Int = 0
        set(value) { field = value; invalidate() }
    var dark: Boolean = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val clip = Path()
    private val density = resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (20 * density).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = density * 0.5f
        bounds.set(inset, inset, width - inset, height - inset)
        val radius = minOf(bounds.width(), bounds.height()) / 2f
        clip.reset()
        clip.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        val saved = canvas.save()
        canvas.clipPath(clip)
        val cell = (3 * density).coerceAtLeast(1f)
        paint.style = Paint.Style.FILL
        val light = if (dark) 0xFF474B50.toInt() else 0xFFF4F6F8.toInt()
        val shade = if (dark) 0xFF30343A.toInt() else 0xFFDCE2E7.toInt()
        var row = 0
        var y = 0f
        while (y < height) {
            var column = 0
            var x = 0f
            while (x < width) {
                paint.color = if ((row + column) % 2 == 0) light else shade
                canvas.drawRect(x, y, x + cell, y + cell, paint)
                x += cell
                column++
            }
            y += cell
            row++
        }
        paint.color = color
        canvas.drawRect(bounds, paint)
        canvas.restoreToCount(saved)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = if (dark) 0x667F8A94 else 0x55707C86
        canvas.drawRoundRect(bounds, radius, radius, paint)
    }
}
