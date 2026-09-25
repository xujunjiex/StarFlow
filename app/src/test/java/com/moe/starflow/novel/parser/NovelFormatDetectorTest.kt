package com.moe.starflow.novel.parser

import com.moe.starflow.novel.model.NovelFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NovelFormatDetectorTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun file(name: String, bytes: ByteArray): File =
        tmp.newFile(name).apply { writeBytes(bytes) }

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File {
        val f = File(tmp.root, name)
        ZipOutputStream(f.outputStream()).use { zos ->
            entries.forEach { (n, b) ->
                zos.putNextEntry(ZipEntry(n)); zos.write(b); zos.closeEntry()
            }
        }
        return f
    }

    @Test
    fun `epub 按 mimetype 认出`() {
        val f = zip("a.epub",
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to "<container/>".toByteArray())
        assertEquals(NovelFormat.EPUB, NovelFormatDetector.detect(f))
    }

    @Test
    fun `有图片的 zip 是漫画不是小说`() {
        val f = zip("b.zip", "001.jpg" to ByteArray(16), "002.jpg" to ByteArray(16))
        assertNull(NovelFormatDetector.detect(f))
    }

    @Test
    fun `有 html 的 zip 是小说包`() {
        val f = zip("c.zip", "ch1.html" to "<p>hi</p>".toByteArray())
        assertEquals(NovelFormat.ZIP_HTML, NovelFormatDetector.detect(f))
    }

    @Test
    fun `扩展名兜底 txt 与 html`() {
        assertEquals(NovelFormat.TXT, NovelFormatDetector.detect(file("d.txt", "你好".toByteArray())))
        assertEquals(NovelFormat.HTML, NovelFormatDetector.detect(file("e.html", "<p>x</p>".toByteArray())))
        assertEquals(NovelFormat.HTML, NovelFormatDetector.detect(file("f.xhtml", "<p>x</p>".toByteArray())))
    }

    @Test
    fun `未知扩展名且不是压缩包 返回 null`() {
        assertNull(NovelFormatDetector.detect(file("g.pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46))))
    }
}
