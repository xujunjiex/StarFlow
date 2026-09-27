package com.moe.starflow.novel.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpubParserTest {

    @get:Rule val tmp = TemporaryFolder()

    private class EpubBuilder(val file: File) {
        private val out = ZipOutputStream(file.outputStream())

        fun put(name: String, body: String): EpubBuilder {
            out.putNextEntry(ZipEntry(name)); out.write(body.toByteArray()); out.closeEntry()
            return this
        }

        fun finish(): File { out.close(); return file }
    }

    private fun epub(name: String): EpubBuilder = EpubBuilder(File(tmp.root, name))

    private val containerXml = """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>
    """.trimIndent()

    /**
     * manifest 里 c2 排在 c1 前面、文件名也不按顺序 —— 只有 spine 是对的。
     * 这样能一次性守住「章节顺序以 spine 为准」这条。
     */
    private fun opf(
        extraManifest: String = "",
        ncx: String? = "toc.ncx",
        spine: String = """<itemref idref="c1"/><itemref idref="c2"/>""",
    ) = """
        <?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>测试之书</dc:title>
            <dc:creator>某作者</dc:creator>
          </metadata>
          <manifest>
            <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
            <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
            <item id="cover" href="images/cover.png" media-type="image/png" properties="cover-image"/>
            ${if (ncx != null) """<item id="ncx" href="$ncx" media-type="application/x-dtbncx+xml"/>""" else ""}
            $extraManifest
          </manifest>
          <spine ${if (ncx != null) """toc="ncx"""" else ""}>$spine</spine>
        </package>
    """.trimIndent()

    private fun ncx(vararg entries: Pair<String, String>) = """
        <?xml version="1.0"?>
        <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
          <navMap>
            ${entries.joinToString("") { (src, label) -> """
              <navPoint id="n"><navLabel><text>$label</text></navLabel><content src="$src"/></navPoint>
            """.trimIndent() }}
          </navMap>
        </ncx>
    """.trimIndent()

    private fun minimalEpub(ncxBody: String? = null): File {
        val b = epub("book.epub")
            .put("mimetype", "application/epub+zip")
            .put("META-INF/container.xml", containerXml)
            .put("OEBPS/content.opf", opf())
            .put("OEBPS/text/ch1.xhtml", "<html><body><h1>第一章</h1><p>正文一</p></body></html>")
            .put("OEBPS/text/ch2.xhtml", "<html><body><h1>第二章</h1><p>正文二</p></body></html>")
            .put("OEBPS/images/cover.png", "PNGDATA")
        if (ncxBody != null) b.put("OEBPS/toc.ncx", ncxBody)
        return b.finish()
    }

    // ===== 元数据与书目 =====

    @Test
    fun `解析出元数据与书目`() = runBlocking {
        val book = EpubParser.parse(minimalEpub())
        assertEquals("测试之书", book.title)
        assertEquals("某作者", book.author)
        assertEquals(2, book.chapters.size)
    }

    @Test
    fun `章节顺序按 spine 而不是 manifest 顺序或文件名`() = runBlocking {
        val book = EpubParser.parse(minimalEpub())
        assertEquals("OEBPS/text/ch1.xhtml", book.chapters[0].locator)
        assertEquals("OEBPS/text/ch2.xhtml", book.chapters[1].locator)
    }

    @Test
    fun `index 与列表位置一致`() = runBlocking {
        val book = EpubParser.parse(minimalEpub())
        assertEquals(listOf(0, 1), book.chapters.map { it.index })
    }

    @Test
    fun `封面取 cover-image 属性`() = runBlocking {
        val book = EpubParser.parse(minimalEpub())
        assertEquals("PNGDATA", String(book.coverBytes ?: ByteArray(0)))
    }

    // ===== 章标题 =====

    @Test
    fun `有 NCX 时章标题取目录而不是正文`() = runBlocking {
        val f = minimalEpub(
            ncx(
                "text/ch1.xhtml" to "第一章　风起",
                "text/ch2.xhtml" to "第二章　云涌",
            )
        )
        val titles = EpubParser.parse(f).chapters.map { it.title }
        assertEquals(listOf("第一章　风起", "第二章　云涌"), titles)
    }

    /** NCX 的 src 常带锚点（`ch1.xhtml#top`），要能匹配上同一章。 */
    @Test
    fun `NCX 里的锚点被忽略`() = runBlocking {
        val f = minimalEpub(ncx("text/ch1.xhtml#top" to "带锚点的标题"))
        assertEquals("带锚点的标题", EpubParser.parse(f).chapters[0].title)
    }

    /** 没有目录时退化：读每章开头取首个短行（通常是 h1）。 */
    @Test
    fun `没有 NCX 时章标题退化为读正文首行`() = runBlocking {
        val f = epub("noncx.epub")
            .put("META-INF/container.xml", containerXml)
            .put("OEBPS/content.opf", opf(ncx = null))
            .put("OEBPS/text/ch1.xhtml", "<html><body><h1>第一章</h1><p>正文一</p></body></html>")
            .put("OEBPS/text/ch2.xhtml", "<html><body><h1>第二章</h1><p>正文二</p></body></html>")
            .finish()
        assertEquals(listOf("第一章", "第二章"), EpubParser.parse(f).chapters.map { it.title })
    }

    @Test
    fun `章标题兜底为第N章`() = runBlocking {
        // 正文首行超长（>60 字）→ 不作为标题
        val longLine = "字".repeat(80)
        val f = epub("longline.epub")
            .put("META-INF/container.xml", containerXml)
            .put("OEBPS/content.opf", opf(ncx = null, spine = """<itemref idref="c1"/>"""))
            .put("OEBPS/text/ch1.xhtml", "<html><body><p>$longLine</p></body></html>")
            .finish()
        assertEquals("第 1 章", EpubParser.parse(f).chapters[0].title)
    }

    // ===== 正文 =====

    @Test
    fun `读章正文去掉标签且不含 head 里的 title`() = runBlocking {
        val f = epub("head.epub")
            .put("META-INF/container.xml", containerXml)
            .put("OEBPS/content.opf", opf(ncx = null, spine = """<itemref idref="c1"/>"""))
            .put(
                "OEBPS/text/ch1.xhtml",
                "<html><head><title>第一章</title></head><body><h1>第一章</h1><p>正文一</p></body></html>",
            )
            .finish()
        val text = EpubParser.loadChapter(f, "OEBPS/text/ch1.xhtml")
        assertEquals("第一章\n\n正文一", text)
    }

    // ===== 错误与加密 =====

    @Test
    fun `缺 container xml 时抛异常而不是返回空书`() {
        val f = epub("bad.epub").put("mimetype", "application/epub+zip").finish()
        var threw = false
        try {
            runBlocking { EpubParser.parse(f) }
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("损坏的 EPUB 必须抛异常（上层据此报导入失败）", threw)
    }

    @Test
    fun `spine 为空时抛 NO_TEXT_CHAPTER`() {
        val f = epub("nospine.epub")
            .put("META-INF/container.xml", containerXml)
            .put("OEBPS/content.opf", opf(ncx = null, spine = ""))
            .put("OEBPS/text/ch1.xhtml", "<p>一</p>")
            .finish()
        var msg: String? = null
        try {
            runBlocking { EpubParser.parse(f) }
        } catch (e: Exception) {
            msg = e.message
        }
        assertEquals("NO_TEXT_CHAPTER", msg)
    }

    /**
     * 关键：**只加密字体不算 DRM**。商用 EPUB 普遍对字体做混淆，一刀切会挡掉大量合法书。
     */
    @Test
    fun `只加密字体时正常放行`() = runBlocking {
        val f = epub("fontenc.epub")
            .put("META-INF/container.xml", containerXml)
            .put(
                "META-INF/encryption.xml",
                """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                     <EncryptedData><CipherData>
                       <CipherReference URI="OEBPS/fonts/body.otf"/>
                     </CipherData></EncryptedData>
                   </encryption>""".trimIndent(),
            )
            .put("OEBPS/content.opf", opf(ncx = null, spine = """<itemref idref="c1"/>"""))
            .put("OEBPS/text/ch1.xhtml", "<p>正文</p>")
            .finish()
        val book = EpubParser.parse(f)
        assertEquals(1, book.chapters.size)
    }

    @Test
    fun `正文被加密时判为 ENCRYPTED`() {
        val f = epub("drm.epub")
            .put("META-INF/container.xml", containerXml)
            .put(
                "META-INF/encryption.xml",
                """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                     <EncryptedData><CipherData>
                       <CipherReference URI="OEBPS/text/ch1.xhtml"/>
                     </CipherData></EncryptedData>
                   </encryption>""".trimIndent(),
            )
            .put("OEBPS/content.opf", opf(ncx = null, spine = """<itemref idref="c1"/>"""))
            .put("OEBPS/text/ch1.xhtml", "<p>正文</p>")
            .finish()
        var msg: String? = null
        try {
            runBlocking { EpubParser.parse(f) }
        } catch (e: Exception) {
            msg = e.message
        }
        assertEquals("ENCRYPTED", msg)
    }

    @Test
    fun `相对路径里的上级目录被正确解析`() = runBlocking {
        val f = epub("updir.epub")
            .put("META-INF/container.xml", containerXml)
            .put(
                "OEBPS/content.opf",
                opf(ncx = null, spine = """<itemref idref="c1"/>""")
                    .replace("""href="text/ch1.xhtml"""", """href="../text/ch1.xhtml""""),
            )
            .put("text/ch1.xhtml", "<p>正文</p>")
            .finish()
        val book = EpubParser.parse(f)
        assertEquals("text/ch1.xhtml", book.chapters[0].locator)
        assertTrue(EpubParser.loadChapter(f, book.chapters[0].locator).contains("正文"))
    }

    @Test
    fun `缺失章条目返回空串而不是抛异常`() = runBlocking {
        assertEquals("", EpubParser.loadChapter(minimalEpub(), "nope.xhtml"))
    }

    @Test
    fun `没有封面时 coverBytes 为 null`() = runBlocking {
        val f = epub("nocover.epub")
            .put("META-INF/container.xml", containerXml)
            .put(
                "OEBPS/content.opf",
                opf(ncx = null, spine = """<itemref idref="c1"/>"""),
            )
            .put("OEBPS/text/ch1.xhtml", "<p>正文</p>")
            .finish()
        assertNull(EpubParser.parse(f).coverBytes)
    }

    /**
     * 二级兜底：EPUB2 的 `<meta name="cover" content="id"/>`。
     *
     * ⚠️ id 与文件名**刻意都不含 "cover"**（`cvr` / `images/c.png`）：否则三级兜底
     * （id/href 含 cover 的图片条目）会顺手把它捞出来，这条测试就测不到二级了。
     */
    @Test
    fun `EPUB2 的 meta cover 指向的图片被当作封面`() = runBlocking {
        val epub2Opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>老书</dc:title>
                <meta name="cover" content="cvr"/>
              </metadata>
              <manifest>
                <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="cvr" href="images/c.png" media-type="image/png"/>
              </manifest>
              <spine><itemref idref="c1"/></spine>
            </package>
        """.trimIndent()
        val f = epub("epub2cover.epub")
            .put("META-INF/container.xml", containerXml)
            .put("OEBPS/content.opf", epub2Opf)
            .put("OEBPS/text/ch1.xhtml", "<p>正文</p>")
            .put("OEBPS/images/c.png", "EPUB2COVER")
            .finish()
        assertEquals("EPUB2COVER", String(EpubParser.parse(f).coverBytes ?: ByteArray(0)))
    }

    /**
     * 三级兜底：manifest 里 id / 文件名含 "cover" 的图片条目（老书的常见写法）。
     */
    @Test
    fun `没有 cover 标记时按文件名兜底取封面`() = runBlocking {
        val noMarkOpf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>老书</dc:title></metadata>
              <manifest>
                <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="img1" href="images/cover.png" media-type="image/png"/>
              </manifest>
              <spine><itemref idref="c1"/></spine>
            </package>
        """.trimIndent()
        val f = epub("namecover.epub")
            .put("META-INF/container.xml", containerXml)
            .put("OEBPS/content.opf", noMarkOpf)
            .put("OEBPS/text/ch1.xhtml", "<p>正文</p>")
            .put("OEBPS/images/cover.png", "BYNAMECOVER")
            .finish()
        assertEquals("BYNAMECOVER", String(EpubParser.parse(f).coverBytes ?: ByteArray(0)))
    }

    // ===== EPUB3 nav 目录（没有 NCX 时） =====

    /**
     * EPUB3 只有 nav 文档（`properties="nav"`）而没有 NCX 时，章标题必须来自 nav。
     * 正文里的 h1 刻意与 nav 不同名，用来区分「标题取自目录」还是「退化成读正文首行」。
     */
    @Test
    fun `只有 EPUB3 nav 时章标题取自 nav`() = runBlocking {
        val navItem =
            """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>"""
        val navDoc = """
            <?xml version="1.0" encoding="utf-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body><nav><ol>
                <li><a href="text/ch1.xhtml">导航标题一</a></li>
                <li><a href="text/ch2.xhtml">导航标题二</a></li>
              </ol></nav></body>
            </html>
        """.trimIndent()
        val f = epub("navonly.epub")
            .put("META-INF/container.xml", containerXml)
            .put("OEBPS/content.opf", opf(extraManifest = navItem, ncx = null))
            .put("OEBPS/nav.xhtml", navDoc)
            .put("OEBPS/text/ch1.xhtml", "<html><body><h1>正文标题一</h1><p>一</p></body></html>")
            .put("OEBPS/text/ch2.xhtml", "<html><body><h1>正文标题二</h1><p>二</p></body></html>")
            .finish()
        assertEquals(
            listOf("导航标题一", "导航标题二"),
            EpubParser.parse(f).chapters.map { it.title },
        )
    }

    // ===== 读取上限 =====

    /**
     * 单条目上限：目录条目 9MB > [NovelReadLimits.MAX_ZIP_ENTRY_BYTES]（8MB）。
     *
     * 这是**导入路径**（parse 会读 container/opf/ncx），所以必须归类成导入失败：
     * 抛的是 `ZipException` —— `NovelImportManager.classify` 把 `ZipException` 归为
     * NOT_ARCHIVE（用户看到「格式不支持或文件损坏」），而不是让进程 OOM 被杀。
     */
    @Test
    fun `超大条目按导入失败抛出而不是 OOM`() {
        // UTF-8 下汉字 3 字节：3.1M 字 ≈ 9.3MB，压缩后仍很小（正是 zip 炸弹的形状）
        val hugeNcx = "<text>" + "字".repeat(3_100_000)
        val f = minimalEpub(hugeNcx)
        var e: Exception? = null
        try {
            runBlocking { EpubParser.parse(f) }
        } catch (ex: Exception) {
            e = ex
        }
        assertTrue(
            "超限必须抛 ZipException（classify → NOT_ARCHIVE），实际: $e",
            e is java.util.zip.ZipException,
        )
    }

    /** 读正文那条路（翻页）同样有上限，不能只有 parse 封顶。 */
    @Test
    fun `单章正文超过条目上限时抛异常`() {
        val hugeBody = "<p>" + "字".repeat(3_100_000) + "</p>"
        val f = epub("hugechapter.epub")
            .put("META-INF/container.xml", containerXml)
            .put("OEBPS/content.opf", opf(ncx = null, spine = """<itemref idref="c1"/>"""))
            .put("OEBPS/text/ch1.xhtml", hugeBody)
            .finish()
        var e: Exception? = null
        try {
            runBlocking { EpubParser.loadChapter(f, "OEBPS/text/ch1.xhtml") }
        } catch (ex: Exception) {
            e = ex
        }
        assertTrue("超限必须抛 ZipException，实际: $e", e is java.util.zip.ZipException)
    }
}
