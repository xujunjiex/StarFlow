package com.moe.starflow.novel.reader

/**
 * 阅读锚点 = **段号 + 段内比例**（0..1）。
 *
 * ### 为什么只有段号不够（用户报的「翻译之后当前页面跳变」）
 * 一段可以长到跨好几页 —— `NovelParagraphSplitter` 只按**空行**分段，所以一个几万字的章节
 * 可能整章只有一段（样例 `英文-单段超长-无空行.txt` 就是）。这时「第几段」定位不到：
 *
 * - 翻页模式：按段号恢复会落到**这一段的第一页**（`pageOfParagraph` 是"含该段的第一页"），
 *   而读者人在这一段的第 3 页 → 每翻一批译文重排一次，就被拽回段首所在的页
 * - 滚动模式：`scrollToPosition(段)` 把段顶吸到屏幕顶，读者本来读到段中间也被弹回去
 *
 * 段内比例在「原文换成译文、长度变了」之后仍然有意义：你在这一段的 30% 处，
 * 重排完还在 30% 处 —— 这也是"位置不变"在文本被替换后唯一说得通的定义。
 */
data class NovelAnchor(val paraIndex: Int = 0, val fraction: Float = 0f) {

    /**
     * 本锚点在「这段的显示文本」里对应的字符位置。
     *
     * 文本长度变了（原文↔译文）也得用它换算 —— 比例是"这一段的大致位置"，
     * 换算出来的字符位置就是重排后要定位、要**分页**的地方。
     */
    fun charOffsetOf(textLength: Int): Int =
        if (textLength <= 1) 0 else ((textLength - 1) * fraction.coerceIn(0f, 1f)).toInt()
}

object NovelAnchors {

    /** 页锚点：本页**第一个** segment 的段号 + 它在该段显示文本里的比例。 */
    fun ofPage(pages: List<NovelPage>, displayTexts: Map<Int, String>, pageIndex: Int): NovelAnchor {
        val seg = pages.getOrNull(pageIndex)?.segments?.firstOrNull() ?: return NovelAnchor()
        val len = displayTexts[seg.paraIndex]?.length ?: 0
        if (len <= 0) return NovelAnchor(seg.paraIndex, 0f)
        return NovelAnchor(seg.paraIndex, (seg.charStart.toFloat() / len).coerceIn(0f, 1f))
    }

    /** 滚动锚点：可见首段的段号 + 已经滚过这一段的多少（0 = 段顶正好贴着屏幕顶）。 */
    fun ofScroll(paraIndex: Int, scrolledIntoItemPx: Int, itemHeightPx: Int): NovelAnchor =
        if (itemHeightPx <= 0) NovelAnchor(paraIndex, 0f)
        else NovelAnchor(
            paraIndex,
            (scrolledIntoItemPx.coerceAtLeast(0).toFloat() / itemHeightPx).coerceIn(0f, 1f),
        )

    /**
     * 锚点落在重排后的哪一页。
     *
     * ⚠️ **不能**退回「包含该段的第一页」（`NovelChapterRepository.pageOfParagraph` 那套）：
     * 段被切成好几页时那正是**段首**所在的页 —— 就是跳变的来源。
     * 这里按段内比例在**重排后的显示文本**里换算出目标字符位置，再找包含它的那一页；
     * 段不在本页表里（异常）才退化成"含该段的第一页"。
     */
    fun pageOf(pages: List<NovelPage>, displayTexts: Map<Int, String>, anchor: NovelAnchor): Int {
        if (pages.isEmpty()) return 0
        val len = displayTexts[anchor.paraIndex]?.length ?: 0
        val target = anchor.charOffsetOf(len)
        var firstPageWithPara = -1
        pages.forEachIndexed { i, page ->
            for (s in page.segments) {
                if (s.paraIndex < anchor.paraIndex) continue
                // 已经越过锚点段都没命中（比例越界/段变短）→ 用含该段的第一页兜底
                if (s.paraIndex > anchor.paraIndex) {
                    return if (firstPageWithPara >= 0) firstPageWithPara else i
                }
                if (firstPageWithPara < 0) firstPageWithPara = i
                if (target >= s.charStart && target < s.charEnd) return i
            }
        }
        return if (firstPageWithPara >= 0) firstPageWithPara else pages.lastIndex
    }
}
