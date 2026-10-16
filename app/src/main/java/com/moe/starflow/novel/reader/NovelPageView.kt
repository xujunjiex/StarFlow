package com.moe.starflow.novel.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.StaticLayout
import android.view.View
import com.moe.starflow.R
import com.moe.starflow.novel.translate.NovelImageTranslation
import com.moe.starflow.novel.translate.NovelImageVisual
import com.moe.starflow.utils.LogCollector

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

    /** 「这一批翻失败了」的段（淡红高亮底；见 [NovelTextRenderer.COLOR_FAILED_BATCH]）。 */
    private var failedBatch: Set<Int> = emptySet()

    /**
     * **上一次刚翻完**的段（淡绿高亮底；见 [NovelTextRenderer.COLOR_LAST_TRANSLATED]）。
     *
     * 与 [activeBatch] 是前后两个时刻：琥珀是"正在飞"，这个是"刚落定"。
     */
    private var recentBatch: Set<Int> = emptySet()
    private var statusColors = NovelStatusColors()

    fun setStatusColors(colors: NovelStatusColors) {
        statusColors = colors
        selectionPaint.color = colors.color(NovelHighlight.SELECTION)
        activeBatchPaint.color = colors.color(NovelHighlight.ACTIVE)
        failedBatchPaint.color = colors.color(NovelHighlight.FAILED)
        recentBatchPaint.color = colors.color(NovelHighlight.COMPLETED)
        invalidate()
    }

    /** 每张插图的显示态（`段号 -> 三态`）；表里没有 = 译文态。 */
    private var imageVisuals: Map<Int, NovelImageVisual> = emptyMap()

    /**
     * 每张插图的译文载荷（`段号 -> 载荷`）。
     *
     * ⚠️ 由宿主经 [setImageState] 灌进来，**不从 `content` 读**：长按翻译一张图之后
     * 宿主手里的载荷比适配器那份 `content` 更新（`submit` 会把阅读位置一起掀掉，
     * 不值得为一张图重提整章内容）。
     */
    private var imageTranslations: Map<Int, NovelImageTranslation> = emptyMap()

    /** 当前聚焦的那张插图（只有它画右下角那排按钮）。 */
    private var focusedImage: Int? = null

    /**
     * 与 `page.segments` 对应的排版结果，**按段号索引**（空文本/未布局的段没有条目）。
     *
     * ⚠️ 键是段号而不是 segment 下标：一页内段号不重复（见 [PageSegment]），而排版只由
     * 「段号 → 显示文本」决定 —— 用段号当键，[NovelPageGeometry] 的行高表就是同一套键。
     */
    private var layouts: Map<Int, StaticLayout> = emptyMap()

    /**
     * 段号 → 这批 [layouts] 是**按哪个显示文本**排的。
     *
     * ⚠️ 有它才能**逐段复用**：译文到达时只有这一批翻到的那几段文本变了，同页其余段的 layout
     * 原样可用（重排一段 = 主线程 draw 里一个 StaticLayout，一屏十几段）。
     */
    private var layoutTexts: Map<Int, String> = emptyMap()

    /** 段号 → 该段的逐行高度 `getLineBottom(i) - getLineTop(i)`（与分页端量的是同一批数）。 */
    private var lineHeights: Map<Int, FloatArray> = emptyMap()

    /** 这批 [layouts] 是按哪个宽度排的 —— 宽度变了必须重排（旋转/分屏）。 */
    private var laidOutWidth = -1

    /**
     * 排版缓存是否已过期（内容/页码/字号变了）。
     *
     * ⚠️ 只换高亮、只换文字色的重绑**不置这个位**：那样 [ensureLayouts] 会在开头直接早退，
     * 整页一个 StaticLayout 都不排。早先这三样都在「作废缓存」的闸门里，于是选择模式每点一下、
     * 切一次阅读背景，都要在主线程 draw 里把整页重排一遍。
     */
    private var layoutsDirty = true

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

    /** 「这一批翻失败了」的高亮底（淡红，与滚动模式共用）。 */
    private val failedBatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NovelTextRenderer.COLOR_FAILED_BATCH
    }

    /** 「上一次刚翻完」的高亮底（淡绿，与滚动模式共用）。 */
    private val recentBatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NovelTextRenderer.COLOR_LAST_TRANSLATED
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
        failedBatch: Set<Int> = emptySet(),
        recentBatch: Set<Int> = emptySet(),
    ) {
        // ⚠️ 排版输入没变就别作废已排好的 layout：选择模式每点一下都会重绑，
        // 全页重建 StaticLayout 是白烧的（而且会闪）
        // ⚠️ 文字色**不进**这个闸门：颜色不参与排版，换色只改 layout 的 paint（见 [applyTextColor]）。
        // 以前它在闸门里 —— 切一次阅读背景就要在主线程 draw 里把整页重排一遍。
        if (this.content !== content || this.page !== page || this.style != style) {
            layoutsDirty = true
            // ⚠️ 字号/行距/边距变了 → 连**复用的底稿**（文本比对表）一起作废：
            // 文本一样但排版参数不同的 layout 不能复用
            if (this.style != style) {
                layouts = emptyMap()
                layoutTexts = emptyMap()
                lineHeights = emptyMap()
            }
            overflowReported = false
        }
        this.content = content
        this.page = page
        this.style = style
        this.textColor = textColor
        this.selected = selected
        this.activeBatch = activeBatch
        this.failedBatch = failedBatch
        this.recentBatch = recentBatch
        applyTextColor()
        invalidate()
    }

    /**
     * 插图的显示态 + 聚焦态 + 译文载荷（长按选中那张 / 宿主刚翻完一张）。
     *
     * ⚠️ **不置 [layoutsDirty]**：三态只换"画哪张位图"，插图占位高度由
     * [NovelImageMetrics] 按图片自身尺寸算，与显示态、与有没有译文都无关 —— 重排是白花的。
     */
    fun setImageState(
        visuals: Map<Int, NovelImageVisual>,
        focused: Int?,
        translations: Map<Int, NovelImageTranslation> = emptyMap(),
    ) {
        if (imageVisuals == visuals && focusedImage == focused && imageTranslations == translations) return
        imageVisuals = visuals
        focusedImage = focused
        imageTranslations = translations
        invalidate()
    }

    /** 某张图当前的显示态（表里没有 = 译文态）。 */
    private fun visualOf(paraIndex: Int): NovelImageVisual =
        imageVisuals[paraIndex] ?: NovelImageVisual.TRANSLATED

    /**
     * 插图按钮条的命中测试（**只认正在编辑的那张图**）。
     *
     * 走法与 [onDraw] 同一条（[NovelPageGeometry.walk] + 同一份 `fit` + 同一个
     * [controlsOf]），所以"手指点到的按钮"与"屏幕上画的那个按钮"按定义一致。
     */
    fun imageControlAt(x: Float, y: Float): NovelImageButton? {
        val controls = controlsOf() ?: return null
        return NovelImageControls.hit(controls.items, x, y)
    }

    /**
     * **正在编辑的那张图**的几何（没在编辑 / 那张图不在本页 → null）。
     *
     * ⚠️ 画与命中都必须走它：两处各算一遍的话，改一下按钮个数/尺寸就会出现
     * 「画了三颗、只认一颗」这类只能靠肉眼发现的错位。
     *
     * @param items 按钮条；[selection] 是选中框（图片矩形向外扩一点）
     */
    internal class ImageControls(
        val items: List<NovelImageControls.Item>,
        val selection: NovelImageControls.Box,
    )

    internal fun controlsOf(): ImageControls? {
        val p = page ?: return null
        val c = content ?: return null
        val focus = focusedImage ?: return null
        ensureLayouts()
        val cw = style.contentWidthPx(width)
        val boundsLeft = style.paddingPx
        val boundsTop = style.topPaddingPx
        val boundsRight = style.paddingPx + cw
        val boundsBottom = (height - style.bottomPaddingPx).coerceAtLeast(style.topPaddingPx + 1f)
        val density = resources.displayMetrics.density
        var out: ImageControls? = null
        NovelPageGeometry.walk(p.segments, { lineHeights[it] }, style.paragraphSpacingPx, style.topPaddingPx) { seg, top, bottom ->
            if (out != null || seg.paraIndex != focus) return@walk
            val image = c.images[seg.paraIndex] ?: return@walk
            val size = NovelImageMetrics.fit(image, cw, style.contentHeightPx(height)) ?: return@walk
            val left = style.paddingPx + (cw - size.width) / 2f
            out = ImageControls(
                items = NovelImageControls.layout(
                    imageLeft = left,
                    imageTop = top,
                    imageRight = left + size.width,
                    imageBottom = bottom,
                    density = density,
                    buttons = NovelImageControls.buttonsFor(
                        hasTranslation = imageTranslations.containsKey(seg.paraIndex),
                    ),
                    // ⚠️ 图太小的时候按钮要能落到图外 —— 但**必须留在正文框内**（否则会画到屏幕外/压住 UI）
                    boundsLeft = boundsLeft,
                    boundsTop = boundsTop,
                    boundsRight = boundsRight,
                    boundsBottom = boundsBottom,
                ),
                selection = NovelImageControls.selectionRect(left, top, left + size.width, bottom, density),
            )
        }
        return out
    }

    /**
     * 把当前文字色灌进**已经排好**的 layout。
     *
     * ⚠️ 颜色不参与排版（见 [NovelTextRenderer.build]），所以换色只改 paint、不重排：
     * StaticLayout 画的时候用的就是构建时传进去的那个 `TextPaint` 实例（`Layout.getPaint()`），
     * 改它的 color 只影响画出来的颜色，几何一点不动。
     */
    private fun applyTextColor() {
        for (lay in layouts.values) lay.paint.color = textColor
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
     *
     * ⚠️ **逐段复用**：段号相同 + 显示文本相同 + 宽度没变 → 上一批的 layout 原样搬过来。
     * 译文到达时只有这一批翻到的那几段文本变了，同页其余段没有理由重排；只换高亮/换色的重绑
     * 更是一个都不该排（[layoutsDirty] 为 false 时这里直接早退）。
     *
     * ⚠️ **插图段只有"一行"高度、没有 layout**（与 [NovelPaginator] 同一套记账）：
     * 高度走 [NovelImageMetrics] 同一份公式。有图的段不再排那串 `📷 [图片]` 占位文本。
     */
    private fun ensureLayouts() {
        val w = width
        if (w <= 0) return
        val c = content ?: return
        val p = page ?: return
        // 宽度没变、内容也没换过 → 什么都不用做
        if (!layoutsDirty && w == laidOutWidth) return
        // ⚠️ 宽度变了 → 下面按文本比对复用**一律不成立**（同一段在不同宽度下断行不同）
        val sameWidth = w == laidOutWidth
        laidOutWidth = w
        val cw = style.contentWidthPx(w)
        val maxImgH = style.contentHeightPx(height)
        val newLayouts = HashMap<Int, StaticLayout>(p.segments.size)
        val newHeights = HashMap<Int, FloatArray>(p.segments.size)
        val newTexts = HashMap<Int, String>(p.segments.size)
        for (seg in p.segments) {
            // 插图段：高度由图片尺寸定。图片尺寸已知（加载时量好），所以这里**不解码**
            val imgSize = c.images[seg.paraIndex]?.let { NovelImageMetrics.fit(it, cw, maxImgH) }
            if (imgSize != null) {
                newHeights[seg.paraIndex] = floatArrayOf(imgSize.height)
                continue
            }
            val text = c.displayOf(seg.paraIndex)
            if (text.isEmpty()) continue
            val reused = if (sameWidth && layoutTexts[seg.paraIndex] == text) {
                layouts[seg.paraIndex]
            } else {
                null
            }
            val layout = reused ?: NovelTextRenderer.build(text, style, cw, textColor)
            newLayouts[seg.paraIndex] = layout
            newTexts[seg.paraIndex] = text
            // 行高表只跟这份 layout 有关，复用时连数组一起复用（不必再遍历一遍行）
            val heights = if (reused != null) lineHeights[seg.paraIndex] else null
            newHeights[seg.paraIndex] = heights ?: FloatArray(layout.lineCount) {
                (layout.getLineBottom(it) - layout.getLineTop(it)).toFloat()
            }
        }
        layouts = newLayouts
        layoutTexts = newTexts
        lineHeights = newHeights
        layoutsDirty = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = content ?: return
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
            usedBottom = bottom

            // 高亮底画在文字/图片**下面**（同一个矩形范围，逐段累加的高度）。
            // 优先级：选中 > 失败 > 正在翻译 > 刚翻完 —— 同时命中时得看得出最要紧的那个
            //（"还在飞"必须压过"刚翻完"，否则读者会以为已经完事了）
            val hl = when {
                seg.paraIndex in selected -> selectionPaint
                seg.paraIndex in failedBatch -> failedBatchPaint
                seg.paraIndex in activeBatch -> activeBatchPaint
                seg.paraIndex in recentBatch -> recentBatchPaint
                else -> null
            }
            if (hl != null) {
                // ⚠️ **铺满整行**（左右边距也填上）+ 圆角（用户要求）：只铺正文列的话
                // 高亮像被两侧裁了一刀，和滚动模式的 item 底色也不是一个样子
                canvas.drawRoundRect(0f, top, widthF, bottom, hlRadiusPx, hlRadiusPx, hl)
            }

            val layout = layouts[seg.paraIndex]
            if (layout == null) {
                // 插图段：整段就是那张图（高度已经由 [NovelImageMetrics] 定好，与分页同一个数）
                drawImageSegment(canvas, c, seg.paraIndex, top, bottom)
                return@walk
            }

            // 行区间由共用件判定（没有度量的段根本不会被回调），画与记账用的是同一个区间
            val range = NovelPageGeometry.lineRange(seg, lineHeights[seg.paraIndex]) ?: return@walk

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
     * 画一张插图：正文列内**居中**，高度就是分页给它记的那一份（[bottom] - [top]）。
     *
     * 有译文载荷时画**覆盖层**（译文/识别原文，见 [NovelImageOverlay]），没有就画原图；
     * 三态里的"纯图"也走原图这条路。聚焦的那张再叠一排按钮。
     *
     * ⚠️ 尺寸再算一次（而不是直接用 [bottom] - [top] 反推宽度）是为了**等比**：由高度反推宽度
     * 在四舍五入下会和 [NovelImageMetrics] 差半个像素，宽高比偶尔就被拉歪一点点。
     * 画不出来（文件被删 / 解码失败）就什么都不画 —— 页面高度已经记过了，不能在这里改。
     */
    private fun drawImageSegment(canvas: Canvas, c: ChapterContent, paraIndex: Int, top: Float, bottom: Float) {
        val image = c.images[paraIndex] ?: return
        val cw = style.contentWidthPx(width)
        // ⚠️ **绝不裁图**：可用高度取「本段记的高度」与「一页正文高度」的**较小值**再夹一次 ——
        // 分页与绘制虽然共用 [NovelImageMetrics]，但换排版参数/换屏尺寸的瞬间两者可能不同源，
        // 那时按分页记的高度画就会顶出这条带子（用户口径：「绝不允许图片内容被切割遮挡」）。
        val bandHeight = (bottom - top).coerceAtLeast(1f)
        val size = NovelImageMetrics.fit(
            image, cw, minOf(bandHeight, style.contentHeightPx(height)),
        ) ?: return
        val left = style.paddingPx + (cw - size.width) / 2f
        val imageRight = left + size.width
        val density = resources.displayMetrics.density
        val focused = paraIndex == focusedImage
        val selection = NovelImageControls.selectionRect(left, top, imageRight, top + size.height, density)
        // 额外裁一道该段自己的高度：图片永远是"画在本段给它记的那条带子里"，
        // 万一哪天公式改了导致尺寸对不上，也只会少画几个像素，不会盖住下一段
        canvas.save()
        canvas.clipRect(0f, top, width.toFloat(), bottom)
        val visual = visualOf(paraIndex)
        val payload = imageTranslations[paraIndex]
        // **编辑模式**：填充画在图片下面（图片会把中间盖掉，只在边缘留一圈底色）
        if (focused) NovelImageControls.drawSelectionFill(canvas, selection, density, statusColors.selectionFill)
        val overlay = if (payload != null) {
            NovelImageOverlay.of(context, image, payload, visual, size.width.toInt())
        } else {
            null
        }
        if (overlay != null) {
            NovelImageStore.drawBitmap(canvas, overlay, left, top, size)
        } else {
            NovelImageStore.draw(canvas, image, left, top, size)
        }
        canvas.restore()
        // ⚠️ 边框都画在 `clipRect` **外面**：它们本来就在图片矩形外侧（外扩 3dp），
        // 画在裁剪里会被切掉上/下两条边。越界由 [NovelImageControls.layout] 的 bounds 夹取兜住。
        //
        // 边框优先级与文本段底色**同一套**（选中 > 失败 > 正在翻译 > 刚翻完）：
        // 「这几张图正在翻」必须像文本段一样有**可见的黄框**（用户口径 2026-10-04：
        // 「这三张图片都要显示黄色状态」）—— 只高亮文本段的话，一整批都是图时屏幕上毫无反馈。
        // 「刚翻完」给绿框（图片没有"底色"可换，框是它唯一的状态载体）。
        val frame = when {
            focused -> statusColors.border(NovelHighlight.SELECTION) to NovelImageControls.SELECTION_BORDER_DP
            paraIndex in failedBatch -> statusColors.border(NovelHighlight.FAILED) to 3f
            paraIndex in activeBatch -> statusColors.border(NovelHighlight.ACTIVE) to 3f
            paraIndex in recentBatch -> statusColors.border(NovelHighlight.COMPLETED) to 3f
            else -> null
        }
        if (frame != null) {
            NovelImageControls.drawFrame(canvas, selection, frame.first, frame.second, density)
        }
        if (focused) {
            val items = controlsOf()?.items.orEmpty()
            NovelImageControls.drawBackdrop(canvas, items, density)
            for (item in items) {
                NovelImageControls.drawIcon(canvas, item, imageIconOf(item.button, visual, paraIndex), density)
            }
        }
    }

    /** 按钮图标（矢量图，白色 tint：按钮底是深色药丸）。 */
    private fun imageIconOf(
        button: NovelImageButton,
        visual: NovelImageVisual,
        paraIndex: Int,
    ): android.graphics.drawable.Drawable? {
        val res = when (button) {
            // 翻译 / 重翻是同一个动作的两个时机：还没翻过用「翻译」图标，翻过了用「刷新」
            NovelImageButton.TRANSLATE -> if (imageTranslations.containsKey(paraIndex)) {
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

    /**
     * 正文画完的总高度越过正文框下沿时**报一行**。
     *
     * 这是「分页账目与绘制是否真的一致」的唯一现场证据：不报 = 底部的字没有被裁。
     * 静态断言做不到这一点（Robolectric 的文本引擎是桩，量不出真机行高）。
     *
     * ⚠️ 走 [LogCollector]（app 内日志查看器可见）：这条是排障用的真凭据，得留在正式包里，
     * 但它**不含任何正文**（只有 px 数与段数），且 [overflowReported] 保证一页只报一次。
     */
    private fun reportOverflow(usedBottom: Float, boxBottom: Float) {
        if (overflowReported || usedBottom <= boxBottom + 0.5f) return
        overflowReported = true
        LogCollector.w(
            TAG,
            "PAGE_OVERFLOW 正文顶出框 ${"%.1f".format(usedBottom - boxBottom)} px " +
                "used=$usedBottom box=$boxBottom h=$height top=${style.topPaddingPx} " +
                "bot=${style.bottomPaddingPx} segs=${page?.segments?.size}",
        )
    }

    companion object {
        private const val TAG = "NovelPageView"

        val DEFAULT_STYLE = NovelTextStyle(fontSizePx = 42f, lineSpacingMultiplier = 1.5f, paragraphSpacingPx = 18f, paddingPx = 24f)
    }
}
