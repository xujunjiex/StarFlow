package com.moe.starflow.novel.reader

import android.graphics.Bitmap
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.data.NovelStorageDir
import com.moe.starflow.novel.model.NovelFormat
import com.moe.starflow.novel.translate.NovelParagraphType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * **端到端**：一本 epub 从压缩包 → 插图落盘 → 量尺寸 → 进 `ChapterContent.images` → 参与分页。
 *
 * 这是"图片到底显不显示得出来"这条链路上唯一能在本地跑通全流程的守卫（真机 UI 层只能看效果）。
 * ⚠️ 要 Robolectric：`BitmapFactory` 是 Android 类（量图片尺寸就靠它）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelChapterImageTest {

    @get:Rule val tmp = TemporaryFolder()

    private val style = NovelTextStyle(
        fontSizePx = 40f,
        lineSpacingMultiplier = 1.5f,
        paragraphSpacingPx = 16f,
        paddingPx = 20f,
        topPaddingPx = 0f,
        bottomPaddingPx = 0f,
    )

    /** 真 PNG（Robolectric 能从字节里读出宽高，不是那种"占位 100×100"）。 */
    private fun pngBytes(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun epub(vararg extra: Pair<String, ByteArray>): File {
        val f = File(tmp.root, "book.epub")
        ZipOutputStream(f.outputStream()).use { zos ->
            fun put(name: String, body: ByteArray) {
                zos.putNextEntry(ZipEntry(name)); zos.write(body); zos.closeEntry()
            }
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
                    .toByteArray(),
            )
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>插图测试</dc:title></metadata><manifest><item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/><item id="img" href="img.png" media-type="image/png"/></manifest><spine><itemref idref="ch1"/></spine></package>"""
                    .toByteArray(),
            )
            put(
                "OEBPS/ch1.xhtml",
                """<?xml version="1.0"?><html><head><title>一</title></head><body><p>正文第一段至少四个字</p><img src="img.png"/><p>正文第二段至少四个字</p></body></html>"""
                    .toByteArray(),
            )
            extra.forEach { (n, b) -> put(n, b) }
        }
        return f
    }

    private fun bookOf(f: File) = ImportedNovel(
        id = 7, title = "插图测试", localRoot = f.absolutePath,
        format = NovelFormat.EPUB, chapterCount = 1, addedAt = 42L,
    )

    /**
     * 整条链路：epub 里的 `<img>` → 落盘到独立隐藏缓存 → 量出原始像素尺寸
     * → 进 `ChapterContent.images`（按段号）。
     */
    @Test
    fun `epub 插图落盘并量出真实尺寸`() = runBlocking {
        val epub = epub("OEBPS/img.png" to pngBytes(60, 90))
        val root = tmp.newFolder("novel_import")
        val repo = NovelChapterRepository(imagesRoot = root)

        val content = repo.load(bookOf(epub), 0, emptyMap(), NovelDisplayMode.TRANSLATED, style, 600, 900)

        val imagePara = content.paragraphs.first { it.type == NovelParagraphType.IMAGE }
        val image = content.images[imagePara.index]
        assertNotNull("插图必须被解出来（否则这一段只会显示 📷 [图片] 占位文本）", image)
        assertEquals("必须量出真实宽度", 60, image!!.width)
        assertEquals("必须量出真实高度", 90, image.height)
        assertTrue("图片必须落在书本自己的目录下", image.file.path.contains(".starflow${File.separator}books${File.separator}"))
        assertTrue("图片文件必须真的写出来了", image.file.isFile && image.file.length() > 0)
        // 三段都在（正文一段都不能少）
        assertTrue(content.pages.flatMap { it.segments }.map { it.paraIndex }.contains(imagePara.index))
    }

    /** 没有配 imagesRoot（翻译任务 / 导出用的那套）时不解图，图片段回落占位文本。 */
    @Test
    fun `不配 imagesRoot 时不写盘且回落占位文本`() = runBlocking {
        val epub = epub("OEBPS/img.png" to pngBytes(10, 10))
        val content = NovelChapterRepository().load(
            bookOf(epub), 0, emptyMap(), NovelDisplayMode.TRANSLATED, style, 600, 900,
        )
        assertTrue(content.images.isEmpty())
        val imagePara = content.paragraphs.first { it.type == NovelParagraphType.IMAGE }
        assertTrue(content.pages.flatMap { it.segments }.map { it.paraIndex }.contains(imagePara.index))
    }

    /** 同一张图被多章引用 / 同章重复加载只落一份盘（按内容哈希命名）。 */
    @Test
    fun `重复加载不会重复落盘`() = runBlocking {
        val epub = epub("OEBPS/img.png" to pngBytes(30, 30))
        val root = tmp.newFolder("novel_import2")
        val repo = NovelChapterRepository(imagesRoot = root)
        val b = bookOf(epub)
        repo.load(b, 0, emptyMap(), NovelDisplayMode.TRANSLATED, style, 600, 900)
        val dir = NovelStorageDir.imageCacheDir(root, bookOf(epub))
        val first = dir.listFiles()!!.map { it.name }.sorted()
        repo.evictAll()
        repo.load(b, 0, emptyMap(), NovelDisplayMode.TRANSLATED, style, 600, 900)
        assertEquals("第二次加载不该多出文件", first, dir.listFiles()!!.map { it.name }.sorted())
    }

    @Test
    fun `扫描书的插图缓存不占用另一部作品的数字目录`() = runBlocking {
        val source = epub("OEBPS/img.png" to pngBytes(30, 30))
        val root = tmp.newFolder("scanned_novels")
        val otherDirectory = File(root, "7").apply { mkdirs() }
        val otherSource = File(otherDirectory, "other.txt").apply { writeText("Keep another book") }
        val book = bookOf(source)
        val repo = NovelChapterRepository(imagesRoot = root)

        val images = repo.imagesOf(book, 0)

        assertTrue(images.isNotEmpty())
        assertTrue(images.values.all { it.file.parentFile == NovelStorageDir.imageCacheDir(root, book) })
        assertEquals("Keep another book", otherSource.readText())
        assertFalse(File(otherDirectory, "images").exists())
    }
}
