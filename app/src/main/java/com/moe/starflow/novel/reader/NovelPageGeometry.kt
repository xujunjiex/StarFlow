package com.moe.starflow.novel.reader

/**
 * 一页正文的**纵向几何**：逐 segment 累加行高，段间距只补在段与段**之间**。
 *
 * ### 为什么必须只有这一份
 * 同一套走法有三处要用：
 * - [NovelPageView.onDraw] —— 画，顺带得出"正文用掉了多少高"（越框就报 `PAGE_OVERFLOW`）
 * - [NovelPageView.paraIndexAt] —— 手指点的 y 落在哪一段（选择模式）
 * - [NovelPaginator] 的 `offsetInPage` —— 锚点离页顶多少 px（决定要不要为锚点强行分页）
 *
 * 以前三处各写一遍、各挂一句"必须与绘制同一套走法"的注释。那种约定靠自觉：某一处改了口径
 * （比如把段间距也补到页内最后一段之后、或换一种行高取法），错位只有真机上看图才发现 ——
 * 本模块的单测是桩（Robolectric 的文本引擎量不出真机行高，见 `novel/CLAUDE.md`），
 * 几何类错误它盖不住。走法收敛成一份之后，"三处一致"成了**结构**而不是约定。
 *
 * ### 高度口径（三条都是踩过坑的）
 * 段高 = 该段在本页的**行区间**内每一行真实高度之和；段间距只在段与段之间补一次。
 * - 行高的唯一来源是 `StaticLayout.getLineBottom(i) - getLineTop(i)`，不做任何估算
 *   （拿「字号 × 行距倍率」估行高：中文字体真实行高是字号的 ≈1.15~1.5 倍，估少了每页多塞几行）
 * - 段间距**不能**折算成整行（`round(段距/行高)`）：不足一行时被兜成一行，每段凭空多占几十 px，
 *   一页因此少放内容、底部留出一大片空白
 * - 页内**最后一段之后不补**段间距：分页的容量模型正是「按段间距分隔」，多补一份会让最后一行的
 *   下沿顶出正文框（用户报过的「底部被裁切」）
 *
 * ⚠️ 行与行是**首尾相接**的：`Layout.getLineBottom(line)` 的实现就是 `getLineTop(line + 1)`
 * （`StaticLayout` 只改 `getLineTop`，`getLineBottom` 是 `Layout` 里的 final 方法）。
 * 所以「行区间内逐行相加」与「`getLineBottom(to-1) - getLineTop(from)`」是同一个数 ——
 * 绘制端按住哪一种取都不会漂，两种写法可以放心互换。
 */
object NovelPageGeometry {

    /**
     * 按显示顺序走一遍页内每一段，把 `(seg, top, bottom)` 交给 [onSegment]。
     *
     * `bottom` **不含**紧跟其后的段间距（那一份已经算进了下一段的 `top`）；页内最后一段之后
     * 不补段间距，所以最后一段的 `bottom` 就是「正文用掉的总高」。
     *
     * @param segments 页内 segment，按显示顺序（与绘制/分页拿到的是同一份）。
     * @param lineHeightsOf `段号 -> 该段的逐行高度`（`getLineBottom(i) - getLineTop(i)`）。
     *   ⚠️ 按**段号**取而不是按 segment 下标取：分页端手里就是一张按段号索引的度量表
     *   （`lineHeights[可见段下标]`），绘制端每个 segment 的 `StaticLayout` 也属于同一个段号 ——
     *   用段号当键，两边才是同一个键空间（一页内段号不重复，见 [PageSegment]）。
     *   返回 null（这一段没有度量）的 segment **整个跳过**：既不算高度，也不给它补段间距。
     *   补了的话，它后面的段会凭空调几十 px、绘制端把最后一行画到正文框外。
     * @param paragraphSpacingPx 段间距。
     * @param startY 第一段的 top（绘制端是正文框顶 `topPaddingPx`，分页端是页顶 0）。
     * @param onSegment 每个**真正参与排版**（有度量、行区间非空）的 segment 回调一次。
     */
    fun walk(
        segments: List<PageSegment>,
        lineHeightsOf: (paraIndex: Int) -> FloatArray?,
        paragraphSpacingPx: Float,
        startY: Float = 0f,
        onSegment: (seg: PageSegment, top: Float, bottom: Float) -> Unit,
    ) {
        var top = startY
        for ((i, seg) in segments.withIndex()) {
            val heights = lineHeightsOf(seg.paraIndex) ?: continue
            val range = lineRange(seg, heights) ?: continue
            var h = 0f
            for (l in range) h += heights[l]
            val bottom = top + h
            onSegment(seg, top, bottom)
            // ⚠️ 段间距只补在段与段**之间**：页内最后一段之后不再补（见类注释）
            top = bottom + if (i != segments.lastIndex) paragraphSpacingPx else 0f
        }
    }

    /**
     * 段在本页的**行区间**（前闭后开），已被该段的行数夹住；夹完为空（= 该段不参与排版）返回 null。
     *
     * ⚠️ 夹取是走法的一部分（它决定「这一段算几行」），所以绘制端裁剪/取 `getLineTop` 也必须用
     * 这个区间，别自己拿 `seg.lineStart/lineEnd` 裸着上：越界的行区间会让画出来的高度与记账的
     * 高度对不上，而那个差最后就落在正文框下沿那行字上。
     */
    fun lineRange(seg: PageSegment, heights: FloatArray?): IntRange? {
        if (heights == null) return null
        val from = seg.lineStart.coerceIn(0, heights.size)
        val to = seg.lineEnd.coerceIn(from, heights.size)
        return if (to > from) from until to else null
    }
}
