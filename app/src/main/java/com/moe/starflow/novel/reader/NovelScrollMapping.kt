package com.moe.starflow.novel.reader

import com.moe.starflow.novel.translate.NovelParagraph

/**
 * 连续滚动模式的位置映射（纯函数，可单测）。
 *
 * 滚动模式**一段一个 item**，没有「页」的概念，所以位置映射就是「第几段 ↔ 第几个 item」。
 * 段落号是断点续读与跨模式对齐的公共锚点：
 * - 分页 → 滚动：由段落号算出 item 下标
 * - 滚动 → 分页：由第一个可见 item 的段落号算出页号（[NovelChapterRepository.pageOfParagraph]）
 */
object NovelScrollMapping {

    /**
     * 滚动列表里显示的段落：与分页同口径（排除 SKIP，它们不显示）。
     *
     * ⚠️ 直接就是 [ChapterContent.visibleParas]（`by lazy`，每个 ChapterContent 算一次），
     * **别在这里现 `filter`**：这个列表被 `getItemCount()`（RecyclerView 每次布局都问）、
     * 每个 item 的绑定、以及滚动回调路径反复问到，而整本当一章的书
     * （`TxtChapterSplitter.wholeBook`）段数上万 —— 现算等于每次都要过一遍整章并新建一张表。
     */
    fun visibleParagraphs(content: ChapterContent): List<NovelParagraph> = content.visibleParas

    /**
     * 段落号 → item 下标。
     *
     * - 精确命中 → 该下标
     * - 落在被跳过的段（SKIP）上 → **下一个可见段**（不要回退，回退会让用户看到已经读过的内容）
     * - 超出本章内容（旧缓存里的更大值）→ **末段**，与 `NovelChapterRepository.pageOfParagraph` 同口径
     */
    fun positionOf(content: ChapterContent, paraIndex: Int): Int {
        val list = visibleParagraphs(content)
        if (list.isEmpty()) return 0
        val exact = list.indexOfFirst { it.index == paraIndex }
        if (exact >= 0) return exact
        val next = list.indexOfFirst { it.index >= paraIndex }
        return if (next >= 0) next else list.lastIndex
    }

    /**
     * item 下标 → 段落号；越界返回 null（列表还没填充时会发生）。
     *
     * ⚠️ 现在就是 [visibleParagraphs]（即 [ChapterContent.visibleParas]）上的一次 O(1) 下标查询，
     * **别再写回现 `filter` 的版本**：调用方是 `NovelReaderActivity.updateFromScroll`
     * （滚动回调，**每帧一次**），现算等于每帧把整章过一遍并新分配一整份表
     * （整本一章、没有章节标记的 TXT 就是每帧几千个元素）。
     */
    fun paraIndexOf(content: ChapterContent, position: Int): Int? =
        visibleParagraphs(content).getOrNull(position)?.index
}
