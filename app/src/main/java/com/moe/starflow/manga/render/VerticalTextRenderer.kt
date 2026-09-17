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
            for (line in layout.lines) {
                var y = line.baseline
                for (ch in line.text) {
                    canvas.drawText(ch.toString(), line.x, y, paint)
                    y += layout.charStep
                }
            }
        } else {
            for (line in layout.lines) {
                if (line.text.isEmpty()) continue
                canvas.drawText(line.text, line.x, line.baseline, paint)
            }
        }
    }
}
