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

    // ===== 2026-09-25 用户日志实证：prefill 吃掉 [1] + 正文里有裸数字行 = 整批前移 + 末尾空 =====
    //
    // 火山(standard)/DeepSeek(prefix) 的续写 prefill `"[1] "` 被服务端吞掉，content 里只有
    // `[2]..[N]`；恰好 OCR 把页脚页码当文字行识别成第 1 个气泡，模型原样返回 `330`。
    // 旧实现走「按行拆」降级，清洗正则 `^\[?\d+]?[.、\s]*` 把 `330` 整行吃掉 → 少一条 →
    // 补齐空串 → 每个气泡拿到**下一个**气泡的译文，末条是空串（用户看到的"漏翻"）。

    /** 火山 page=371 第一批的真实 Response（4 条，首行是页码 330）。 */
    @Test
    fun numberedParse_prefillOffset_doesNotDropNumericFirstLine() {
        val text = "330\n[2] 一片寂静……\n[3] 啥！？别这样我……这好奇怪……\n[4] 不行——我忍不住了，能重新做回自己真的太开心了！"
        assertEquals(
            listOf("330", "一片寂静……", "啥！？别这样我……这好奇怪……", "不行——我忍不住了，能重新做回自己真的太开心了！"),
            TranslateUtils.parseNumberedTranslations(text, 4)
        )
    }

    /** 火山 page=372 第一批的真实 Response（首行是页码 331）。 */
    @Test
    fun numberedParse_prefillOffset_page372() {
        val text = "331\n[2] 呼 呼 呼 呼 呼！\n[3] 没事的，大家都知道步骤。\n[4] 不，不，不，重点不是这个！！"
        assertEquals(
            listOf("331", "呼 呼 呼 呼 呼！", "没事的，大家都知道步骤。", "不，不，不，重点不是这个！！"),
            TranslateUtils.parseNumberedTranslations(text, 4)
        )
    }

    /** 裸数字行在任何位置都不能被当成编号标记吃掉。 */
    @Test
    fun numberedParse_bareNumberLineIsNeverSwallowed() {
        // 无任何编号标记 → 走按行拆降级
        assertEquals(
            listOf("330", "一片寂静……"),
            TranslateUtils.parseNumberedTranslations("330\n一片寂静……", 2)
        )
        // 带编号标记时，正文里的数字（含 "330 号" 这种）也不能被削掉
        assertEquals(
            listOf("330 号", "乙"),
            TranslateUtils.parseNumberedTranslations("330 号\n[2] 乙", 2)
        )
    }

    /** 模型跳号时**留空对位**，不允许整批前移（空条目由调用方回退原文）。 */
    @Test
    fun numberedParse_skippedNumber_keepsAlignment() {
        val text = "330\n[2] 甲\n[4] 丁"
        assertEquals(
            listOf("330", "甲", "", "丁"),
            TranslateUtils.parseNumberedTranslations(text, 4)
        )
    }

    /** 没有 prefill 偏移（模型自报 [1]）时行为不变。 */
    @Test
    fun numberedParse_fullNumbering_unchanged() {
        assertEquals(
            listOf("甲", "乙", "丙"),
            TranslateUtils.parseNumberedTranslations("[1] 甲\n[2] 乙\n[3] 丙", 3)
        )
    }
}
