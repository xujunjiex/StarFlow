package com.moe.starflow.novel.reader

/**
 * 页内的一段：「某段落在本页显示的那部分」。
 *
 * ⚠️ 为什么整页存 segment 而不是拼成一个字符串：**跨页的段落**要在渲染时决定这半段显示
 * 原文还是译文。只有知道它属于哪个 `paraIndex`，段未翻译时才能继续显示原文、已翻译才能
 * 换上译文。把整页拼成一个串就丢掉了这个对应关系。
 *
 * ### 行区间是渲染的**唯一**几何来源
 * [lineStart]/[lineEnd] 是段落自身 `StaticLayout` 里的**行号**区间（前闭后开）。绘制时按行区间
 * 去画同一份 layout 的这几行，高度直接取 `getLineBottom(lineEnd-1) - getLineTop(lineStart)`
 * —— 与分页记账用的是同一个数，**按定义不可能对不上**。
 *
 * 早期版本只存字符区间，绘制时把这一段 substring 出来**重新排版**：重新排版会重新断行，
 * 行数与分页时算的不一定相同（尤其段被切开、或中英混排时），于是就成了「分页说放得下、
 * 画出来却顶出框」——底部那行被裁掉一截。行区间法把「重新排版」这一步整个去掉了。
 *
 * [charStart]/[charEnd] 是同一区间的字符表示（翻译按段落号取），与行区间由同一份 layout
 * 推出，两者必然一致。
 */
data class PageSegment(
    val paraIndex: Int,
    val charStart: Int,
    val charEnd: Int,
    val lineStart: Int,
    val lineEnd: Int,
)

/**
 * 「哪些页已经翻译完了」—— 底部进度条的绿块就是它（与漫画 `ReaderProgressBar.setTranslatedPages` 同一套）。
 *
 * ⚠️ 判据是**整页的可翻译段都有译文**，不是「页里有译文」：半页译文不画绿，否则用户以为这页翻完了。
 * ⚠️ 页边界会随字号 / 行距 / 边距变化 → 每次重排后都要**用新页表重算**，否则绿块停在旧位置，
 * 用户以为译文丢了（`refreshOverlay` 每次 loadChapter 都会跑，所以改排版自然重算）。
 *
 * ⚠️ **图片段与 SKIP 段（`……`）不参与判据**：它们按设计永远不翻译，要求它们「有译文」的话，
 * 任何含 `<img>` 占位（`📷 [图片]`，插图版 EPUB 每章都有）的页**永远变不绿** ——
 * 进度条对这类书永远差一截，而滚动模式那条路同样如此。传 [translatable] 进来把它们排除。
 */
internal fun translatedPagesOf(
    pages: List<NovelPage>,
    translatable: Set<Int>,
    translated: Set<Int>,
): Set<Int> =
    pages.mapIndexedNotNull { i, page ->
        i.takeIf {
            page.segments.isNotEmpty() &&
                page.segments.all { s -> s.paraIndex !in translatable || s.paraIndex in translated }
        }
    }.toSet()

/** 一页 = 若干 segment（按显示顺序）。 */
data class NovelPage(val segments: List<PageSegment>)

/**
 * 排版参数。分页与绘制**共用同一份**，保证「怎么分的就怎么画」——
 * 两处各算一套的话，字号一改就会出现文字与分页错位。
 *
 * @param paddingPx 左右内边距
 * @param topPaddingPx 上内边距（正文顶部与屏幕顶之间的距离）
 * @param bottomPaddingPx 下内边距（正文底部与屏幕底之间的距离）
 * @param keepParagraphsWhole **整段保护**：分页不在段落中间切断（放不下的段整段挪到下一页）。
 *   句子被分页从中间切断，翻译也只能按半句来，读者看到的是半句话 —— 所以开着更好，
 *   但**产品默认是关**（面板里的「保持段落完整」开关，见 `NovelPanelStyle.keepParagraphsWhole`），
 *   代价是页面底部可能剩不到一段的空白。
 *   ⚠️ 这里的默认值（true）**只对"没传这个参数的调用方"生效**；生产路径一律由
 *   `NovelPanelStyle.textStyle()` 显式灌入用户的设置值。新调用方别依赖这个默认值。
 *
 * ⚠️ 上下内边距**不只是好看**：顶部三件浮层（返回/菜单/章节胶囊）与底部胶囊压在屏幕上下，
 * 不留出空间正文就会被压在 UI 底下。
 */
data class NovelTextStyle(
    val fontSizePx: Float,
    val lineSpacingMultiplier: Float,
    val paragraphSpacingPx: Float,
    val paddingPx: Float,
    val topPaddingPx: Float = 0f,
    val bottomPaddingPx: Float = 0f,
    val keepParagraphsWhole: Boolean = true,
) {
    /** 一页真正能放正文的高度。 */
    fun contentHeightPx(pageHeightPx: Int): Float =
        (pageHeightPx - topPaddingPx - bottomPaddingPx).coerceAtLeast(1f)

    /** 一页真正能放正文的宽度。 */
    fun contentWidthPx(pageWidthPx: Int): Int =
        (pageWidthPx - 2 * paddingPx).toInt().coerceAtLeast(1)
}
