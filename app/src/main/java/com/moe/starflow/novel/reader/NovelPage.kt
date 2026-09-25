package com.moe.starflow.novel.reader

/**
 * 页内的一段：「某段落在本页显示的那部分字符」。
 *
 * ⚠️ 为什么整页存 segment 而不是拼成一个字符串：**跨页的段落**要在渲染时决定这半段显示
 * 原文还是译文。只有知道它属于哪个 `paraIndex`，段未翻译时才能继续显示原文、已翻译才能
 * 换上译文。把整页拼成一个串就丢掉了这个对应关系。
 */
data class PageSegment(
    val paraIndex: Int,
    val charStart: Int,
    val charEnd: Int,
)

/** 一页 = 若干 segment（按显示顺序）。 */
data class NovelPage(val segments: List<PageSegment>)

/**
 * 排版参数。分页与绘制**共用同一份**，保证「怎么分的就怎么画」——
 * 两处各算一套的话，字号一改就会出现文字与分页错位。
 */
data class NovelTextStyle(
    val fontSizePx: Float,
    val lineSpacingMultiplier: Float,
    val paragraphSpacingPx: Float,
    val paddingPx: Float,
)
