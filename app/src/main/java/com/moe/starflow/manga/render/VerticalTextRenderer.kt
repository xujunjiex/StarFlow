package com.moe.starflow.manga.render
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.state.*
import com.moe.starflow.manga.*

import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

/**
 * 排版产物 → 画布。**纯绘制，不做任何布局决策**。
 *
 * 所有几何（字号、断行/分列、字距、行距、每行位置）都已由 [LayoutEngine.plan] 算定并保证不越界；
 * 这里只负责把 [TextLayout] 画出来。`clipRect` 保留为兜底保险 ——
 * 历史上它曾是「主力」（布局溢出被静默裁掉），现在不该再有东西可裁。
 */
object VerticalTextRenderer {

    fun draw(canvas: Canvas, layout: TextLayout, textColor: Int, fontTypeface: Typeface? = null) {
        if (layout.isEmpty) return
        val paint = Paint().apply {
            color = textColor
            textSize = layout.fontSize
            isAntiAlias = true
            typeface = fontTypeface ?: Typeface.DEFAULT
        }
        // 竖排逐字绘制（居中于列心）；横排整行绘制
        val vertical = layout.charStep > 0f
        paint.textAlign = if (vertical) Paint.Align.CENTER else Paint.Align.LEFT
        if (!vertical && layout.tracking > 0f && layout.fontSize > 0f) {
            paint.letterSpacing = layout.tracking / layout.fontSize   // em = px/字号（API 21+）
        }

        // ⚠️ 不设 canvas.clipRect：内核产物已构造性保证不越界（含最小 padding），
        // 这里再裁剪只会把「本该暴露的布局 bug」藏起来 —— 旧实现正是靠 clipRect 静默吃掉溢出字符，
        // 才让「最后一行显示不完整」查无实据。真越界时宁可画出来看见，也不要无声无息。
        if (vertical) {
            val glyphs = HashMap<Char, VerticalGlyphRules.Glyph>()
            val bounds = Rect()
            val defaultTypefacePaint = Paint(paint).apply { typeface = Typeface.DEFAULT }
            val fontSize = layout.fontSize
            for (line in layout.lines) {
                var y = line.baseline
                for (unit in VerticalTypography.units(line.text)) {
                    if (unit.isTcy) {
                        // 縦中横：整组数字横排占一格，按墨迹居中并压到 1.1 字宽以内。
                        drawTcyGroup(canvas, unit.text, line.x, y - fontSize / 2f, paint)
                    } else {
                        val ch = unit.text[0]
                        val glyph = glyphs.getOrPut(ch) {
                            VerticalGlyphRules.resolve(ch, paint::hasGlyph, defaultTypefacePaint::hasGlyph)
                        }
                        // 压缩标点也按整格基线落笔（墨迹仍在右上角），只有下面的步进减半。
                        drawVerticalGlyph(canvas, glyph, line.x, y, paint, bounds)
                    }
                    y += VerticalTypography.unitAdvance(unit, fontSize) + layout.tracking
                }
            }
        } else {
            for (line in layout.lines) {
                if (line.text.isEmpty()) continue
                canvas.drawText(line.text, line.x, line.baseline, paint)
            }
        }
    }

    /**
     * 縦中横：把一组数字横向画在一格内。
     *
     * 与 [LayoutEngine] 的约定：TCY 格纵向占 1 em，横向最多 [VerticalTypography.TCY_MAX_WIDTH_RATIO] 字宽，
     * 超宽只做横向压缩（不折行、不丢字）。按墨迹中心落在格心，避免不同数字宽度造成视觉偏移。
     */
    private fun drawTcyGroup(canvas: Canvas, text: String, cx: Float, centerY: Float, paint: Paint) {
        if (text.isEmpty()) return
        val fontSize = paint.textSize
        if (fontSize <= 0f) return
        val width = paint.measureText(text)
        if (width <= 0f) return
        val scaleX = (fontSize * VerticalTypography.TCY_MAX_WIDTH_RATIO / width)
            .coerceIn(0.05f, 1f)
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val dx = if (bounds.isEmpty) 0f else -(bounds.left + bounds.right) / 2f
        val dy = if (bounds.isEmpty) 0f else -(bounds.top + bounds.bottom) / 2f
        val previousAlign = paint.textAlign
        val saved = canvas.save()
        try {
            canvas.translate(cx, centerY)
            canvas.scale(scaleX, 1f)
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(text, dx, dy, paint)
        } finally {
            paint.textAlign = previousAlign
            canvas.restoreToCount(saved)
        }
    }

    private fun drawVerticalGlyph(
        canvas: Canvas, glyph: VerticalGlyphRules.Glyph, x: Float, baseline: Float,
        paint: Paint, bounds: Rect,
    ) {
        val previousTypeface = paint.typeface
        if (glyph.useDefaultTypeface) paint.typeface = Typeface.DEFAULT
        try {
            if (glyph.rotation == 0f && !glyph.corner) {
                canvas.drawText(glyph.text, x, baseline, paint)
                return
            }
            paint.getTextBounds(glyph.text, 0, glyph.text.length, bounds)
            if (bounds.isEmpty) {
                canvas.drawText(glyph.text, x, baseline, paint)
                return
            }
            val size = paint.textSize
            // Fallback rotation and corner positioning stay inside this character cell.
            val saved = canvas.save()
            val previousAlign = paint.textAlign
            try {
                paint.textAlign = Paint.Align.LEFT
                if (glyph.corner) {
                    val scale = minOf(1f, size * 0.5f / maxOf(bounds.width(), bounds.height()))
                    canvas.translate(x + size * 0.45f, baseline - size * 0.95f)
                    canvas.scale(scale, scale)
                    canvas.drawText(glyph.text, -bounds.right.toFloat(), -bounds.top.toFloat(), paint)
                } else if (glyph.rotation != 0f) {
                    canvas.translate(x, baseline - size / 2f)
                    canvas.rotate(glyph.rotation)
                    val scale = minOf(1f, size / maxOf(bounds.width(), bounds.height()))
                    canvas.scale(scale, scale)
                    canvas.drawText(glyph.text, -bounds.exactCenterX(), -bounds.exactCenterY(), paint)
                }
            } finally {
                paint.textAlign = previousAlign
                canvas.restoreToCount(saved)
            }
        } finally {
            paint.typeface = previousTypeface
        }
    }
}
