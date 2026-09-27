package com.moe.starflow.novel.reader

import android.content.Context
import android.graphics.Canvas
import android.text.StaticLayout
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
 *
 * ⚠️ **item 上不挂点击**：整套手势（中间格显隐 chrome / 右上角菜单 / 上下半屏翻段）由宿主
 * 挂在 RecyclerView 上的同一个手势探测器统一判定 —— 与漫画的 Webtoon 模式完全一致。
 * item 各自 `setOnClickListener` 会先吃掉事件，手势判定就永远拿不到"点在哪一格"。
 */
class NovelScrollAdapter : RecyclerView.Adapter<NovelScrollAdapter.VH>() {

    private var content: ChapterContent? = null
    private var style: NovelTextStyle = NovelPageView.DEFAULT_STYLE
    private var textColor: Int = NovelPageAdapter.DEFAULT_TEXT_COLOR
    private var backgroundColor: Int = android.graphics.Color.WHITE

    /** 选择模式里被选中的段（item 底色）。 */
    private var selected: Set<Int> = emptySet()

    /** 正在翻译 / 刚翻完的段（琥珀底色）。 */
    private var activeBatch: Set<Int> = emptySet()

    class VH(val view: ParagraphView) : RecyclerView.ViewHolder(view)

    fun submit(content: ChapterContent, style: NovelTextStyle, textColor: Int, backgroundColor: Int) {
        this.content = content
        this.style = style
        this.textColor = textColor
        this.backgroundColor = backgroundColor
        notifyDataSetChanged()
    }

    /** 译文到达/切显示模式：条数不变时用轻量刷新，避免重置滚动位置。 */
    fun refresh() {
        notifyItemRangeChanged(0, itemCount)
    }

    /** 只换配色（切阅读背景）时的轻量刷新。 */
    fun setColors(textColor: Int, backgroundColor: Int) {
        if (this.textColor == textColor && this.backgroundColor == backgroundColor) return
        this.textColor = textColor
        this.backgroundColor = backgroundColor
        notifyItemRangeChanged(0, itemCount)
    }

    /** 选择模式的选中集变化（[ParagraphView.bind] 自带「没变就 return」的闸门）。 */
    fun setSelected(sel: Set<Int>) {
        if (selected == sel) return
        selected = sel
        notifyItemRangeChanged(0, itemCount)
    }

    /** 「正在翻译」高亮变化。 */
    fun setActiveBatch(sel: Set<Int>) {
        if (activeBatch == sel) return
        activeBatch = sel
        notifyItemRangeChanged(0, itemCount)
    }

    /** item 下标 → 段号（选择模式命中用；越界返回 null）。 */
    fun paraIndexAt(position: Int): Int? =
        content?.let { NovelScrollMapping.paraIndexOf(it, position) }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = ParagraphView(parent.context)
        v.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return VH(v)
    }

    // ⚠️ 可见段走 [ChapterContent.visibleParas]（`load()` 里算一次的那份）：这是「第几个 item 是
    // 第几段」的唯一来源，与 [NovelScrollMapping.paraIndexOf] 是同一份表 —— 以前这里每次
    // 现 `filter`（`getItemCount` 被 RecyclerView 每次布局都问一遍），整本一章的书就是每次几千个元素。
    override fun getItemCount(): Int = content?.visibleParas?.size ?: 0

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = content ?: return
        val para = c.visibleParas.getOrNull(position) ?: return
        holder.view.bind(
            c.displayOf(para.index), style, textColor, backgroundColor,
            selected = para.index in selected,
            active = para.index in activeBatch,
        )
    }

    /** 一段的自绘 View。 */
    class ParagraphView(context: Context) : View(context) {

        private var text: String = ""
        private var style: NovelTextStyle = NovelPageView.DEFAULT_STYLE
        private var textColor: Int = NovelPageAdapter.DEFAULT_TEXT_COLOR
        private var backgroundColor: Int = android.graphics.Color.WHITE
        private var selected: Boolean = false
        private var active: Boolean = false

        /** 已经真正设到底色上的颜色（切选中态/切背景都要比它，别比 [backgroundColor]）。 */
        private var appliedBg: Int = backgroundColor

        /**
         * 这一段**排好版的 layout**：`onMeasure` 量高度与 `onDraw` 画的是**同一份**。
         *
         * ⚠️ 早先 `onMeasure` 调 `NovelTextRenderer.build` 量高、`onDraw` 调 `NovelTextRenderer.draw`
         * （它内部又 `build` 一次）—— 一段每次绑定排两遍；而 item 是 `WRAP_CONTENT`、bind 又会
         * `requestLayout()`，于是**每次绑定都真排两遍**（100~300 字的段约 0.3~1ms/遍）。
         * 现在按「文本 + 排版参数 + 宽度」做闸门，没动就直接复用。
         * ⚠️ 颜色**不进闸门**：它不参与测量，换色在 [NovelTextRenderer.drawLayout] 里改 paint 即可。
         */
        private var layout: StaticLayout? = null
        private var layoutText: String? = null
        private var layoutStyle: NovelTextStyle? = null

        /** 这份 layout 是按哪个正文宽度排的（宽度变了必须重排，否则行数与真机不符）。 */
        private var layoutWidth: Int = -1

        /**
         * 取本段当前的 layout（闸门没动就不重排）。
         *
         * ⚠️ 宽度必须进闸门（旋转/分屏、以及列表宽度未定时 `onMeasure` 与 `onDraw` 读到的宽度
         * 可能不同）—— 与 [NovelPageView.ensureLayouts] 的 `laidOutWidth` 是同一条约束。
         */
        private fun layoutFor(contentWidth: Int): StaticLayout? {
            val t = text
            if (t.isEmpty()) return null
            val cached = layout
            if (cached != null && layoutText == t && layoutStyle == style && layoutWidth == contentWidth) {
                return cached
            }
            return NovelTextRenderer.build(t, style, contentWidth).also {
                layout = it
                layoutText = t
                layoutStyle = style
                layoutWidth = contentWidth
            }
        }

        /** 高亮底：铺满整行 + 圆角（与分页模式同一个观感）。 */
        private val highlightBg = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = NovelTextRenderer.HIGHLIGHT_CORNER_DP * resources.displayMetrics.density
        }

        /** 平铺的普通阅读底色。 */
        private val plainBg = android.graphics.drawable.ColorDrawable(backgroundColor)

        fun bind(
            text: String,
            style: NovelTextStyle,
            textColor: Int,
            backgroundColor: Int,
            selected: Boolean = false,
            active: Boolean = false,
        ) {
            if (this.text == text && this.style == style && this.textColor == textColor &&
                this.backgroundColor == backgroundColor && this.selected == selected &&
                this.active == active
            ) {
                return
            }
            // ⚠️ 「要不要重新测量」必须在**赋值之前**判定：item 是 `WRAP_CONTENT`，
            // `requestLayout()` 必然带回一遍 `onMeasure`（那里要拿 layout 的高度）。
            val remeasure = this.text != text || this.style != style
            this.text = text
            this.style = style
            this.textColor = textColor
            this.backgroundColor = backgroundColor
            this.selected = selected
            this.active = active
            // 底色优先级：**选中 > 正在翻译 > 阅读背景**。
            // 「正在翻译」用半透明琥珀：深浅两种背景下都看得见，又不盖住正文
            val want = when {
                selected -> NovelTextRenderer.COLOR_SELECTION
                active -> NovelTextRenderer.COLOR_ACTIVE_BATCH
                else -> backgroundColor
            }
            // ⚠️ 底色变了才动 background：setBackground/setBackgroundColor 内部都会 requestLayout，
            // 而 bind 是在布局过程中被调的 —— 每次都设会触发"布局中再次请求布局"的第二遍布局
            if (appliedBg != want) {
                appliedBg = want
                if (want == backgroundColor) {
                    plainBg.color = want
                    background = plainBg
                } else {
                    // 高亮：圆角 + 铺满整行（item 本身是 MATCH_PARENT，边距也被填上）
                    highlightBg.setColor(want)
                    background = highlightBg
                }
            }
            // ⚠️ **只有影响测量的输入**（文本 / 排版参数）变了才 requestLayout：底色、选中态、
            // 翻译高亮、文字色都只是绘制输入，条目几何一点没变 —— invalidate 就够。
            // 以前这里无条件 requestLayout：选择模式每点一下、切一次阅读背景，都要把所有可见项
            // 重新量一遍（每项 = 一次 StaticLayout 量高）。底色那条路径由 setBackground 自己收尾
            // （它会 invalidate，**首次**挂上时还会 requestLayout —— 见上一条注释的闸门）。
            if (remeasure) requestLayout()
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val contentWidth = style.contentWidthPx(w)
            // ⚠️ 条目高度 = 正文高度 + **段间距**。以前这里还加了 `2 × style.paddingPx` ——
            // 那是**左右**边距（16~64dp），被当成上下留白来用，于是滚动模式下段间距被撑得极大、
            // 拖「段间距」滑块几乎看不出变化（用户报的「不受段落面板控制」就是这个）。
            // 首项顶部 / 末项底部的留白由列表自己的 paddingTop/paddingBottom 负责。
            // ⚠️ 量高用的就是 [layoutFor] 那份 layout —— onDraw 复用它，一段只排一次。
            val h = if (text.isEmpty()) 0
            else (layoutFor(contentWidth)?.height ?: 0) + style.paragraphSpacingPx.toInt()
            setMeasuredDimension(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // ⚠️ 这里**不再重排**：拿 onMeasure 已经排好的那一份（闸门保证命中），
            // 文字色只改 paint（见 [NovelTextRenderer.drawLayout]）
            val lay = layoutFor(style.contentWidthPx(width)) ?: return
            NovelTextRenderer.drawLayout(
                canvas = canvas,
                layout = lay,
                x = style.paddingPx,
                y = 0f,
                color = textColor,
            )
        }
    }
}
