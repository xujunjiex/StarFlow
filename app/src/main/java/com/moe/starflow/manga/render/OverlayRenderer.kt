package com.moe.starflow.manga.render
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.state.*
import com.moe.starflow.manga.*

import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.config.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.types.TextDirection
import com.moe.starflow.utils.CustomPreference

object OverlayRenderer {

    /**
     * 加载自定义结果字体（`Custom_Result_Font`，文件在 `getExternalFilesDir/font/`）。
     * 无设置/文件缺失/加载失败返回 null（调用方用默认字体）。游戏与漫画模式共用。
     */
    fun loadResultTypeface(context: Context, prefs: CustomPreference): Typeface? {
        val name = prefs.getString("Custom_Result_Font", "")
        if (name.isEmpty()) return null
        return try {
            val file = java.io.File(context.getExternalFilesDir(null), "font/$name")
            if (file.exists()) Typeface.createFromFile(file) else null
        } catch (e: Exception) {
            null
        }
    }

    /** 合并 overlay 的分隔记号：竖排组间 / 横排组间 */
    private const val VERTICAL_SEPARATOR = "◇"
    private const val HORIZONTAL_SEPARATOR = "──"

    /**
     * 竖排字符步距系数，必须与 VerticalTextRenderer 绘制一致（其内部 VERTICAL_CHAR_RATIO）。
     * 尺寸计算若用更大的系数会高估每列容量不足 → 列数算多 → drawRect 宽出 1-2 列空白。
     */
    private const val VERTICAL_CHAR_RATIO = 1.1f

    /**
     * 横排行距系数（仅横排换行步进），保持与竖排字距解耦。
     */
    private const val HORIZONTAL_LINE_RATIO = 1.2f

    /**
     * 竖排每列可容纳字符数，与 VerticalTextRenderer 绘制逻辑一致：
     * 起点 y = top + fontSize，每字步进 fontSize*VERTICAL_CHAR_RATIO，超出 bottom 换列。
     */
    private fun capacityForHeight(height: Int, fontSize: Float): Int {
        if (height <= 0) return 1
        val step = fontSize * VERTICAL_CHAR_RATIO
        return maxOf(1, ((height - fontSize) / step).toInt() + 1)
    }

    /** 竖排布局结果：列数 + 拉伸后的列距 */
    private data class VerticalLayout(val columns: Int, val spacing: Float)

    /**
     * 竖排布局：列数由每列容量决定；列距在小范围 [1.0fs, 1.8fs] 内拉伸，
     * 让文字列宽尽量填满 region 宽。
     *
     * 背景：列数是整数离散的（3 字要么 1 列要么 2 列），fit 字号无法精确填满气泡宽，
     * 导致左侧空白列；拉伸列距（气泡宽/列数，clamp 到合理范围）可消除该空白。
     */
    private fun verticalLayout(textLength: Int, region: Rect, fontSize: Float): VerticalLayout {
        val charsPerColumn = capacityForHeight(region.height(), fontSize)
        val columns = (textLength + charsPerColumn - 1) / charsPerColumn
        val target = region.width().toFloat() / columns.coerceAtLeast(1)
        val spacing = target.coerceIn(fontSize, fontSize * 1.8f)
        return VerticalLayout(columns, spacing)
    }

    /** 单气泡的绘制参数（Phase 1 产物） */
    private data class Param(
        val region: TranslatedBubble,
        val displayText: String,
        val fitFontSize: Float,
        val neededRect: Rect
    )

    /** 一个绘制单元：单气泡，或同方向合并组（多气泡文本用记号连接成一个白块） */
    private data class DrawItem(
        val drawRect: Rect,
        val displayTexts: List<String>,
        val direction: TextDirection,
        val fitFontSize: Float,
        val merged: Boolean,
        val angle: Float = 0f,
        val centerX: Float = -1f,
        val centerY: Float = -1f
    )

    fun renderOverlay(
        original: Bitmap,
        regions: List<TranslatedBubble>,
        fontSize: Float = 16f,
        autoFit: Boolean = true,
        textColor: Int = Color.BLACK,
        bgColor: Int = Color.argb(200, 255, 255, 255),
        useOriginalText: Boolean = false,
        verticalDirection: TextDirection? = null,
        fontTypeface: Typeface? = null,
        showCacheMarker: Boolean = false  // 缓存命中标记开关（⚡，默认关闭）
    ): Bitmap {
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        // 竖排方向覆盖：所有竖排气泡（RL 或 LR）统一用当前配置方向，横排保持。
        // 保证历史/缓存命中的气泡（方向可能是旧设置时存的）也按当前设置实时渲染。
        val effectiveRegions = if (verticalDirection != null) {
            regions.map { r ->
                if (r.direction == TextDirection.VERTICAL_RL || r.direction == TextDirection.VERTICAL_LR) {
                    r.copy(direction = verticalDirection)
                } else r
            }
        } else regions

        // Phase 1: 每气泡的绘制参数（文字、字号、所需矩形）
        val params = effectiveRegions.map { region ->
            // 实际显示的文字：原文模式用 originalText，否则用译文
            // 注意：⚡ 标志只用于翻译进程中的内存缓存命中（isInMemoryCache），数据库反序列化的 bubbles 永远不显示 ⚡
            val displayText = if (useOriginalText) {
                region.originalText
            } else if (region.isInMemoryCache && showCacheMarker) {
                "⚡${region.translatedText}"
            } else {
                region.translatedText
            }
            val baseFontSize = if (autoFit) region.fontSize else fontSize
            // 自动：尽量放大填满气泡；非自动：用户字号原样，绝不缩放
            val fitFontSize = if (autoFit) {
                VerticalTextRenderer.calculateFitFontSize(
                    displayText, region.rect, region.direction, baseFontSize
                )
            } else {
                baseFontSize
            }
            // 自动模式：drawRect 用原始气泡（覆盖原文区域），文字靠 fit 字号 + 列距填满气泡宽
            // 非自动模式：drawRect 贴合文字（收缩居中/平衡扩展）
            val neededRect = if (autoFit) {
                region.rect
            } else {
                calculateCompactRect(region.rect, displayText, region.direction, fitFontSize)
            }
            Param(region, displayText, fitFontSize, neededRect)
        }

        // Phase 2: neededRect 重叠的气泡合并成组（union-find 传递闭包）
        val groupOf = mergeOverlapping(params)

        // Phase 3: 构建绘制单元。同方向合并组 → 一个大白块 + 记号分隔；异方向/倾斜/字号不一致 → 独立绘制
        val drawItems = mutableListOf<DrawItem>()
        params.indices.groupBy { groupOf[it] }.forEach { (_, ids) ->
            val members = ids.map { params[it] }.filter { it.displayText.isNotEmpty() }
            if (members.isEmpty()) return@forEach
            val singleton: DrawItem = {
                val m = members[0]
                DrawItem(
                    m.neededRect, listOf(m.displayText), m.region.direction, m.fitFontSize,
                    merged = false, m.region.angle, m.region.centerX, m.region.centerY
                )
            }()
            if (members.size == 1) {
                drawItems += singleton
            } else if (members.all { !hasTilt(it.region) } &&
                members.all { it.region.direction == members[0].region.direction } &&
                members.all { kotlin.math.abs(it.fitFontSize - members[0].fitFontSize) < 2f }
            ) {
                // 同方向、无倾斜、字号一致 → 合并为一个白块，组内用记号分隔
                drawItems += buildMergedItem(members)
            } else {
                // 异方向/倾斜/字号差异 → 各自独立绘制（不合并，允许重叠）
                members.forEach { m ->
                    drawItems += DrawItem(
                        m.neededRect, listOf(m.displayText), m.region.direction, m.fitFontSize,
                        merged = false, m.region.angle, m.region.centerX, m.region.centerY
                    )
                }
            }
        }

        // 绘制（面积降序，避免小单元被大单元的白块覆盖）
        val bgPaint = Paint().apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        drawItems.sortedByDescending { it.drawRect.width() * it.drawRect.height() }.forEach { item ->
            canvas.save()
            if (kotlin.math.abs(item.angle) > 0.5f) {
                canvas.rotate(item.angle, item.centerX, item.centerY)
            }
            canvas.save()
            canvas.clipRect(item.drawRect)
            canvas.drawBitmap(original, 0f, 0f, null)
            canvas.restore()

            canvas.drawRect(item.drawRect, bgPaint)

            canvas.save()
            canvas.clipRect(item.drawRect)
            val text = if (item.merged) {
                val sep = if (item.direction == TextDirection.HORIZONTAL) HORIZONTAL_SEPARATOR else VERTICAL_SEPARATOR
                item.displayTexts.joinToString(sep)
            } else {
                item.displayTexts[0]
            }
            // 竖排列距在小范围拉伸填满 drawRect 宽（列数是整数离散的，字号无法精确填满，
            // 调列距可消除左侧空白列）
            val columnSpacing = if (text.isNotEmpty() &&
                (item.direction == TextDirection.VERTICAL_RL || item.direction == TextDirection.VERTICAL_LR)
            ) {
                verticalLayout(text.length, item.drawRect, item.fitFontSize).spacing
            } else {
                null
            }
            // 横排自动模式：填充排版（行距填高/字距填宽/垂直居中），译文尽量填满选区且不越界
            val horizontalLayout = if (autoFit && item.direction == TextDirection.HORIZONTAL && text.isNotEmpty()) {
                VerticalTextRenderer.computeHorizontalFillLayout(text, item.drawRect, item.fitFontSize)
            } else {
                null
            }
            VerticalTextRenderer.drawText(
                canvas = canvas,
                text = text,
                region = item.drawRect,
                direction = item.direction,
                fontSize = item.fitFontSize,
                textColor = textColor,
                autoFit = false,
                // 竖排列组水平居中：避免文字从右缘开始导致左侧整片空白
                centered = true,
                columnSpacingOverride = columnSpacing,
                fontTypeface = fontTypeface,
                horizontalLayout = horizontalLayout
            )
            canvas.restore()
            canvas.restore()
        }

        return result
    }

    private fun hasTilt(region: TranslatedBubble): Boolean = kotlin.math.abs(region.angle) > 0.5f

    /** union-find：neededRect 两两相交 → 归为同一合并组 */
    private fun mergeOverlapping(params: List<Param>): IntArray {
        val n = params.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            if (parent[x] != x) parent[x] = find(parent[x])
            return parent[x]
        }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (Rect.intersects(params[i].neededRect, params[j].neededRect)) {
                    val ri = find(i)
                    val rj = find(j)
                    if (ri != rj) parent[rj] = ri
                }
            }
        }
        return IntArray(n) { find(it) }
    }

    /**
     * 同方向合并组：各成员文本用记号连接成一个绘制单元。
     * drawRect 容纳连接文本，锚点在组内流向起始角（VERTICAL_RL 右上、VERTICAL_LR/HORIZONTAL 左上），
     * 宽高贴合文本所需，避免重叠的白块叠白块。
     */
    private fun buildMergedItem(members: List<Param>): DrawItem {
        val direction = members[0].region.direction
        val fontSize = members[0].fitFontSize
        // 按各方向阅读顺序排列成员：
        // VERTICAL_RL 列从右往左（右列先、同列上先）；VERTICAL_LR/HORIZONTAL 左→右、上→下
        val sorted = when (direction) {
            TextDirection.VERTICAL_RL -> members.sortedWith(
                compareByDescending<Param> { it.neededRect.right }.thenBy { it.neededRect.top }
            )
            TextDirection.VERTICAL_LR -> members.sortedWith(
                compareBy<Param> { it.neededRect.left }.thenBy { it.neededRect.top }
            )
            TextDirection.HORIZONTAL -> members.sortedWith(
                compareBy<Param> { it.neededRect.top }.thenBy { it.neededRect.left }
            )
        }
        val texts = sorted.map { it.displayText }

        val top = members.minOf { it.neededRect.top }
        val bottom = members.maxOf { it.neededRect.bottom }
        val leftAll = members.minOf { it.neededRect.left }
        val rightAll = members.maxOf { it.neededRect.right }
        val height = (bottom - top).coerceAtLeast(1)

        val charHeight = fontSize * HORIZONTAL_LINE_RATIO
        val padding = (fontSize * 0.4f).toInt()

        val drawRect = when (direction) {
            TextDirection.VERTICAL_RL -> {
                val sep = VERTICAL_SEPARATOR
                val mergedText = texts.joinToString(sep)
                // 宽高平衡：限制列数，避免合并文本无脑横向铺开
                val (bw, bh) = balancedVerticalSize(mergedText.length, fontSize, height, rightAll - leftAll)
                val width = bw + 2 * padding
                val drawHeight = maxOf(height, bh + 2 * padding)
                Rect(rightAll - width, top, rightAll, top + drawHeight)
            }
            TextDirection.VERTICAL_LR -> {
                val sep = VERTICAL_SEPARATOR
                val mergedText = texts.joinToString(sep)
                val (bw, bh) = balancedVerticalSize(mergedText.length, fontSize, height, rightAll - leftAll)
                val width = bw + 2 * padding
                val drawHeight = maxOf(height, bh + 2 * padding)
                Rect(leftAll, top, leftAll + width, top + drawHeight)
            }
            TextDirection.HORIZONTAL -> {
                val sep = HORIZONTAL_SEPARATOR
                val mergedText = texts.joinToString(sep)
                val paint = Paint().apply { textSize = fontSize }
                val maxLineWidth = (rightAll - leftAll).toFloat().coerceAtLeast(1f)
                var lines = 0
                for (paragraph in mergedText.split("\n")) {
                    if (paragraph.isEmpty()) { lines++; continue }
                    var remaining = paragraph
                    while (remaining.isNotEmpty()) {
                        val count = paint.breakText(remaining, true, maxLineWidth, null)
                        if (count <= 0) break
                        remaining = remaining.substring(count)
                        lines++
                    }
                }
                val w = (rightAll - leftAll) + 2 * padding
                val h = (lines * charHeight).toInt() + 2 * padding
                Rect(leftAll, top, leftAll + w, top + h)
            }
        }
        return DrawItem(drawRect, texts, direction, fontSize, merged = true)
    }

    /**
     * 竖排文字平衡扩展尺寸：限制列数上限，列数超出时加大列高（利用垂直空间）换窄宽度。
     * 避免长译文在矮气泡里"无脑横向"（每列 1 字 → 列数 = 字数 → 覆盖大片无内容区域）。
     *
     * @param textLength 文字长度
     * @param fontSize 字号
     * @param regionHeight 区域高度（原气泡高 / 合并组并集高），决定自然列数
     * @param regionWidth 区域宽度（原气泡宽 / 合并组并集宽），决定列数上限
     * @return (宽, 高) 文字所需尺寸
     */
    private fun balancedVerticalSize(
        textLength: Int,
        fontSize: Float,
        regionHeight: Int,
        regionWidth: Int
    ): Pair<Int, Int> {
        val charHeight = fontSize * VERTICAL_CHAR_RATIO
        val columnSpacing = fontSize * VERTICAL_CHAR_RATIO
        // 最多列数：至少 4 列，宽区域可更多
        val maxColumns = maxOf(4, (regionWidth / columnSpacing).toInt()).coerceAtLeast(1)
        // 自然列数：优先利用区域高度（每列尽量多字），容量与绘制函数一致
        val baseCharsPerCol = capacityForHeight(regionHeight, fontSize)
        val naturalColumns = (textLength + baseCharsPerCol - 1) / baseCharsPerCol
        return if (naturalColumns <= maxColumns) {
            // 区域高度足够 → 保持自然列数
            val w = (naturalColumns * columnSpacing).toInt()
            val h = (minOf(textLength, baseCharsPerCol) * charHeight).toInt()
            w to h
        } else {
            // 列数超上限 → 加大列高（高度扩展）换窄宽度，最多 maxColumns 列
            val charsPerCol = (textLength + maxColumns - 1) / maxColumns
            val w = (maxColumns * columnSpacing).toInt()
            val h = (charsPerCol * charHeight).toInt()
            w to h
        }
    }

    /**
     * 非自动模式专用：计算文字实际所需矩形。
     *
     * 背景问题：小字号时文字不填满气泡，若 drawRect 用整个气泡 rect，背景色块会覆盖
     * 大片空白。这里把 drawRect 收缩到「文字实际尺寸 + 小 padding」，视觉上白底贴合文字。
     *
     * 文字超出气泡（大字号长译文）时按文字实际所需尺寸扩展（贴合形状），锚点在文字流向
     * 起始角；宽高都贴合文字，避免只横向拉宽 + 保留气泡原高导致的形状失衡与截断。
     */
    private fun calculateCompactRect(
        rect: Rect,
        text: String,
        direction: TextDirection,
        fontSize: Float
    ): Rect {
        val charHeight = fontSize * VERTICAL_CHAR_RATIO
        val columnSpacing = fontSize * VERTICAL_CHAR_RATIO
        val padding = (fontSize * 0.4f).toInt()

        val textW: Float
        val textH: Float
        when (direction) {
            TextDirection.VERTICAL_RL, TextDirection.VERTICAL_LR -> {
                // 列距拉伸填满气泡宽（列数离散，fit 字号无法精确填满，调列距消除左侧空白）
                val layout = verticalLayout(text.length, rect, fontSize)
                val charsPerColumn = capacityForHeight(rect.height(), fontSize)
                textW = layout.columns * layout.spacing
                textH = minOf(text.length, charsPerColumn) * charHeight
            }
            TextDirection.HORIZONTAL -> {
                val paint = Paint().apply { textSize = fontSize }
                val maxLineWidth = rect.width().toFloat()
                var lines = 0
                var maxLineW = 0f
                for (paragraph in text.split("\n")) {
                    if (paragraph.isEmpty()) { lines++; continue }
                    var remaining = paragraph
                    while (remaining.isNotEmpty()) {
                        val count = paint.breakText(remaining, true, maxLineWidth, null)
                        if (count <= 0) break
                        val line = remaining.substring(0, count)
                        maxLineW = maxOf(maxLineW, paint.measureText(line))
                        remaining = remaining.substring(count)
                        lines++
                    }
                }
                textW = maxLineW
                textH = lines * fontSize * HORIZONTAL_LINE_RATIO
            }
        }

        // 文字本体超出气泡 → 扩展：锚点在文字流向起始角（VERTICAL_RL 右上、VERTICAL_LR/HORIZONTAL 左上）。
        if (textW > rect.width() || textH > rect.height()) {
            if (direction == TextDirection.VERTICAL_RL || direction == TextDirection.VERTICAL_LR) {
                // 竖排：宽高平衡扩展。长译文在矮气泡里若每列 1 字会无脑横向铺开，
                // 覆盖大片无内容区域；限制列数、加大列高（利用垂直空间）换窄宽度。
                val (bw, bh) = balancedVerticalSize(text.length, fontSize, rect.height(), rect.width())
                val w = maxOf(1, bw + 2 * padding)
                val h = maxOf(1, bh + 2 * padding)
                val left = if (direction == TextDirection.VERTICAL_RL) rect.right - w else rect.left
                return Rect(left, rect.top, left + w, rect.top + h)
            }
            // 横排：贴合文字形状（向下扩展）
            val w = maxOf(1, (textW + 2 * padding).toInt())
            val h = maxOf(1, (textH + 2 * padding).toInt())
            return Rect(rect.left, rect.top, rect.left + w, rect.top + h)
        }

        // 收缩居中：背景贴合文字，气泡内其余区域露出原图（避免大片空白）
        val w = (textW + 2 * padding).toInt().coerceIn(1, rect.width())
        val h = (textH + 2 * padding).toInt().coerceIn(1, rect.height())
        val left = rect.centerX() - w / 2
        val top = rect.centerY() - h / 2
        return Rect(left, top, left + w, top + h)
    }
}
