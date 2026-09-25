package com.moe.starflow.novel.reader

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * 分页模式：一页一个 item。
 *
 * 用 `notifyItemRangeChanged` 而不是 `notifyDataSetChanged` 刷新译文：后者是
 * structure-changed 事件，`ViewPager2` 会重建全部页面并把当前页弹回第一页
 * （漫画的 Webtoon 模式踩过这个坑）。
 */
class NovelPageAdapter : RecyclerView.Adapter<NovelPageAdapter.VH>() {

    private var content: ChapterContent? = null
    private var style: NovelTextStyle = NovelPageView.DEFAULT_STYLE

    class VH(val view: NovelPageView) : RecyclerView.ViewHolder(view)

    fun submit(content: ChapterContent, style: NovelTextStyle) {
        this.content = content
        this.style = style
        notifyDataSetChanged()
    }

    /** 内容（译文到达/切显示模式）变了但页数不变时的轻量刷新。 */
    fun refresh() {
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = NovelPageView(parent.context)
        // ViewPager2 的页面就用 RecyclerView.LayoutParams（它的 LayoutParams 未公开）
        v.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        return VH(v)
    }

    override fun getItemCount(): Int = content?.pages?.size ?: 0

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = content ?: return
        val page = c.pages.getOrNull(position) ?: return
        holder.view.bind(c, page, style)
    }
}
