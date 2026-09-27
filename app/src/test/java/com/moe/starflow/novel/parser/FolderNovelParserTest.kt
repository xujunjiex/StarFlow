package com.moe.starflow.novel.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 文件夹子（整个夹 = 一部小说，夹内每个 txt = 一章）。
 *
 * 这条语义是用户明确要求的，且**推翻了早期实现**（那时是「夹内每个文件一部书」）——
 * 那种语义在「一本小说被拆成 300 个 txt」时会变出 300 本书。这里把新语义钉死。
 */
// 夹内 epub 那一组要跑 EpubParser（内部用 android.util.Xml），纯 JVM 单测里会 "not mocked"
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderNovelParserTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun dir(name: String = "novel"): File = File(tmp.root, name).apply { mkdirs() }

    private fun File.w(name: String, text: String, enc: java.nio.charset.Charset = Charsets.UTF_8) =
        File(this, name).writeBytes(text.toByteArray(enc))

    @Test
    fun `夹内每个 txt 是一章`() = runBlocking {
        val d = dir()
        d.w("01 雾港.txt", "第一章正文")
        d.w("02 旧信.txt", "第二章正文")
        d.w("03 潮汐.txt", "第三章正文")

        val book = FolderNovelParser.parse(d)
        assertEquals("整个夹 = 一部小说，不是三本", 3, book.chapters.size)
        assertEquals(listOf("01 雾港", "02 旧信", "03 潮汐"), book.chapters.map { it.title })
        assertEquals(listOf(0, 1, 2), book.chapters.map { it.index })
        assertEquals("夹名就是书名", "novel", book.title)
    }

    /** 字典序会把 `第10章` 排到 `第2章` 前面 —— 必须自然序。 */
    @Test
    fun `章节按自然序排列`() = runBlocking {
        val d = dir()
        listOf("第10章.txt", "第2章.txt", "第1章.txt").forEach { d.w(it, "正文") }
        assertEquals(
            listOf("第1章", "第2章", "第10章"),
            FolderNovelParser.parse(d).chapters.map { it.title },
        )
    }

    @Test
    fun `按 locator 读到各章各自的内容`() = runBlocking {
        val d = dir()
        d.w("a.txt", "甲的内容")
        d.w("b.txt", "乙的内容")
        val book = FolderNovelParser.parse(d)
        assertEquals("甲的内容", FolderNovelParser.loadChapter(d, book.chapters[0].locator))
        assertEquals("乙的内容", FolderNovelParser.loadChapter(d, book.chapters[1].locator))
    }

    @Test
    fun `夹内非小说文件被忽略`() = runBlocking {
        val d = dir()
        d.w("01.txt", "正文")
        d.w("封面.jpg", "notreallyanimage")
        d.w("说明.pdf", "x")
        assertEquals(1, FolderNovelParser.parse(d).chapters.size)
    }

    /** 子目录不递归：递归会把「夹里套了一堆杂七杂八」也变成章节。 */
    @Test
    fun `子目录里的文件不算章节`() = runBlocking {
        val d = dir()
        d.w("01.txt", "正文")
        val sub = File(d, "sub").apply { mkdirs() }
        File(sub, "02.txt").writeText("子目录里的")
        assertEquals(listOf("01"), FolderNovelParser.parse(d).chapters.map { it.title })
    }

    /** 编码探测要串起来：GBK 的章文件名与内容都要正常。 */
    @Test
    fun `夹内 GBK 文件按编码探测正确解码`() = runBlocking {
        val d = dir()
        val gbk = java.nio.charset.Charset.forName("GBK")
        d.w("第一章.txt", "雾还没散，港口的钟响了七下。", gbk)
        val book = FolderNovelParser.parse(d)
        val text = FolderNovelParser.loadChapter(d, book.chapters[0].locator)
        assertEquals("雾还没散，港口的钟响了七下。", text)
    }

    /** 空夹 / 只有非小说文件 → 报 NO_TEXT_CHAPTER（上层据此给「未找到可阅读的文本章节」）。 */
    @Test
    fun `空夹抛 NO_TEXT_CHAPTER`() {
        val d = dir()
        var msg: String? = null
        try {
            runBlocking { FolderNovelParser.parse(d) }
        } catch (e: Exception) {
            msg = e.message
        }
        assertEquals("NO_TEXT_CHAPTER", msg)
    }

    @Test
    fun `只有图片的夹抛 NO_TEXT_CHAPTER`() {
        val d = dir()
        d.w("a.jpg", "x")
        d.w("b.png", "y")
        var msg: String? = null
        try {
            runBlocking { FolderNovelParser.parse(d) }
        } catch (e: Exception) {
            msg = e.message
        }
        assertEquals("NO_TEXT_CHAPTER", msg)
    }

    @Test
    fun `传入文件而不是目录时抛 NO_TEXT_CHAPTER`() {
        val f = File(tmp.root, "notadir.txt").apply { writeText("x") }
        var msg: String? = null
        try {
            runBlocking { FolderNovelParser.parse(f) }
        } catch (e: Exception) {
            msg = e.message
        }
        assertEquals("NO_TEXT_CHAPTER", msg)
    }

    // ===== 夹内 epub 当一卷 =====

    /** 「一部小说 = 若干卷 epub + 若干散章 txt」这种下载形态要能读。 */
    @Test
    fun `夹内 epub 展开成多章并带卷名前缀`() = runBlocking {
        val d = dir()
        d.w("01 散章.txt", "散章正文")
        val epub = File(d, "02 第一卷.epub")
        java.util.zip.ZipOutputStream(epub.outputStream()).use { z ->
            fun put(n: String, b: String) {
                z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(b.toByteArray()); z.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put(
                "META-INF/container.xml",
                """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles>
                   <rootfile full-path="c.opf"/></rootfiles></container>""",
            )
            put(
                "c.opf",
                """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
                   <metadata><dc:title>卷名</dc:title></metadata>
                   <manifest><item id="a" href="a.xhtml"/><item id="b" href="b.xhtml"/></manifest>
                   <spine><itemref idref="a"/><itemref idref="b"/></spine></package>""",
            )
            put("a.xhtml", "<h1>甲章</h1><p>甲正文</p>")
            put("b.xhtml", "<h1>乙章</h1><p>乙正文</p>")
        }

        val book = FolderNovelParser.parse(d)
        assertEquals("1 个散章 + 卷内 2 章", 3, book.chapters.size)
        assertEquals("01 散章", book.chapters[0].title)
        assertEquals("02 第一卷 · 甲章", book.chapters[1].title)
        assertEquals("02 第一卷 · 乙章", book.chapters[2].title)
        assertEquals(listOf(0, 1, 2), book.chapters.map { it.index })

        assertEquals("散章正文", FolderNovelParser.loadChapter(d, book.chapters[0].locator))
        assertEquals("甲章\n\n甲正文", FolderNovelParser.loadChapter(d, book.chapters[1].locator))
        assertEquals("乙章\n\n乙正文", FolderNovelParser.loadChapter(d, book.chapters[2].locator))
    }

    @Test
    fun `缺失的章节文件返回空串而不是抛异常`() = runBlocking {
        val d = dir()
        d.w("a.txt", "x")
        assertTrue(FolderNovelParser.loadChapter(d, "nope.txt").isEmpty())
    }

    /**
     * 坏卷（下载被截断的 epub）只丢自己那一卷：别的卷与散章照常导入，整本书**不能**导入失败。
     *
     * 但「跳过」必须留痕（`LogCollector.w`）—— 静默丢卷时书照样「导入成功」，用户只会发现
     * 凭空少了几章却无从查起。这里用「坏卷被跳过 + 兄弟章节都在」钉住行为（日志由实现保证，
     * 测试不去断言 logger）。
     */
    @Test
    fun `夹内损坏的 epub 卷被跳过且不影响其它章节`() = runBlocking {
        val d = dir()
        d.w("01 散章.txt", "第一章正文")
        // 截断的 zip 头：ZipFile 打开就抛 ZipException
        File(d, "02 坏卷.epub").writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x01, 0x02, 0x03))
        d.w("03 散章.txt", "第三章正文")

        val book = FolderNovelParser.parse(d)
        assertEquals("坏卷一章都不能出现", listOf("01 散章", "03 散章"), book.chapters.map { it.title })
        assertEquals("跨过坏卷后 index 依然连续", listOf(0, 1), book.chapters.map { it.index })
        assertEquals("第三章正文", FolderNovelParser.loadChapter(d, book.chapters[1].locator))
    }
}
