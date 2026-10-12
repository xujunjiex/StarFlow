package com.moe.starflow.manga.render

/** Display-only CJK vertical forms (Unicode UAX #50 and UnicodeData <vertical> mappings). */
internal object VerticalGlyphRules {
    data class Glyph(
        val text: String,
        val rotation: Float = 0f,
        val corner: Boolean = false,
        val useDefaultTypeface: Boolean = false
    )

    private val forms = buildMap<Char, Char> {
        fun pair(horizontal: String, vertical: String) {
            horizontal.forEachIndexed { i, ch -> put(ch, vertical[i]) }
        }
        pair("()（）﹙﹚", "︵︶︵︶︵︶")
        pair("[]［］", "﹇﹈﹇﹈")
        pair("{}｛｝﹛﹜", "︷︸︷︸︷︸")
        pair("〔〕﹝﹞", "︹︺︹︺")
        pair("【】〖〗", "︻︼︗︘")
        pair("〈〉《》", "︿﹀︽︾")
        pair("「」『』｢｣", "﹁﹂﹃﹄﹁﹂")
        pair("…‥—–_＿", "︙︰︱︲︳︳")
        pair(",，、。:：;；!！?？", "︐︐︑︒︓︓︔︔︕︕︖︖")
    }
    private val rotated = "〘〙〚〛｟｠⁅⁆⟨⟩⟪⟫<>＜＞“”‘’\"'〝〞〟«»‹›‐‑‒―-－﹣~～〜〰ーｰ゠﹏￣¯‾|｜"
    private val cornerMarks = ",，、。．.､｡﹐﹑﹒"
    private val uprightMarks = "!！?？‼⁇⁈⁉"

    fun resolve(
        ch: Char,
        hasGlyph: (String) -> Boolean,
        hasDefaultGlyph: (String) -> Boolean = hasGlyph
    ): Glyph {
        val form = forms[ch]
        if (form != null) {
            val verticalForm = form.toString()
            if (hasGlyph(verticalForm)) return Glyph(verticalForm)
            if (hasDefaultGlyph(verticalForm)) {
                return Glyph(verticalForm, useDefaultTypeface = true)
            }
        }
        if (ch in cornerMarks) return Glyph(ch.toString(), corner = true)
        if (ch in uprightMarks) return Glyph(ch.toString())
        if (form != null || ch in rotated) return Glyph(ch.toString(), rotation = 90f)
        // Includes characters already encoded as vertical presentation forms.
        return Glyph(ch.toString())
    }
}
