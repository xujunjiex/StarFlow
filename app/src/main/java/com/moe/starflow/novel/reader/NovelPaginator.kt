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
     * 锚点落在自然分页的这一比例**以下**时才值得强行分页（见 [paginate]）。
     *
     * 略小于半页：把"读者回跳的最大距离"和"上一页留下的最大空白"一起压在半页以内。
     */
    private const val FORCE_BREAK_MIN_FRACTION = 0.45f

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
                // ⚠️ 起点取 `starts.first()` 而不是写死 0：`paginateAround` 会把锚点所在段
                // 的**行度量从锚点行切开**再交给这里（那时首行起点不是 0，也不代表整段的开头）
                current.add(
                    PageSegment(para.index, starts.first().coerceIn(0, textLen), textLen, 0, lineCount),
                )
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
        /** 阅读锚点（带位重排）：它必须落在**页首**，见 [paginateAround]。 */
        anchor: NovelAnchor? = null,
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

        // 锚点 → (可见段下标, 段内行号)：锚点的字符位置落在哪一行，那一行就要另起一页。
        // ⚠️ 行号由**同一份 layout** 反查（`getLineForOffset`），与 `lineStarts/lineHeights`
        // 同源，不会出现"切在行中间"。
        val visIndexOf = HashMap<Int, Int>(visible.size)
        visible.forEachIndexed { i, p -> visIndexOf[p.index] = i }
        val target = anchor?.let { a ->
            val vi = visIndexOf[a.paraIndex] ?: return@let null
            val len = visible[vi].originalText.length
            val off = a.charOffsetOf(len).coerceIn(0, (len - 1).coerceAtLeast(0))
            vi to layouts[vi].getLineForOffset(off)
        }

        // 先按**自然分页**算一遍，量出锚点落在它那一页的什么位置再决定要不要强分。
        //
        // ⚠️ 强分是有代价的：锚点被顶到页首，意味着**上一页在锚点处提前结束**，
        // 空出来的正好是"锚点在自然分页里离页顶的距离"。所以：
        // - 锚点本来就在页面上半部分 → 不强分：读者几乎看不出位移，强分反而留一大片空白
        //   （用户报的「三态切换后翻页，有页面提前分页、底部大片空白」）
        // - 锚点落在下半部分 → 强分：不强分读者要往回跳将近一屏，强分只让上一页少几行
        // 阈值取略小于半页，把"最大位移"和"最大空白"一起压在半页以内。
        val natural = paginateByLines(
            visible, lineStarts, lineHeights, style.paragraphSpacingPx, contentHeight,
            style.keepParagraphsWhole,
        )
        val forceBreak = target?.takeIf { (vi, line) ->
            shouldForceBreak(natural, visIndexOf, vi, line, lineHeights, style.paragraphSpacingPx, contentHeight)
        }
        if (forceBreak == null) return natural

        return paginateAround(visible, lineStarts, lineHeights, style, contentHeight, forceBreak!!)
    }

    /**
     * 以 [breakAt]（可见段下标, 段内行号）为界分两段填页：
     * - **后半段**（从锚点行开始）照常**从上往下**填 —— 锚点行正好落在它的第一页页首
     * - **前半段**（锚点之前）**从下往上**填 —— 每一页都尽量塞满，最后一页正好在锚点前结束
     *
     * ⚠️ 为什么不直接"从头填、到锚点就提前收页"（上一版的写法）：那会让锚点前一页
     * 只剩小半页内容、底部留一大片空白（用户报的「有的页面提前莫名其妙分页，底部大片空白」，
     * 尤其是双语↔译文/原文来回切的时候）。倒着填就没有空白，两边的目标同时成立。
     */
    internal fun paginateAround(
        visible: List<NovelParagraph>,
        lineStarts: List<IntArray>,
        lineHeights: List<FloatArray>,
        style: NovelTextStyle,
        contentHeight: Float,
        breakAt: Pair<Int, Int>,
    ): List<NovelPage> {
        val (breakVi, breakLine) = breakAt
        val lines = flatten(visible, lineStarts, lineHeights)
        val breakIdx = lines.indexOfFirst { it.visibleIndex == breakVi && it.lineIndex == breakLine }
        if (breakIdx < 0) {
            return paginateByLines(
                visible, lineStarts, lineHeights, style.paragraphSpacingPx, contentHeight,
                style.keepParagraphsWhole,
            )
        }

        // 前半段：倒着填（页序在函数里已经翻正）
        val head = pagesBackward(
            lines, breakIdx, style.paragraphSpacingPx, contentHeight, style.keepParagraphsWhole,
        )

        // 后半段：把锚点所在段的行度量从锚点行切开，交给原来的正向分页 ——
        // 它从空页开始，所以第一页一定以锚点行开头
        val tailParas = visible.subList(breakVi, visible.size)
        val tailStarts = lineStarts.subList(breakVi, lineStarts.size).mapIndexed { i, a ->
            if (i == 0) a.copyOfRange(breakLine.coerceIn(0, a.size), a.size) else a
        }
        val tailHeights = lineHeights.subList(breakVi, lineHeights.size).mapIndexed { i, a ->
            if (i == 0) a.copyOfRange(breakLine.coerceIn(0, a.size), a.size) else a
        }
        val tail = paginateByLines(
            tailParas, tailStarts, tailHeights, style.paragraphSpacingPx, contentHeight,
            style.keepParagraphsWhole,
        )
        // ⚠️ 尾部第一段的行度量是从锚点行**切片**出来的，段内行号得加回偏移 ——
        // 绘制端拿的是**整段**的 layout，行号必须是整段的绝对行号，否则会画错行。
        // 只有第一段需要搬（后面的段没切过）。
        val anchorPara = visible[breakVi].index
        val tailFixed = if (breakLine == 0) tail else tail.map { page ->
            NovelPage(
                page.segments.map { seg ->
                    if (seg.paraIndex == anchorPara) {
                        seg.copy(lineStart = seg.lineStart + breakLine, lineEnd = seg.lineEnd + breakLine)
                    } else {
                        seg
                    }
                },
            )
        }
        return head + tailFixed
    }

    /** 展平后的一行：段号、段内行号、字符区间、行高。 */
    private class Line(
        val visibleIndex: Int,
        val paraIndex: Int,
        val lineIndex: Int,
        val charStart: Int,
        val charEnd: Int,
        val height: Float,
    )

    private fun flatten(
        paragraphs: List<NovelParagraph>,
        lineStarts: List<IntArray>,
        lineHeights: List<FloatArray>,
    ): List<Line> {
        val out = ArrayList<Line>()
        paragraphs.forEachIndexed { vi, p ->
            val starts = lineStarts.getOrNull(vi)?.takeIf { it.isNotEmpty() } ?: intArrayOf(0)
            val heights = lineHeights.getOrNull(vi)?.takeIf { it.size == starts.size }
                ?: FloatArray(starts.size) { 0f }
            val len = p.originalText.length
            for (l in starts.indices) {
                val from = starts[l].coerceIn(0, len)
                val to = if (l + 1 < starts.size) starts[l + 1].coerceIn(from, len) else len
                out += Line(vi, p.index, l, from, to, heights[l])
            }
        }
        return out
    }

    /**
     * **从下往上**填页：每页尽量塞满，最后一页正好在 `endExclusive` 前一行结束。
     *
     * 段间距只在**跨段**时补一次（与正向同一套规则，只是方向相反）；
     * 一页里同一段连续的行合并成一个 segment（行区间必须在同一段内连续）。
     */
    private fun pagesBackward(
        lines: List<Line>,
        endExclusive: Int,
        paragraphSpacingPx: Float,
        pageHeightPx: Float,
        keepParagraphsWhole: Boolean,
    ): List<NovelPage> {
        if (endExclusive <= 0) return emptyList()
        // 整段真实高度（判"这一段自己装得下一页吗"）
        val wholeOf = HashMap<Int, Float>()
        for (l in lines) wholeOf[l.paraIndex] = (wholeOf[l.paraIndex] ?: 0f) + l.height
        val pagesOut = ArrayList<NovelPage>()
        // 倒着走时先拿到的行在阅读顺序上更靠后 —— 先按倒序收集，收满一页再翻正
        var rev: MutableList<PageSegment> = ArrayList()
        var used = 0f
        var lastPara = -1
        var i = endExclusive - 1

        fun flush() {
            if (rev.isEmpty()) return
            pagesOut.add(NovelPage(rev.asReversed()))
            rev = ArrayList()
            used = 0f
            lastPara = -1
        }

        while (i >= 0) {
            val ln = lines[i]
            val gap = if (rev.isNotEmpty() && ln.paraIndex != lastPara) paragraphSpacingPx else 0f
            // ⚠️ **整段优先在倒着填时同样要生效**：这一段自己装得下一页、但这一页放不下它整段时，
            // 整段挪到（阅读顺序上更早的）上一页 —— 与正向分页同一条规则，否则开着
            // 「保持段落完整」也会被这里从中间切开
            if (keepParagraphsWhole && rev.isNotEmpty() && ln.paraIndex != lastPara) {
                val whole = wholeOf[ln.paraIndex] ?: 0f
                if (whole <= pageHeightPx && used + gap + whole > pageHeightPx) {
                    flush()
                    continue
                }
            }
            if (rev.isNotEmpty() && used + gap + ln.height > pageHeightPx) {
                flush()
                continue    // 换页后重来这一行；新页为空，gap 自然是 0，不会死循环
            }
            val head = rev.lastOrNull()
            if (head != null && head.paraIndex == ln.paraIndex && head.lineStart == ln.lineIndex + 1) {
                // 同一段的相邻行 → 并进同一个 segment（倒着走 = 往前扩）
                rev[rev.size - 1] = head.copy(charStart = ln.charStart, lineStart = ln.lineIndex)
            } else {
                rev.add(PageSegment(ln.paraIndex, ln.charStart, ln.charEnd, ln.lineIndex, ln.lineIndex + 1))
            }
            used += gap + ln.height
            lastPara = ln.paraIndex
            i--
        }
        flush()
        // pagesOut 是按"从后往前"的顺序生成的 → 翻正
        return pagesOut.asReversed()
    }

    /**
     * 值不值得为这个锚点**强行分页**（纯函数，可单测）。
     *
     * 强分把锚点顶到页首，代价是**上一页在锚点处提前结束**，空出来的正好是"锚点在自然分页里
     * 离页顶的距离"：
     * - 锚点在上半部分 → 不强分：读者几乎看不出位移，强分反而在上一页留一大片空白
     *   （用户报的「三态切换后翻页，有页面提前分页、底部大片空白」）
     * - 锚点在下半部分 → 强分：不强分读者要往回跳将近一屏，强分只让上一页少几行
     */
    internal fun shouldForceBreak(
        naturalPages: List<NovelPage>,
        visIndexOf: Map<Int, Int>,
        targetVis: Int,
        targetLine: Int,
        lineHeights: List<FloatArray>,
        paragraphSpacingPx: Float,
        pageHeightPx: Float,
        minFraction: Float = FORCE_BREAK_MIN_FRACTION,
    ): Boolean {
        val off = offsetInPage(
            naturalPages, visIndexOf, targetVis, targetLine, lineHeights, paragraphSpacingPx,
        ) ?: return false
        return off > pageHeightPx * minFraction
    }

    /**
     * 锚点在**自然分页**里距离所在页顶多少 px（页表里找不到 → null）。
     *
     * 走法与绘制完全一致（逐 segment 累加真实行高、段间距只补在段之间），
     * 否则量出来的"距离页顶"和用户看到的对不上。
     */
    private fun offsetInPage(
        pages: List<NovelPage>,
        visIndexOf: Map<Int, Int>,
        targetVis: Int,
        targetLine: Int,
        lineHeights: List<FloatArray>,
        paragraphSpacingPx: Float,
    ): Float? {
        for (page in pages) {
            var y = 0f
            for ((i, seg) in page.segments.withIndex()) {
                val vi = visIndexOf[seg.paraIndex] ?: continue
                val heights = lineHeights.getOrNull(vi)
                val from = seg.lineStart
                val to = if (heights != null) seg.lineEnd.coerceAtMost(heights.size) else from
                if (vi == targetVis && targetLine in from until to && heights != null) {
                    var off = 0f
                    for (l in from until targetLine.coerceAtMost(heights.size)) off += heights[l]
                    return y + off
                }
                if (heights != null) for (l in from until to) y += heights[l]
                if (i != page.segments.lastIndex) y += paragraphSpacingPx
            }
        }
        return null
    }
}
