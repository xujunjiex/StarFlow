package com.moe.starflow.novel.data

import android.content.Context
import android.net.Uri
import com.moe.starflow.novel.model.NovelFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelImporterTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    /** 每个用例用独立的缓存子目录，避免同名文件互相干扰。 */
    private lateinit var workDir: File

    @Before
    fun setUp() {
        NovelStore.save(ctx, emptyList())
        NovelStorageDir.root(ctx).deleteRecursively()
        workDir = File(ctx.cacheDir, "novel_test_${System.nanoTime()}").apply { mkdirs() }
    }

    private fun uriOf(name: String, bytes: ByteArray): Uri {
        val f = File(workDir, name)
        f.writeBytes(bytes)
        return Uri.fromFile(f)
    }

    private fun fileUri(name: String, text: String): Uri = uriOf(name, text.toByteArray(Charsets.UTF_8))

    private fun zipUri(name: String, vararg entries: Pair<String, ByteArray>): Uri {
        val f = File(workDir, name)
        ZipOutputStream(f.outputStream()).use { zos ->
            entries.forEach { (n, b) ->
                zos.putNextEntry(ZipEntry(n)); zos.write(b); zos.closeEntry()
            }
        }
        return Uri.fromFile(f)
    }

    // ===== 单文件 =====

    @Test
    fun `导入 txt 产出条目与章数`() = runBlocking {
        val text = "第一章 起\n\n正文一\n\n第二章 承\n\n正文二"
        val novel = NovelImporter.importFile(ctx, fileUri("a.txt", text), id = 1)

        assertEquals(NovelFormat.TXT, novel.format)
        assertEquals(2, novel.chapterCount)
        assertEquals("a", novel.title)
        assertEquals(1L, novel.id)
        assertTrue("原文件必须已复制到应用目录", File(novel.localRoot).exists())
        assertEquals(text.toByteArray().size.toLong(), novel.sizeBytes)
    }

    /** 没有内嵌封面时用书名首字生成占位，否则书架一片灰认不出哪本是哪本。 */
    @Test
    fun `无内嵌封面时生成占位封面`() = runBlocking {
        val novel = NovelImporter.importFile(ctx, fileUri("a.txt", "第一章\n\n正文"), id = 2)
        assertNotNull(novel.coverPath)
        assertTrue("封面文件必须存在", File(novel.coverPath!!).exists())
        assertTrue("封面文件名必须带 novel_ 前缀避免与漫画封面撞名", File(novel.coverPath!!).name.startsWith("novel_2_"))
    }

    @Test
    fun `导入 epub 取到内嵌标题与作者`() = runBlocking {
        val uri = zipUri(
            "b.epub",
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to
                """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles>
                   <rootfile full-path="c.opf"/></rootfiles></container>""".toByteArray(),
            "c.opf" to
                """<package xmlns="http://www.idpf.org/2007/opf"
                            xmlns:dc="http://purl.org/dc/elements/1.1/">
                   <metadata><dc:title>书名人</dc:title><dc:creator>作者人</dc:creator></metadata>
                   <manifest><item id="a" href="a.xhtml"/></manifest>
                   <spine><itemref idref="a"/></spine></package>""".toByteArray(),
            "a.xhtml" to "<h1>一</h1><p>正文</p>".toByteArray(),
        )
        val novel = NovelImporter.importFile(ctx, uri, id = 3)
        assertEquals(NovelFormat.EPUB, novel.format)
        assertEquals("书名人", novel.title)
        assertEquals("作者人", novel.author)
        assertEquals(1, novel.chapterCount)
    }

    @Test
    fun `导入单 html`() = runBlocking {
        val novel = NovelImporter.importFile(ctx, fileUri("c.html", "<html><head><title>网页书</title></head><body><p>正文</p></body></html>"), id = 4)
        assertEquals(NovelFormat.HTML, novel.format)
        assertEquals("网页书", novel.title)
        assertEquals(1, novel.chapterCount)
    }

    // ===== 选错书架的提示 =====

    /** 图片包要给出「这是漫画」的提示，而不是笼统的「格式不支持」。 */
    @Test
    fun `图片 zip 判为漫画并抛 WrongFormat`() = runBlocking {
        val uri = zipUri("m.zip", "001.jpg" to ByteArray(16), "002.jpg" to ByteArray(16))
        var caught: NovelWrongFormatException? = null
        try {
            NovelImporter.importFile(ctx, uri, id = 5)
        } catch (e: NovelWrongFormatException) {
            caught = e
        }
        assertNotNull("图片 zip 必须报 WrongFormat 而不是静默失败", caught)
        assertTrue("必须标成「这是漫画」以便提示去漫画书架", caught!!.isManga)
        assertFalse("失败时半成品目录必须删掉", NovelStorageDir.bookDir(ctx, 5).exists())
    }

    @Test
    fun `完全不支持的文件标成非漫画`() = runBlocking {
        var caught: NovelWrongFormatException? = null
        try {
            NovelImporter.importFile(ctx, uriOf("d.pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46)), id = 6)
        } catch (e: NovelWrongFormatException) {
            caught = e
        }
        assertNotNull(caught)
        assertFalse(caught!!.isManga)
    }

    // ===== 失败清理 =====

    @Test
    fun `损坏的 epub 失败后清掉半成品目录与封面`() = runBlocking {
        val uri = zipUri("bad.epub", "mimetype" to "application/epub+zip".toByteArray())
        try {
            NovelImporter.importFile(ctx, uri, id = 7)
        } catch (e: Exception) {
            // 预期失败
        }
        assertFalse("半成品目录必须删掉", NovelStorageDir.bookDir(ctx, 7).exists())
        assertTrue(
            "封面也必须清掉",
            NovelStorageDir.coversDir(ctx).listFiles()?.none { it.name.startsWith("novel_7_") } ?: true,
        )
    }

    // ===== 目录导入 =====
    //
    // ⚠️ 这几条测的是 NovelImporter.importCandidates —— `DocumentFile.fromTreeUri` 需要真实
    // DocumentsProvider，Robolectric 里给不出可用的 tree uri。所以「列目录」那一段（candidateDocs）
    // 由下面的纯函数测试覆盖，「每个文件一部书」这条规则在这里覆盖。

    /** 小说侧「夹内每个文本文件 = 一部书」（与漫画的「整个夹 = 一部」不同）。 */
    @Test
    fun `目录导入时每个文本文件一部书`() = runBlocking {
        val candidates = listOf(
            "1.txt" to fileUri("1.txt", "第一章 A\n\n正文"),
            "2.txt" to fileUri("2.txt", "第一章 B\n\n正文"),
        )
        var next = 10L
        val list = NovelImporter.importCandidates(ctx, candidates, nextId = { next++ })

        assertEquals(2, list.size)
        assertEquals(listOf(10L, 11L), list.map { it.id })
        assertEquals(listOf("1", "2"), list.map { it.title })
    }

    /** 目录导入的 id 由调用方分配：夹内文件数扫描后才知道，自己递增会与并发导入撞号。 */
    @Test
    fun `目录导入使用调用方给的 id 分配器`() = runBlocking {
        val given = mutableListOf<Long>()
        val list = NovelImporter.importCandidates(
            ctx,
            listOf(
                "a.txt" to fileUri("a.txt", "第一章\n\n正文"),
                "b.txt" to fileUri("b.txt", "第一章\n\n正文"),
            ),
            nextId = { (100L + given.size).also { given += it } },
        )
        assertEquals(listOf(100L, 101L), list.map { it.id })
        assertEquals(listOf(100L, 101L), given)
    }

    @Test
    fun `目录里没有文本文件时返回空表`() = runBlocking {
        val list = NovelImporter.importCandidates(ctx, emptyList(), nextId = { 1L })
        assertTrue(list.isEmpty())
    }

    /** 单个文件坏掉不能拖垮整夹：跳过它，其余照常导入。 */
    @Test
    fun `目录内单个文件失败时跳过其余照常导入`() = runBlocking {
        val candidates = listOf(
            "ok1.txt" to fileUri("ok1.txt", "第一章\n\n正文"),
            "bad.pdf" to uriOf("bad.pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46)),
            "ok2.txt" to fileUri("ok2.txt", "第一章\n\n正文"),
        )
        var next = 1L
        val list = NovelImporter.importCandidates(ctx, candidates, nextId = { next++ })
        assertEquals(listOf("ok1", "ok2"), list.map { it.title })
    }

    /** 扩展名过滤 + 自然序（字典序会把 10.txt 排到 2.txt 前面）。 */
    @Test
    fun `可导入文件名按自然序排列且非文本被滤掉`() {
        val names = listOf("2.txt", "cover.jpg", "10.txt", "1.txt", "notes.pdf", "b.epub", "a.HTML")
        assertEquals(
            listOf("1.txt", "2.txt", "10.txt", "a.HTML", "b.epub"),
            NovelImporter.sortImportableNames(names),
        )
    }

    @Test
    fun `可导入判定只认文本类扩展名`() {
        for (n in listOf("a.txt", "b.epub", "c.html", "d.xhtml", "e.htm", "f.zip", "g.TXT")) {
            assertTrue(n, NovelImporter.isImportableFileName(n))
        }
        for (n in listOf("a.jpg", "b.pdf", "c.mobi", "noext")) {
            assertFalse(n, NovelImporter.isImportableFileName(n))
        }
    }

    /** 名字里带路径/反斜杠时不能被当成目标文件名（Windows file:// 会给出整条路径）。 */
    @Test
    fun `从 uri 兜底取文件名只取最后一段`() = runBlocking {
        val novel = NovelImporter.importFile(ctx, fileUri("plain.txt", "第一章\n\n正文"), id = 20)
        assertEquals("plain", novel.title)
    }

    // ===== 指纹单调 =====

    /**
     * addedAt 是译文身份指纹：删书后重导会复用同一个 id，若时间戳也相同，
     * 旧译文就会映射到新书上。所以必须单调递增。
     */
    @Test
    fun `addedAt 在连续导入下单调递增`() = runBlocking {
        val a = NovelImporter.importFile(ctx, fileUri("x1.txt", "第一章\n\n正文"), id = 1)
        NovelStore.add(ctx, a)
        val b = NovelImporter.importFile(ctx, fileUri("x2.txt", "第一章\n\n正文"), id = 2)
        NovelStore.add(ctx, b)
        val c = NovelImporter.importFile(ctx, fileUri("x3.txt", "第一章\n\n正文"), id = 3)
        assertTrue("addedAt 必须严格递增：${a.addedAt} / ${b.addedAt} / ${c.addedAt}", a.addedAt < b.addedAt && b.addedAt < c.addedAt)
    }

    @Test
    fun `nextId 是清单最大 id 加一`() = runBlocking {
        val imported = NovelImporter.importFile(ctx, fileUri("n1.txt", "第一章\n\n正文"), id = 1)
        NovelStore.save(ctx, NovelStore.load(ctx) + imported)
        assertEquals(2L, NovelImporter.nextId(ctx))
    }
}
