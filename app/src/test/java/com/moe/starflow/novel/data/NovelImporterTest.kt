package com.moe.starflow.novel.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
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

    /**
     * 失败清理：半成品目录、**本次导入生成的封面**都要删掉，但**同 id 的漫画封面不能误删**。
     *
     * ⚠️ 这条以前是**假绿**：那个 zip 在格式判定那一步就失败了，`novel_7_*` 封面从来没被生成过，
     * `none { … }` 恒真 —— 它连「小说封面带 novel_ 前缀，才不会被同 id 的漫画封面误删」这条
     * 规则都没守住。所以这里手动摆一个**无前缀**的漫画封面（`MangaImporter` 的命名
     * `covers/<id>_<ts>.jpg`）+ 真的用 [NovelImporter.placeholderCover] 造一个小说封面。
     */
    @Test
    fun `导入失败后清掉半成品目录与小说封面但不碰漫画封面`() = runBlocking {
        val covers = NovelStorageDir.coversDir(ctx).apply { mkdirs() }
        val mangaCover = File(covers, "7_111.jpg").apply { writeBytes(ByteArray(4)) }
        val novelCover = File(NovelImporter.placeholderCover(ctx, 7, "x"))
        assertTrue("前提：占位封面必须带 novel_ 前缀", novelCover.name.startsWith("novel_7_"))
        assertTrue("前提：两个封面都得先存在", mangaCover.exists() && novelCover.exists())

        val uri = zipUri("bad.epub", "mimetype" to "application/epub+zip".toByteArray())
        try {
            NovelImporter.importFile(ctx, uri, id = 7)
        } catch (e: Exception) {
            // 预期失败
        }
        assertFalse("半成品目录必须删掉", NovelStorageDir.bookDir(ctx, 7).exists())
        assertFalse("本次导入生成的封面必须删掉", novelCover.exists())
        assertTrue("同 id 的漫画封面（无前缀）不能被误删", mangaCover.exists())
    }

    // ===== 空文件 =====

    /**
     * ⚠️ 0 字节的 txt 以前会走「格式判定 → TXT → 0 章 → NO_TEXT_CHAPTER」，
     * 用户看到的是「未找到可阅读的文本章节」，而「空文件」这条原因**永远不可达**（死代码）。
     */
    @Test
    fun `0 字节文件报空文件原因`() = runBlocking {
        var caught: Throwable? = null
        try {
            NovelImporter.importFile(ctx, uriOf("empty.txt", ByteArray(0)), id = 8)
        } catch (e: Throwable) {
            caught = e
        }
        assertNotNull("0 字节文件必须导入失败", caught)
        assertEquals(NovelImporter.ERROR_EMPTY, (caught as? IllegalStateException)?.message)
        assertEquals(NovelImportFailureReason.EMPTY, NovelImportManager.classify(caught!!))
        assertFalse("失败后不能留下目录（否则书架上会留一张空卡片）", NovelStorageDir.bookDir(ctx, 8).exists())
        assertTrue(
            "失败后也不能留下封面",
            NovelStorageDir.coversDir(ctx).listFiles()?.none { it.name.startsWith("novel_8_") } ?: true,
        )
    }

    /** 有内容、但一章都读不出来 → 仍是「无可读章节」，不能一并报成空文件。 */
    @Test
    fun `有内容但没有章节报无可读章节`() = runBlocking {
        var caught: Throwable? = null
        try {
            NovelImporter.importFile(ctx, uriOf("blank.txt", " ".toByteArray()), id = 9)
        } catch (e: Throwable) {
            caught = e
        }
        assertEquals(
            NovelImportFailureReason.NO_TEXT_CHAPTER,
            NovelImportManager.classify(caught!!),
        )
    }

    // ===== 路径安全（显示名是 provider 给的，不可信）=====

    /**
     * 假装成 DocumentsProvider：`DISPLAY_NAME` 里带路径分隔符。
     *
     * SAF 的显示名完全由 provider 决定，实现粗糙/恶意的 provider 返回 `../evil.txt` 时，
     * 直接拼路径就能把文件写到 `novel_import/<id>/` 外面（能覆盖别的书、`covers/`、应用日志）。
     */
    private class NastyNameProvider(private val displayName: String) : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String = "text/plain"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            // ⚠️ DocumentFile 一次只查一列并按下标 0 取值，投影必须原样满足
            val cols: Array<String> = if (projection.isNullOrEmpty()) {
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            } else {
                Array(projection.size) { projection[it] }
            }
            val cursor = MatrixCursor(cols)
            cursor.addRow(
                cols.map { col ->
                    when (col) {
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> displayName
                        DocumentsContract.Document.COLUMN_SIZE -> 64L
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 0L
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> "text/plain"
                        else -> null
                    }
                }.toTypedArray()
            )
            return cursor
        }
    }

    @Test
    fun `显示名带路径分隔符时不会写出目标目录`() = runBlocking {
        listOf("../evil.txt", "..\\evil.txt", "a/b/evil.txt").forEachIndexed { i, nasty ->
            val id = 20L + i
            val authority = "nasty$id"
            ShadowContentResolver.registerProviderInternal(authority, NastyNameProvider(nasty))
            val uri = Uri.parse("content://$authority/doc")
            Shadows.shadowOf(ctx.contentResolver)
                .registerInputStream(uri, "第一章 A\n\n正文一".byteInputStream())

            val novel = NovelImporter.importFile(ctx, uri, id = id)
            val bookDir = NovelStorageDir.bookDir(ctx, id).canonicalFile
            val copied = File(novel.localRoot).canonicalFile
            assertTrue(
                "显示名「$nasty」把文件写到了目标目录外：${copied.path}",
                copied.path.startsWith(bookDir.path + File.separator),
            )
            assertEquals("名字必须收敛成单段", "evil.txt", copied.name)
        }
        assertFalse(
            "novel_import/ 下不该出现被挤出来的文件",
            File(NovelStorageDir.root(ctx), "evil.txt").exists(),
        )
    }

    // ===== 文件夹子（整个夹 = 一部小说）=====
    //
    // ⚠️ importDirectory 走 SAF 的 `DocumentFile.fromTreeUri`，需要真实 DocumentsProvider，
    // Robolectric 里给不出可用的 tree uri。所以这里只钉「格式判定把目录认成小说子」，
    // 夹内分章的语义由 `FolderNovelParserTest` 用真目录覆盖。

    @Test
    fun `目录被判成文件夹子而不是不支持`() {
        val d = File(ctx.cacheDir, "folder_${System.nanoTime()}").apply { mkdirs() }
        File(d, "01.txt").writeText("正文")
        assertEquals(
            "整个夹 = 一部小说，不能被判成「不是小说」",
            NovelFormat.FOLDER,
            com.moe.starflow.novel.parser.NovelFormatDetector.detect(d),
        )
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
