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
 *
 * @param paddingPx 左右内边距
 * @param topPaddingPx 上内边距（正文顶部与屏幕顶之间的距离）
 * @param bottomPaddingPx 下内边距（正文底部与屏幕底之间的距离）
 *
 * ⚠️ 上下内边距**不只是好看**：顶部三件浮层（返回/菜单/章节胶囊）与底部胶囊压在屏幕上下，
 * 不留出空间正文就会被压在 UI 底下。默认自动取一组能避开这些浮层的值
 * （见 `NovelPanelStyle.verticalPadding`）。
 */
data class NovelTextStyle(
    val fontSizePx: Float,
    val lineSpacingMultiplier: Float,
    val paragraphSpacingPx: Float,
    val paddingPx: Float,
    val topPaddingPx: Float = 0f,
    val bottomPaddingPx: Float = 0f,
) {
    /** 一页真正能放正文的高度。 */
    fun contentHeightPx(pageHeightPx: Int): Float =
        (pageHeightPx - topPaddingPx - bottomPaddingPx).coerceAtLeast(1f)

    /** 一页真正能放正文的宽度。 */
    fun contentWidthPx(pageWidthPx: Int): Int =
        (pageWidthPx - 2 * paddingPx).toInt().coerceAtLeast(1)
}
