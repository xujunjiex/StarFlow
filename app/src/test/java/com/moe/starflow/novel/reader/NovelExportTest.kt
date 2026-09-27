package com.moe.starflow.novel.reader

import android.content.Context
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.model.NovelFormat
import com.moe.starflow.novel.translate.NovelParagraph
import com.moe.starflow.novel.translate.NovelParagraphType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** 导出的文本规则（与阅读器共用 `NovelPageBilingual`，这里钉住的是"确实复用了它"）+ 整本落盘。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelExportTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun para(index: Int, text: String) = NovelParagraph(index, NovelParagraphType.TEXT, text)

    @Test
    fun `三种模式各自导出对应的文本`() {
        val paras = listOf(para(0, "one"), para(1, "two"))
        val tr = mapOf(0 to "一", 1 to "二")

        assertEquals("一\n二\n", NovelExport.buildChapterText(paras, tr, NovelExport.Kind.TRANSLATED))
        assertEquals("one\ntwo\n", NovelExport.buildChapterText(paras, tr, NovelExport.Kind.ORIGINAL))
        // 双语 = 原文一行、译文一行（与阅读器双语同序）
        assertEquals(
            "one\n一\ntwo\n二\n",
            NovelExport.buildChapterText(paras, tr, NovelExport.Kind.BILINGUAL),
        )
    }

    /** 未翻译的段在**任何**模式下都回落原文：留空会让导出文件像丢了内容。 */
    @Test
    fun `未翻译的段回落原文`() {
        val paras = listOf(para(0, "one"), para(1, "two"))
        val tr = mapOf(0 to "一")
        assertEquals("一\ntwo\n", NovelExport.buildChapterText(paras, tr, NovelExport.Kind.TRANSLATED))
        assertEquals("one\n一\ntwo\n", NovelExport.buildChapterText(paras, tr, NovelExport.Kind.BILINGUAL))
    }

    @Test
    fun `SKIP 段不进导出`() {
        val paras = listOf(
            para(0, "one"),
            NovelParagraph(1, NovelParagraphType.SKIP, "……"),
            para(2, "three"),
        )
        assertEquals("one\nthree\n", NovelExport.buildChapterText(paras, emptyMap(), NovelExport.Kind.ORIGINAL))
    }

    /** 文件名里的非法字符要清掉（书名来自用户文件，什么都可能有）。 */
    @Test
    fun `文件名清洗`() {
        assertEquals("a_b_c", NovelExport.sanitize("a/b:c"))
        assertEquals("novel", NovelExport.sanitize("   "))
        assertEquals("书名", NovelExport.sanitize("书名"))
    }

    // ===== 整本导出（落盘）=====

    private fun txtFile(text: String): File {
        val dir = File(ctx.cacheDir, "novel_export_${System.nanoTime()}").apply { mkdirs() }
        return File(dir, "book.txt").apply { writeText(text) }
    }

    private fun bookOf(id: Long, title: String, file: File) = ImportedNovel(
        id = id,
        title = title,
        localRoot = file.absolutePath,
        format = NovelFormat.TXT,
        chapterCount = 2,
        addedAt = id * 1000L,
    )

    /**
     * 整本导出：章序、每章的标题行、正文与 [NovelExport.buildChapterText] **逐字一致**，
     * 未翻译的章回落原文（留空会让导出的文件看起来像丢了内容）。
     */
    @Test
    fun `导出整本：章序、标题行、未翻译章回落原文、内容与 buildChapterText 一致`() = runBlocking {
        val book = bookOf(9, "书", txtFile("第一章 起\n\n这是正文第一段\n\n第二章 承\n\n这是正文第二段"))
        val repo = NovelChapterRepository()
        val ch0 = mapOf(0 to "T1", 1 to "T2")

        val out = NovelExport.exportBook(ctx, book, repo, NovelExport.Kind.TRANSLATED) { i ->
            if (i == 0) ch0 else emptyMap()
        }
        val content = out.readText()

        val root = (ctx.getExternalFilesDir(null) ?: ctx.filesDir).absolutePath
        assertTrue("导出必须落在应用专属目录里（不需要任何存储权限）", out.absolutePath.startsWith(root))

        val titles = repo.chaptersOf(book).map { it.title }
        assertEquals("前提：样本必须真有两章", 2, titles.size)
        // 整份文件 = 各章「标题 + 空行 + buildChapterText + 空行」
        val expected = buildString {
            titles.forEachIndexed { i, title ->
                append(title).append('\n').append('\n')
                append(
                    NovelExport.buildChapterText(
                        repo.paragraphsOf(book, i),
                        if (i == 0) ch0 else emptyMap(),
                        NovelExport.Kind.TRANSLATED,
                    )
                ).append('\n')
            }
        }
        assertEquals(expected, content)
        assertTrue("章序必须是 1 → 2", content.indexOf(titles[0]) < content.indexOf(titles[1]))
        assertTrue("有译文的章要出译文：$content", content.contains("T1") && content.contains("T2"))
        assertTrue("未翻译的章必须回落原文：$content", content.contains("这是正文第二段"))
    }

    /**
     * ⚠️ 文件名必须能区分不同的书：以前只有「书名-模式.txt」，两本**同名**的书（很常见）
     * 会互相覆盖，用户看到的就是「导出来的不是这本书」。
     */
    @Test
    fun `同名不同书导出的文件名不会互相覆盖`() = runBlocking {
        val repo = NovelChapterRepository()
        val a = NovelExport.exportBook(
            ctx, bookOf(9, "同名书", txtFile("第一章 A\n\n这是正文")), repo, NovelExport.Kind.ORIGINAL,
        ) { emptyMap() }
        val b = NovelExport.exportBook(
            ctx, bookOf(10, "同名书", txtFile("第一章 A\n\n这是正文")), repo, NovelExport.Kind.ORIGINAL,
        ) { emptyMap() }

        assertTrue("两本书的导出文件不能同名：${a.name} / ${b.name}", a.name != b.name)
        assertTrue("文件名要能认出是哪本书", a.name.contains("9") && b.name.contains("10"))
        assertTrue("仍然是 txt", a.name.endsWith(".txt"))
    }
}
