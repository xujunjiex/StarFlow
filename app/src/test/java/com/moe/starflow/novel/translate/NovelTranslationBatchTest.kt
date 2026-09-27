package com.moe.starflow.novel.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelTranslationBatchTest {

    // ===== 位置兜底（模型丢掉编号时）=====

    /** 条数一致、长度也合理 → 按顺序对应。模型不重复编号是常见行为，不能因此判失败。 */
    @Test
    fun `位置兜底条数一致且长度合理时按顺序对应`() {
        val source = "字".repeat(60)
        val map = NovelTranslationBatch.parseTolerant(
            "译".repeat(25) + "\n\n" + "译".repeat(30),
            listOf(5, 9),
        ) { source }

        assertEquals(25, map[5]?.length)
        assertEquals(30, map[9]?.length)
    }

    /**
     * **回归**：只请求一段时「条数一致」不携带任何信息 —— 任何非空回复切出来都是 1 条，
     * 于是模型那句「抱歉，我无法翻译这段内容。」会被当成译文、以 SUCCESS 写库，
     * 用户正文里永久出现这句拒绝语**且再也不会被重试**（引擎恰好在整批被拒时降级成
     * 单段请求，而内容审查正是它最可能的输出）。
     */
    @Test
    fun `单段位置兜底不接受明显不是译文的短回复`() {
        val source = "他抬起头，看了看窗外的天色，心中忽然涌起一阵说不清的怅惘。风从窗缝里钻进来，带着土腥气。"
        val refusal = "抱歉，我无法翻译这段内容。"

        val map = NovelTranslationBatch.parseTolerant(refusal, listOf(3)) { source }

        assertTrue("拒绝语不能被当成译文，实际=$map", map.isEmpty())
    }

    /** 长度合理的单段译文照样接受 —— 闸门只管明显不像译文的那种。 */
    @Test
    fun `单段位置兜底仍接受正常长度的译文`() {
        val source = "他抬起头，看了看窗外的天色，心中忽然涌起一阵说不清的怅惘。风从窗缝里钻进来，带着土腥气。"
        val translated = "He looked up at the sky outside the window, a vague melancholy rising in his chest."

        val map = NovelTranslationBatch.parseTolerant(translated, listOf(3)) { source }

        assertEquals(3, map.keys.single())
    }

    /**
     * ⚠️ 长度比是**启发式，不是证明**：原文短到几个字时比值没有区分力，闸门直接放行。
     * 这条把已知的界限钉住，免得下次有人以为闸门能挡住一切。
     */
    @Test
    fun `原文很短时位置兜底不设长度下限`() {
        val map = NovelTranslationBatch.parseTolerant("抱歉", listOf(3)) { "甲" }
        assertEquals("抱歉", map[3])
    }

    /** 编号还在时走编号路径，压根不进位置兜底（闸门不该影响正常解析）。 */
    @Test
    fun `带编号的回复不受位置闸门影响`() {
        val short = "抱歉，我无法翻译这段内容。"
        val map = NovelTranslationBatch.parseTolerant("[3] $short", listOf(3)) { "字".repeat(60) }
        assertEquals(short, map[3])
    }

    // ===== 拼 prompt =====

    @Test
    fun `拼出的 prompt 带真实编号而不是批内序号`() {
        val us = listOf(NovelUnit(5, "第一段"), NovelUnit(9, "第二段"))
        val prompt = NovelTranslationBatch.buildPrompt(us, listOf(5, 9))
        assertTrue(prompt, prompt.contains("[5] 第一段"))
        assertTrue(prompt, prompt.contains("[9] 第二段"))
    }

    @Test
    fun `拼 prompt 时跳过不存在的编号`() {
        val us = listOf(NovelUnit(5, "第一段"))
        val prompt = NovelTranslationBatch.buildPrompt(us, listOf(5, 9))
        assertTrue(prompt, prompt.contains("[5]"))
        assertFalse("不存在的编号不该拼进 prompt", prompt.contains("[9]"))
    }

    // ===== 解析 =====

    @Test
    fun `解析回编号对应关系`() {
        val map = NovelTranslationBatch.parse("[5] 译文甲\n[9] 译文乙", listOf(5, 9))
        assertEquals("译文甲", map[5])
        assertEquals("译文乙", map[9])
    }

    @Test
    fun `缺号的译文不写进结果`() {
        val map = NovelTranslationBatch.parse("[5] 只有这个", listOf(5, 9))
        assertEquals("只有这个", map[5])
        assertFalse("缺号不能补空串 —— 空串会被当成成功译文写库", map.containsKey(9))
    }

    @Test
    fun `多返回的编号被忽略`() {
        val map = NovelTranslationBatch.parse("[5] 甲\n[9] 乙\n[77] 多余的", listOf(5, 9))
        assertEquals(2, map.size)
        assertFalse(map.containsKey(77))
    }

    @Test
    fun `空回复与无编号回复得到空映射`() {
        assertTrue(NovelTranslationBatch.parse("", listOf(1, 2)).isEmpty())
        assertTrue(NovelTranslationBatch.parse("没有任何编号", listOf(1, 2)).isEmpty())
    }

    @Test
    fun `空译文不被当成有效结果`() {
        assertTrue(NovelTranslationBatch.parse("[5]   ", listOf(5)).isEmpty())
    }

    @Test
    fun `模型把一条译文写成多行时能完整取回`() {
        val map = NovelTranslationBatch.parse("[5] 第一行\n第二行\n[9] 另一段", listOf(5, 9))
        assertEquals("第一行\n第二行", map[5])
        assertEquals("另一段", map[9])
    }

    /** 点串归一化复用既有实现：被模型换行拆开的省略号要接回同一行。 */
    @Test
    fun `译文的点串被归一`() {
        val map = NovelTranslationBatch.parse("[5] 他沉默…\n…然后走了", listOf(5))
        assertEquals("他沉默……然后走了", map[5])
    }

    @Test
    fun `编号在行首以外的位置也能解析`() {
        val map = NovelTranslationBatch.parse("[5] 甲\n\n[9] 乙", listOf(5, 9))
        assertEquals("甲", map[5])
        assertEquals("乙", map[9])
    }
}
