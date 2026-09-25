package com.moe.starflow.novel.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TxtChapterSplitterTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun txt(name: String, text: String, charset: java.nio.charset.Charset = Charsets.UTF_8): File =
        File(tmp.root, name).apply { writeBytes(text.toByteArray(charset)) }

    // ===== 章节标题识别 =====

    @Test
    fun `标准中文章节标题被识别`() {
        val text = """
            第一章　风起

            正文一

            第二章　云涌

            正文二
        """.trimIndent()
        val chapters = TxtChapterSplitter.split(text)
        assertEquals(2, chapters.size)
        assertEquals("第一章　风起", chapters[0].title)
        assertEquals("第二章　云涌", chapters[1].title)
        assertTrue(chapters[0].charStart < chapters[1].charStart)
    }

    @Test
    fun `阿拉伯数字与常见后缀都识别`() {
        val text = "第1章 开始\n\nA\n\n第 12 话 结束\n\nB\n\n第3卷\n\nC"
        assertEquals(
            listOf("第1章 开始", "第 12 话 结束", "第3卷"),
            TxtChapterSplitter.split(text).map { it.title },
        )
    }

    @Test
    fun `特殊章节名识别`() {
        val text = "序章\n\nA\n\n楔子\n\nB\n\n番外 他们的后来\n\nC"
        assertEquals(
            listOf("序章", "楔子", "番外 他们的后来"),
            TxtChapterSplitter.split(text).map { it.title },
        )
    }

    @Test
    fun `英文 Chapter 与方括号标题识别`() {
        assertEquals(listOf("Chapter 3"), TxtChapterSplitter.split("Chapter 3\n\nA").map { it.title })
        // 方括号保留在标题里：目录显示的就是原文那一行的样子，去掉括号反而要额外解释规则
        assertEquals(listOf("【卷之二】"), TxtChapterSplitter.split("【卷之二】\n\nA").map { it.title })
    }

    /**
     * 正文里出现的「第N章」表述不能被当成章节 —— 正则锚定行首是唯一的防线，
     * 去掉 `^\s*` 这条测试必红。
     */
    @Test
    fun `正文里的数字编号不被误判为章节`() {
        val text = "第一章 开始\n\n他在第 3 章看到过这个说法，那是很久以前的事了。\n\n继续"
        assertEquals(1, TxtChapterSplitter.split(text).size)
    }

    // ===== 兜底切分 =====

    @Test
    fun `无章节标记时按字数兜底切分且标题为第N节`() {
        val body = "这是一段很长的正文。".repeat(2000)   // 20000 字
        val chapters = TxtChapterSplitter.split(body)
        assertTrue("应切出多节，实际 ${chapters.size}", chapters.size >= 2)
        assertTrue(chapters[0].title.startsWith("第"))
        assertTrue(chapters[0].title.endsWith("节"))
    }

    @Test
    fun `兜底切点落在换行处不从句子中间切开`() {
        val sb = StringBuilder()
        repeat(600) { sb.append("第").append(it).append("行内容，凑字数用。\n") }
        val text = sb.toString()
        val chapters = TxtChapterSplitter.split(text)
        assertTrue(chapters.size >= 2)
        val boundary = chapters[0].charEnd
        assertTrue(
            "切点 $boundary 不在换行处",
            boundary == text.length || text[boundary - 1] == '\n',
        )
    }

    @Test
    fun `兜底切分不产生空节`() {
        val chapters = TxtChapterSplitter.split("字".repeat(30000))   // 单行超长，无换行可回退
        assertTrue(chapters.isNotEmpty())
        assertTrue("不该有空节", chapters.all { it.charEnd > it.charStart })
    }

    // ===== 覆盖率硬约束 =====

    /**
     * 所有章节区间按顺序拼接必须**完整覆盖**全文、无重叠、无丢字。
     * 这是「用户不会丢内容」的唯一保证。
     */
    @Test
    fun `章节区间完整覆盖正文无丢字`() {
        val text = "第一章 A\n\n正文一\n\n第二章 B\n\n正文二"
        val chapters = TxtChapterSplitter.split(text)
        assertEquals(0, chapters[0].charStart)
        assertEquals(text.length, chapters.last().charEnd)
        for (i in 0 until chapters.size - 1) {
            assertEquals(chapters[i].charEnd, chapters[i + 1].charStart)
        }
    }

    /**
     * 首个章节标记之前的内容必须单独成节 —— 很多 txt 开头有作者的话/简介，
     * 只按标记切会把这整段静默丢掉（用户看到的就是「开头少了一截」）。
     */
    @Test
    fun `首个标记之前的内容不丢`() {
        val preface = "作者的话：本书是练笔之作，请多包涵。"
        val text = "$preface\n\n第一章 A\n\n正文一"
        val chapters = TxtChapterSplitter.split(text)
        assertEquals(2, chapters.size)
        assertEquals(0, chapters[0].charStart)
        assertEquals("前言", chapters[0].title)
        assertEquals(preface, text.substring(chapters[0].charStart, chapters[0].charEnd).trim())
        assertEquals(text.length, chapters.last().charEnd)
    }

    @Test
    fun `标记前的纯空白不单独成节`() {
        val text = "\n\n  \n\n第一章 A\n\n正文"
        val chapters = TxtChapterSplitter.split(text)
        assertEquals(1, chapters.size)
        assertEquals("第一章 A", chapters[0].title)
    }

    @Test
    fun `空文本返回空列表`() {
        assertEquals(0, TxtChapterSplitter.split("").size)
        assertEquals(0, TxtChapterSplitter.split("   \n\n  ").size)
    }

    @Test
    fun `CRLF 换行也能识别章节`() {
        val text = "第一章 起\r\n\r\n正文一\r\n\r\n第二章 承\r\n\r\n正文二"
        val titles = TxtChapterSplitter.split(text).map { it.title }
        assertEquals(listOf("第一章 起", "第二章 承"), titles)
    }

    // ===== TxtParser =====

    @Test
    fun `解析 txt 给出书名与章节目录`() = runBlocking {
        val f = txt("book.txt", "第一章 起\n\n正文一\n\n第二章 承\n\n正文二")
        val book = TxtParser.parse(f)
        assertEquals("book", book.title)
        assertEquals(2, book.chapters.size)
        assertEquals(listOf(0, 1), book.chapters.map { it.index })
    }

    @Test
    fun `按定位读章返回该章正文`() = runBlocking {
        val f = txt("book.txt", "第一章 起\n\n正文一\n\n第二章 承\n\n正文二")
        val book = TxtParser.parse(f)
        val ch2 = TxtParser.loadChapter(f, book.chapters[1].locator)
        assertTrue("实际：$ch2", ch2.contains("第二章 承"))
        assertTrue(ch2.contains("正文二"))
        assertTrue("不该串到前一章", !ch2.contains("正文一"))
    }

    /** 编码探测要串起来：GBK 文件解析出的章节标题必须是对的（不是乱码）。 */
    @Test
    fun `GBK 编码的 txt 解析出正确章节`() = runBlocking {
        val gbk = java.nio.charset.Charset.forName("GBK")
        val f = txt("gbk.txt", "第一章 风起\n\n正文一\n\n第二章 云涌\n\n正文二", gbk)
        val book = TxtParser.parse(f)
        assertEquals(listOf("第一章 风起", "第二章 云涌"), book.chapters.map { it.title })
        assertTrue(TxtParser.loadChapter(f, book.chapters[0].locator).contains("正文一"))
    }

    @Test
    fun `坏定位退化成整篇而不是抛异常`() = runBlocking {
        val f = txt("book.txt", "第一章 起\n\n正文一")
        val text = TxtParser.loadChapter(f, "garbage")
        assertTrue(text.contains("正文一"))
    }
}
