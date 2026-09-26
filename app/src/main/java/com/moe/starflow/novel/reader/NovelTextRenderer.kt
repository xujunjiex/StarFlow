package com.moe.starflow.novel.reader

import android.graphics.Canvas
import android.text.StaticLayout
import android.text.TextPaint

/**
 * 正文绘制的共用件：**一段文本 → 一个 StaticLayout**。
 *
 * 分页模式与滚动模式都走这里，保证「怎么分的就怎么画」——两处各写一套的话，字号/行距
 * 迟早会在其中一处忘记同步（表现为「分页模式排版正常，滚动模式行距不对」）。
 *
 * ⚠️ 绘制**只接受字符串**，不持有段落/模式等状态：显示文本已经由
 * [NovelPageBilingual.displayText] 在分页之前就定好了，这里再做一次「原文还是译文」的判断
 * 就又多出一个真相来源。
 */
object NovelTextRenderer {

    /**
     * 正文色（浅色背景）。
     *
     * ⚠️ 颜色**不参与排版**，所以 [build] 给它一个默认值，分页器（[NovelPaginator]）不必关心；
     * 只有真正画的时候才由宿主按阅读背景灌进来。
     */
    const val COLOR_MAIN = 0xFF111111.toInt()

    /**
     * **选择模式**的选中底色。
     *
     * ⚠️ 半透明（约 18% alpha）：白色/米黄/黑三种阅读背景下都要看得出选中，
     * 实色会把正文压得看不清。分页与滚动两种模式共用同一个值，否则同一段东西
     * 在两个模式下的"选中"长得不一样。
     */
    const val COLOR_SELECTION = 0x2E55AEEA

    /**
     * **正在翻译 / 刚翻完**的那几段的底色。
     *
     * 用途（用户要求）：译文到达后必须重排，位置总有轻微偏移；高亮让用户一眼看出
     * "刚翻的是哪几段"，从而在偏移后仍能对上原来读的地方。手动/自动/增量都要有。
     *
     * ⚠️ 与 [COLOR_SELECTION] 用**不同色相**（选择=蓝，翻译=琥珀），
     * 两者同时出现时不能混成一块分不清。
     */
    const val COLOR_ACTIVE_BATCH = 0x2EFFB300

    /**
     * 高亮底（选中 / 正在翻译）的**圆角半径**（dp）。
     *
     * 分页与滚动两种模式共用一个值：同一段东西在两个模式下的"高亮"必须是同一个观感。
     * 高亮还要求**铺满整行**（左右边距也填上），别只铺正文列 —— 那看着像被两侧裁了一刀。
     */
    const val HIGHLIGHT_CORNER_DP = 8f

    fun build(text: String, style: NovelTextStyle, contentWidth: Int, color: Int = COLOR_MAIN): StaticLayout {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = style.fontSizePx
            this.color = color
        }
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, contentWidth.coerceAtLeast(1))
            .setLineSpacing(0f, style.lineSpacingMultiplier)
            .setIncludePad(false)
            .build()
    }

    /** 画一段文本，返回消耗的高度。 */
    fun draw(
        canvas: Canvas,
        text: String,
        style: NovelTextStyle,
        contentWidth: Int,
        x: Float,
        y: Float,
        color: Int = COLOR_MAIN,
    ): Float {
        if (text.isEmpty()) return 0f
        val layout = build(text, style, contentWidth, color)
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
        return layout.height.toFloat()
    }
}
