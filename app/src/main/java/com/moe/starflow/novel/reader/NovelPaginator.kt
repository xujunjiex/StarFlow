package com.moe.starflow.novel.reader

import com.moe.starflow.novel.translate.NovelParagraph
import com.moe.starflow.novel.translate.NovelParagraphType

/**
 * 章文本分页。
 *
 * ### 架构：纯核心 + Android 外壳
 * [paginateByLines] 是**纯函数**（输入「每段的行起点 + 每行真实高度」，输出页表），全部分页
 * 逻辑都在这里，可普通单测。 [paginate] 只是用 `StaticLayout` 量出这两个数组再调它。
 *
 * ⚠️ 这么分是被 Robolectric 逼的：它的 `Paint.breakText` **不按宽度换行**，排出来的
 * `StaticLayout` 行数与真机不符 —— 依赖真实换行的断言在 Robolectric 下是**假绿**。
 * 所以换行信息由调用方/测试显式提供，分页逻辑本身与文本引擎解耦。
 *
 * ### 高度口径：**只认 `StaticLayout` 量出来的数**，不做任何估算
 * 这里的 `lineHeights` 就是 `getLineBottom(i) - getLineTop(i)`，段高就是它们的和
 * （`setIncludePad(false)` 时等于 `layout.height`）—— 绘制端按同一份 layout 的同一批
 * 数字累加。**分页与绘制共用一套几何**，中间没有任何「按字号估行高」「把段距量化成整行」
 * 这类换算，所以「分页说放得下、画出来顶出框」在结构上就不可能发生。
 *
 * 踩过的两个坑都死在「估算」上：
 * - 用 `字号 × 行距倍率` 估行高：中文字体真实行高是字号的 ≈1.15~1.5 倍（含 `lineSpacing`
 *   的空隙），估少了就每页多塞几行，最后几行被画到正文框外面
 * - 把段间距按 `round(段距/行高)` 折算成**整行**：段距不足一行时被兜成一行，
 *   每段凭空多占几十 px，一页因此少放内容、底部留出一大片空白
 *
 * ### 硬约束
 * 对**每一段**，它的全部 segment 的 `[charStart, charEnd)` 按顺序拼接必须完整覆盖
 * `[0, 文本长度)`、无重叠、无丢字。这是「用户不会丢字」的唯一保证，有守卫测试逐段验证。
 */
object NovelPaginator {

    /**
     * @param lineStarts 与 `paragraphs` 中**可见段**一一对应：`lineStarts[i]` 是第 i 个可见段
     *   每行的段内起始偏移（升序，首元素应为 0）。空数组按「整段一行」处理。
     * @param lineHeights 与 `lineStarts` 一一对应且**等长**：每行的真实高度（px），
     *   直接来自 `StaticLayout.getLineBottom(i) - getLineTop(i)`。
     * @param keepParagraphsWhole **整段优先**：放不下就整段挪到下一页，而不是从中间切断。
     *   这是「每一页翻译完整、句子不会跨页中断」的前提（超过一整页的段才不得不切开）。
     */
    internal fun paginateByLines(
        paragraphs: List<NovelParagraph>,
        lineStarts: List<IntArray>,
        lineHeights: List<FloatArray>,
        paragraphSpacingPx: Float,
        pageHeightPx: Float,
        keepParagraphsWhole: Boolean = true,
    ): List<NovelPage> {
        val visible = paragraphs.filter { it.type != NovelParagraphType.SKIP }
        if (visible.isEmpty()) return emptyList()

        val pageHeight = pageHeightPx.coerceAtLeast(1f)

        val pages = mutableListOf<NovelPage>()
        var current = mutableListOf<PageSegment>()
        var usedPx = 0f

        fun flush() {
            if (current.isNotEmpty()) {
                pages.add(NovelPage(current))
                current = mutableListOf()
                usedPx = 0f
            }
        }

        for ((vi, para) in visible.withIndex()) {
            val starts = lineStarts.getOrNull(vi)?.takeIf { it.isNotEmpty() } ?: intArrayOf(0)
            // 度量缺失（或与行起点不等长）时按 0 高兜底：宁可少算高度，也不能因此丢段
            val heights = lineHeights.getOrNull(vi)?.takeIf { it.size == starts.size }
                ?: FloatArray(starts.size) { 0f }
            val textLen = para.originalText.length
            val lineCount = starts.size
            // 整段真实高度 = 各行高度之和 = `StaticLayout.height`（includePad=false）
            val wholeHeight = heights.sum()

            // ── 整段优先：整段放不下就整段挪到下一页 ──
            // ⚠️ 不做这一步的话，段落会被从中间切开：同一段落在两页上各显示半截，
            // 翻译也只能按半段来（"句子中断"），读者看到的是半句话。
            if (keepParagraphsWhole && wholeHeight <= pageHeight) {
                val gap = if (current.isNotEmpty()) paragraphSpacingPx else 0f
                if (usedPx + gap + wholeHeight > pageHeight) flush()
                val gapNow = if (current.isNotEmpty()) paragraphSpacingPx else 0f
                current.add(PageSegment(para.index, 0, textLen, 0, lineCount))
                usedPx += gapNow + wholeHeight
                continue
            }

            // ── 超长段（比一整页还长）：只能按行切，别无选择 ──
            var lineIdx = 0
            var firstChunkOfPara = true
            while (lineIdx < lineCount) {
                val gap = if (firstChunkOfPara && current.isNotEmpty()) paragraphSpacingPx else 0f
                // 本页扣掉段间距后还能放几行 —— 逐行累加**真实行高**，与绘制端逐行累加同一批数
                var take = 0
                var taken = 0f
                while (lineIdx + take < lineCount &&
                    usedPx + gap + taken + heights[lineIdx + take] <= pageHeight
                ) {
                    taken += heights[lineIdx + take]
                    take++
                }
                if (take == 0) {
                    // 一行都放不下 → 换页后重来（新页为空，gap 自然变 0，不会死循环）
                    if (current.isNotEmpty()) {
                        flush()
                        continue
                    }
                    // 空页仍放不下（页高不足一行）：硬放一行，避免死循环
                    take = 1
                    taken = heights[lineIdx]
                }
                val lastIdx = lineIdx + take - 1
                val charStart = starts[lineIdx].coerceIn(0, textLen)
                val charEnd = if (lastIdx + 1 < lineCount) {
                    starts[lastIdx + 1].coerceIn(charStart, textLen)
                } else {
                    textLen
                }
                current.add(PageSegment(para.index, charStart, charEnd, lineIdx, lastIdx + 1))
                usedPx += gap + taken
                lineIdx += take
                firstChunkOfPara = false
                if (lineIdx < lineCount) flush()
            }
        }
        flush()
        return pages
    }

    /**
     * 生产入口：用 `StaticLayout` 量出每段的行起点与**每行真实高度**，再交给纯核心切页。
     *
     * 宽高都要扣掉内边距（渲染时文字就是画在这个内框里的）：左右扣 `paddingPx`，
     * **上下扣 `top/bottomPaddingPx`** —— 顶部三件浮层与底部胶囊压在屏幕上下，
     * 不扣的话正文会被压在 UI 底下，而且页边界会落在浮层覆盖的区域里。
     *
     * ⚠️ 这里排出来的 `StaticLayout` 与 `NovelPageView` 绘制时排的是**同一份**
     * （同样的文本、宽度、字号、行距），所以行号区间在两边指向同一批行。
     */
    fun paginate(
        paragraphs: List<NovelParagraph>,
        style: NovelTextStyle,
        widthPx: Int,
        heightPx: Int,
    ): List<NovelPage> {
        val visible = paragraphs.filter { it.type != NovelParagraphType.SKIP }
        if (visible.isEmpty() || widthPx <= 0 || heightPx <= 0) return emptyList()

        val contentWidth = style.contentWidthPx(widthPx)
        val contentHeight = style.contentHeightPx(heightPx)

        val layouts = visible.map { NovelTextRenderer.build(it.originalText, style, contentWidth) }
        val lineStarts = layouts.map { l -> IntArray(l.lineCount) { l.getLineStart(it) } }
        val lineHeights = layouts.map { l ->
            FloatArray(l.lineCount) { (l.getLineBottom(it) - l.getLineTop(it)).toFloat() }
        }

        return paginateByLines(
            visible, lineStarts, lineHeights, style.paragraphSpacingPx, contentHeight,
            style.keepParagraphsWhole,
        )
    }
}
