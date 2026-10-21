package com.moe.starflow.mangaimport.translate

import com.moe.starflow.data.ImportedPageTranslation
import com.moe.starflow.data.TranslationCacheUtils
import com.moe.starflow.manga.types.TranslatedBubble

/** 阅读器翻译记录字段 ↔ 气泡列表 的纯串行化/反串行化。 */
object PageTranslationCodec {

    /** 供展示和检索的编号原文，区域映射以 bubbleRects 中的完整载荷为准。 */
    fun sourceText(bubbles: List<TranslatedBubble>): String =
        numbered(bubbles) { it.originalText }

    /** "[N] 译文\n[2] …"。 */
    fun translatedText(bubbles: List<TranslatedBubble>): String =
        numbered(bubbles) { it.translatedText }

    /** 完整区域载荷 JSON：坐标在页面位图空间，文本保留换行和正文编号。 */
    fun bubbleRects(bubbles: List<TranslatedBubble>): String =
        TranslationCacheUtils.serializeBubbleRects(bubbles)

    /** 从一行记录重建气泡（渲染译文图/原文图用）。记录无有效数据返回 null。 */
    fun fromRow(
        row: ImportedPageTranslation,
        defaultFontSize: Float,
        bgColor: Int,
    ): List<TranslatedBubble>? {
        if (row.sourceText.isNullOrBlank() || row.translatedText.isNullOrBlank()) return null
        val (originals, translations) = TranslationCacheUtils.readBubbleTexts(
            row.sourceText, row.translatedText, row.bubbleRects)
        return TranslationCacheUtils.rebuildBubblesFromCache(
            originals, translations, row.bubbleRects, defaultFontSize, bgColor,
        )
    }

    private inline fun numbered(
        bubbles: List<TranslatedBubble>,
        text: (TranslatedBubble) -> String,
    ): String = bubbles.mapIndexed { i, b -> "[${i + 1}] ${text(b)}" }.joinToString("\n")
}
