package com.moe.starflow.mangaimport.reader

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View

/**
 * 翻译面板里的**卡片外观**（章节卡片 + 展开的页/段落行），漫画与小说共用。
 *
 * 用户口径（2026-09-27）：
 * - 「章节卡片排版很丑…**要有卡片的样子**」→ 圆角 + 独立底色 + 卡片之间留白
 * - 「展开已经翻译或者等待的单个页面 pxx **要有对应的动画和效果**」→ 各状态**底色不同**，
 *   展开时淡入 + 上浮（见 `ReaderPageStateAdapter.bindPage`）
 * - 「当前正在提交等待返回的批次片段**背景要高亮**」→ [Tone.ACTIVE] 用琥珀色底（与小说阅读器里
 *   `COLOR_ACTIVE_BATCH` 的高亮同一套语义：一眼能看出"这几段正在等服务端返回"）
 *
 * ⚠️ 面板**不随全局主题**、只跟阅读背景（`darkPanel`），所以这里两套色值写死成对：
 * 漏一处就会在深色面板上出现白底白字（项目里已踩过多次）。
 */
object CardBackdrop {

    /** 行的语义状态 → 决定底色。 */
    enum class Tone {
        /** 章节卡片（浅色下 = **白底 + 细描边**：面板本身是白的，再用灰块就"和整体 UI 不搭"）。 */
        CARD,

        /** 展开出来的普通行：与卡片同色、**无描边** → 读起来像"在章卡片这个组里"。 */
        PLAIN,

        /** **正在提交 / 等待返回**（翻译中）：琥珀高亮。 */
        ACTIVE,

        /** 排队中（还没开始翻，「等待」）。 */
        WAITING,

        /** 失败。 */
        FAILED,

        /** 展开出来的详情块（比行再深一档）。 */
        DETAIL,
    }

    private const val CORNER_CARD_DP = 10f
    private const val CORNER_ROW_DP = 8f

    /** 面板底（浅）/ 面板底（深）—— 与 `sheet_*_menu.xml` 的容器底色同一套。 */
    private fun colorOf(tone: Tone, dark: Boolean): Int = when (tone) {
        // ⚠️ 浅色面板本身就是白的：卡片改**白底 + 细描边**（原来给灰块，用户反馈"和整体 UI 不搭"）；
        // 深色下才是深灰块 + 亮描边。行与卡片同色但**不描边**（靠缩进表达从属）。
        Tone.CARD, Tone.PLAIN -> if (dark) 0xFF23282E.toInt() else 0xFFFFFFFF.toInt()
        Tone.ACTIVE -> if (dark) 0xFF3A3020.toInt() else 0xFFFFF4E0.toInt()
        Tone.WAITING -> if (dark) 0xFF26262A.toInt() else 0xFFF2F2F4.toInt()
        Tone.FAILED -> if (dark) 0xFF3A2426.toInt() else 0xFFFDEDED.toInt()
        Tone.DETAIL -> if (dark) 0xFF181C20.toInt() else 0xFFEDEFF3.toInt()
    }

    /** 造一张卡片底（每次调用都新建：`View.setBackground` 不能共用同一个 Drawable 实例）。 */
    fun of(tone: Tone, dark: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(if (tone == Tone.CARD) CORNER_CARD_DP else CORNER_ROW_DP)
        setColor(colorOf(tone, dark))
        // 只有「章卡片」画描边：行/详情靠同色 + 缩进表达从属关系，再描边就太吵
        val stroke = if (tone == Tone.CARD) dp(if (dark) 0.5f else 1f).toInt() else 0
        setStroke(stroke, if (dark) 0x22FFFFFF else 0x1F000000)
    }

    /** 直接把底色贴到 View 上（返回 Drawable 便于调用方再改）。 */
    fun apply(view: View, tone: Tone, dark: Boolean): GradientDrawable {
        val bg = of(tone, dark)
        // ⚠️ 卡片自己贴了底 → 布局里那个 `?android:attr/selectableItemBackground` 就没了，
        // 点卡片会"毫无反馈"。这里补一层水波纹（包在卡片底外面，圆角跟着卡片走）。
        view.background = RippleDrawable(
            ColorStateList.valueOf(if (dark) 0x22FFFFFF else 0x14000000),
            bg,
            null,
        )
        return bg
    }

    private fun dp(v: Float): Float = v * android.content.res.Resources.getSystem().displayMetrics.density

    /**
     * 子行**不画底**（透出卡片的底）：它已经在卡片的框里，再画一块底就又多出一层"卡片感"。
     * 仍然补一层点击水波，保证点行有反馈。
     */
    fun applyFlat(view: View, dark: Boolean) {
        view.background = RippleDrawable(
            ColorStateList.valueOf(if (dark) 0x22FFFFFF else 0x14000000),
            GradientDrawable().apply { setColor(0x00000000) },
            null,
        )
    }

    /**
     * **嵌在章卡片里的行**（展开出来的页 / 段）。
     *
     * 用户口径（2026-09-27）：「章节的每一页的展开要放到章节的卡片组里面」——
     * 只靠缩进 + 同色还不够（每行仍是独立圆角块，看着像另起一张卡）。这里做成
     * **方角 + 左侧 3dp 竖线 + 上下零间距** → 一眼就是"上面那张卡片里的条目"。
     */
    fun applyNested(view: View, tone: Tone, dark: Boolean) {
        val stripeWidth = dp(if (tone == Tone.PLAIN) 3f else 2f).toInt()
        val stripe = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(stripeColor(tone, dark))
        }
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(colorOf(tone, dark))
        }
        val layers = LayerDrawable(arrayOf(stripe, fill)).apply {
            // 填充整体右移一条竖线的宽度 → 竖线贴最左
            setLayerInset(1, stripeWidth, 0, 0, 0)
        }
        view.background = RippleDrawable(
            ColorStateList.valueOf(if (dark) 0x22FFFFFF else 0x14000000),
            layers,
            null,
        )
    }

    /** 嵌套行左边那条竖线的颜色（普通行=淡灰，特殊态=对应强调色）。 */
    private fun stripeColor(tone: Tone, dark: Boolean): Int = when (tone) {
        Tone.ACTIVE -> 0xFFFF9F0A.toInt()
        Tone.WAITING -> if (dark) 0xFF6E6E73.toInt() else 0xFFC7C7CC.toInt()
        Tone.FAILED -> 0xFFCC5555.toInt()
        else -> if (dark) 0xFF3A4046.toInt() else 0xFFD8DCE3.toInt()
    }
}
