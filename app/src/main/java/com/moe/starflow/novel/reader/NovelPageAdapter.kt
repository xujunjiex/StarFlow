package com.moe.starflow.novel.reader

import android.view.GestureDetector
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * 分页模式：一页一个 item。
 *
 * 用 `notifyItemRangeChanged` 而不是 `notifyDataSetChanged` 刷新译文：后者是
 * structure-changed 事件，`ViewPager2` 会重建全部页面并把当前页弹回第一页
 * （漫画的 Webtoon 模式踩过这个坑）。
 *
 * ### 点击
 * 手势由宿主注入（[tapDetector]）。与漫画一样把**同一个手势探测器**挂在页 View 上，
 * 于是「中间格显隐 chrome / 右上角开菜单 / 左右半屏翻页」在两种内容上是同一套坐标判定。
 */
class NovelPageAdapter : RecyclerView.Adapter<NovelPageAdapter.VH>() {

    private var content: ChapterContent? = null
    private var style: NovelTextStyle = NovelPageView.DEFAULT_STYLE
    private var textColor: Int = NovelPageAdapter.DEFAULT_TEXT_COLOR

    /**
     * 单击手势探测器（宿主注入；分页与滚动两个模式共用同一个实例）。
     *
     * ⚠️ 挂上之后触摸监听**必须返回 false**：消费掉事件会让 ViewPager2 收不到横向拖拽，
     * 翻页手势就没了。单击由探测器在 `onSingleTapConfirmed` 里判定 —— 滑动会发
     * ACTION_CANCEL，不会被误判成单击。
     */
    var tapDetector: GestureDetector? = null

    class VH(val view: NovelPageView) : RecyclerView.ViewHolder(view)

    fun submit(content: ChapterContent, style: NovelTextStyle, textColor: Int) {
        this.content = content
        this.style = style
        this.textColor = textColor
        notifyDataSetChanged()
    }

    /** 内容（译文到达/切显示模式）变了但页数不变时的轻量刷新。 */
    fun refresh() {
        notifyItemRangeChanged(0, itemCount)
    }

    /** 只换配色（切阅读背景）时的轻量刷新：不必重排，重画即可。 */
    fun setTextColor(color: Int) {
        if (textColor == color) return
        textColor = color
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = NovelPageView(parent.context)
        // ViewPager2 的页面就用 RecyclerView.LayoutParams（它的 LayoutParams 未公开）
        v.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        v.setOnTouchListener { _, e ->
            tapDetector?.onTouchEvent(e)
            false
        }
        return VH(v)
    }

    override fun getItemCount(): Int = content?.pages?.size ?: 0

    override fun onBindViewHolder(holder: VH, position: Int) {
        // ⚠️ `tag` 是翻页动画的页号来源：`NoneTransformer` / `CoverTransformer`（与漫画共用同一份
        // 实现）靠 `page.tag as? Int` 认出「哪一页是锚点」。不打这个 tag，动画模式下所有页都会
        // 被判成"非锚点页"而隐藏 → 画面全空。
        holder.itemView.tag = position
        val c = content ?: return
        val page = c.pages.getOrNull(position) ?: return
        holder.view.bind(c, page, style, textColor)
    }

    companion object {
        const val DEFAULT_TEXT_COLOR = 0xFF1A1A1A.toInt()
    }
}
