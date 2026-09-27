package com.moe.starflow.novel.reader

import com.moe.starflow.novel.translate.NovelParagraph
import com.moe.starflow.novel.translate.NovelParagraphType

/**
 * 右下角翻译按钮的语义。
 *
 * ⚠️ **文本翻译没有「缓存命中」这一套**：漫画那边是「这一页已经有译文 → 按钮变重翻」，
 * 小说**不这么做**（用户明确要求）—— 有没有自己的译文不该改变按钮的含义，
 * 否则「点一下会发生什么」取决于看不见的缓存状态。重翻只从**长按选择**进来。
 */
enum class NovelTranslateAction {
    /** 翻没翻过的段。 */
    TRANSLATE,

    /** 选中的段全都已有译文 → 只重翻。 */
    RETRANSLATE,

    /** 选中里既有已翻译也有未翻译 → 重翻已翻的 + 翻没翻的。 */
    TRANSLATE_AND_RETRANSLATE,
}

/**
 * 「这段能不能翻」的唯一判据（纯函数）。
 *
 * ⚠️ 别把 [NovelParagraphType.SKIP] / [NovelParagraphType.IMAGE] 算进「待翻译」：
 * 短行（`……`、`嗯。`）与图片标记**按设计永远不翻译**，把它们算成「没翻」的话，
 * 含这类段的一页永远判不出「整页都翻过了」，翻译按钮就常驻且点了没反应。
 */
internal fun NovelParagraph.isTranslatable(): Boolean =
    type == NovelParagraphType.TEXT && originalText.isNotBlank()

/**
 * 翻译浮层组显示规格的唯一计算出口（纯函数，可单测）。
 *
 * 判据（用户口径，别再改错）：
 * - **翻页模式**：看**当前页**；**滚动模式**：三态看**当前屏幕可见段**、翻译按钮看整章
 * - 页面还有没翻的段 → 显示翻译按钮；页面的段都翻过了 → 只留三态
 * - 三态按钮：当前视野里有译文才显示（没译文时点了看不出任何变化）
 * - 选择模式下翻译按钮的语义由**选中集**决定，与页面/整章的译文状态无关
 */
object NovelTranslateChrome {

    /**
     * 无选择时翻译按钮的语义；`null` = 不显示。
     *
     * @param untranslated 当前视野里**还没译文**的可翻译段数
     */
    fun actionFor(untranslated: Int): NovelTranslateAction? =
        if (untranslated > 0) NovelTranslateAction.TRANSLATE else null

    /**
     * 选择模式下翻译按钮的语义；`null` = 还没选任何段（退回普通语义）。
     *
     * @param untranslated 选中里没译文的段数
     * @param translated 选中里已有译文的段数
     */
    fun actionForSelection(untranslated: Int, translated: Int): NovelTranslateAction? = when {
        untranslated + translated <= 0 -> null
        translated <= 0 -> NovelTranslateAction.TRANSLATE
        untranslated <= 0 -> NovelTranslateAction.RETRANSLATE
        else -> NovelTranslateAction.TRANSLATE_AND_RETRANSLATE
    }

    /** 三态切换按钮：当前视野里有译文才显示（选中与否同一套判据）。 */
    fun showToggle(translated: Int): Boolean = translated > 0

    /**
     * 「清除译文」按钮：**选中的段里有译过的**才显示。
     *
     * ⚠️ 与 [showToggle] 的判据不同：三态是"当前屏幕上有没有译文"，而清除是对**选中集**动手 ——
     * 选中几段没翻过的段时不该出现这个按钮（清无可清）。
     */
    fun showClear(translatedSelected: Int): Boolean = translatedSelected > 0
}
