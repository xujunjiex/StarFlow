package com.moe.starflow.novel.translate

/**
 * 段落类型。
 *
 * [IMAGE] 是图片占位（HTML 里的 `<img>` 转出的 `📷 [图片]` 标记）；
 * [SKIP] 是过短到没有翻译价值的段落（"……"、分隔符、单字对白）。
 *
 * 两者都不参与翻译。之所以分开而不是合并成一个「不翻」类型：展示语义不同 ——
 * 图片占位将来要渲染成图，短段则是直接不显示。
 */
enum class NovelParagraphType { TEXT, IMAGE, SKIP }

/**
 * 一个段落。
 *
 * [index] 是切分后的数组下标（含 IMAGE / SKIP 段），**是译文对应的唯一 key**。
 * 译文表主键里的 `paraIndex` 就是它。
 */
data class NovelParagraph(
    val index: Int,
    val type: NovelParagraphType,
    val originalText: String,
)
