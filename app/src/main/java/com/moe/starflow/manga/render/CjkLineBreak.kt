package com.moe.starflow.manga.render

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
     * 只剩一个字符时允许例外，避免把整段卡死在同一个切点。
     */
    fun adjustBreak(text: String, start: Int, end: Int): Int {
        if (start < 0 || end <= start || end >= text.length) return end
        var cut = end
        var guard = 0
        while (cut > start + 1 && guard++ < text.length) {
            var moved = false
            if (text[cut - 1] in NO_LINE_END) {
                cut--
                moved = true
            }
            if (cut < text.length && cut > start + 1 && text[cut] in NO_LINE_START) {
                cut--
                moved = true
            }
            if (!moved) break
        }
        return cut.coerceAtLeast(start + 1)
    }

    /** 是否包含中日韩宽字符；纯拉丁长词不走这套标点规则。 */
    fun hasCjk(text: String): Boolean = text.any { ch ->
        val c = ch.code
        c in 0x2E80..0x9FFF || c in 0xF900..0xFAFF || c in 0xFF00..0xFFEF ||
            c in 0x3040..0x30FF || c in 0xAC00..0xD7AF
    }
}
