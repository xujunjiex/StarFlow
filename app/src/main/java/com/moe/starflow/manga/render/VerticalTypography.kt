package com.moe.starflow.manga.render

/**
 * 竖排排版单位：字符流先切成**不可再分的格子**，排版与绘制都按它走。
 *
 * 为什么需要它：
 * - 数字连续串在日漫竖排里是「縦中横」（TCY）：2–4 位数字横排占**一格**，
 *   而不是一位一格竖着叠。切列/算列高必须把它当一个整体，否则会从中间断开。
 * - 后续的标点半格（0.5 em）也会是另一种格子；把「切分」与「步进」收在一处，
 *   内核和渲染器就不会各算一套。
 *
 * ⚠️ 构造性约定：[units] 必须覆盖输入的全部字符（不丢字），切列只允许发生在 unit 边界。
 */
internal data class VerticalUnit(
    /** 该格实际要画的文字（TCY 为整组数字）。 */
    val text: String,
    /** 在输入字符串中的字符区间 `[start, end)`，供避头尾/点串回退映射。 */
    val start: Int,
    val end: Int,
    val kind: Kind
) {
    enum class Kind { CHAR, TCY }

    val isTcy: Boolean get() = kind == Kind.TCY
}

/**
 * 竖排字格的切分与步进规则（单一事实来源）。
 *
 * 渲染器必须调用同一个 [units]/[unitAdvance]，不能自己按「一个 char 一格」另写一套 ——
 * 否则会出现「内核算 3 列、画出来 4 列」的历史问题。
 */
internal object VerticalTypography {

    /** TCY 最多合并的位数；超过 4 位按 4 位分组（与 MTU/日漫常见做法一致）。 */
    const val TCY_MAX_DIGITS = 4

    /** TCY 横排总宽上限（×字号）。超宽时横向压缩，不折行。 */
    const val TCY_MAX_WIDTH_RATIO = 1.1f

    /** 单个数字合并会变成「一位横排」，没有意义；至少 2 位才成组。 */
    private const val TCY_MIN_DIGITS = 2

    /** 半角与全角数字。 */
    fun isDigit(cp: Int): Boolean = cp in '0'.code..'9'.code || cp in 0xFF10..0xFF19

    /**
     * 切分：连续数字串按 [TCY_MAX_DIGITS] 分组，长度 ≥2 的组为 [VerticalUnit.Kind.TCY]，
     * 余下单个数字与所有非数字字符为 [VerticalUnit.Kind.CHAR]。
     */
    fun units(text: String): List<VerticalUnit> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<VerticalUnit>(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (!isDigit(ch.code)) {
                out += VerticalUnit(ch.toString(), i, i + 1, VerticalUnit.Kind.CHAR)
                i++
                continue
            }
            var runEnd = i
            while (runEnd < text.length && isDigit(text[runEnd].code)) runEnd++
            var start = i
            while (start < runEnd) {
                val end = minOf(start + TCY_MAX_DIGITS, runEnd)
                val count = end - start
                out += VerticalUnit(
                    text = text.substring(start, end),
                    start = start,
                    end = end,
                    kind = if (count >= TCY_MIN_DIGITS) VerticalUnit.Kind.TCY else VerticalUnit.Kind.CHAR
                )
                start = end
            }
            i = runEnd
        }
        return out
    }

    /**
     * 一个格子的纵向步进（不含字距）。
     *
     * TCY 与普通字都占 1 em；标点半格在下一步实现，只需要在这里区分。
     */
    fun unitAdvance(unit: VerticalUnit, fontSize: Float): Float = when (unit.kind) {
        VerticalUnit.Kind.CHAR, VerticalUnit.Kind.TCY -> fontSize
    }

    /** 一列的墨迹高度：各格步进之和 + 格间字距（末格后不加）。 */
    fun columnHeight(units: List<VerticalUnit>, fontSize: Float, tracking: Float): Float {
        if (units.isEmpty()) return 0f
        var height = 0f
        for ((index, unit) in units.withIndex()) {
            if (index > 0) height += tracking
            height += unitAdvance(unit, fontSize)
        }
        return height
    }
}
