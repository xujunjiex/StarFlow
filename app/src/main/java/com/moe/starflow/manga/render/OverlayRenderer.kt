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
import com.moe.starflow.manga.types.TextAlign
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
     * 竖排字符步距系数。**单一来源已迁到 [LayoutEngine.VERTICAL_CHAR_RATIO]** ——
     * 此前 OverlayRenderer 与 VerticalTextRenderer 各写一份并注释「必须一致」，
     * 不一致即溢出（被 clipRect 静默裁掉）。这里保留别名只为合并块尺寸估算可读。
     */
    private const val VERTICAL_CHAR_RATIO = LayoutEngine.VERTICAL_CHAR_RATIO

    /** 横排行距系数，单一来源同上。 */
    private const val HORIZONTAL_LINE_RATIO = LayoutEngine.HORIZONTAL_LINE_RATIO

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
        showCacheMarker: Boolean = false,  // 缓存命中标记开关（⚡，默认关闭）
        align: TextAlign = TextAlign.CENTER,          // 横排对齐（竖排不受影响）
        trackingRatio: Float = LayoutEngine.TRACKING_DEFAULT_RATIO,  // 用户字间距（×字号）
        leadingRatio: Float = LayoutEngine.LEADING_DEFAULT_RATIO,    // 用户行间距（×字号）
        /** 渲染阶段重叠合并开关（个性化页可关，见 OverlayConfig.mergeOverlap）。 */
        mergeOverlap: Boolean = true,
        density: Float = 1f                            // 用于把 MIN_PADDING_DP 换算成 px
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
            // 自动模式的字号由 LayoutEngine 在绘制时按最终 drawRect 二分选定，此处不预估。
            // ⚠️ 估计值只服务非自动路径的 calculateCompactRect：它决定 drawRect（收缩贴合 / 扩展），
            // 而自动模式的 drawRect 恒为原气泡。
            val fitFontSize = if (autoFit) {
                region.fontSize
            } else {
                fontSize
            }
            // 自动模式：drawRect 用原始气泡（覆盖原文区域），文字靠 fit 字号 + 列距填满气泡宽
            // 非自动模式：drawRect 贴合文字（收缩居中/平衡扩展）
            val neededRect = if (autoFit) {
                region.rect
            } else {
                // 非自动模式：字号由用户定，矩形随文字实际尺寸扩展/收缩（见 calculateCompactRect）
                calculateCompactRect(
                    region.rect, displayText, region.direction, fitFontSize,
                    trackingRatio, leadingRatio, LayoutEngine.MIN_PADDING_DP * density
                )
            }
            Param(region, displayText, fitFontSize, neededRect)
        }

        // Phase 2: neededRect 重叠的气泡合并成组（union-find 传递闭包）
        val groupOf = if (mergeOverlap) mergeOverlapping(params) else IntArray(params.size) { it }

        // Phase 3: 构建绘制单元。同方向合并组 → 一个大白块 + 记号分隔；异方向/倾斜/字号不一致 → 独立绘制
        val drawItems = mutableListOf<DrawItem>()
        params.indices.groupBy { groupOf[it] }.forEach { (_, ids) ->
            val members = ids.map { params[it] }.filter { it.displayText.isNotEmpty() }
            if (members.isEmpty()) return@forEach
            // ⚠️ 「字号一致」这条只对**非自动**模式成立：那里字号由用户定，差异大时合并会比例失调。
            // 自动模式下合并块由 LayoutEngine 的一次排版统一决定字号，「成员字号要一致」不再必要 ——
            // 保留它反而会让原本能合并的组退回独立绘制，丢失 ◇ 分隔白块。
            val sameFontOk = autoFit ||
                members.all { kotlin.math.abs(it.fitFontSize - members[0].fitFontSize) < 2f }
            if (members.size == 1) {
                val m = members[0]
                drawItems += DrawItem(
                    m.neededRect, listOf(m.displayText), m.region.direction, m.fitFontSize,
                    merged = false, m.region.angle, m.region.centerX, m.region.centerY
                )
            } else if (members.all { !hasTilt(it.region) } &&
                members.all { it.region.direction == members[0].region.direction } &&
                sameFontOk
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
            // 统一排版内核：字号选择 + 断行/分列 + 字距行距分配一次算完，产物保证不越出 drawRect。
            // 自动模式由内核二分选字号；非自动模式字号原样、间距不拉伸（见三态行为矩阵）。
            val layout = LayoutEngine.plan(
                measurer = PaintTextMeasurer(Paint().apply { isAntiAlias = true; typeface = fontTypeface }),
                text = text,
                region = Box.from(item.drawRect),
                direction = item.direction,
                requestedFontSize = item.fitFontSize,
                autoFit = autoFit,
                align = align,
                trackingRatio = trackingRatio,
                leadingRatio = leadingRatio,
                // 绝对内边距：竖排左右、横排上下都留白，文字不贴 overlay 边缘。
                // 用 px 而非「字号×比例」—— 自动字号会把字缩小，比例边距同步退化成 0。
                minPaddingPx = LayoutEngine.MIN_PADDING_DP * density
            )
            VerticalTextRenderer.draw(canvas, layout, textColor, fontTypeface)
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
     *
     * ⚠️ **绘制区恒等于「成员气泡区域的并集」，与合并后文字有多长无关** —— 这是硬约束，
     * 踩过很惨的一次：旧实现按**文本长度**反推块尺寸（`balancedVerticalSize(合并文本长度, …)`
     * 定宽、`maxOf(高度, 文字块高)` 定高，还不够就 `maxOf(blockSize, …)` 撑），
     * 而块内字号又由 `LayoutEngine` 按**块尺寸**二分选定（区域越大字号上限越高），
     * 于是「文本越长 → 块越大 → 字号越大 → 文本需要更多地方」形成正反馈 ——
     * 真机上直接并出一个盖住大半页、里面文字同样巨大的白块。
     * 按气泡取并集后，块永远不会超出这些气泡原本占的地方，字号也就跟着落回正常量级。
     *
     * 组内文本按阅读顺序用记号（[VERTICAL_SEPARATOR]/[HORIZONTAL_SEPARATOR]）连接后交给
     * `LayoutEngine` 排版；文本超出并集区域时由它自行缩字号/断行，**不截断、也不越界**。
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
        // 并集：必须用**成员的气泡矩形**（region.rect），不是非自动模式下扩过的 neededRect ——
        // 「绝不超出原始两个气泡占据的空间」以气泡为准。
        val drawRect = Rect(
            members.minOf { it.region.rect.left },
            members.minOf { it.region.rect.top },
            members.maxOf { it.region.rect.right },
            members.maxOf { it.region.rect.bottom }
        )
        return DrawItem(drawRect, texts, direction, fontSize, merged = true)
    }

    /**
     * 非自动模式专用：计算文字实际所需矩形（字号由用户定，本函数只决定白块大小）。
     *
     * 用户明确要求：**大文字超出原气泡就扩展、小文字就收缩**，且边距统一、
     * 字号/字间距/行间距严格等于用户设定值。因此这里用 [LayoutEngine] 在**不限尺寸**的
     * 虚构区域里量一次文字块的真实尺寸：
     * - 任意文本都能精确量出（旧实现套用「平衡扩展」公式，与绘制未必一致 → 极长文本可能裁切）
     * - 用户字距/行距被如实计入（旧实现只加 padding，间距调大了就会被裁）
     *
     * 两侧边距严格相等：增长时以原气泡中心为锚点双向扩展（旧实现锚在「文字流向起始角」，
     * 只往一侧长，看着像偏了）。
     */
    private fun calculateCompactRect(
        rect: Rect,
        text: String,
        direction: TextDirection,
        fontSize: Float,
        trackingRatio: Float,
        leadingRatio: Float,
        minPaddingPx: Float
    ): Rect {
        if (text.isEmpty() || fontSize <= 0f) return rect
        val pad = LayoutEngine.paddingFor(
            rect.width().toFloat(), rect.height().toFloat(), minPaddingPx
        ).toInt()

        // 量尺寸的区域：**只让「生长轴」无界**，另一轴沿用气泡尺寸以保持原有形状比例。
        // ⚠️ 两轴都设无界是错的：竖排的每列容量由高度决定，高度无界 ⇒ 永远 1 列 ⇒ 文字块变成
        // 一根极细极高的柱子（实测踩过）。横排向右排、向下生长；竖排向下排、向两侧生长。
        val huge = if (direction == TextDirection.HORIZONTAL) {
            Rect(0, 0, rect.width(), 1 shl 20)
        } else {
            Rect(0, 0, 1 shl 20, rect.height())
        }
        val measured = LayoutEngine.plan(
            measurer = PaintTextMeasurer(Paint().apply { textSize = fontSize; isAntiAlias = true }),
            text = text,
            region = Box.from(huge),
            direction = direction,
            requestedFontSize = fontSize,
            autoFit = false,
            align = TextAlign.LEFT,
            trackingRatio = trackingRatio,
            leadingRatio = leadingRatio,
            minPaddingPx = 0f          // 尺寸已含 padding，这里不再叠加
        )
        if (measured.isEmpty) return rect

        val w = (measured.totalWidth + 2 * pad).toInt().coerceAtLeast(1)
        val h = (measured.totalHeight + 2 * pad).toInt().coerceAtLeast(1)

        // 以原气泡中心为锚点：文字块比气泡小就收缩（露出原图），大就向外扩展
        val left = rect.centerX() - w / 2
        val top = rect.centerY() - h / 2
        return Rect(left, top, left + w, top + h)
    }
}
