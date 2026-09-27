package com.moe.starflow.novel.parser

import com.moe.starflow.novel.model.NovelFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.Charset

/**
 * **真格式样本**的端到端解析守卫。
 *
 * 与其余测试的区别：那些用代码现搭字符串，这里读的是磁盘上真实的文件字节 ——
 * 编码、BOM、zip 结构、EPUB 目录全都是真的。**编码相关的 bug 只有真字节能抓到**
 * （在源码里写 `"中文".toByteArray(Charsets.UTF_8)` 永远测不出 GBK 文件读成乱码）。
 *
 * 样本由 `docs/` 外的脚本生成，同一套样本也用于真机演示（见 README）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelFixturesTest {

    private fun fixture(name: String): File {
        val url = javaClass.classLoader!!.getResource("novel-fixtures/$name")
            ?: error("样本缺失: $name")
        return File(url.toURI())
    }

    // ===== TXT：三种编码都要解对 =====

    @Test
    fun `UTF8 简体样本解出六个正确章节`() = runBlocking {
        val book = TxtParser.parse(fixture("simp-utf8.txt"))
        assertEquals(6, book.chapters.size)
        assertEquals("第一章　雾港的清晨", book.chapters[0].title)
        assertEquals("第六章　出港", book.chapters[5].title)
        val first = TxtParser.loadChapter(fixture("simp-utf8.txt"), book.chapters[0].locator)
        assertTrue(first.contains("雾还没散"))
    }

    /**
     * GBK 样本：这是国内网文最常见的编码。若编码探测失效，这里会解出整片乱码
     * （而且**不会抛异常** —— 只是用户看到一堆问号和方块）。
     */
    @Test
    fun `GBK 简体样本解出与 UTF8 样本相同的章节标题`() = runBlocking {
        val gbkBook = TxtParser.parse(fixture("simp-gbk.txt"))
        val utf8Book = TxtParser.parse(fixture("simp-utf8.txt"))
        assertEquals(utf8Book.chapters.map { it.title }, gbkBook.chapters.map { it.title })
        val ch1 = TxtParser.loadChapter(fixture("simp-gbk.txt"), gbkBook.chapters[0].locator)
        assertEquals(
            TxtParser.loadChapter(fixture("simp-utf8.txt"), utf8Book.chapters[0].locator),
            ch1,
        )
        assertTrue("GBK 样本绝不能解出乱码", ch1.contains("雾还没散"))
    }

    /** 繁体 Big5 样本：私用区判据的端到端验证（判错了会整篇是私用区乱码）。 */
    @Test
    fun `繁体 Big5 样本解出正确繁体且不含私用区乱码`() = runBlocking {
        val book = TxtParser.parse(fixture("trad-big5.txt"))
        assertEquals(6, book.chapters.size)
        assertEquals("第一章　霧港的清晨", book.chapters[0].title)
        val ch1 = TxtParser.loadChapter(fixture("trad-big5.txt"), book.chapters[0].locator)
        assertTrue("实际：$ch1", ch1.contains("霧還沒散"))
        val private = ch1.count { it.code in 0xE000..0xF8FF }
        assertEquals("解出的正文里不该有私用区字符（那是 GB18030 硬解 Big5 的特征）", 0, private)
    }

    /** 首个章节标记之前的内容不能丢（样本开头有「作者的话」与「更新说明」）。 */
    @Test
    fun `带前言的样本首个标记前内容单独成节`() = runBlocking {
        val book = TxtParser.parse(fixture("preface-simp-utf8.txt"))
        // 前言 + 序章 + 六章
        assertEquals(8, book.chapters.size)
        assertEquals("前言", book.chapters[0].title)
        val preface = TxtParser.loadChapter(fixture("preface-simp-utf8.txt"), book.chapters[0].locator)
        assertTrue(preface.contains("作者的话"))
        assertTrue(preface.contains("更新说明"))
    }

    @Test
    fun `纯 ASCII 样本不误判为中文编码且按 Chapter 切章`() = runBlocking {
        val book = TxtParser.parse(fixture("ascii.txt"))
        assertEquals(2, book.chapters.size)
        assertEquals("Chapter 1", book.chapters[0].title)
        assertTrue(TxtParser.loadChapter(fixture("ascii.txt"), book.chapters[0].locator).contains("foggy"))
    }

    // ===== 格式判定 =====

    @Test
    fun `样本文件的格式判定全部正确`() {
        assertEquals(NovelFormat.TXT, NovelFormatDetector.detect(fixture("simp-utf8.txt")))
        assertEquals(NovelFormat.TXT, NovelFormatDetector.detect(fixture("simp-gbk.txt")))
        assertEquals(NovelFormat.TXT, NovelFormatDetector.detect(fixture("trad-big5.txt")))
        assertEquals(NovelFormat.EPUB, NovelFormatDetector.detect(fixture("mini.epub")))
        assertEquals("含 html 章与封面图的包要判成小说包（文本优先）", NovelFormat.ZIP_HTML, NovelFormatDetector.detect(fixture("html-pack.zip")))
        assertNull("纯图片包判成「不是小说」", NovelFormatDetector.detect(fixture("manga-pack.zip")))
    }

    // ===== EPUB =====

    @Test
    fun `EPUB 样本解析出元数据目录与封面`() = runBlocking {
        val book = EpubParser.parse(fixture("mini.epub"))
        assertEquals("雾港", book.title)
        assertEquals("测试作者", book.author)
        assertEquals(6, book.chapters.size)
        assertEquals("第一章　雾港的清晨", book.chapters[0].title)
        assertEquals("OEBPS/text/ch01.xhtml", book.chapters[0].locator)
        assertTrue("内嵌封面要能取到", (book.coverBytes?.size ?: 0) > 0)
    }

    @Test
    fun `EPUB 样本读出的正文不含 head 里的 title`() = runBlocking {
        val text = EpubParser.loadChapter(fixture("mini.epub"), "OEBPS/text/ch01.xhtml")
        assertTrue(text.contains("雾还没散"))
        // head 里是 <title>第一章　雾港的清晨</title>，正文 h1 同名 —— 只应出现一次
        assertEquals("章节标题只该出现一次（head 的 title 不能漏进正文）", 1, Regex("第一章　雾港的清晨").findAll(text).count())
    }

    // ===== ZIP 小说包 =====

    /**
     * 包内的文本条目都成章，含 `readme.txt`（样本里特意放了它）。
     *
     * 为什么不做「按文件名黑名单过滤 readme/说明」：黑名单永远列不全（`説明.txt`、`簡介.txt`…），
     * 而且一旦漏过滤用户会疑惑「我包里的文件怎么没进来」—— 多一章垃圾章是可见且可删的，
     * 少一章内容是用户发现不了的。
     */
    @Test
    fun `ZIP 小说包样本按自然序给出章节`() = runBlocking {
        val book = ZipHtmlParser.parse(fixture("html-pack.zip"))
        assertEquals("6 个 html 章 + readme.txt", 7, book.chapters.size)
        assertEquals("readme", book.chapters[0].title)
        assertEquals(listOf("ch01", "ch02", "ch03", "ch04", "ch05", "ch06"), book.chapters.drop(1).map { it.title })
        val ch1 = ZipHtmlParser.loadChapter(fixture("html-pack.zip"), "text/ch01.html")
        assertTrue("实际：${ch1.take(40)}", ch1.startsWith("第一章　雾港的清晨\n\n雾还没散"))
    }

    /** zip 里混着 readme.txt 与 cover.jpg，仍应按文本条目正常成章（不能被图片否决）。 */
    @Test
    fun `ZIP 包的 readme 与封面不影响章节解析`() = runBlocking {
        val book = ZipHtmlParser.parse(fixture("html-pack.zip"))
        assertTrue("readme.txt 也是一个文本条目，允许成章", book.chapters.any { it.locator == "readme.txt" })
        assertTrue("封面图不该被当成章节", book.chapters.none { it.locator == "cover.jpg" })
    }

    // ===== 漫画包拦截 =====

    @Test
    fun `漫画包被判成漫画而不是文本`() {
        val archive = fixture("manga-pack.zip")
        assertNull(NovelFormatDetector.detect(archive))
        assertTrue(
            "要能被认出「这是漫画」以便给出对得上的提示",
            com.moe.starflow.mangaimport.data.ArchivedMangaReader.listImageFilesInArchive(archive).isNotEmpty(),
        )
    }

    // ===== 编码探测的两条底线 =====

    @Test
    fun `探测结果与样本真实编码一致`() {
        assertEquals(Charsets.UTF_8, TextEncoding.detect(fixture("simp-utf8.txt").readBytes()))
        assertEquals(
            "GBK 样本应由 GB18030 解出（GBK 是其子集，同一批字节解码结果一致）",
            Charset.forName("GB18030"),
            TextEncoding.detect(fixture("simp-gbk.txt").readBytes()),
        )
        assertEquals(
            "繁体样本必须判成 Big5，否则整篇是私用区乱码",
            Charset.forName("Big5"),
            TextEncoding.detect(fixture("trad-big5.txt").readBytes()),
        )
    }
}
