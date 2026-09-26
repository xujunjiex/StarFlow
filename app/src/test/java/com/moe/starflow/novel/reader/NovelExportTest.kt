package com.moe.starflow.novel.reader

import com.moe.starflow.novel.translate.NovelParagraph
import com.moe.starflow.novel.translate.NovelParagraphType
import org.junit.Assert.assertEquals
import org.junit.Test

/** 导出的文本规则（与阅读器共用 `NovelPageBilingual`，这里钉住的是"确实复用了它"）。 */
class NovelExportTest {

    private fun para(index: Int, text: String) = NovelParagraph(index, NovelParagraphType.TEXT, text)

    @Test
    fun `三种模式各自导出对应的文本`() {
        val paras = listOf(para(0, "one"), para(1, "two"))
        val tr = mapOf(0 to "一", 1 to "二")

        assertEquals("一\n二\n", NovelExport.buildChapterText(paras, tr, NovelExport.Kind.TRANSLATED))
        assertEquals("one\ntwo\n", NovelExport.buildChapterText(paras, tr, NovelExport.Kind.ORIGINAL))
        // 双语 = 原文一行、译文一行（与阅读器双语同序）
        assertEquals(
            "one\n一\ntwo\n二\n",
            NovelExport.buildChapterText(paras, tr, NovelExport.Kind.BILINGUAL),
        )
    }

    /** 未翻译的段在**任何**模式下都回落原文：留空会让导出文件像丢了内容。 */
    @Test
    fun `未翻译的段回落原文`() {
        val paras = listOf(para(0, "one"), para(1, "two"))
        val tr = mapOf(0 to "一")
        assertEquals("一\ntwo\n", NovelExport.buildChapterText(paras, tr, NovelExport.Kind.TRANSLATED))
        assertEquals("one\n一\ntwo\n", NovelExport.buildChapterText(paras, tr, NovelExport.Kind.BILINGUAL))
    }

    @Test
    fun `SKIP 段不进导出`() {
        val paras = listOf(
            para(0, "one"),
            NovelParagraph(1, NovelParagraphType.SKIP, "……"),
            para(2, "three"),
        )
        assertEquals("one\nthree\n", NovelExport.buildChapterText(paras, emptyMap(), NovelExport.Kind.ORIGINAL))
    }

    /** 文件名里的非法字符要清掉（书名来自用户文件，什么都可能有）。 */
    @Test
    fun `文件名清洗`() {
        assertEquals("a_b_c", NovelExport.sanitize("a/b:c"))
        assertEquals("novel", NovelExport.sanitize("   "))
        assertEquals("书名", NovelExport.sanitize("书名"))
    }
}
