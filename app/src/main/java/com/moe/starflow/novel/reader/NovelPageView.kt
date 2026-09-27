package com.moe.starflow.novel.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.StaticLayout
import android.view.View

/**
 * 分页模式下**一页**的自绘 View。
 *
 * 逐 segment 绘制：每个 segment 记录的是「段落自身 layout 的哪几行」（`lineStart..lineEnd`），
 * 这里排出一份**同样的** layout，按行区间画出来、按同一个行区间累加高度。
 * 与分页端共用一套几何，中间没有任何估算（见 [NovelPaginator] 的类注释）——
 * 纵向走法本身收敛在 [NovelPageGeometry] 一份里，本文件不再自己排一遍。
 *
 * ⚠️ 早期版本把 segment 的 substring 取出来**重新排版**，高度按 `行数 × 估算行高` 累加：
 * 重新断行会让行数变化、估算行高会偏，两边一错位底部那行就被画到框外（用户看到的「被裁切」）。
 * 现在绘制与分页读的是同一份 layout 的同一批 `getLineTop/getLineBottom`，**按定义**对得上。
 */
class NovelPageView(context: Context) : View(context) {

    private var content: ChapterContent? = null
    private var page: NovelPage? = null
    private var style: NovelTextStyle = DEFAULT_STYLE
    private var textColor: Int = NovelPageAdapter.DEFAULT_TEXT_COLOR

    /** 选择模式里被选中的段（高亮底）。 */
    private var selected: Set<Int> = emptySet()

    /** 正在翻译 / 刚翻完的段（琥珀高亮底，让用户在重排偏移后仍能找到）。 */
    private var activeBatch: Set<Int> = emptySet()

    /**
     * 与 `page.segments` 对应的排版结果，**按段号索引**（空文本/未布局的段没有条目）。
     *
     * ⚠️ 键是段号而不是 segment 下标：一页内段号不重复（见 [PageSegment]），而排版只由
     * 「段号 → 显示文本」决定 —— 用段号当键，[NovelPageGeometry] 的行高表就是同一套键。
     */
    private var layouts: Map<Int, StaticLayout> = emptyMap()

    /** 段号 → 该段的逐行高度 `getLineBottom(i) - getLineTop(i)`（与分页端量的是同一批数）。 */
    private var lineHeights: Map<Int, FloatArray> = emptyMap()

    /** 这批 [layouts] 是按哪个宽度排的 —— 宽度变了必须重排（旋转/分屏）。 */
    private var laidOutWidth = -1

    /** 越界只报一次（onDraw 每帧都会跑，不设闸门会刷爆日志）。 */
    private var overflowReported = false

    /**
     * 选择模式的高亮底（颜色定义在 [NovelTextRenderer.COLOR_SELECTION]，与滚动模式共用）。
     */
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NovelTextRenderer.COLOR_SELECTION
    }

    /** 「正在翻译 / 刚翻完」的高亮底（颜色与滚动模式共用）。 */
    private val activeBatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NovelTextRenderer.COLOR_ACTIVE_BATCH
    }

    /** 高亮底与滚动模式共用的圆角半径。 */
    private val hlRadiusPx by lazy { NovelTextRenderer.HIGHLIGHT_CORNER_DP * resources.displayMetrics.density }

    fun bind(
        content: ChapterContent,
        page: NovelPage,
        style: NovelTextStyle,
        textColor: Int,
        selected: Set<Int> = emptySet(),
        activeBatch: Set<Int> = emptySet(),
    ) {
        // ⚠️ 排版输入没变就别作废已排好的 layout：选择模式每点一下都会重绑，
        // 全页重建 StaticLayout 是白烧的（而且会闪）
        if (this.content !== content || this.page !== page ||
            this.style != style || this.textColor != textColor
        ) {
            laidOutWidth = -1
            layouts = emptyMap()
            lineHeights = emptyMap()
            overflowReported = false
        }
        this.content = content
        this.page = page
        this.style = style
        this.textColor = textColor
        this.selected = selected
        this.activeBatch = activeBatch
        invalidate()
    }

    /**
     * 页内命中：本 View 坐标 [y] 落在哪一段上（选择模式用）。
     *
     * 走法就是 [NovelPageGeometry.walk]（与 [onDraw] 同一条），所以"手指点的段"与"画出来的段"
     * 按定义对得上。
     * 段与段之间的空隙归**上一段**（空隙只有段间距几十像素，归给"空白"太容易误退出）；
     * 正文上下之外的区域返回 null = 真正的空白（点它退出选择模式）。
     */
    fun paraIndexAt(y: Float): Int? {
        val p = page ?: return null
        if (y < style.topPaddingPx) return null
        ensureLayouts()
        var prev: Int? = null
        var hit: Int? = null
        NovelPageGeometry.walk(p.segments, { lineHeights[it] }, style.paragraphSpacingPx, style.topPaddingPx) { seg, top, bottom ->
            if (hit != null) return@walk
            when {
                y < top -> hit = prev ?: seg.paraIndex   // 落在段间空隙里 → 归上一段
                y < bottom -> hit = seg.paraIndex
                else -> prev = seg.paraIndex
            }
        }
        return hit
    }

    /**
     * 按当前宽度把这一页的排版算好（一次），绘制时直接用。
     *
     * 放在这里而不是逐帧在 `onDraw` 里排：折页动画每帧都会重画，每帧重排整页会掉帧。
     * 逐行高度也在这里一次算好 —— 它只跟排版输入有关，没有理由每帧重算。
     */
    private fun ensureLayouts() {
        val w = width
        if (w <= 0 || w == laidOutWidth) return
        val c = content ?: return
        val p = page ?: return
        laidOutWidth = w
        val cw = style.contentWidthPx(w)
        val newLayouts = HashMap<Int, StaticLayout>(p.segments.size)
        val newHeights = HashMap<Int, FloatArray>(p.segments.size)
        for (seg in p.segments) {
            val layout = c.displayOf(seg.paraIndex)
                .takeIf { it.isNotEmpty() }
                ?.let { NovelTextRenderer.build(it, style, cw, textColor) }
                ?: continue
            newLayouts[seg.paraIndex] = layout
            newHeights[seg.paraIndex] = FloatArray(layout.lineCount) {
                (layout.getLineBottom(it) - layout.getLineTop(it)).toFloat()
            }
        }
        layouts = newLayouts
        lineHeights = newHeights
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = page ?: return
        ensureLayouts()

        val padding = style.paddingPx
        val boxTop = style.topPaddingPx
        val boxBottom = height - style.bottomPaddingPx
        val widthF = width.toFloat()

        // 正文画在「正文框」内。分页已经按同一份几何保证放得下，这一刀只是最后一道兜底
        // （排版模型若被改错，宁可少画一行也不能画到框外）—— 真被切到时 [reportOverflow] 会报出来。
        // ⚠️ 横向**不裁**（裁到正文列）：高亮底要铺满整行（左右边距也填上，见下），
        // 而文字本来就只画在正文列里（layout 的宽度就是正文列宽），裁横向没有意义。
        canvas.save()
        canvas.clipRect(0f, boxTop, widthF, boxBottom)

        var usedBottom = boxTop
        // ⚠️ 纵向走法走共用件（[NovelPageGeometry]）：绘制、命中测试、分页量"锚点离页顶多少 px"
        // 必须是同一份，否则三处各改各的，错位只有真机上看图才发现
        NovelPageGeometry.walk(p.segments, { lineHeights[it] }, style.paragraphSpacingPx, boxTop) { seg, top, bottom ->
            val layout = layouts[seg.paraIndex] ?: return@walk
            // 行区间由共用件判定（没有度量的段根本不会被回调），画与记账用的是同一个区间
            val range = NovelPageGeometry.lineRange(seg, lineHeights[seg.paraIndex]) ?: return@walk
            usedBottom = bottom

            // 高亮底画在文字**下面**（同一个矩形范围，逐段累加的高度）。
            // 选中优先于「正在翻译」：两者同时命中时得看得出是选中
            val hl = when {
                seg.paraIndex in selected -> selectionPaint
                seg.paraIndex in activeBatch -> activeBatchPaint
                else -> null
            }
            if (hl != null) {
                // ⚠️ **铺满整行**（左右边距也填上）+ 圆角（用户要求）：只铺正文列的话
                // 高亮像被两侧裁了一刀，和滚动模式的 item 底色也不是一个样子
                canvas.drawRoundRect(0f, top, widthF, bottom, hlRadiusPx, hlRadiusPx, hl)
            }

            // 只画 [range] 这几行：整份 layout 一起画会把区间外的行也画出来，
            // 而本页的高度记账只算了这几行。
            // ⚠️ 先 translate 再 clipRect：这样裁剪矩形是**布局坐标**下的行区间，
            // 与 getLineTop/getLineBottom 同一套坐标，不用自己做一次换算
            val lineTop = layout.getLineTop(range.first).toFloat()
            val lineBottom = layout.getLineBottom(range.last).toFloat()
            canvas.save()
            canvas.translate(padding, top - lineTop)
            canvas.clipRect(0f, lineTop, widthF, lineBottom)
            layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()

        reportOverflow(usedBottom, boxBottom)
    }

    /**
     * 正文画完的总高度越过正文框下沿时**报一行**。
     *
     * 这是「分页账目与绘制是否真的一致」的唯一现场证据：不报 = 底部的字没有被裁。
     * 静态断言做不到这一点（Robolectric 的文本引擎是桩，量不出真机行高）。
     */
    private fun reportOverflow(usedBottom: Float, boxBottom: Float) {
        if (overflowReported || usedBottom <= boxBottom + 0.5f) return
        overflowReported = true
        NovelDebug.log(
            "PAGE_OVERFLOW 正文顶出框 ${"%.1f".format(usedBottom - boxBottom)} px " +
                "used=$usedBottom box=$boxBottom h=$height top=${style.topPaddingPx} " +
                "bot=${style.bottomPaddingPx} segs=${page?.segments?.size}"
        )
    }

    companion object {
        val DEFAULT_STYLE = NovelTextStyle(fontSizePx = 42f, lineSpacingMultiplier = 1.5f, paragraphSpacingPx = 18f, paddingPx = 24f)
    }
}
