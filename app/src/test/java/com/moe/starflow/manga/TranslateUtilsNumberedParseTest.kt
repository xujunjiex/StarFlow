package com.moe.starflow.manga

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 编号译文解析里的省略号归一化。
 *
 * 真实反馈的原始形态：模型把**一条**译文写成三行
 * ```
 * [1] .
 * .
 * .
 * [2] ...
 * ```
 * 而 `NUMBERED_TRANSLATION_REGEX` 的 `([\s\S]*?)` 是**跨行**捕获 → 第一条译文变成 `".\n.\n."`。
 * 横排渲染就是三行各一个点；竖排里 `\n` 还各占一个字符格，更难看，并且白占 overlay 空间
 * 把字号压小（用户原话：「非常难看」「破坏字体大小平衡」）。
 *
 * 契约：解析阶段就把「两侧都是点」的换行接回去；正文换行保持不动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslateUtilsNumberedParseTest {

    @Test
    fun numberedParse_joinsEllipsisSplitAcrossLines() {
        val text = "[1] .\n.\n.\n[2] 你好"
        assertEquals(listOf("...", "你好"), TranslateUtils.parseNumberedTranslations(text, 2))
    }

    @Test
    fun numberedParse_joinsSixDots() {
        val text = "[1] ...\n...\n[2] 你好"
        assertEquals(listOf("......", "你好"), TranslateUtils.parseNumberedTranslations(text, 2))
    }

    /** 正文内部的换行是正常排版，必须原样保留（只处理点与点之间的换行）。 */
    @Test
    fun numberedParse_keepsNormalMultilineText() {
        val text = "[1] 第一句\n第二句\n[2] x"
        assertEquals(listOf("第一句\n第二句", "x"), TranslateUtils.parseNumberedTranslations(text, 2))
    }

    @Test
    fun partialParse_alsoJoinsEllipsis() {
        val text = "[1] .\n.\n.\n[2] 你好\n[3] 未完"
        assertEquals(
            listOf(1 to "...", 2 to "你好"),
            TranslateUtils.parseNumberedTranslationsPartial(text)
        )
    }
}
