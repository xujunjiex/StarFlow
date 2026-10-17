package com.moe.starflow.novel.reader

import android.graphics.Canvas
import android.text.StaticLayout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import com.moe.starflow.novel.parser.NovelFontSizeSpan
import android.text.TextPaint

/**
 * 正文绘制的共用件：**一段文本 → 一个 StaticLayout**。
 *
 * 分页模式与滚动模式都走这里，保证「怎么分的就怎么画」——两处各写一套的话，字号/行距
 * 迟早会在其中一处忘记同步（表现为「分页模式排版正常，滚动模式行距不对」）。
 *
 * ⚠️ 绘制**不判断「原文还是译文」**：显示文本已经由 [NovelPageBilingual.displayText] 在分页之前
 * 就定好了，这里再做一次判断就又多出一个真相来源 —— 所以 [drawLayout] 收到的只是一份排好版的
 * layout，不持有段落/模式等任何状态。
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
     * **这一批翻失败了**的那几段的底色（淡红）。
     *
     * 用户口径 2026-10-03：「失败的段落要能一眼看出来」——失败原因在面板章行展开里是原文，
     * 但在正文里没有标记的话，用户看到的只是"这几段还是原文"，分不清「没轮到」和「翻失败了」。
     *
     * ⚠️ 与 [COLOR_ACTIVE_BATCH]（琥珀=正在翻译）**不同色相**，且**优先于它**：
     * 一批刚失败时它还可能在飞集合里停一拍，不能让红色被琥珀盖掉。
     */
    const val COLOR_FAILED_BATCH = 0x33FF5252

    /**
     * **刚翻完**的那几段的底色（淡绿）。
     *
     * 用户口径 2026-10-04：「上一次翻译完成的段落（不管是对比还是原文模式），都要**换一个背景色**
     * 用来提示刚刚翻译的是哪一段，4 种翻译模式都要有这个机制」。
     *
     * 与 [COLOR_ACTIVE_BATCH]（琥珀 = **正在飞**）是**两个时刻**：译文到达后琥珀立刻擦掉，
     * 那几段就再没有任何标记，而译文引起的重排会让人一时找不到刚才读到哪 —— 这个颜色就是补那一拍。
     *
     * ⚠️ 用**绿**（第三个色相）：选择=蓝 / 正在翻译=琥珀 / 失败=红 / 刚翻完=绿，
     * 四者可能同屏出现，同色相就分不清了。半透明，深浅两种阅读背景下都看得见又不压正文。
     */
    const val COLOR_LAST_TRANSLATED = 0x3D43A047

    /**
     * 插图**边框**用的不透明版本（底色那三个值是半透明的，当 3dp 描边画在图上几乎看不见）。
     *
     * ⚠️ 插图没有"底色"可铺（图是不透明的，铺了也被盖住），它的状态**只能**靠这一圈框表达：
     * 「这几张图正在翻」「这三张都要显示黄色状态」（用户口径 2026-10-04）全靠它。
     * 用 `COLOR_ACTIVE_BATCH`（18% alpha）当描边在真机上基本看不出颜色 —— 那是"改了但没生效"。
     */
    const val BORDER_ACTIVE_BATCH = 0xFFFFB300.toInt()
    const val BORDER_FAILED_BATCH = 0xFFFF5252.toInt()
    const val BORDER_LAST_TRANSLATED = 0xFF43A047.toInt()

    /**
     * 高亮底（选中 / 正在翻译）的**圆角半径**（dp）。
     *
     * 分页与滚动两种模式共用一个值：同一段东西在两个模式下的"高亮"必须是同一个观感。
     * 高亮还要求**铺满整行**（左右边距也填上），别只铺正文列 —— 那看着像被两侧裁了一刀。
     */
    const val HIGHLIGHT_CORNER_DP = 8f

    fun build(
        text: String,
        style: NovelTextStyle,
        contentWidth: Int,
        color: Int = COLOR_MAIN,
        fontSizeSpans: List<NovelFontSizeSpan> = emptyList(),
    ): StaticLayout {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = style.fontSizePx
            typeface = style.typeface
            this.color = color
        }
        val styledText: CharSequence = if (style.useEpubFontSizes && fontSizeSpans.isNotEmpty()) {
            SpannableString(text).apply {
                fontSizeSpans.forEach { span ->
                    val start = span.start.coerceIn(0, text.length)
                    val end = span.end.coerceIn(start, text.length)
                    if (start < end) setSpan(RelativeSizeSpan(span.scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        } else text
        return StaticLayout.Builder
            .obtain(styledText, 0, styledText.length, paint, contentWidth.coerceAtLeast(1))
            .setLineSpacing(0f, style.lineSpacingMultiplier)
            .setIncludePad(false)
            .build()
    }

    /**
     * 画一份**已经排好**的 layout（[build] 的产物），返回它的高度。
     *
     * ⚠️ 这里**不再收字符串、也不再自己 `build()`**：调用方（`NovelScrollAdapter.ParagraphView`）
     * 为了量高度已经排过一遍，而它每次绑定都会 `requestLayout` —— 绘制端再排一次就是**每段每次绑定排两遍**
     * （100~300 字的段 0.3~1ms/遍）。现在「量」与「画」共用调用方缓存的那一份。
     *
     * ⚠️ 颜色在这里才灌进 layout 自己的 paint：颜色不参与测量（见 [COLOR_MAIN]），
     * 只为换个颜色重排是白烧；而 StaticLayout 画的时候用的就是构建时传进去的那个 [TextPaint] 实例
     * （`Layout.getPaint()`），改它的 color 只影响画出来的颜色，几何一点不变。
     */
    fun drawLayout(
        canvas: Canvas,
        layout: StaticLayout,
        x: Float,
        y: Float,
        color: Int = COLOR_MAIN,
    ): Float {
        layout.paint.color = color
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
        return layout.height.toFloat()
    }
}
