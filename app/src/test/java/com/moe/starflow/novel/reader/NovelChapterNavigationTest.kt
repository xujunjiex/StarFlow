package com.moe.starflow.novel.reader

import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.model.NovelFormat
import com.moe.starflow.novel.parser.TxtParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 章节切换的端到端路径守卫（导入 → 目录 → 逐章加载）。
 *
 * 存在理由：章节是阅读器的核心导航，但「切章无效」从界面上很难一眼看出原因 ——
 * 可能出在目录为空、章号算错、或加载时读的还是上一章的缓存。这里把整条路径钉死。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelChapterNavigationTest {

    @get:Rule val tmp = TemporaryFolder()

    private val style = NovelTextStyle(40f, 1.5f, 16f, 20f)

    /** 用真样本（6 章），而不是现搭字符串 —— 分章正则是这条链上最容易出问题的一环。 */
    private fun fixtureBook(): ImportedNovel {
        val src = File(
            javaClass.classLoader!!.getResource("novel-fixtures/simp-utf8.txt")!!.toURI()
        )
        val dest = File(tmp.root, "book.txt")
        src.copyTo(dest, overwrite = true)
        return ImportedNovel(
            id = 1, title = "雾港", localRoot = dest.absolutePath,
            format = NovelFormat.TXT, chapterCount = 6, addedAt = 1L,
        )
    }

    @Test
    fun `目录解析出全部章节且顺序正确`() = runBlocking {
        val repo = NovelChapterRepository()
        val chapters = repo.chaptersOf(fixtureBook())
        assertEquals("目录必须是 6 章", 6, chapters.size)
        assertEquals("第一章　雾港的清晨", chapters[0].title)
        assertEquals("第六章　出港", chapters[5].title)
        assertEquals("index 必须与列表位置一致", listOf(0, 1, 2, 3, 4, 5), chapters.map { it.index })
    }

    /** 逐章加载：**每一章的正文必须不同**，相同就说明章号没生效、一直在读同一章。 */
    @Test
    fun `逐章加载拿到的是各自不同的正文`() = runBlocking {
        val repo = NovelChapterRepository()
        val b = fixtureBook()
        val texts = (0 until 6).map { ch ->
            repo.load(b, ch, emptyMap(), NovelDisplayMode.TRANSLATED, style, 1080, 1920)
                .paragraphs.joinToString("\n") { it.originalText }
        }
        assertTrue("每一章都该有正文", texts.all { it.isNotBlank() })
        for (i in 0 until 5) {
            assertNotEquals("第 $i 章与第 ${i + 1} 章正文不该相同", texts[i], texts[i + 1])
        }
        assertTrue("第 1 章应含「雾还没散」", texts[0].contains("雾还没散"))
        assertTrue("第 2 章应含「那封信」", texts[1].contains("那封信"))
        assertTrue("第 6 章应含「离港」", texts[5].contains("离港"))
    }

    @Test
    fun `章标题随章号变化`() = runBlocking {
        val repo = NovelChapterRepository()
        val b = fixtureBook()
        val titles = (0 until 6).map { ch ->
            repo.load(b, ch, emptyMap(), NovelDisplayMode.TRANSLATED, style, 1080, 1920).title
        }
        assertEquals(6, titles.toSet().size)
        assertEquals("第一章　雾港的清晨", titles[0])
    }

    /**
     * 换章后**译文不能串**：译文是「章内 paraIndex」为键的，若加载新章时仍带着上一章的
     * 译文表，新章的第 0 段会被套上上一章第 0 段的译文。
     */
    @Test
    fun `换章时旧章译文不会串到新章`() = runBlocking {
        val repo = NovelChapterRepository()
        val b = fixtureBook()
        // 第 1 章的译文
        val ch0 = repo.load(b, 0, mapOf(0 to "第一章的译文"), NovelDisplayMode.TRANSLATED, style, 1080, 1920)
        assertEquals("第一章的译文", ch0.displayOf(0))

        // 换到第 2 章：调用方应先把译文换成新章的（这里是空的）
        val ch1 = repo.load(b, 1, emptyMap(), NovelDisplayMode.TRANSLATED, style, 1080, 1920)
        assertNotEquals("新章第 0 段不该显示上一章的译文", "第一章的译文", ch1.displayOf(0))
        // 段 0 是章标题行，正文在段 1
        assertEquals("未翻译时应显示原文", "第二章　旧信", ch1.displayOf(0))
        assertTrue("实际：${ch1.displayOf(1)}", ch1.displayOf(1).contains("那封信"))
    }

    /**
     * 断点续读：**每个**段落号都要定位到「确实包含该段」的那一页。
     *
     * ⚠️ 不断言「页数 ≥ 2」：Robolectric 的 StaticLayout 不按宽度换行（每段恒 1 行），
     * 章节在单测里永远只占一页。断言「定位结果包含目标段」在单页与多页下都成立。
     */
    @Test
    fun `段落号能定位到含它的页`() = runBlocking {
        val repo = NovelChapterRepository()
        val b = fixtureBook()
        val c = repo.load(b, 0, emptyMap(), NovelDisplayMode.TRANSLATED, style, 1080, 800)
        assertTrue(c.pages.isNotEmpty())
        for (seg in c.pages.flatMap { it.segments }) {
            val page = repo.pageOfParagraph(c.pages, seg.paraIndex)
            assertTrue(
                "段落 ${seg.paraIndex} 定位到第 $page 页，但该页不含它",
                c.pages[page].segments.any { it.paraIndex == seg.paraIndex },
            )
        }
    }

    @Test
    fun `txt 解析器直接给出的章节数与仓库一致`() = runBlocking {
        val book = TxtParser.parse(File(fixtureBook().localRoot))
        assertEquals(6, book.chapters.size)
    }
}
