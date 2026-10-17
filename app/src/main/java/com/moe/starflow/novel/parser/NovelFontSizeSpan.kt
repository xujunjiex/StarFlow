package com.moe.starflow.novel.parser

/** A relative font-size range in the extracted chapter text. */
data class NovelFontSizeSpan(
    val start: Int,
    val end: Int,
    val scale: Float,
) {
    init {
        require(start >= 0 && end >= start)
        require(scale.isFinite() && scale > 0f)
    }
}

/** Plain chapter text plus EPUB typography ranges, indexed in UTF-16 characters. */
data class ChapterText(
    val text: String,
    val fontSizeSpans: List<NovelFontSizeSpan> = emptyList(),
)
