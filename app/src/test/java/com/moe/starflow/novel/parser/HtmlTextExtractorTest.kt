package com.moe.starflow.novel.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HtmlTextExtractorTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun zip(name: String, vararg entries: Pair<String, String>): File {
        val f = File(tmp.root, name)
        ZipOutputStream(f.outputStream()).use { zos ->
            entries.forEach { (n, body) ->
                zos.putNextEntry(ZipEntry(n)); zos.write(body.toByteArray()); zos.closeEntry()
            }
        }
        return f
    }

    // ===== 标签 → 文本 =====

    @Test
    fun `段落标签转成空行分隔`() {
        assertEquals("第一段\n\n第二段", HtmlTextExtractor.extract("<p>第一段</p><p>第二段</p>"))
    }

    @Test
    fun `br 转单换行`() {
        assertEquals("上行\n下行", HtmlTextExtractor.extract("上行<br/>下行"))
    }

    @Test
    fun `标题标签当段落处理`() {
        assertEquals("第一章\n\n正文", HtmlTextExtractor.extract("<h1>第一章</h1><p>正文</p>"))
    }

    @Test
    fun `script 与 style 被剔除`() {
        val html = "<style>p{color:red}</style><script>var a=1;</script><p>正文</p>"
        assertEquals("正文", HtmlTextExtractor.extract(html))
    }

    /**
     * `head` 整段要被剥掉。EPUB 每章 XHTML 的 head 都带 `<title>`，不剥的话**每章正文开头
     * 都多出一行书名/章名**，而那行还会被当正文送去翻译。
     */
    @Test
    fun `head 里的 title 不会漏进正文`() {
        val html = "<html><head><title>书名</title><meta charset=\"utf-8\"/></head><body><p>正文</p></body></html>"
        assertEquals("正文", HtmlTextExtractor.extract(html))
    }

    /** 畸形文档（head 没闭合）时宁可留着，也不能把整个正文吃掉。 */
    @Test
    fun `head 未闭合时不吞掉正文`() {
        val out = HtmlTextExtractor.extract("<html><head><title>书名</title><body><p>正文</p></body></html>")
        assertTrue("实际: $out", out.contains("正文"))
    }

    @Test
    fun `img 转成占位标记`() {
        val out = HtmlTextExtractor.extract("<p>前</p><img src=\"a.png\"/><p>后</p>")
        assertTrue("实际: $out", out.contains(HtmlTextExtractor.IMAGE_MARK))
        assertFalse(out.contains("<img"))
    }

    @Test
    fun `连续空行压缩为最多两个换行`() {
        val out = HtmlTextExtractor.extract("<p>一</p><br/><br/><br/><p>二</p>")
        assertFalse("实际: ${out.replace("\n", "\\n")}", out.contains("\n\n\n"))
    }

    // ===== 实体解码 =====

    @Test
    fun `常用命名实体被解码`() {
        assertEquals(
            "A & B \"引号\" 空格",
            HtmlTextExtractor.extract("<p>A &amp; B &quot;引号&quot; &nbsp;空格</p>"),
        )
    }

    @Test
    fun `数字实体被解码`() {
        assertEquals("'引号'…", HtmlTextExtractor.extract("<p>&#39;引号&#39;&hellip;</p>"))
        assertEquals("A", HtmlTextExtractor.extract("<p>&#x41;</p>"))
    }

    /**
     * 关键守卫：**不得二次解码**。
     *
     * 原文里的 `&amp;lt;` 想表达的是**字面量四个字符** `&lt;`；串行 `replace` 的写法会先还原
     * `&amp;` 得到 `&lt;`，再被 `&lt;` 规则吃掉变成 `<` —— 于是正文里的 HTML 示例文本
     * 凭空变成一个尖括号。单趟解码不会出这个问题。
     */
    @Test
    fun `实体不被二次解码`() {
        assertEquals("&lt;div&gt;", HtmlTextExtractor.extract("<p>&amp;lt;div&amp;gt;</p>"))
        assertEquals("&#39;", HtmlTextExtractor.extract("<p>&amp;#39;</p>"))
    }

    @Test
    fun `认不出的实体原样保留而不是吞掉`() {
        assertEquals("&foo; bar", HtmlTextExtractor.extract("<p>&foo; bar</p>"))
    }

    // ===== 元数据与边界 =====

    @Test
    fun `取 title`() {
        assertEquals("书名", HtmlTextExtractor.titleOf("<html><head><title>书名</title></head></html>"))
        assertNull(HtmlTextExtractor.titleOf("<p>没有标题</p>"))
    }

    @Test
    fun `空 HTML 返回空串`() {
        assertEquals("", HtmlTextExtractor.extract(""))
        assertEquals("", HtmlTextExtractor.extract("<html><body></body></html>"))
    }

    /** 电子书里未闭合的标签很常见，不该因此读不出正文。 */
    @Test
    fun `未闭合标签也能抽出正文`() {
        val out = HtmlTextExtractor.extract("<p>第一段<p>第二段<div>第三段")
        assertTrue(out.contains("第一段"))
        assertTrue(out.contains("第二段"))
        assertTrue(out.contains("第三段"))
        assertFalse(out.contains("<p"))
    }

    /**
     * 病态输入守卫：5 万个未闭合的 `<script` 标签。
     *
     * 旧实现是 `<(script|style)\b[^>]*>.*?</\1>` 与 `<[^>]+>` 两条正则，共同形状是「后面必须有
     * 一个 `>` 或 `</x>` 才收口」，而引擎不会为它预扫 —— 每个起始 `<` / `<script` 都要把后面
     * 整篇重扫一遍再回溯。实测 400KB 的这类输入在旧实现上要 **21 秒**（`<div ` / `<h1` 那种
     * 未闭合标签更糟），导入 / 翻页线程被钉死，用户看到的就是「卡住」。
     *
     * 两种形状都钉：`<script `（连 `>` 都没有，考验开标签扫描）与 `<script>`（有开无闭，
     * 考验闭合标签查找）。阈值 5 秒很宽松：线性扫描实测几十毫秒，而旧实现 21 秒起步。
     */
    @Test
    fun `未闭合 script 的畸形文档不会退化成二次扫描`() {
        listOf("<script ", "<script>").forEach { token ->
            val html = "<p>正文</p>" + token.repeat(50_000)
            val start = System.nanoTime()
            val out = HtmlTextExtractor.extract(html)
            val ms = (System.nanoTime() - start) / 1_000_000
            assertTrue("「$token」x50k 应在 5s 内返回，实际 ${ms}ms", ms < 5_000)
            assertTrue("「$token」时正文不能被吃掉: $out", out.contains("正文"))
        }
    }

    // ===== HtmlParser =====

    @Test
    fun `单 html 整篇一章且标题取 title`() = runBlocking {
        val f = File(tmp.root, "a.html").apply {
            writeText("<html><head><title>书名</title></head><body><p>正文</p></body></html>")
        }
        val book = HtmlParser.parse(f)
        assertEquals("书名", book.title)
        assertEquals(1, book.chapters.size)
        assertEquals("书名", book.chapters[0].title)
        assertEquals("正文", HtmlParser.loadChapter(f, book.chapters[0].locator))
    }

    @Test
    fun `单 html 没有 title 时退回文件名`() = runBlocking {
        val f = File(tmp.root, "无标题.html").apply { writeText("<p>正文</p>") }
        assertEquals("无标题", HtmlParser.parse(f).title)
    }

    // ===== ZipHtmlParser =====

    /**
     * 自然序是硬要求：字典序会把 `ch10` 排到 `ch2` 前面，用户读到第 2 章直接跳第 10 章。
     */
    @Test
    fun `zip 小说包按自然序排章而不是字典序`() = runBlocking {
        val f = zip(
            "novel.zip",
            "ch1.html" to "<p>一</p>",
            "ch10.html" to "<p>十</p>",
            "ch2.html" to "<p>二</p>",
        )
        val titles = ZipHtmlParser.parse(f).chapters.map { it.title }
        assertEquals(listOf("ch1", "ch2", "ch10"), titles)
    }

    @Test
    fun `zip 里只有文本条目才算章 目录条目被忽略`() = runBlocking {
        val f = File(tmp.root, "n2.zip")
        ZipOutputStream(f.outputStream()).use { zos ->
            fun put(n: String, b: String) {
                zos.putNextEntry(ZipEntry(n)); zos.write(b.toByteArray()); zos.closeEntry()
            }
            put("text/", "")
            put("text/ch1.html", "<p>一</p>")
            put("text/ch2.xhtml", "<p>二</p>")
            put("cover.jpg", "notreallyanimage")
        }
        assertEquals(2, ZipHtmlParser.parse(f).chapters.size)
    }

    /** `.txt` 条目是纯文本，不能再走 HTML 提取（否则正文里的 `<` 会被当标签吃掉）。 */
    @Test
    fun `zip 里的 txt 条目按纯文本读`() = runBlocking {
        val f = zip("n3.zip", "1.txt" to "条件是 a < b 且 c > d")
        val book = ZipHtmlParser.parse(f)
        val text = ZipHtmlParser.loadChapter(f, book.chapters[0].locator)
        assertTrue("实际: $text", text.contains("a < b"))
    }

    @Test
    fun `zip 里 html 条目被转成纯文本`() = runBlocking {
        val f = zip("n4.zip", "ch1.html" to "<h1>第一章</h1><p>正文一</p>")
        val book = ZipHtmlParser.parse(f)
        val text = ZipHtmlParser.loadChapter(f, book.chapters[0].locator)
        assertEquals("第一章\n\n正文一", text)
    }

    @Test
    fun `缺失条目返回空串而不是抛异常`() = runBlocking {
        val f = zip("n5.zip", "ch1.html" to "<p>一</p>")
        assertEquals("", ZipHtmlParser.loadChapter(f, "does/not/exist.html"))
    }
}
