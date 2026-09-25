package com.moe.starflow.novel.reader

import android.content.Context
import android.graphics.Canvas
import android.text.StaticLayout
import android.view.View

/**
 * 分页模式下**一页**的自绘 View。
 *
 * 逐 segment 绘制：每个 segment 记录的是「段落自身 layout 的哪几行」（`lineStart..lineEnd`），
 * 这里排出一份**同样的** layout，按行区间画出来、按同一个行区间累加高度。
 * 与分页端共用一套几何，中间没有任何估算（见 [NovelPaginator] 的类注释）。
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

    /** 与 `page.segments` 一一对应的排版结果（空文本/未布局时为 null）。 */
    private var layouts: List<StaticLayout?> = emptyList()

    /** 这批 [layouts] 是按哪个宽度排的 —— 宽度变了必须重排（旋转/分屏）。 */
    private var laidOutWidth = -1

    /** 越界只报一次（onDraw 每帧都会跑，不设闸门会刷爆日志）。 */
    private var overflowReported = false

    fun bind(content: ChapterContent, page: NovelPage, style: NovelTextStyle, textColor: Int) {
        this.content = content
        this.page = page
        this.style = style
        this.textColor = textColor
        // 宽度要等布局完成才知道，这里只作废旧排版；真正的排版在 onDraw 里按需做一次
        laidOutWidth = -1
        layouts = emptyList()
        overflowReported = false
        invalidate()
    }

    /**
     * 按当前宽度把这一页的排版算好（一次），绘制时直接用。
     *
     * 放在这里而不是逐帧在 `onDraw` 里排：折页动画每帧都会重画，每帧重排整页会掉帧。
     */
    private fun ensureLayouts() {
        val w = width
        if (w <= 0 || w == laidOutWidth) return
        val c = content ?: return
        val p = page ?: return
        laidOutWidth = w
        val cw = style.contentWidthPx(w)
        layouts = p.segments.map { seg ->
            c.displayOf(seg.paraIndex)
                .takeIf { it.isNotEmpty() }
                ?.let { NovelTextRenderer.build(it, style, cw, textColor) }
        }
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
        canvas.save()
        canvas.clipRect(padding, boxTop, widthF - padding, boxBottom)

        var y = boxTop
        for ((i, seg) in p.segments.withIndex()) {
            val layout = layouts.getOrNull(i) ?: continue
            val from = seg.lineStart.coerceIn(0, layout.lineCount)
            val to = seg.lineEnd.coerceIn(from, layout.lineCount)
            if (to <= from) continue
            val top = layout.getLineTop(from).toFloat()
            val bottom = layout.getLineBottom(to - 1).toFloat()

            // 只画 [from, to) 这几行：整份 layout 一起画会把区间外的行也画出来，
            // 而本页的高度记账只算了这几行。
            // ⚠️ 先 translate 再 clipRect：这样裁剪矩形是**布局坐标**下的行区间，
            // 与 getLineTop/getLineBottom 同一套坐标，不用自己做一次换算
            canvas.save()
            canvas.translate(padding, y - top)
            canvas.clipRect(0f, top, widthF, bottom)
            layout.draw(canvas)
            canvas.restore()

            y += bottom - top
            // 段间距只补在段与段**之间**：页内最后一段之后不该再补 —— 分页的容量模型正是
            // 「按段间距分隔」，多补一份会让最后一行的下沿顶出正文框
            if (i != p.segments.lastIndex) y += style.paragraphSpacingPx
        }
        canvas.restore()

        reportOverflow(y, boxBottom)
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
