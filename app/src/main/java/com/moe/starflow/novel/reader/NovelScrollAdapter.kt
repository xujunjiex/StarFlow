package com.moe.starflow.novel.reader

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * 连续滚动模式：**一段一个 item**。
 *
 * 与分页的关键差别：这里没有「页」，所以「跨页段落显示原文还是译文」的问题不存在 ——
 * 一段就是一整段，直接按 [ChapterContent.displayOf] 画。
 *
 * ⚠️ 行高必须按内容自量（`wrap_content` + `onMeasure`）：不同段落长度差几十倍，
 * 固定行高会让长段被裁、短段留白。
 */
class NovelScrollAdapter : RecyclerView.Adapter<NovelScrollAdapter.VH>() {

    private var content: ChapterContent? = null
    private var style: NovelTextStyle = NovelPageView.DEFAULT_STYLE

    /** 点击某段：手动模式下补翻这一段。 */
    var onParagraphClick: ((Int) -> Unit)? = null

    class VH(val view: ParagraphView) : RecyclerView.ViewHolder(view)

    fun submit(content: ChapterContent, style: NovelTextStyle) {
        this.content = content
        this.style = style
        notifyDataSetChanged()
    }

    /** 译文到达/切显示模式：条数不变时用轻量刷新，避免重置滚动位置。 */
    fun refresh() {
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = ParagraphView(parent.context)
        v.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return VH(v)
    }

    override fun getItemCount(): Int = content?.let { NovelScrollMapping.visibleParagraphs(it).size } ?: 0

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = content ?: return
        val para = NovelScrollMapping.visibleParagraphs(c).getOrNull(position) ?: return
        holder.view.bind(c.displayOf(para.index), style)
        holder.view.setOnClickListener { onParagraphClick?.invoke(para.index) }
    }

    /** 一段的自绘 View。 */
    class ParagraphView(context: Context) : View(context) {

        private var text: String = ""
        private var style: NovelTextStyle = NovelPageView.DEFAULT_STYLE

        fun bind(text: String, style: NovelTextStyle) {
            if (this.text == text && this.style == style) return
            this.text = text
            this.style = style
            requestLayout()
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val contentWidth = (w - 2 * style.paddingPx).toInt().coerceAtLeast(1)
            val h = if (text.isEmpty()) 0
            else NovelTextRenderer.build(text, style, contentWidth).height +
                (2 * style.paddingPx).toInt() + style.paragraphSpacingPx.toInt()
            setMeasuredDimension(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (text.isEmpty()) return
            NovelTextRenderer.draw(
                canvas = canvas,
                text = text,
                style = style,
                contentWidth = (width - 2 * style.paddingPx).toInt().coerceAtLeast(1),
                x = style.paddingPx,
                y = style.paddingPx,
            )
        }
    }
}
