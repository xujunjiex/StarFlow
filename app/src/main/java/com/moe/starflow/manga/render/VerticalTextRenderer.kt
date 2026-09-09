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

object VerticalTextRenderer {

    /** 竖排字符步距系数（上下相邻字步进），与 OverlayRenderer.VERTICAL_CHAR_RATIO 必须一致 */
    private const val VERTICAL_CHAR_RATIO = 1.1f

    /** 横排换行行距系数（与竖排字距解耦，保持 1.2；非填充模式默认行距） */
    private const val HORIZONTAL_LINE_RATIO = 1.2f

    /** 横排「自动填充」模式字号选取的高度系数：留 ~5% 给行距/居中，比非填充更接近大字撑满 */
    private const val HORIZONTAL_FIT_RATIO = 1.05f

    /** 横排自动填充排版产物：字号 + 行列表 + 各行宽 + 每行基线 y + 字距（px，em = px/字号）。 */
    data class HorizontalFillLayout(
        val fontSize: Float,
        val lines: List<String>,
        val lineWidths: List<Float>,
        val baselines: List<Float>,
        val letterSpacing: Float
    )

    /**
     * 横排自动填充排版（自动字号模式「大字优先」）：译文尽量填满 region，全程不越界。
     * 调用前字号已由 calculateFitFontSize 选到「最大能放入」。三段填充 + 居中兜底：
     *  1. 行距拉伸填高（多行）：字形总高之外的剩余高度平均分到行间；
     *  2. 字距拉伸填宽（单行 + 横向富余）：一行恰好铺满区域宽；
     *  3. 仍有余量（行距夹上限/单行）→ 内容块垂直居中。
     */
    fun computeHorizontalFillLayout(text: String, region: Rect, fontSize: Float): HorizontalFillLayout {
        if (fontSize <= 0f || region.width() <= 0 || region.height() <= 0) {
            return HorizontalFillLayout(fontSize, emptyList(), emptyList(), emptyList(), 0f)
        }
        val maxWidth = region.width().toFloat()
        val regionH = region.height().toFloat()

        // 分行；若该字号下总字形高超区域高（如合并组文字偏大），按比例缩小字号重新分行，保证不越界
        var effectiveFont = fontSize
        var paint = Paint().apply { textSize = effectiveFont; isAntiAlias = true }
        var (lines, widths) = wrapTextLines(text, paint, maxWidth)
        var glyphHeight = lines.size * effectiveFont
        var guard = 0
        while (lines.isNotEmpty() && glyphHeight > regionH && guard < 5) {
            effectiveFont = (effectiveFont * (regionH / glyphHeight)).coerceAtLeast(1f)
            paint.textSize = effectiveFont
            val (l2, w2) = wrapTextLines(text, paint, maxWidth)
            lines = l2; widths = w2
            glyphHeight = lines.size * effectiveFont
            guard++
        }
        if (lines.isEmpty()) return HorizontalFillLayout(effectiveFont, emptyList(), emptyList(), emptyList(), 0f)

        val rowHeight = effectiveFont

        // 行距拉伸填高：剩余高度均分行间；间隙先夹到 [0.05, 1.5]×font，再收束到「可用值」以内保证不越界
        var lineSpacing = rowHeight
        if (lines.size > 1) {
            val leftover = (regionH - glyphHeight).coerceAtLeast(0f)
            val availableGap = leftover / (lines.size - 1)
            var gap = availableGap.coerceIn(rowHeight * 0.05f, rowHeight * 1.5f)
            gap = gap.coerceAtMost(availableGap.coerceAtLeast(0f))
            lineSpacing = rowHeight + gap
        }

        // 字距填宽：单行且横向有富余 → 铺满宽度；夹上限 1×font
        var letterSpacing = 0f
        if (lines.size == 1 && lines[0].length >= 2 && widths[0] < maxWidth) {
            val target = (maxWidth - widths[0]) / (lines[0].length - 1)
            letterSpacing = target.coerceIn(0f, rowHeight)
        }

        // 内容块高度 → 垂直居中兜底（blockHeight ≤ regionH，保证最后一行不超 bottom）
        val blockHeight = glyphHeight + (lines.size - 1) * (lineSpacing - rowHeight)
        val topPad = ((regionH - blockHeight) / 2f).coerceAtLeast(0f)
        val baselines = lines.indices.map { i -> region.top + topPad + rowHeight + i * lineSpacing }

        return HorizontalFillLayout(effectiveFont, lines, widths, baselines, letterSpacing)
    }

    /** 按 \n 分段 + breakText 换行，返回（行, 各行宽）。空段保留为空行（占一行高）。 */
    private fun wrapTextLines(text: String, paint: Paint, maxWidth: Float): Pair<List<String>, List<Float>> {
        val lines = mutableListOf<String>()
        val widths = mutableListOf<Float>()
        for (paragraph in text.split("\n")) {
            if (paragraph.isEmpty()) {
                lines.add(""); widths.add(0f)
                continue
            }
            var remaining = paragraph
            while (remaining.isNotEmpty()) {
                val count = paint.breakText(remaining, true, maxWidth, null)
                if (count <= 0) break
                val line = remaining.substring(0, count)
                lines.add(line)
                widths.add(paint.measureText(line))
                remaining = remaining.substring(count)
            }
        }
        return lines to widths
    }

    // 从上到下，列从右到左（传统日漫）
    fun drawVerticalTextRL(
        canvas: Canvas,
        text: String,
        region: Rect,
        fontSize: Float = 16f,
        textColor: Int = Color.BLACK,
        centered: Boolean = false,
        columnSpacingOverride: Float? = null,
        fontTypeface: Typeface? = null
    ) {
        val paint = Paint().apply {
            color = textColor
            textSize = fontSize
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = fontTypeface ?: Typeface.DEFAULT
        }

        val charHeight = fontSize * VERTICAL_CHAR_RATIO
        val columnSpacing = columnSpacingOverride ?: (fontSize * VERTICAL_CHAR_RATIO)
        val charsPerColumn = maxOf(1, ((region.height() - fontSize) / charHeight).toInt() + 1)
        val columns = (text.length + charsPerColumn - 1) / charsPerColumn
        // 水平居中：列组中心对齐 region 中心（最右列中心）；否则从右缘开始
        var currentX = if (centered) {
            region.centerX() + (columns * columnSpacing) / 2f - columnSpacing / 2
        } else {
            region.right - columnSpacing / 2
        }
        // 垂直：单列短文字时文字块垂直居中（避免一列占满高但只有几个字）；多列从顶部开始（每列填满）
        val textHeight = minOf(text.length, charsPerColumn) * charHeight
        var currentY = if (centered && columns <= 1) {
            (region.top + region.bottom - textHeight) / 2 + fontSize
        } else {
            region.top + fontSize
        }

        // 裁剪到区域内，防止文字溢出
        canvas.save()
        canvas.clipRect(region)

        for (char in text) {
            if (currentY > region.bottom) {
                currentX -= columnSpacing
                currentY = region.top + fontSize
                if (currentX < region.left) break
            }
            canvas.drawText(char.toString(), currentX, currentY, paint)
            currentY += charHeight
        }

        canvas.restore()
    }

    // 从上到下，列从左到右
    fun drawVerticalTextLR(
        canvas: Canvas,
        text: String,
        region: Rect,
        fontSize: Float = 16f,
        textColor: Int = Color.BLACK,
        centered: Boolean = false,
        columnSpacingOverride: Float? = null,
        fontTypeface: Typeface? = null
    ) {
        val paint = Paint().apply {
            color = textColor
            textSize = fontSize
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = fontTypeface ?: Typeface.DEFAULT
        }

        val charHeight = fontSize * VERTICAL_CHAR_RATIO
        val columnSpacing = columnSpacingOverride ?: (fontSize * VERTICAL_CHAR_RATIO)
        val charsPerColumn = maxOf(1, ((region.height() - fontSize) / charHeight).toInt() + 1)
        val columns = (text.length + charsPerColumn - 1) / charsPerColumn
        // 水平居中：列组中心对齐 region 中心（最左列中心）；否则从左缘开始
        var currentX = if (centered) {
            region.centerX() - (columns * columnSpacing) / 2f + columnSpacing / 2
        } else {
            region.left + columnSpacing / 2
        }
        // 垂直：单列短文字时文字块垂直居中；多列从顶部开始
        val textHeight = minOf(text.length, charsPerColumn) * charHeight
        var currentY = if (centered && columns <= 1) {
            (region.top + region.bottom - textHeight) / 2 + fontSize
        } else {
            region.top + fontSize
        }

        // 裁剪到区域内，防止文字溢出
        canvas.save()
        canvas.clipRect(region)

        for (char in text) {
            if (currentY > region.bottom) {
                currentX += columnSpacing
                currentY = region.top + fontSize
                if (currentX > region.right) break
            }
            canvas.drawText(char.toString(), currentX, currentY, paint)
            currentY += charHeight
        }

        canvas.restore()
    }

    fun drawHorizontalText(
        canvas: Canvas,
        text: String,
        region: Rect,
        fontSize: Float = 16f,
        textColor: Int = Color.BLACK,
        fontTypeface: Typeface? = null,
        layout: HorizontalFillLayout? = null
    ) {
        val paint = Paint().apply {
            color = textColor
            textSize = layout?.fontSize ?: fontSize
            isAntiAlias = true
            typeface = fontTypeface ?: Typeface.DEFAULT
        }

        // 自动填充模式：按预计算的行/基线/字距绘制（多行行距已拉伸填高、单行字距已填宽、内容垂直居中）
        if (layout != null && layout.lines.isNotEmpty()) {
            if (layout.letterSpacing > 0f && paint.textSize > 0f) {
                paint.letterSpacing = layout.letterSpacing / paint.textSize  // em = px/字号（API 21+）
            }
            for (i in layout.lines.indices) {
                val line = layout.lines[i]
                if (line.isEmpty()) continue
                // 水平居中；有字距时按铺满后的实际宽度居中
                val spacedWidth = layout.lineWidths[i] + layout.letterSpacing * (line.length - 1).coerceAtLeast(0)
                val x = region.centerX() - spacedWidth / 2f
                canvas.drawText(line, x, layout.baselines[i], paint)
            }
            return
        }

        val lineHeight = fontSize * HORIZONTAL_LINE_RATIO
        val maxWidth = region.width().toFloat()
        var currentY = region.top + fontSize

        val paragraphs = text.split("\n")
        for (paragraph in paragraphs) {
            if (currentY > region.bottom) break
            if (paragraph.isEmpty()) {
                currentY += lineHeight
                continue
            }

            var remaining = paragraph
            while (remaining.isNotEmpty() && currentY <= region.bottom) {
                val count = paint.breakText(remaining, true, maxWidth, null)
                if (count <= 0) break
                val line = remaining.substring(0, count)
                canvas.drawText(line, region.left.toFloat(), currentY, paint)
                currentY += lineHeight
                remaining = remaining.substring(count)
            }
        }
    }

    fun drawText(
        canvas: Canvas,
        text: String,
        region: Rect,
        direction: TextDirection,
        fontSize: Float = 16f,
        textColor: Int = Color.BLACK,
        autoFit: Boolean = true,
        centered: Boolean = false,
        columnSpacingOverride: Float? = null,
        fontTypeface: Typeface? = null,
        horizontalLayout: HorizontalFillLayout? = null
    ) {
        var actualFontSize = fontSize
        if (autoFit) {
            actualFontSize = calculateFitFontSize(text, region, direction, fontSize)
        }
        when (direction) {
            TextDirection.VERTICAL_RL -> drawVerticalTextRL(canvas, text, region, actualFontSize, textColor, centered, columnSpacingOverride, fontTypeface)
            TextDirection.VERTICAL_LR -> drawVerticalTextLR(canvas, text, region, actualFontSize, textColor, centered, columnSpacingOverride, fontTypeface)
            TextDirection.HORIZONTAL -> drawHorizontalText(canvas, text, region, actualFontSize, textColor, fontTypeface, horizontalLayout)
        }
    }

    fun calculateFitFontSize(
        text: String,
        region: Rect,
        direction: TextDirection,
        maxFontSize: Float
    ): Float {
        val regionWidth = region.width().toFloat()
        val regionHeight = region.height().toFloat()
        if (regionWidth <= 0 || regionHeight <= 0 || text.isEmpty()) return maxFontSize

        // 字体上限不能超过矩形本身能容纳的大小
        val rectLimit = when (direction) {
            TextDirection.VERTICAL_RL, TextDirection.VERTICAL_LR -> regionHeight / VERTICAL_CHAR_RATIO
            TextDirection.HORIZONTAL -> regionWidth / HORIZONTAL_LINE_RATIO
        }
        val cappedMax = minOf(maxFontSize, rectLimit)

        val minFontSize = 8f
        // 二分查找最优字体大小
        var lo = minFontSize
        var hi = cappedMax
        var best = minFontSize

        while (hi - lo >= 0.5f) {
            val mid = (lo + hi) / 2
            if (doesTextFit(text, region, direction, mid)) {
                best = mid
                lo = mid
            } else {
                hi = mid
            }
        }
        return best
    }

    private fun doesTextFit(
        text: String,
        region: Rect,
        direction: TextDirection,
        fontSize: Float
    ): Boolean {
        val charHeight = fontSize * VERTICAL_CHAR_RATIO
        val columnSpacing = fontSize * VERTICAL_CHAR_RATIO
        val regionWidth = region.width().toFloat()
        val regionHeight = region.height().toFloat()

        return when (direction) {
            TextDirection.VERTICAL_RL, TextDirection.VERTICAL_LR -> {
                val charsPerColumn = (regionHeight / charHeight).toInt()
                val columns = if (charsPerColumn > 0) (text.length + charsPerColumn - 1) / charsPerColumn else text.length
                val neededWidth = columns * columnSpacing
                neededWidth <= regionWidth && charsPerColumn > 0
            }
            TextDirection.HORIZONTAL -> {
                val paint = Paint().apply { textSize = fontSize }
                // 高度预算用 1.05（字形高 + 少量间隙）：与自动填充布局一致，让字号更接近「大字撑满」
                val lineHeight = fontSize * HORIZONTAL_FIT_RATIO
                val maxLines = (regionHeight / lineHeight).toInt()
                var lines = 0
                val paragraphs = text.split("\n")
                for (paragraph in paragraphs) {
                    if (paragraph.isEmpty()) {
                        lines++
                        if (lines > maxLines) return false
                        continue
                    }
                    var remaining = paragraph
                    while (remaining.isNotEmpty()) {
                        val count = paint.breakText(remaining, true, regionWidth, null)
                        if (count <= 0) break
                        remaining = remaining.substring(count)
                        lines++
                        if (lines > maxLines) return false
                    }
                }
                true
            }
        }
    }
}
