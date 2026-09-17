package com.moe.starflow.manga.render

import android.graphics.Paint
import android.graphics.Rect
import com.moe.starflow.manga.types.TextAlign
import com.moe.starflow.manga.types.TextDirection
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 度量抽象：把 Paint 的字体度量隔离在接口后，排版逻辑因此成为纯函数、可做纯 JVM 单测
 * （Robolectric 的 `Paint.breakText` 不按宽度换行，测试里直接用真实 Paint 构造不出多行用例）。
 */
interface TextMeasurer {
    var fontSize: Float
    fun measure(text: String): Float
    fun breakText(text: String, maxWidth: Float): Int

    /** 竖排字框宽（= 字号）。 */
    fun glyphWidth(): Float

    /** 竖排字框高（= 字号 × [LayoutEngine.VERTICAL_CHAR_RATIO]）。 */
    fun glyphHeight(): Float
}

/** 基于 [Paint] 的真实度量实现（生产路径）。 */
class PaintTextMeasurer(
    private val paint: Paint = Paint().apply { isAntiAlias = true }
) : TextMeasurer {
    override var fontSize: Float
        get() = paint.textSize
        set(value) { paint.textSize = value }

    override fun measure(text: String): Float = paint.measureText(text)
    override fun breakText(text: String, maxWidth: Float): Int =
        paint.breakText(text, true, maxWidth, null)

    override fun glyphWidth(): Float = paint.textSize
    override fun glyphHeight(): Float = paint.textSize * LayoutEngine.VERTICAL_CHAR_RATIO
}

/**
 * 一条排版单元。横排时 = 一行；竖排时 = 一列（列内字符按 [TextLayout.charStep] 自上而下排布）。
 */
data class TextLine(
    val text: String,
    /** 横排=行宽（含字距）；竖排=列宽。 */
    val width: Float,
    /** 横排=字形高；竖排=列高。 */
    val height: Float,
    /** 横排=行起点 x；竖排=列中心 x。 */
    val x: Float,
    /** 横排=基线 y；竖排=首字基线 y。 */
    val baseline: Float
)

/**
 * 排版产物。**不变量（构造性成立，不是靠调用方自觉）**：
 * - [totalWidth] ≤ region.width、[totalHeight] ≤ region.height
 * - [lines] 覆盖输入文本的**全部**字符（不截断）
 */
data class TextLayout(
    val fontSize: Float,
    val lines: List<TextLine>,
    /** 主轴额外字距（横排=行内字距；竖排=列内字距）。 */
    val tracking: Float,
    /** 次轴步进（横排=行距；竖排=列距），含字形本身。 */
    val leading: Float,
    /** 竖排列内字符步进（含字形）；横排恒为 0。 */
    val charStep: Float,
    val totalWidth: Float,
    val totalHeight: Float
) {
    val isEmpty: Boolean get() = lines.isEmpty()
}

/**
 * 漫画译文排版内核：字号选择、断行/分列、间距**一次算完**，绘制端不再做任何决策。
 *
 * 历史教训（本内核要根除的）：布局与绘制分居 `OverlayRenderer` / `VerticalTextRenderer` 两个文件，
 * 各自算一遍容量与行数，靠「常量必须一致」的注释手工同步；一旦不一致就溢出，而溢出被
 * `canvas.clipRect` 静默裁掉，表现为「最后一行显示不完整」且无任何异常可查。
 *
 * **间距是输入，字号是输出**（[plan] 的 [trackingRatio] / [leadingRatio]）：
 * 字号二分直接把两者算进尺寸预算 —— 用户把间距调大，能装下的字号就变小，
 * 因此**任何间距组合下都不会溢出或截断**。反过来（先定死最大字号、再拿剩余空间去凑间距）
 * 是行不通的：字号选到「最大能装下」时该轴已被填满、余量恒为 0，间距根本没有空间可分。
 */
object LayoutEngine {

    /**
     * 竖排字符步距系数（列内上下相邻字步进）。**唯一来源**：
     * OverlayRenderer 曾各写一份并注释「必须一致」，不一致即溢出。
     */
    const val VERTICAL_CHAR_RATIO = 1.1f

    /** 横排换行行距系数（与竖排字距解耦）。 */
    const val HORIZONTAL_LINE_RATIO = 1.2f

    /** 字号下限：装不下就继续缩，绝不截断（用户明确选择的取舍：宁可字小，不可丢字）。 */
    private const val MIN_FONT_SIZE = 1f

    /**
     * 用户字间距 / 行间距的取值范围与默认值（**×字号**）。
     *
     * 间距现在是**用户输入**、字号是推导结果（见 [plan]）：字号二分时会把这两个值算进尺寸预算，
     * 因此「用户把间距调大 → 字号自动变小」，任何组合下都不会溢出或截断。
     */
    const val TRACKING_MIN_RATIO = 0f
    const val TRACKING_MAX_RATIO = 1f
    const val TRACKING_DEFAULT_RATIO = 0f
    const val LEADING_MIN_RATIO = 0f
    const val LEADING_MAX_RATIO = 1f
    const val LEADING_DEFAULT_RATIO = 0f

    /**
     * 内容区最小内边距（dp，**绝对值**）。由调用方按设备密度换算成 px 传入 [plan]。
     *
     * ⚠️ 必须用绝对值而非「字号 × 比例」：比例边距在小字号下会退化成几乎为零 ——
     * 自动字号把字缩小以塞进气泡时，边距同步缩小，文字照样贴边（实测就是这个问题）。
     * 用户要的是「不管字多小，离边缘都要留出看得见的间隔」。
     */
    const val MIN_PADDING_DP = 3f

    /** 内边距上限：占短边比例，避免小气泡里 padding 吃掉全部可用空间。 */
    const val MAX_PADDING_RATIO = 0.12f

    /** 字号二分精度。 */
    private const val FIT_EPSILON = 0.5f

    /** 浮点比较容差（px）。 */
    private const val EPS = 0.01f

    /**
     * 排版主入口。
     *
     * @param measurer    度量实现（生产用 [PaintTextMeasurer]，测试注入假实现）
     * @param autoFit     true = 由内核二分选字号；false = 用 [requestedFontSize] 原样
     * @param align       横排的行对齐方式（竖排不使用：竖排列块保持水平居中）
     * @param trackingRatio 用户字间距（×字号，0 = 自然）。**参与字号预算**：调大 → 字号相应变小。
     * @param leadingRatio  用户行间距（×字号，0 = 自然行距）。同样参与预算。
     * @param minPaddingPx 内容区最小内边距（**px 绝对值**，调用方按密度换算 `MIN_PADDING_DP`）。
     *                     四周统一生效：竖排保证左右留白，横排保证上下留白，文字不贴 overlay 边缘。
     */
    fun plan(
        measurer: TextMeasurer,
        text: String,
        region: Box,
        direction: TextDirection,
        requestedFontSize: Float,
        autoFit: Boolean,
        align: TextAlign,
        trackingRatio: Float = TRACKING_DEFAULT_RATIO,
        leadingRatio: Float = LEADING_DEFAULT_RATIO,
        minPaddingPx: Float = 0f
    ): TextLayout {
        if (text.isEmpty() || region.width <= 0 || region.height <= 0 || requestedFontSize <= 0f) {
            return TextLayout(0f, emptyList(), 0f, 0f, 0f, 0f, 0f)
        }
        val font = if (autoFit) {
            searchFitFontSize(measurer, text, region, direction, requestedFontSize, trackingRatio, leadingRatio, minPaddingPx)
        } else {
            requestedFontSize
        }
        val layout = buildLayout(measurer, text, region, direction, font, align, trackingRatio, leadingRatio, minPaddingPx)
        if (!overflows(layout, region, minPaddingPx)) return layout

        // 保底：用户把间距拉满时，极小气泡里可能连字号下限都塞不下。
        // 「不溢出、不截断」是硬约束，间距只是偏好 —— 放弃间距再排一次。
        return buildLayout(measurer, text, region, direction, font, align, 0f, 0f, minPaddingPx)
    }

    /** 内容块是否越出可用区（扣除内边距后才算）。 */
    private fun overflows(layout: TextLayout, region: Box, minPaddingPx: Float): Boolean {
        if (layout.isEmpty) return false
        val pad = resolvePadding(region, minPaddingPx)
        return layout.totalWidth > (region.width - 2 * pad) + EPS ||
            layout.totalHeight > (region.height - 2 * pad) + EPS
    }

    // ---------------- 字号选择 ----------------

    /**
     * 二分搜索「装得下的最大字号」。判定与最终排版共用 [buildLayout]，
     * 因此不存在「算出 3 行、画出 4 行」这类两套算法打架的空间。
     */
    private fun searchFitFontSize(
        measurer: TextMeasurer,
        text: String,
        region: Box,
        direction: TextDirection,
        maxFontSize: Float,
        trackingRatio: Float,
        leadingRatio: Float,
        minPaddingPx: Float
    ): Float {
        // 字号上限不超过矩形本身能容纳的大小
        val rectLimit = when (direction) {
            TextDirection.HORIZONTAL -> region.width / HORIZONTAL_LINE_RATIO
            else -> region.height / VERTICAL_CHAR_RATIO
        }
        val hiInit = minOf(maxFontSize, rectLimit).coerceAtLeast(MIN_FONT_SIZE)
        var hi = hiInit
        var lo = MIN_FONT_SIZE
        if (!fits(measurer, text, region, direction, lo, trackingRatio, leadingRatio, minPaddingPx)) return lo
        var best = lo
        while (hi - lo >= FIT_EPSILON) {
            val mid = (lo + hi) / 2f
            if (fits(measurer, text, region, direction, mid, trackingRatio, leadingRatio, minPaddingPx)) {
                best = mid
                lo = mid
            } else {
                hi = mid
            }
        }
        return best
    }

    private fun fits(
        measurer: TextMeasurer,
        text: String,
        region: Box,
        direction: TextDirection,
        font: Float,
        trackingRatio: Float,
        leadingRatio: Float,
        minPaddingPx: Float
    ): Boolean {
        measurer.fontSize = font
        val pad = resolvePadding(region, minPaddingPx)
        val availW = region.width - 2 * pad
        val availH = region.height - 2 * pad
        if (availW <= 0f || availH <= 0f) return false
        val layout = buildLayout(measurer, text, region, direction, font, TextAlign.CENTER, trackingRatio, leadingRatio, minPaddingPx)
        if (layout.isEmpty) return false
        return layout.totalWidth <= availW + EPS && layout.totalHeight <= availH + EPS
    }

    /**
     * 实际内边距（px）：取「绝对值」与「短边比例上限」的较小者。
     *
     * 绝对下限保证文字永远离边缘有可见间隔（这是用户要的）；比例上限保证小气泡里 padding
     * 不会吃掉全部可用空间（否则可用区 ≤ 0 → 排版为空）。上限取 12% 短边，可用区仍剩 76%。
     *
     * 公开给非自动模式的紧凑矩形计算用 —— 两条路径必须用同一份边距，
     * 否则「自动贴着气泡边、非自动留着白边」会显得像 bug。
     */
    fun paddingFor(regionWidth: Float, regionHeight: Float, minPaddingPx: Float): Float {
        if (minPaddingPx <= 0f) return 0f
        val cap = minOf(regionWidth, regionHeight) * MAX_PADDING_RATIO
        return minOf(minPaddingPx, cap)
    }

    private fun resolvePadding(region: Box, minPaddingPx: Float): Float =
        paddingFor(region.width, region.height, minPaddingPx)

    // ---------------- 排版 ----------------

    private fun buildLayout(
        measurer: TextMeasurer,
        text: String,
        region: Box,
        direction: TextDirection,
        font: Float,
        align: TextAlign,
        trackingRatio: Float,
        leadingRatio: Float,
        minPaddingPx: Float
    ): TextLayout {
        measurer.fontSize = font
        val pad = resolvePadding(region, minPaddingPx)
        val availW = (region.width - 2 * pad).coerceAtLeast(0f)
        val availH = (region.height - 2 * pad).coerceAtLeast(0f)
        if (availW <= 0f || availH <= 0f) return TextLayout(font, emptyList(), 0f, 0f, 0f, 0f, 0f)

        return when (direction) {
            TextDirection.HORIZONTAL -> buildHorizontal(measurer, text, region, font, align, trackingRatio, leadingRatio, pad, availW, availH)
            else -> buildVertical(measurer, text, region, direction, font, trackingRatio, leadingRatio, pad, availW, availH)
        }
    }

    private fun buildHorizontal(
        @Suppress("UNUSED_PARAMETER") measurer: TextMeasurer,
        text: String,
        region: Box,
        font: Float,
        align: TextAlign,
        trackingRatio: Float,
        leadingRatio: Float,
        pad: Float,
        availW: Float,
        availH: Float
    ): TextLayout {
        // 用户字距（px）。断行必须**把字距算进预算**：k 个字的行实际占 k*h + (k-1)*tracking。
        // ⚠️ 曾用「可用宽 + 一个字距」放宽断行、事后才补 tracking*(k-1)，是错的 ——
        // 断行器会把每行填到 availW+tracking 满，补完字距后每行恒超 availW 约 tracking*k，
        // 于是 fits() 判定所有多行布局都装不下，二分搜索退化到「整段挤成一行」的极小字号
        // （实测 363×214 的气泡：有字距 → 7.65px/1 行，无字距 → 33.4px/5 行）。
        val tracking = (trackingRatio * font).coerceAtLeast(0f)
        val lines = wrapHorizontal(measurer, text, availW, tracking)
        if (lines.isEmpty()) return TextLayout(font, emptyList(), 0f, 0f, 0f, 0f, 0f)

        val naturalWidths = lines.map { measurer.measure(it) }
        val longestLen = lines.maxOf { it.length }
        val totalWidth = (naturalWidths.maxOrNull() ?: 0f) + tracking * (longestLen - 1).coerceAtLeast(0)

        // 行距：用户值叠加在自然行距之上（保持 1×字号的天然呼吸感；要更紧就调小 …但下限是自然值）
        val n = lines.size
        val leading = font * (HORIZONTAL_LINE_RATIO + leadingRatio.coerceAtLeast(0f))
        val totalHeight = if (n <= 1) font else (n - 1) * leading + font

        // 余量居中（间距固定在预算内，余量一般很小；触底/触顶时给 0）
        val topPad = ((availH - totalHeight) / 2f).coerceAtLeast(0f)

        val out = lines.mapIndexed { i, line ->
            val lineW = naturalWidths[i] + tracking * (line.length - 1).coerceAtLeast(0)
            val x = when (align) {
                TextAlign.LEFT -> region.left + pad
                TextAlign.CENTER -> region.centerX - lineW / 2f
                TextAlign.RIGHT -> region.right - pad - lineW
            }
            TextLine(
                text = line,
                width = lineW,
                height = font,
                x = x,
                baseline = region.top + pad + topPad + font + i * leading
            )
        }
        return TextLayout(font, out, tracking, leading, 0f, totalWidth, totalHeight)
    }

    private fun buildVertical(
        measurer: TextMeasurer,
        text: String,
        region: Box,
        direction: TextDirection,
        font: Float,
        trackingRatio: Float,
        leadingRatio: Float,
        pad: Float,
        availW: Float,
        availH: Float
    ): TextLayout {
        // 列内字距叠加在自然字步距上；列距叠加在自然列步距上。两者都参与字号二分预算。
        val charStep = font * (VERTICAL_CHAR_RATIO + trackingRatio.coerceAtLeast(0f))
        val colStep = font * (VERTICAL_CHAR_RATIO + leadingRatio.coerceAtLeast(0f))
        val capacity = verticalCapacity(availH, font, charStep)
        if (capacity <= 0) return TextLayout(font, emptyList(), 0f, 0f, 0f, 0f, 0f)

        val n = text.length
        val columns = ceil(n.toFloat() / capacity).toInt().coerceAtLeast(1)
        val charsInTallest = minOf(n, capacity)

        val colHeight = if (charsInTallest <= 1) font else (charsInTallest - 1) * charStep + font
        val totalWidth = if (columns <= 1) font else (columns - 1) * colStep + font

        // 列块水平居中（竖排不吃对齐设置）；列高不足时垂直居中
        val blockLeft = region.left + pad + ((availW - totalWidth) / 2f).coerceAtLeast(0f)
        val topPad = ((availH - colHeight) / 2f).coerceAtLeast(0f)
        val firstBaseline = region.top + pad + topPad + font

        val out = (0 until columns).map { j ->
            val from = j * capacity
            val to = minOf(n, from + capacity)
            val chunk = text.substring(from, to)
            val count = chunk.length
            val x = when (direction) {
                TextDirection.VERTICAL_RL -> blockLeft + totalWidth - font / 2f - j * colStep
                else -> blockLeft + font / 2f + j * colStep
            }
            TextLine(
                text = chunk,
                width = font,
                height = if (count <= 1) font else (count - 1) * charStep + font,
                x = x,
                baseline = firstBaseline
            )
        }
        return TextLayout(font, out, charStep - font * VERTICAL_CHAR_RATIO, colStep, charStep, totalWidth, colHeight)
    }

    /**
     * 竖排每列可容纳字符数：起点 y = 首字基线，每字步进 `step`（含用户字距），
     * 末字底边 `(k-1)*step + font` 必须 ≤ 可用高 → `k ≤ (可用高 - font)/step + 1`。
     */
    private fun verticalCapacity(availH: Float, font: Float, step: Float): Int {
        if (step <= 0f || availH < font) return 0
        return floor((availH - font) / step).toInt() + 1
    }

    /**
     * 按 \n 分段 + breakText 换行。空段保留为空行（占一行高）。
     *
     * [tracking] 为行内字距（px），**必须纳入断行预算**：k 个字的行实际占
     * `naturalWidth(k) + (k-1)×tracking`。`breakText` 只知道自然宽度，所以先按自然宽
     * 取一个上界，再按「补上字距后是否仍不超可用宽」逐个回退。字距越大每行字数越少、
     * 行数越多 —— 这个结果必须与 [buildHorizontal] 的宽度计算**完全一致**，否则装不下的
     * 布局会被误判为装得下（或反之），字号二分随即失真。
     */
    private fun wrapHorizontal(
        measurer: TextMeasurer,
        text: String,
        maxWidth: Float,
        tracking: Float
    ): List<String> {
        if (maxWidth <= 0f) return emptyList()
        val lines = mutableListOf<String>()
        for (paragraph in text.split("\n")) {
            if (paragraph.isEmpty()) {
                lines.add("")
                continue
            }
            var remaining = paragraph
            while (remaining.isNotEmpty()) {
                var count = measurer.breakText(remaining, maxWidth)
                if (count <= 0) break
                if (tracking > 0f) {
                    // 自然宽 ≤ maxWidth 只保证不含字距时放得下；加上 (k-1) 个字距后可能超出
                    while (count > 1 &&
                        measurer.measure(remaining.substring(0, count)) + (count - 1) * tracking > maxWidth
                    ) {
                        count--
                    }
                }
                lines.add(remaining.substring(0, count))
                remaining = remaining.substring(count)
            }
        }
        return lines
    }
}
