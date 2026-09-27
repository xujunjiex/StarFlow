package com.moe.starflow.novel.reader

import com.moe.starflow.novel.translate.NovelParagraph
import com.moe.starflow.novel.translate.NovelParagraphType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本章为空」的判据回归守卫。
 *
 * 用户可见的故障：整章正文都是不足 4 字的短行（例如一章全是「……」「嗯。」）时，
 * `paragraphs` 非空而**可显示的段**为空 —— 判据只看 `paragraphs.isEmpty()` 的话
 * 既不显示「本章为空」、页适配器又是 0 项，用户看到**一块没有任何解释的白屏**，
 * 而且这一章的段数分母恒为 0（永远算不出翻译进度）。
 */
class ChapterContentTest {

    private fun content(paras: List<NovelParagraph>) = ChapterContent(
        chapterIndex = 0,
        title = "第一章",
        paragraphs = paras,
        displayTexts = emptyMap(),
        pages = emptyList(),
        translatableIndexes = emptySet(),
    )

    @Test
    fun `整章都是不可显示段时判为空`() {
        val only = listOf(NovelParagraph(0, NovelParagraphType.SKIP, "……"))
        assertTrue("全是 SKIP 段就是没有东西可显示", content(only).isEmpty)
    }

    @Test
    fun `段表本身就空时判为空`() {
        assertTrue(content(emptyList()).isEmpty)
    }

    @Test
    fun `有可显示段时不算空`() {
        val paras = listOf(NovelParagraph(0, NovelParagraphType.TEXT, "正文"))
        assertFalse(content(paras).isEmpty)
    }

    /** 图片段也**算**可显示（要画「📷 [图片]」），不能因为不可翻译就被判成空。 */
    @Test
    fun `只有图片段时不算空`() {
        val paras = listOf(NovelParagraph(0, NovelParagraphType.IMAGE, "📷 [图片]"))
        assertFalse(content(paras).isEmpty)
    }

    /** `visibleParas` 是派生属性：手写的夹具不传它也不能是空表（否则滚动列表会全空）。 */
    @Test
    fun `可见段由段表派生而不是另外传进来`() {
        val paras = listOf(
            NovelParagraph(0, NovelParagraphType.TEXT, "正文"),
            NovelParagraph(1, NovelParagraphType.SKIP, "……"),
            NovelParagraph(2, NovelParagraphType.TEXT, "再一段"),
        )
        assertEquals(listOf(0, 2), content(paras).visibleParas.map { it.index })
    }
}
