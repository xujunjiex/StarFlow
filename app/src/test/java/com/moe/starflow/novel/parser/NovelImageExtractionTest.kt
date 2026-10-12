package com.moe.starflow.novel.parser

import com.moe.starflow.novel.translate.NovelParagraphSplitter
import com.moe.starflow.novel.translate.NovelParagraphType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * **插图抽取**的守卫（纯 JVM）。
 *
 * 这里钉的是"图片能不能被找出来、顺序对不对" —— 显示不出来是其次，**顺序错一位**才是灾难：
 * 第 3 章的插图配了第 4 章的脸，而且**看不出错**。所以
 * [HtmlTextExtractor.extractWithImages] 的地址表与正文里的图片标记一一对应这条不变量
 * 必须逐条钉死。
 *
 * ⚠️ 这些用例不验证"能不能解码成位图"（那要 Android 的 `BitmapFactory`，见
 * `NovelChapterRepository`）：用几个"不像标记语言"的字节就够了。
 */
class NovelImageExtractionTest {

    @get:Rule val tmp = TemporaryFolder()

    /** 假图片字节：以 PNG 魔数开头、但不是标记语言（`ZipImages.looksLikeMarkup` 判 false）。 */
    private fun blob(): ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File {
        val f = File(tmp.root, name)
        ZipOutputStream(f.outputStream()).use { zos ->
            entries.forEach { (n, body) ->
                zos.putNextEntry(ZipEntry(n)); zos.write(body); zos.closeEntry()
            }
        }
        return f
    }

    private fun zipOf(name: String, vararg entries: Pair<String, String>): File =
        zip(name, *entries.map { (n, b) -> n to b.toByteArray() }.toTypedArray())

    private fun text(name: String) = name.toByteArray()

    // ===== 标签层：地址抽取 =====

    @Test
    fun `img 的地址按文档顺序抽出`() {
        val html = "<p>前</p><img src=\"a.png\"/><p>中</p><img src='b.jpg'><p>后</p>"
        val ex = HtmlTextExtractor.extractWithImages(html)
        assertEquals(listOf("a.png", "b.jpg"), ex.imageSources)
        assertTrue(ex.text.contains("前"))
        assertTrue(ex.text.contains("后"))
    }

    /** 标签上没写地址的那张也占一格：少一项会让后面所有插图**整体错位一张**。 */
    @Test
    fun `没有地址的图片也占一个位置`() {
        val srcs = HtmlTextExtractor.extractWithImages("<img src=\"a.png\"/><img/><img src=\"c.png\"/>")
            .imageSources
        assertEquals(listOf("a.png", "", "c.png"), srcs)
    }

    /** EPUB3 封面：`<svg><image xlink:href="cover.jpg"/></svg>`（只认 `<img>` 的话封面永远出不来）。 */
    @Test
    fun `svg 里的 image 标签也能抽出地址`() {
        val html = """<div><svg viewBox="0 0 600 800"><image width="600" height="800" xlink:href="cover.jpg"/></svg></div>"""
        assertEquals(listOf("cover.jpg"), HtmlTextExtractor.extractWithImages(html).imageSources)
        // 裸 href 写法（EPUB3 允许）
        assertEquals(
            listOf("c2.png"),
            HtmlTextExtractor.extractWithImages("""<svg><image href="c2.png"/></svg>""").imageSources,
        )
    }

    /** `data-src`（懒加载图片）里的 `src` **不能**被当成真图地址。 */
    @Test
    fun `data-src 里的 src 不算数`() {
        assertEquals(
            listOf("real.png"),
            HtmlTextExtractor.extractWithImages("""<img data-src="lazy.png" src="real.png"/>""").imageSources,
        )
    }

    /** `head` / `script` 里的图**不算**：它们与正文里的标记数必须一致，否则顺序整体错位。 */
    @Test
    fun `head 与 script 里的图片不计入`() {
        val html = "<html><head><img src=\"head.png\"/></head><body>" +
            "<script>var s='<img src=x.png>'</script><p>正文</p><img src=\"body.png\"/></body></html>"
        assertEquals(listOf("body.png"), HtmlTextExtractor.extractWithImages(html).imageSources)
    }

    // ===== 分段层：每张图单独成段 =====

    /**
     * ⚠️ 关键一条：`<p>文字<img/></p>` 必须切成**两段**。
     *
     * 图片标记只带单换行的话，它和相邻文字会被切成**同一段**，而该段被判成 IMAGE →
     * **那半句文字凭空消失**（既没显示成文字，也没显示成图）。
     */
    @Test
    fun `行内图片与文字切成两段`() {
        val ex = HtmlTextExtractor.extractWithImages("<p>前半句话<img src=\"a.png\"/>后半句话</p>")
        val ps = NovelParagraphSplitter.split(ex.text)
        assertEquals(listOf("前半句话", HtmlTextExtractor.IMAGE_MARK, "后半句话"), ps.map { it.originalText })
        assertEquals(
            listOf(NovelParagraphType.TEXT, NovelParagraphType.IMAGE, NovelParagraphType.TEXT),
            ps.map { it.type },
        )
    }

    /** 图片段数 == 抽出的地址数：配对规则（第 i 张图配第 i 个标记）就是靠它成立的。 */
    @Test
    fun `图片段与地址一一对应`() {
        val html = "<p>正文</p><img src=\"1.png\"/><p>正文</p><img src=\"2.png\"/>" +
            "<svg><image xlink:href=\"3.png\"/></svg>"
        val ex = HtmlTextExtractor.extractWithImages(html)
        val imageParas = NovelParagraphSplitter.split(ex.text).filter { it.type == NovelParagraphType.IMAGE }
        assertEquals(3, ex.imageSources.size)
        assertEquals(ex.imageSources.size, imageParas.size)
    }

    // ===== EPUB：读字节 =====

    @Test
    fun `epub 读出章内插图字节（相对路径与上级目录）`() = runBlocking {
        val f = zip(
            "book.epub",
            "OEBPS/Text/ch1.xhtml" to text("<p>正文</p><img src=\"../Images/a.png\"/><img src=\"b.png\"/>"),
            "OEBPS/Images/a.png" to blob(),
            "OEBPS/Text/b.png" to blob(),
        )
        val src = EpubParser.loadChapterSource(f, "OEBPS/Text/ch1.xhtml")
        assertEquals(2, src.images.size)
        assertTrue("相对上级目录的那张要读出来", src.images[0] != null)
        assertTrue("同目录的那张要读出来", src.images[1] != null)
    }

    /** 百分号转义的地址（`Images/封面 1.png`）也要能找到条目。 */
    @Test
    fun `epub 解百分号转义后再找条目`() = runBlocking {
        val f = zip(
            "book2.epub",
            "OEBPS/Text/ch1.xhtml" to text("<img src=\"../Images/%E5%B0%81%E9%9D%A2%201.png\"/>"),
            "OEBPS/Images/封面 1.png" to blob(),
        )
        val src = EpubParser.loadChapterSource(f, "OEBPS/Text/ch1.xhtml")
        assertTrue("转义地址必须解出来", src.images[0] != null)
    }

    /** SVG 包装（`<img src="cover.svg">` → svg 里的位图）也要能读出来。 */
    @Test
    fun `epub 里的 svg 包装往里找一层位图`() = runBlocking {
        val svg = """<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg"><image xlink:href="real.png"/></svg>"""
        val f = zip(
            "book3.epub",
            "OEBPS/Text/ch1.xhtml" to text("<img src=\"../Images/cover.svg\"/>"),
            "OEBPS/Images/cover.svg" to svg.toByteArray(),
            "OEBPS/Images/real.png" to blob(),
        )
        val src = EpubParser.loadChapterSource(f, "OEBPS/Text/ch1.xhtml")
        assertTrue("svg 里那张位图必须被读出来", src.images[0] != null)
    }

    /** 读不到的图返回 null（渲染回落占位文本）——**不能**抛异常、也不能少一项。 */
    @Test
    fun `读不到的插图返回 null 且不改变张数`() = runBlocking {
        val f = zip(
            "book4.epub",
            "OEBPS/Text/ch1.xhtml" to text("<img src=\"missing.png\"/><img src=\"ok.png\"/>"),
            "OEBPS/Text/ok.png" to blob(),
        )
        val src = EpubParser.loadChapterSource(f, "OEBPS/Text/ch1.xhtml")
        assertEquals(2, src.images.size)
        assertNull(src.images[0])
        assertTrue(src.images[1] != null)
    }

    // ===== ZIP 小说包 =====

    @Test
    fun `zip 小说包读出章内插图`() = runBlocking {
        val f = zip(
            "novel.zip",
            "text/ch1.html" to text("<p>正文</p><img src=\"../img/a.png\"/>"),
            "img/a.png" to blob(),
        )
        val src = ZipHtmlParser.loadChapterSource(f, "text/ch1.html")
        assertTrue(src.images[0] != null)
        assertEquals("正文\n\n${HtmlTextExtractor.IMAGE_MARK}", src.text)
    }

    /** `data:` 内嵌小图（base64）。 */
    @Test
    fun `data uri 内嵌图片能解出来`() = runBlocking {
        val b64 = java.util.Base64.getEncoder().encodeToString(blob())
        val f = zipOf("novel2.zip", "ch1.html" to "<img src=\"data:image/png;base64,$b64\"/>")
        val src = ZipHtmlParser.loadChapterSource(f, "ch1.html")
        assertTrue(src.images[0] != null)
    }

    // ===== 本地地址解析的路径穿越守卫 =====

    @Test
    fun `本地路径解析不允许越出书本目录`() {
        val base = tmp.newFolder("book")
        assertNull(ZipImages.resolveLocal(base, "../../etc/passwd"))
        assertTrue(ZipImages.resolveLocal(base, "images/a.png")!!.path.startsWith(base.canonicalPath))
    }

    // ===== 文件夹：html 章节能读到插图（2026-10-04 加）=====
    //
    // 用户口径：「所有支持的格式都支持图片预览」。文件夹导入以前只拷 txt/epub ——
    // 「一堆 html + images/ 目录」这种最常见的分发形态既不认章节、也没有图。

    @Test
    fun `文件夹里的 html 章节能读出插图`() = runBlocking {
        val dir = tmp.newFolder("folder")
        File(dir, "images").mkdirs()
        File(dir, "images/a.png").writeBytes(blob())
        File(dir, "ch2.txt").writeText("第二章正文")
        File(dir, "ch1.html").writeText("<p>第一章正文</p><img src=\"images/a.png\"/>")

        val book = FolderNovelParser.parse(dir)
        assertEquals("html 与 txt 都要成章，且按自然序", listOf("ch1", "ch2"), book.chapters.map { it.title })

        val src = FolderNovelParser.loadChapterSource(dir, "ch1.html")
        assertTrue("正文要读出来", src.text.contains("第一章正文"))
        assertEquals(1, src.images.size)
        assertTrue("夹内 html 的插图必须读得出来（导入时已递归拷进来）", src.images[0] != null)

        // txt 那一章照旧是纯文本，不受 html 分支影响
        val txt = FolderNovelParser.loadChapterSource(dir, "ch2.txt")
        assertTrue(txt.text.contains("第二章正文"))
        assertTrue("txt 章没有图", txt.images.isEmpty())
    }

    /** 章节分类子目录里的 HTML 成章，章节 locator 保留相对路径。 */
    @Test
    fun `章节子目录里的 html 成章`() = runBlocking {
        val dir = tmp.newFolder("folder2")
        File(dir, "text").mkdirs()
        File(dir, "text/ch1.html").writeText("<p>藏在子目录里</p>")
        File(dir, "ch0.txt").writeText("根目录的正文")

        val book = FolderNovelParser.parse(dir)
        assertEquals(listOf("ch0", "text · ch1"), book.chapters.map { it.title })
        assertEquals(listOf("ch0.txt", "text/ch1.html"), book.chapters.map { it.locator })
        assertTrue(FolderNovelParser.loadChapter(dir, book.chapters[1].locator).contains("藏在子目录里"))
    }

    /** 夹内 html 引用的图**不在**时返回 null（渲染回落占位），不能抛也不能改变张数。 */
    @Test
    fun `夹内 html 缺图时返回 null`() = runBlocking {
        val dir = tmp.newFolder("folder3")
        File(dir, "ch1.html").writeText("<img src=\"images/missing.png\"/><img src=\"images/ok.png\"/>")
        File(dir, "images").mkdirs()
        File(dir, "images/ok.png").writeBytes(blob())

        val src = FolderNovelParser.loadChapterSource(dir, "ch1.html")
        assertEquals(2, src.images.size)
        assertNull(src.images[0])
        assertTrue(src.images[1] != null)
    }
}
