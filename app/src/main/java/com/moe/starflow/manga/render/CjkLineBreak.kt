package com.moe.starflow.manga.render

import com.moe.starflow.manga.config.TranslationTextRules

/**
 * CJK 行断边界守卫：**闭标点不能出现在行/列首，开括号不能落在行/列尾**。
 *
 * 依据 Unicode UAX #14 的行断类子集（CL/CP/OP），不是按字形随意定规则。
 * 只移动切点、不增删字符；切点只会前移，因此不会破坏调用方已经算好的宽度预算。
 */
internal object CjkLineBreak {

    /** 不允许出现在行首/列首的闭标点（含常见中英引号、点号）。 */
    private const val NO_LINE_START =
        "、。，．！？：；）］｝〉》」』】〕〗〙〛”’,.;:!?)]}»›…‥"

    /** 不允许落在行尾/列尾的开括号（含常见中英引号）。 */
    private const val NO_LINE_END =
        "（［｛〈《「『【〔〖〘〚“‘([{«‹"

    /**
     * 把切点 [end] 调整到合法的 CJK 断点。
     *
     * [start] 是当前行/列起点；返回值始终满足 `start < result <= end`。
     * 回退以完整码点或整串省略号为单位；只剩一个单元时允许标点例外，
     * 避免拆开代理对、已受保护的点串，或把整段卡死在同一个切点。
     */
    fun adjustBreak(text: String, start: Int, end: Int): Int {
        if (start < 0 || end <= start || end >= text.length) return end
        var cut = end
        while (cut > start) {
            if (text[cut - 1] !in NO_LINE_END && text[cut] !in NO_LINE_START) break
            var previous = text.offsetByCodePoints(cut, -1)
            if (TranslationTextRules.isEllipsisDot(text[previous])) {
                while (previous > start && TranslationTextRules.isEllipsisDot(text[previous - 1])) {
                    previous--
                }
            }
            // 本行只剩一个码点/点串，继续前移就会变成空行；保留当前预算内的切点。
            // 点串自身超出容量时，仍由调用方原有的硬切兜底处理。
            if (previous <= start) break
            cut = previous
        }
        return cut
    }

    /** 是否包含中日韩宽字符；纯拉丁长词不走这套标点规则。 */
    fun hasCjk(text: String): Boolean = text.any { ch ->
        val c = ch.code
        c in 0x2E80..0x9FFF || c in 0xF900..0xFAFF || c in 0xFF00..0xFFEF ||
            c in 0x3040..0x30FF || c in 0xAC00..0xD7AF
    }
}
