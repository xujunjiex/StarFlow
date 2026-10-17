package com.moe.starflow.novel.reader

import android.content.Context
import android.graphics.Canvas
import android.text.StaticLayout
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.R
import com.moe.starflow.novel.translate.NovelImageTranslation
import com.moe.starflow.novel.translate.NovelImageVisual

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
    private var statusColors = NovelStatusColors()

    fun setStatusColors(colors: NovelStatusColors) {
        if (statusColors == colors) return
        statusColors = colors
        notifyItemRangeChanged(0, itemCount)
    }

    /** 正在翻译 / 刚翻完的段（琥珀底色）。 */
    private var activeBatch: Set<Int> = emptySet()

    /** 「翻失败了」的段（淡红底）。 */
    private var failedBatch: Set<Int> = emptySet()

    /** **上一次刚翻完**的段（淡绿底；见 [NovelTextRenderer.COLOR_LAST_TRANSLATED]）。 */
    private var recentBatch: Set<Int> = emptySet()

    /** 每张插图的显示态（`段号 -> 三态`）；表里没有 = 译文态。 */
    private var imageVisuals: Map<Int, NovelImageVisual> = emptyMap()

    /** 当前聚焦的那张插图（只有它画右下角那排按钮）。 */
    private var focusedImage: Int? = null

    /** 每张插图的译文载荷（宿主灌进来；见 `NovelPageView.setImageState` 的说明）。 */
    private var imageTranslations: Map<Int, NovelImageTranslation> = emptyMap()

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

    /** 「翻失败了」高亮变化。 */
    fun setFailedBatch(sel: Set<Int>) {
        if (failedBatch == sel) return
        failedBatch = sel
        notifyItemRangeChanged(0, itemCount)
    }

    /** 「上一次刚翻完」的高亮变化（只重画）。 */
    fun setRecentBatch(sel: Set<Int>) {
        if (recentBatch == sel) return
        recentBatch = sel
        notifyItemRangeChanged(0, itemCount)
    }

    /** 插图显示态 + 聚焦态变化（只重画：插图高度与显示态无关）。 */
    fun setImageState(
        visuals: Map<Int, NovelImageVisual>,
        focused: Int?,
        translations: Map<Int, NovelImageTranslation> = emptyMap(),
    ) {
        if (imageVisuals == visuals && focusedImage == focused && imageTranslations == translations) return
        imageVisuals = visuals
        focusedImage = focused
        imageTranslations = translations
        notifyItemRangeChanged(0, itemCount)
    }

    /**
     * 插图按钮条的命中测试（滚动模式：列表里那一条）。
     *
     * @param x/[y] 相对**条目根视图**的坐标（调用方减掉 `child.left/top`）
     */
    fun imageControlAt(holder: ParagraphView, x: Float, y: Float): NovelImageButton? =
        holder.imageControlAt(x, y)

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
            fontSizeSpans = c.displayFontSizeSpans[para.index].orEmpty(),
            image = c.images[para.index],
            payload = imageTranslations[para.index],
            visual = imageVisuals[para.index] ?: NovelImageVisual.TRANSLATED,
            focused = focusedImage == para.index,
            selected = para.index in selected,
            active = para.index in activeBatch,
            failed = para.index in failedBatch,
            recent = para.index in recentBatch,
            statusColors = statusColors,
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
        private var failed: Boolean = false
        private var recent: Boolean = false
        private var statusColors = NovelStatusColors()

        /**
         * 这一段的插图（有图就**画图不画那串 `📷 [图片]` 占位文本**）。
         *
         * ⚠️ 滚动模式**不限高**（[NovelImageMetrics.fit] 传 `0f`）：这里没有"页"，
         * 一张整页插图就该按正文列宽铺开、有多高滚多高 —— 与分页模式不得不把它压进一页是两回事。
         */
        private var image: NovelImage? = null

        /** 这一段的图片译文载荷（null = 这张图还没翻过，只画原图）。 */
        private var payload: NovelImageTranslation? = null

        /** 这一段的显示态（译文 / 识别原文 / 原图）。 */
        private var visual: NovelImageVisual = NovelImageVisual.TRANSLATED

        /** 这一段是不是**聚焦**的那张图（只有它画右下角那排按钮）。 */
        private var focused: Boolean = false

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
        private var fontSizeSpans: List<com.moe.starflow.novel.parser.NovelFontSizeSpan> = emptyList()
        private var layoutFontSizeSpans: List<com.moe.starflow.novel.parser.NovelFontSizeSpan> = emptyList()

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
            if (cached != null && layoutText == t && layoutStyle == style &&
                layoutFontSizeSpans == fontSizeSpans && layoutWidth == contentWidth
            ) {
                return cached
            }
            return NovelTextRenderer.build(t, style, contentWidth, fontSizeSpans = fontSizeSpans).also {
                layout = it
                layoutText = t
                layoutStyle = style
                layoutFontSizeSpans = fontSizeSpans
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
            fontSizeSpans: List<com.moe.starflow.novel.parser.NovelFontSizeSpan> = emptyList(),
            image: NovelImage? = null,
            payload: NovelImageTranslation? = null,
            visual: NovelImageVisual = NovelImageVisual.TRANSLATED,
            focused: Boolean = false,
            selected: Boolean = false,
            active: Boolean = false,
            failed: Boolean = false,
            recent: Boolean = false,
            statusColors: NovelStatusColors = NovelStatusColors(),
        ) {
            if (this.text == text && this.style == style && this.textColor == textColor &&
                this.fontSizeSpans == fontSizeSpans &&
                this.backgroundColor == backgroundColor && this.image == image &&
                this.payload == payload && this.visual == visual && this.focused == focused &&
                this.selected == selected && this.active == active && this.failed == failed &&
                this.recent == recent && this.statusColors == statusColors
            ) {
                return
            }
            // ⚠️ 「要不要重新测量」必须在**赋值之前**判定：item 是 `WRAP_CONTENT`，
            // `requestLayout()` 必然带回一遍 `onMeasure`（那里要拿 layout / 图片的高度）。
            // ⚠️ 三态 / 聚焦**不进**这里：它们只换画哪张位图，条目高度一点没变（插图高度按图片自身尺寸算）
            val remeasure = this.text != text || this.style != style || this.fontSizeSpans != fontSizeSpans || this.image != image
            this.text = text
            this.style = style
            this.fontSizeSpans = fontSizeSpans
            this.textColor = textColor
            this.backgroundColor = backgroundColor
            this.image = image
            this.payload = payload
            this.visual = visual
            this.focused = focused
            this.selected = selected
            this.active = active
            this.failed = failed
            this.recent = recent
            this.statusColors = statusColors
            // 底色优先级：**选中 > 失败 > 正在翻译 > 刚翻完 > 阅读背景**。
            // 「正在翻译」用半透明琥珀、「失败」用半透明淡红、「刚翻完」用半透明淡绿：
            // 深浅两种背景下都看得见，又不盖住正文
            val want = when {
                selected -> statusColors.color(NovelHighlight.SELECTION)
                failed -> statusColors.color(NovelHighlight.FAILED)
                active -> statusColors.color(NovelHighlight.ACTIVE)
                recent -> statusColors.color(NovelHighlight.COMPLETED)
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
            // 插图段：高度由图片本身决定（与分页模式同一个公式，只是这里**不限高**）。
            // 量不出来（尺寸异常）就落回文本路径，显示占位文本 —— 不能量成 0 高把这一段吞掉
            image?.let { img ->
                NovelImageMetrics.fit(img, contentWidth, 0f)?.let { size ->
                    setMeasuredDimension(w, (size.height + style.paragraphSpacingPx).toInt())
                    return
                }
            }
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
            val contentWidth = style.contentWidthPx(width)
            // 插图段：整段就是那张图（左右居中；高度与 onMeasure 记的是同一个数）
            image?.let { img ->
                val size = NovelImageMetrics.fit(img, contentWidth, 0f)
                if (size != null) {
                    val left = style.paddingPx + (contentWidth - size.width) / 2f
                    val density = resources.displayMetrics.density
                    // 选中效果的**填充**画在图片下面（图片会盖掉中间，只在边缘留一圈底色）
                    val selection = NovelImageControls.selectionRect(left, 0f, left + size.width, size.height, density)
                    if (focused) NovelImageControls.drawSelectionFill(canvas, selection, density, statusColors.selectionFill)
                    // 有译文载荷 → 画覆盖层（译文 / 识别原文）；纯图态或没载荷 → 画原图
                    val overlay = payload?.let {
                        NovelImageOverlay.of(context, img, it, visual, size.width.toInt())
                    }
                    val drawn = if (overlay != null) {
                        NovelImageStore.drawBitmap(canvas, overlay, left, 0f, size)
                        true
                    } else {
                        NovelImageStore.draw(canvas, img, left, 0f, size)
                    }
                    if (drawn) {
                        // 边框都画在图片**之后**（在图片矩形外侧，不遮挡图内容）。
                        // 优先级与文本段底色同一套：选中(蓝) > 失败(红) > 正在翻译(琥珀) ——
                        // 「这几张图正在翻」要有可见的黄框（用户口径：三张图都要显示黄色状态）
                        val frame = when {
                            focused -> statusColors.border(NovelHighlight.SELECTION) to NovelImageControls.SELECTION_BORDER_DP
                            failed -> statusColors.border(NovelHighlight.FAILED) to 3f
                            active -> statusColors.border(NovelHighlight.ACTIVE) to 3f
                            recent -> statusColors.border(NovelHighlight.COMPLETED) to 3f
                            else -> null
                        }
                        if (frame != null) {
                            // ⚠️ 滚动模式的**条目边界就是图片边界**（高度 = 图高 + 段间距），
                            // 边框画到图片外侧会被条目的裁剪吃掉上边 —— 这里改成**画在内侧**
                            // （描边宽度的 1/2 内缩，视觉上仍是一圈框，但一定看得见）。
                            val inset = frame.second * density / 2f
                            val inside = NovelImageControls.Box(
                                selection.left + inset + 3f * density,
                                selection.top + inset + 3f * density,
                                selection.right - inset - 3f * density,
                                selection.bottom - inset - 3f * density,
                            )
                            NovelImageControls.drawFrame(canvas, inside, frame.first, frame.second, density)
                        }
                        if (focused) {
                            val items = imageControls()
                            NovelImageControls.drawBackdrop(canvas, items, density)
                            for (item in items) {
                                NovelImageControls.drawIcon(canvas, item, imageIconOf(item.button), density)
                            }
                        }
                        return
                    }
                    // 画不出来（文件没了/解码失败）→ 落回下面的占位文本，别留一片空白
                }
            }
            // ⚠️ 这里**不再重排**：拿 onMeasure 已经排好的那一份（闸门保证命中），
            // 文字色只改 paint（见 [NovelTextRenderer.drawLayout]）
            val lay = layoutFor(contentWidth) ?: return
            NovelTextRenderer.drawLayout(
                canvas = canvas,
                layout = lay,
                x = style.paddingPx,
                y = 0f,
                color = textColor,
            )
        }

        /**
         * 编辑模式里那张图的**按钮条几何**（画与命中都走它）。
         *
         * ⚠️ 滚动模式的条目**高度就是图片高度**（没有额外留白），所以"放到图片下方"没有空间，
         * 于是把**正文框下方的段间距**也算进可摆放区域（[style.paragraphSpacingPx]）——
         * 否则极小的图（占位小图）永远只能把按钮缩到看不清。真正的边界由列表自己的
         * clipping 兜住，这里只是给 `layout` 一个合理的摆放范围。
         */
        private fun imageControls(): List<NovelImageControls.Item> {
            val img = image ?: return emptyList()
            val contentWidth = style.contentWidthPx(width)
            val size = NovelImageMetrics.fit(img, contentWidth, 0f) ?: return emptyList()
            val left = style.paddingPx + (contentWidth - size.width) / 2f
            return NovelImageControls.layout(
                imageLeft = left,
                imageTop = 0f,
                imageRight = left + size.width,
                imageBottom = size.height,
                density = resources.displayMetrics.density,
                buttons = NovelImageControls.buttonsFor(hasTranslation = payload != null),
                boundsLeft = style.paddingPx,
                boundsTop = 0f,
                boundsRight = style.paddingPx + contentWidth,
                boundsBottom = height.toFloat().coerceAtLeast(size.height),
            )
        }

        /**
         * 插图按钮条的命中测试（坐标相对本条目）。
         *
         * 走法与 [onDraw] 同一份几何（同一个 [imageControls]），
         * 所以"点到的按钮"与"看到的按钮"必然一致。
         */
        fun imageControlAt(x: Float, y: Float): NovelImageButton? {
            if (!focused) return null
            return NovelImageControls.hit(imageControls(), x, y)
        }

        /** 按钮图标（白色 tint：按钮底是深色药丸）。 */
        private fun imageIconOf(button: NovelImageButton): android.graphics.drawable.Drawable? {
            val res = when (button) {
                // 翻译 / 重翻是同一个动作的两个时机：还没翻过用「翻译」图标，翻过了用「刷新」
                NovelImageButton.TRANSLATE -> if (payload != null) {
                    R.drawable.ic_refresh
                } else {
                    R.drawable.ic_reader_translate
                }
                NovelImageButton.TOGGLE -> when (visual) {
                    NovelImageVisual.TRANSLATED -> R.drawable.ic_display_translated
                    NovelImageVisual.ORIGINAL -> R.drawable.ic_display_original
                    NovelImageVisual.PLAIN -> R.drawable.ic_display_plain
                }
                NovelImageButton.DELETE -> R.drawable.ic_action_delete
            }
            return context.getDrawable(res)?.mutate()?.apply {
                setTint(android.graphics.Color.WHITE)
            }
        }
    }
}
