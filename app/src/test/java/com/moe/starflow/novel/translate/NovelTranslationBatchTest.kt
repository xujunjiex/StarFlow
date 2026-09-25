package com.moe.starflow.novel.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelTranslationBatchTest {

    private fun units(vararg texts: String): List<NovelUnit> =
        texts.mapIndexed { i, t -> NovelUnit(i, t) }

    // ===== 分批 =====

    @Test
    fun `按批大小分组且编号全覆盖`() {
        val us = (0 until 20).map { NovelUnit(it, "段落$it") }
        val batches = NovelTranslationBatch.buildBatches(us, batchSize = 8)
        assertEquals(listOf(8, 8, 4), batches.map { it.size })
        assertEquals((0 until 20).toList(), batches.flatten())
    }

    @Test
    fun `超长段落单独成批`() {
        val long = "字".repeat(NovelTranslationBatch.MAX_BATCH_CHARS + 100)
        val batches = NovelTranslationBatch.buildBatches(units("短一", long, "短二"), batchSize = 8)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), batches)
    }

    /**
     * 关键差别：普通长段落（几百字，网文常态）**必须仍能成批**。
     * 参考实现的「长度 ≥ 28 就单独成批」规则正是把小说翻译拖慢的根因。
     */
    @Test
    fun `普通长段落不会被打散成单条`() {
        val us = List(6) { NovelUnit(it, "字".repeat(300) + it) }
        val batches = NovelTranslationBatch.buildBatches(us, batchSize = 8)
        assertEquals(1, batches.size)
        assertEquals(6, batches[0].size)
    }

    @Test
    fun `批大小非法时至少一段一批而不是死循环`() {
        val batches = NovelTranslationBatch.buildBatches(units("一", "二"), batchSize = 0)
        assertEquals(listOf(listOf(0), listOf(1)), batches)
    }

    @Test
    fun `空输入得到空批表`() {
        assertTrue(NovelTranslationBatch.buildBatches(emptyList(), 8).isEmpty())
    }

    /**
     * 回归守卫：批里的编号必须是**真实 paraIndex**，不能是「在待翻列表里的位置」。
     *
     * 一章里若有图片段/过短段（占 index 但不参与翻译），两者就会错开，
     * 译文会被静默写到错误的段落上。
     */
    @Test
    fun `批里带的是真实 paraIndex 而不是位置`() {
        // 段 0 与 2 是图形/短段被跳过，真正待翻的是 1、5、9
        val us = listOf(NovelUnit(1, "甲"), NovelUnit(5, "乙"), NovelUnit(9, "丙"))
        val batches = NovelTranslationBatch.buildBatches(us, batchSize = 8)
        assertEquals(listOf(listOf(1, 5, 9)), batches)
        assertFalse("位置 0 不该出现在批里", batches.flatten().contains(0))
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
