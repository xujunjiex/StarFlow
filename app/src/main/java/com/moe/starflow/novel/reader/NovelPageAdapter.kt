package com.moe.starflow.novel.reader

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.mangaimport.reader.CurlPageView

/**
 * 分页模式：一页一个 item。
 *
 * ### 关于 `notifyDataSetChanged` 的一个纠正（实测过，别再照旧注释改）
 * 这里原本写着「`notifyDataSetChanged` 是 structure-changed 事件，ViewPager2 会重建全部页面
 * 并把当前页弹回第一页」。**实测不成立**：ViewPager2 的 `DataSetChangeObserver` 把
 * `onChanged` / `onItemRangeChanged/Inserted/Removed/Moved` 全部当成同一件事，
 * `notifyItemRangeChanged` 与 `notifyDataSetChanged` 在它眼里完全等价；页数不变时两者都
 * **保持当前页**。真正的坑只有一条：**页数缩到当前页号以下**（重排后页变少）时，
 * LinearLayoutManager 找不到锚点项 → 位置重置到第 0 页。所以刷新页表后**必须紧跟一次
 * `setCurrentItem`**（`loadChapter` 就是这么做的，另有 `snapPagerToAnchor` 兜底）。
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

    /** 选择模式里被选中的段（高亮底）。 */
    private var selected: Set<Int> = emptySet()

    /** 正在翻译 / 刚翻完的段（琥珀高亮底）。 */
    private var activeBatch: Set<Int> = emptySet()

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

    /** 选择模式的选中集变化（只重画，不重排 —— 见 [NovelPageView.bind] 的排版输入闸门）。 */
    fun setSelected(sel: Set<Int>) {
        if (selected == sel) return
        selected = sel
        notifyItemRangeChanged(0, itemCount)
    }

    /** 「正在翻译」高亮变化（只重画）。 */
    fun setActiveBatch(sel: Set<Int>) {
        if (activeBatch == sel) return
        activeBatch = sel
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
        // 用 setPageBackground 而不是 setBackgroundColor：折页时底色必须跟着裁剪一起动
        holder.curl.setPageBackground(backgroundColor)
        val c = content ?: return
        val page = c.pages.getOrNull(position) ?: return
        NovelDebug.log(
            "bindPage pos=$position segs=${page.segments.size} " +
                "p0=${page.segments.firstOrNull()?.let { s -> NovelDebug.brief(c.displayOf(s.paraIndex)) }}"
        )
        holder.page.bind(c, page, style, textColor, selected, activeBatch)
    }

    companion object {
        const val DEFAULT_TEXT_COLOR = 0xFF1A1A1A.toInt()
    }
}
