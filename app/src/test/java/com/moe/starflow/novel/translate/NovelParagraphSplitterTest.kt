package com.moe.starflow.novel.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelParagraphSplitterTest {

    @Test
    fun `双换行分段且段内单换行保留`() {
        val ps = NovelParagraphSplitter.split("第一段\n第一段第二行\n\n第二段")
        assertEquals(2, ps.size)
        assertEquals("第一段\n第一段第二行", ps[0].originalText)
        assertEquals("第二段", ps[1].originalText)
    }

    @Test
    fun `index 是数组下标且连续`() {
        val ps = NovelParagraphSplitter.split("段落一\n\n段落二\n\n段落三")
        assertEquals(listOf(0, 1, 2), ps.map { it.index })
    }

    @Test
    fun `连续空行被压缩且不占 index`() {
        val ps = NovelParagraphSplitter.split("一\n\n\n\n\n二")
        assertEquals(2, ps.size)
        assertEquals(listOf(0, 1), ps.map { it.index })
        assertEquals(listOf("一", "二"), ps.map { it.originalText })
    }

    @Test
    fun `CRLF 被归一`() {
        assertEquals(listOf("一", "二"), NovelParagraphSplitter.split("一\r\n\r\n二").map { it.originalText })
    }

    @Test
    fun `图片占位段被标为 IMAGE`() {
        val ps = NovelParagraphSplitter.split("正文一\n\n📷 [图片]\n\n正文二")
        assertEquals(NovelParagraphType.IMAGE, ps[1].type)
    }

    @Test
    fun `过短段落被标为 SKIP`() {
        val ps = NovelParagraphSplitter.split("正文一\n\n……\n\n正文二")
        assertEquals(NovelParagraphType.SKIP, ps[1].type)
        assertTrue(ps[1].originalText.length < NovelParagraphSplitter.MIN_TEXT_LENGTH)
    }

    /** SKIP / IMAGE 段**照样占 index** —— 它们的译文行也存在于库里，只是状态恒为 IDLE。 */
    @Test
    fun `跳过翻译的段也占 index 保证与译文表对齐`() {
        val ps = NovelParagraphSplitter.split("正文一\n\n……\n\n正文二")
        assertEquals(listOf(0, 1, 2), ps.map { it.index })
        assertEquals(NovelParagraphType.SKIP, ps[1].type)
    }

    @Test
    fun `全部是 TEXT 的文本切出全 TEXT`() {
        val ps = NovelParagraphSplitter.split("这是第一段正文\n\n这是第二段正文")
        assertTrue(ps.all { it.type == NovelParagraphType.TEXT })
    }

    @Test
    fun `空文本返回空列表`() {
        assertEquals(0, NovelParagraphSplitter.split("").size)
        assertEquals(0, NovelParagraphSplitter.split("   \n\n   ").size)
    }

    /**
     * 确定性：同一输入两次切分结果必须**完全一致**。
     * 任何依赖哈希迭代序或 locale 的实现都会在这里炸 —— 而译文错位后是看不出错的。
     */
    @Test
    fun `切分是确定性的`() {
        val text = (1..200).joinToString("\n\n") { "第$it 段的内容，长度不一。".repeat(it % 3 + 1) }
        val a = NovelParagraphSplitter.split(text)
        val b = NovelParagraphSplitter.split(text)
        assertEquals(a, b)
        for (i in a.indices) {
            assertEquals(a[i].index, b[i].index)
            assertEquals(a[i].originalText, b[i].originalText)
            assertEquals(a[i].type, b[i].type)
        }
    }

    /** 切分规则版本是译文失效机制的开关，改成 0 或删掉会让旧译文静默错位。 */
    @Test
    fun `切分版本号是正数`() {
        assertTrue(NovelParagraphSplitter.SPLIT_VERSION >= 1)
    }

    @Test
    fun `段首段尾空白被裁掉`() {
        val ps = NovelParagraphSplitter.split("  正文一  \n\n\t正文二\t")
        assertEquals(listOf("正文一", "正文二"), ps.map { it.originalText })
    }

    @Test
    fun `行首行尾的空格被裁但段内保留`() {
        val ps = NovelParagraphSplitter.split("行一   \n行二   \n\n次段")
        assertEquals("行一\n行二", ps[0].originalText)
    }

    @Test
    fun `只有空白的段被整段丢掉`() {
        val ps = NovelParagraphSplitter.split("正文\n\n   \n\n正文二")
        assertEquals(2, ps.size)
        assertFalse(ps.any { it.originalText.isBlank() })
    }
}
