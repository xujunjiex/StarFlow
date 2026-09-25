package com.moe.starflow.novel.reader

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.mangaimport.reader.CurlPageView

/**
 * 分页模式：一页一个 item。
 *
 * 用 `notifyItemRangeChanged` 而不是 `notifyDataSetChanged` 刷新译文：后者是
 * structure-changed 事件，`ViewPager2` 会重建全部页面并把当前页弹回第一页
 * （漫画的 Webtoon 模式踩过这个坑）。
 *
 * ### item 是 [CurlPageView] 包着 [NovelPageView]
 * 外层用**漫画那同一个** [CurlPageView] 是为了「仿真」翻页动画：折页效果靠它
 * `dispatchDraw` 里对**自己的子树**做裁剪 + 镜像，包一层就自动对文字页生效，
 * 不必为文字另写一套折页。`SimulationTransformer` 也是按 `page is CurlPageView` 找上来的。
 *
 * ### 每页自带不透明底色
 * 阅读背景原本只画在 ViewPager2 容器上。**覆盖**动画是页面互相叠着推的，
 * 页面透明就会变成「只有文字在动、背景不动」。所以底色画到每一页上。
 *
 * ### 手势不在这里
 * 单击探测器挂在 **ViewPager2 内部那个 RecyclerView** 上（见 `NovelReaderActivity.setupOverlays`）。
 * ⚠️ 挂在单个页 View 上是不行的：页 View 不消费 `ACTION_DOWN`，ViewGroup 就不会把它记为
 * touch target，后续 MOVE/UP 根本不会再派发过来 → 探测器只收到 DOWN，
 * `onSingleTapConfirmed` 永远不触发（表现为「点哪儿都没反应」）。踩过。
 */
class NovelPageAdapter : RecyclerView.Adapter<NovelPageAdapter.VH>() {

    private var content: ChapterContent? = null
    private var style: NovelTextStyle = NovelPageView.DEFAULT_STYLE
    private var textColor: Int = NovelPageAdapter.DEFAULT_TEXT_COLOR
    private var backgroundColor: Int = android.graphics.Color.WHITE

    class VH(val curl: CurlPageView, val page: NovelPageView) : RecyclerView.ViewHolder(curl)

    fun submit(content: ChapterContent, style: NovelTextStyle, textColor: Int, backgroundColor: Int) {
        this.content = content
        this.style = style
        this.textColor = textColor
        this.backgroundColor = backgroundColor
        notifyDataSetChanged()
    }

    /** 内容（译文到达/切显示模式）变了但页数不变时的轻量刷新。 */
    fun refresh() {
        notifyItemRangeChanged(0, itemCount)
    }

    /** 只换配色（切阅读背景）时的轻量刷新：不必重排，重画即可。 */
    fun setColors(textColor: Int, backgroundColor: Int) {
        if (this.textColor == textColor && this.backgroundColor == backgroundColor) return
        this.textColor = textColor
        this.backgroundColor = backgroundColor
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val curl = CurlPageView(parent.context)
        // ViewPager2 的页面就用 RecyclerView.LayoutParams（它的 LayoutParams 未公开）
        curl.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        val page = NovelPageView(parent.context)
        curl.addView(
            page,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ),
        )
        return VH(curl, page)
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.curl.clearFold()
    }

    override fun getItemCount(): Int = content?.pages?.size ?: 0

    override fun onBindViewHolder(holder: VH, position: Int) {
        // ⚠️ 先复位动画残留（与漫画 `resetItemTransform` 同一用意）：VH 会被复用，
        // 上一个位置的页面可能正带着 alpha=0 / 位移（无动画与覆盖动画都会写这两个），
        // 不复位就会「重绑出来的新页是隐形的」。
        holder.curl.resetTransform()
        holder.itemView.tag = position
        holder.curl.setBackgroundColor(backgroundColor)
        val c = content ?: return
        val page = c.pages.getOrNull(position) ?: return
        NovelDebug.log(
            "bindPage pos=$position segs=${page.segments.size} " +
                "p0=${page.segments.firstOrNull()?.let { s -> NovelDebug.brief(c.displayOf(s.paraIndex)) }}"
        )
        holder.page.bind(c, page, style, textColor)
    }

    companion object {
        const val DEFAULT_TEXT_COLOR = 0xFF1A1A1A.toInt()
    }
}
