package com.moe.starflow.novel.reader

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * 插图编辑模式里那一排按钮。
 *
 * ⚠️ **没有单独的「重新翻译」按钮**：翻译与重翻是同一个动作的两个时机
 * （还没翻过 → 翻译；翻过了 → 重翻），合成一颗按钮后图标随状态变即可 ——
 * 拆成两颗会让"第一次进编辑模式"时出现一颗**点了什么都不会发生**的按钮。
 */
enum class NovelImageButton {
    /** 翻译 / 重新翻译（图标按"有没有译文"变）。 */
    TRANSLATE,

    /** 三态切换（译文 / 识别原文 / 原图）——**只在有译文时才出现**。 */
    TOGGLE,

    /** 删除这张图的译文（回落原图）——**只在有译文时才出现**。 */
    DELETE,
}

/**
 * **插图编辑模式**的几何：选中效果 + 按钮条的位置与命中（纯算术，可普通单测）。
 *
 * 用户口径（2026-10）：
 * - 「长按图片 → **出现翻译按钮**，点击翻译才会 OCR 翻译」（长按**不**自动开翻）
 * - 「图片右下角的按钮**小一点、加点透明**」
 * - 「**退出编辑模式隐藏**相关按钮」
 * - 「选中图片要有对应的**选中效果**」
 * - 「要考虑到多种图片尺寸，**翻译按钮可能因为图片尺寸太小没法放在图片上面**」
 *
 * ⚠️ 几何收敛在这里一份：分页模式（[NovelPageView]）与滚动模式
 * （[NovelScrollAdapter.ParagraphView]）各画各的，但**位置与命中必须同源** ——
 * 各算一套的话，"看着有按钮、点上去没反应"或"点在三态上却删了译文"就是这么来的。
 */
object NovelImageControls {

    /**
     * 单个按钮的边长（dp）。
     *
     * 用户口径「小一点」：34dp → **26dp**（约 4.6mm，仍在可点范围内，但不至于压住插图内容）。
     */
    const val BUTTON_DP = 26f

    /** 按钮之间、以及与图片右下角的间距（dp）。 */
    const val GAP_DP = 4f
    const val INSET_DP = 6f

    /** 图标在按钮里的内边距（dp）：26dp 的按钮留 5dp，图标 16dp。 */
    private const val ICON_PAD_DP = 5f

    /** 选中框：描边宽度（dp）与向外扩的余量（dp，让描边不压住图片内容）。 */
    const val SELECTION_BORDER_DP = 2f
    const val SELECTION_INFLATE_DP = 3f

    /**
     * 选中效果：半透明填充（与文本段选中的蓝同一个色相，见
     * [NovelTextRenderer.COLOR_SELECTION]）+ 不透明描边。
     *
     * ⚠️ 用**蓝色**而不是翻译的琥珀：琥珀的语义是"这批正在翻译"（[NovelTextRenderer.COLOR_ACTIVE_BATCH]），
     * 编辑模式与"在翻译"是两件事 —— 而且翻译完之后琥珀就擦掉了，选中态必须一直看得到。
     */
    const val SELECTION_FILL = 0x3D55AEEA
    const val SELECTION_BORDER = 0xFF55AEEA.toInt()

    /** 按钮的绘制位置（屏幕/画布坐标，左上右下）。 */
    data class Item(
        val button: NovelImageButton,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom

        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
        val size: Float get() = right - left
    }

    /**
     * 当前该显示哪几颗按钮（**顺序 = 从左到右**）。
     *
     * - 还没翻过：只有「翻译」
     * - 翻过了：`[重翻] [三态] [删除]` —— 破坏性操作放最右，离前两颗远一点，少误触
     */
    fun buttonsFor(hasTranslation: Boolean): List<NovelImageButton> =
        if (hasTranslation) {
            listOf(NovelImageButton.TRANSLATE, NovelImageButton.TOGGLE, NovelImageButton.DELETE)
        } else {
            listOf(NovelImageButton.TRANSLATE)
        }

    /** 选中框的矩形（用**自己的**四浮点盒子，不用 `RectF`）。 */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        /** 画的时候才转 `RectF`（⚠️ `RectF` 在普通 JVM 单测里是空实现，几何不能建在它上面）。 */
        fun toRectF(): RectF = RectF(left, top, right, bottom)
    }

    /** 选中框的矩形：图片绘制矩形向外扩 [SELECTION_INFLATE_DP]（描边画在框上、不压住图片内容）。 */
    fun selectionRect(
        imageLeft: Float,
        imageTop: Float,
        imageRight: Float,
        imageBottom: Float,
        density: Float,
    ): Box {
        val pad = SELECTION_INFLATE_DP * density
        return Box(imageLeft - pad, imageTop - pad, imageRight + pad, imageBottom + pad)
    }

    /**
     * 选中效果的**填充**（画在图片**下面**：图片不透明会把填充盖住，所以它只是"边缘一圈底色"）。
     *
     * ⚠️ 描边要单独用 [drawSelectionFrame] 画在图片**之后**（[NovelPageView] 那段有自己的
     * `clipRect`，把描边画在里面会被裁掉一半 —— 而且描边本来就在图片矩形**外面**）。
     */
    fun drawSelectionFill(canvas: Canvas, rect: Box, density: Float, color: Int = SELECTION_FILL) {
        val fill = fillPaint
        fill.color = color
        val r = 6f * density
        canvas.drawRoundRect(rect.toRectF(), r, r, fill)
    }

    /** 选中效果的**描边**（画在图片之后，外扩 [SELECTION_INFLATE_DP]，任何尺寸的图都看得见）。 */
    fun drawSelectionFrame(canvas: Canvas, rect: Box, density: Float) {
        drawFrame(canvas, rect, SELECTION_BORDER, SELECTION_BORDER_DP, density)
    }

    /**
     * 通用描边（选中框 / 「这批正在翻译」的黄框 / 「失败」的红框都用它）。
     *
     * ⚠️ 与 [drawSelectionFill] 一样，**必须画在图片之后、段裁剪之外**：框在图片矩形外侧，
     * 画进裁剪里会被切掉上/下两条边。图片不透明，只有"框"能表达状态。
     */
    fun drawFrame(canvas: Canvas, rect: Box, color: Int, widthDp: Float, density: Float) {
        val stroke = strokePaint
        stroke.color = color
        stroke.strokeWidth = widthDp * density
        val r = 6f * density
        canvas.drawRoundRect(rect.toRectF(), r, r, stroke)
    }

    /**
     * 按钮条的位置。**三步走**（越靠前越优先）：
     *
     * 1. **图片内右下角**（[INSET_DP] 内侧）—— 图片够大时的常规摆放
     * 2. **图片正下方、与图片右边缘对齐**（[GAP_DP] 之外）—— 图片太窄/太矮时（用户口径：
     *    「翻译按钮可能因为图片尺寸太小没法放在图片上面」）。⚠️ 绝不再缩按钮：缩到十几 dp
     *    就点不准了，宁可挪到图外
     * 3. **图片正上方**（下方没空间时）
     *
     * 最后统一夹进 [boundsLeft]..[boundsRight] / [boundsTop]..[boundsBottom]（正文框），
     * 保证按钮**永远在屏内、可点**；正文框比按钮条还窄（极端分屏）时才等比缩小。
     */
    fun layout(
        imageLeft: Float,
        imageTop: Float,
        imageRight: Float,
        imageBottom: Float,
        density: Float,
        buttons: List<NovelImageButton> = buttonsFor(hasTranslation = true),
        boundsLeft: Float = 0f,
        boundsTop: Float = 0f,
        boundsRight: Float = imageRight,
        boundsBottom: Float = imageBottom,
    ): List<Item> {
        if (buttons.isEmpty()) return emptyList()
        val size = BUTTON_DP * density
        val gap = GAP_DP * density
        val inset = INSET_DP * density
        var natural = buttons.size * size + (buttons.size - 1) * gap
        val availW = (boundsRight - boundsLeft).coerceAtLeast(1f)
        // 只有"正文框比按钮条还窄"才缩（极端分屏）；图片本身小不缩，改成挪到图外
        val scale = if (natural > availW) availW / natural else 1f
        val s = size * scale
        val g = gap * scale
        natural = buttons.size * s + (buttons.size - 1) * g

        val imgW = imageRight - imageLeft
        val imgH = imageBottom - imageTop
        val fitsInside = natural + inset * 2f <= imgW && s + inset * 2f <= imgH

        var left = if (fitsInside) imageRight - inset - natural else imageRight - natural
        var top = when {
            fitsInside -> imageBottom - inset - s
            // 图片下方放得下就放下方；否则放上方；再不行就贴着图片底边（夹取会兜住）
            imageBottom + gap + s <= boundsBottom -> imageBottom + gap
            imageTop - gap - s >= boundsTop -> imageTop - gap - s
            else -> imageBottom - s
        }
        left = left.coerceIn(boundsLeft, (boundsRight - natural).coerceAtLeast(boundsLeft))
        top = top.coerceIn(boundsTop, (boundsBottom - s).coerceAtLeast(boundsTop))

        return buttons.mapIndexed { i, b ->
            val l = left + i * (s + g)
            Item(b, l, top, l + s, top + s)
        }
    }

    /** 命中了哪颗按钮（没命中 = null：调用方按"点了图片其他位置"处理）。 */
    fun hit(items: List<Item>, x: Float, y: Float): NovelImageButton? =
        items.firstOrNull { it.contains(x, y) }?.button

    /**
     * 画按钮条的底：每颗按钮一个**圆角方块**。
     *
     * 用户口径「加点透明」：底色 alpha 从 `0xCC`(80%) 降到 **`0x99`(60%)** ——
     * 压在插图上看得出按钮在哪，又不至于把图上的内容盖死。
     */
    fun drawBackdrop(canvas: Canvas, items: List<Item>, density: Float) {
        val bg = backdropPaint
        bg.color = BACKDROP_COLOR
        val r = 7f * density
        for (it in items) canvas.drawRoundRect(it.left, it.top, it.right, it.bottom, r, r, bg)
    }

    /** 把 [icon] 画进 [item] 的矩形（居中、留 [ICON_PAD_DP] 内边距）。 */
    fun drawIcon(canvas: Canvas, item: Item, icon: Drawable?, density: Float) {
        if (icon == null) return
        val pad = ICON_PAD_DP * density
        val side = (item.size - pad * 2).coerceAtLeast(1f)
        val cx = item.centerX
        val cy = item.centerY
        icon.setBounds(
            (cx - side / 2f).toInt(),
            (cy - side / 2f).toInt(),
            (cx + side / 2f).toInt(),
            (cy + side / 2f).toInt(),
        )
        icon.draw(canvas)
    }

    /** 半透明深色底（与 `bg_reader_translate_group` 同一观感，但更透一点：60%）。 */
    private const val BACKDROP_COLOR = 0x991E1E22.toInt()

    /** ⚠️ 都是 `by lazy`：纯几何单测在普通 JVM 里跑，类加载时碰 `Paint` 会 "not mocked"。 */
    private val backdropPaint: Paint by lazy { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val fillPaint: Paint by lazy { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val strokePaint: Paint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    }
}
