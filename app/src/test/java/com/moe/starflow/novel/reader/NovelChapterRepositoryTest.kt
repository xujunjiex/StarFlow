package com.moe.starflow.novel.reader

import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.model.NovelFormat
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// 仓库用 StaticLayout / LruCache，都是 Android 框架类 —— 纯 JVM 单测里会 "not mocked"
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelChapterRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private val style = NovelTextStyle(fontSizePx = 40f, lineSpacingMultiplier = 1.5f, paragraphSpacingPx = 16f, paddingPx = 20f)

    private fun bookOf(text: String): ImportedNovel {
        val f = File(tmp.root, "t.txt").apply { writeText(text) }
        return ImportedNovel(
            id = 1, title = "t", localRoot = f.absolutePath,
            format = NovelFormat.TXT, chapterCount = 2, addedAt = 1L,
        )
    }

    private val twoChapters = "第一章 起\n\n正文一\n\n第二章 承\n\n正文二"

    // ===== 基本加载 =====

    @Test
    fun `加载章返回段落与页表`() = runBlocking {
        val repo = NovelChapterRepository()
        val c = repo.load(bookOf(twoChapters), 0, emptyMap(), NovelDisplayMode.TRANSLATED, style, 1080, 1920)
        assertEquals(0, c.chapterIndex)
        assertTrue(c.paragraphs.isNotEmpty())
        assertTrue("必须至少分出一页", c.pages.isNotEmpty())
        assertTrue("显示文本要覆盖每个段落", c.displayTexts.keys.containsAll(c.paragraphs.map { it.index }))
    }

    /**
     * **一行一段的 txt 必须按行切成多段**（用户报的「三国演义只有 2 段，原文明明很多段」）。
     *
     * 这条从 `TxtParser.loadChapter` 走到分段，钉住「TXT 正文要先把单换行提升成段落分隔」。
     */
    @Test
    fun `一行一段的 txt 按行切成多段`() = runBlocking {
        val text = "第一章 起\n\n第一段正文至少四个字\n第二段正文至少四个字\n第三段正文至少四个字"
        val repo = NovelChapterRepository()
        val c = repo.load(bookOf(text), 0, emptyMap(), NovelDisplayMode.TRANSLATED, style, 1080, 1920)
        val body = c.paragraphs.map { it.originalText }
        for (want in listOf("第一段正文至少四个字", "第二段正文至少四个字", "第三段正文至少四个字")) {
            assertTrue("必须按行切开（实际 ${body.size} 段：$body）", body.contains(want))
        }
    }

    @Test
    fun `章标题读得出来`() = runBlocking {
        val repo = NovelChapterRepository()
        val c = repo.load(bookOf(twoChapters), 0, emptyMap(), NovelDisplayMode.TRANSLATED, style, 1080, 1920)
        assertEquals("第一章 起", c.title)
    }

    /**
     * 字数的口径必须和**翻译时真正发出去的字符**一致：只算可翻译段。
     * 图片占位（`📷 [图片]`）与不足 4 字的短行（`……`）按设计永不翻译，算进去会把费用估虚高。
     */
    @Test
    fun `字数只算可翻译段`() = runBlocking {
        val text = "第一章 起\n\n这是第一段正文八个字\n\n……\n\n📷 [图片]\n\n第二段正文也八个字"
        val repo = NovelChapterRepository()
        val book = bookOf(text)

        val paras = repo.paragraphsOf(book, 0)
        assertTrue("样本里必须真的有不翻译的段", paras.any { !it.isTranslatable() })
        assertTrue("样本里必须有可翻译的段", paras.any { it.isTranslatable() })
        val expected = paras.filter { it.isTranslatable() }.sumOf { it.originalText.length }
        assertEquals(expected, repo.charCountOf(book, 0))
    }

    @Test
    fun `文件丢失时不崩溃返回空内容`() = runBlocking {
        val repo = NovelChapterRepository()
        val missing = bookOf(twoChapters).copy(localRoot = File(tmp.root, "nope.txt").absolutePath)
        val c = repo.load(missing, 0, emptyMap(), NovelDisplayMode.TRANSLATED, style, 1080, 1920)
        assertTrue(c.paragraphs.isEmpty())
        assertTrue(c.pages.isEmpty())
    }

    @Test
    fun `越界的章号返回空内容`() = runBlocking {
        val repo = NovelChapterRepository()
        val c = repo.load(bookOf(twoChapters), 99, emptyMap(), NovelDisplayMode.TRANSLATED, style, 1080, 1920)
        assertTrue(c.isEmpty)
    }

    // ===== 缓存 =====

    @Test
    fun `同参数重复加载命中缓存返回同一实例`() = runBlocking {
        val repo = NovelChapterRepository()
        val b = bookOf(twoChapters)
        val a1 = repo.load(b, 0, emptyMap(), NovelDisplayMode.TRANSLATED, style, 1080, 1920)
        val a2 = repo.load(b, 0, emptyMap(), NovelDisplayMode.TRANSLATED, style, 1080, 1920)
        assertSame("同参数必须命中缓存，否则每翻一页都重排整章", a1, a2)
    }

    /**
     * 改排版参数必须重算页表。
     *
     * ⚠️ 这里断言的是**缓存未命中**（拿到不同实例），不是「页数变了」：
     * Robolectric 的 `StaticLayout` **不按宽度换行**（每段恒 1 行），页数在单测里根本不随字号变。
     * 分页正确性由 `NovelPaginatorTest` 用**合成行起点**覆盖 —— 那才是与文本引擎解耦的部分。
     */
    @Test
    fun `排版参数变化时重算页表`() = runBlocking {
        val repo = NovelChapterRepository()
        val b = bookOf("第一章\n\n" + "正文内容。".repeat(400))
        val a = repo.load(b, 0, emptyMap(), NovelDisplayMode.TRANSLATED, style, 1080, 1920)
        val bigger = repo.load(
            b, 0, emptyMap(), NovelDisplayMode.TRANSLATED,
            style.copy(fontSizePx = style.fontSizePx * 2), 1080, 1920,
        )
        assertNotSame("字号变了必须重算（否则用户改了字号没反应）", a, bigger)
    }

    /**
     * 译文到达后必须重排：译文比原文长，沿用旧页表会把最后一截裁掉。
     * 这也是缓存键里带「译文版本」的理由 —— 少了它就会命中过期页表。
     */
    /**
     * 译文到达后必须重算页表：译文比原文长，沿用旧页表会把最后一截裁掉。
     * 这是缓存键里带「译文版本」的理由 —— 少了它就会命中过期页表。
     *
     * 同上：Robolectric 不按宽度换行，所以断言「重算了」而不是「页数变了」。
     */
    @Test
    fun `译文到达时重算页表`() = runBlocking {
        val repo = NovelChapterRepository()
        val b = bookOf("第一章\n\n短句\n\n另一段短句")
        val original = repo.load(b, 0, emptyMap(), NovelDisplayMode.TRANSLATED, style, 1080, 400)
        val translated = repo.load(
            b, 0, mapOf(1 to "把这句翻成很长的一段译文"), NovelDisplayMode.TRANSLATED, style, 1080, 400,
        )
        assertNotSame("译文到达必须重算", original, translated)
        assertEquals("把这句翻成很长的一段译文", translated.displayOf(1))
    }

    /** 同样的译文再取一次应命中缓存（否则每批译文到达都重排一遍）。 */
    @Test
    fun `相同译文重复加载命中缓存`() = runBlocking {
        val repo = NovelChapterRepository()
        val b = bookOf("第一章\n\n原文甲")
        val t = mapOf(1 to "译文甲")
        assertSame(
            repo.load(b, 0, t, NovelDisplayMode.TRANSLATED, style, 1080, 1920),
            repo.load(b, 0, t, NovelDisplayMode.TRANSLATED, style, 1080, 1920),
        )
    }

    @Test
    fun `切显示模式会重新分页`() = runBlocking {
        val repo = NovelChapterRepository()
        val b = bookOf("第一章\n\n短句\n\n另一段短句")
        val t = mapOf(1 to "译文甲", 2 to "译文乙")
        val a = repo.load(b, 0, t, NovelDisplayMode.TRANSLATED, style, 1080, 1920)
        val c = repo.load(b, 0, t, NovelDisplayMode.BILINGUAL, style, 1080, 1920)
        assertNotEquals(a.displayTexts[1], c.displayTexts[1])
        assertTrue("双语的显示文本应同时含原文与译文", c.displayOf(1).contains("译文甲"))
    }

    // ===== 显示文本 =====

    @Test
    fun `译文模式只显示译文`() = runBlocking {
        val repo = NovelChapterRepository()
        val b = bookOf("第一章\n\n原文甲")
        val c = repo.load(b, 0, mapOf(1 to "译文甲"), NovelDisplayMode.TRANSLATED, style, 1080, 1920)
        assertEquals("译文甲", c.displayOf(1))
    }

    @Test
    fun `原文模式忽略译文`() = runBlocking {
        val repo = NovelChapterRepository()
        val b = bookOf("第一章\n\n原文甲")
        val c = repo.load(b, 0, mapOf(1 to "译文甲"), NovelDisplayMode.ORIGINAL, style, 1080, 1920)
        assertEquals("原文甲", c.displayOf(1))
    }

    @Test
    fun `未翻译时回落原文`() = runBlocking {
        val repo = NovelChapterRepository()
        val b = bookOf("第一章\n\n原文甲")
        val c = repo.load(b, 0, emptyMap(), NovelDisplayMode.TRANSLATED, style, 1080, 1920)
        assertEquals("原文甲", c.displayOf(1))
    }

    // ===== 页表与显示文本的一致性（切片必须不越界） =====

    /**
     * 硬约束：每个 segment 的字符区间必须落在**显示文本**的范围内。
     * 分页与绘制用的是同一份文本，越界说明两者脱节了 —— 表现为绘制时下标越界崩溃或画出乱码。
     */
    @Test
    fun `所有 segment 的字符区间都在显示文本内`() = runBlocking {
        val repo = NovelChapterRepository()
        val b = bookOf("第一章 起\n\n" + "第一段正文，比较长。".repeat(30) + "\n\n第二段")
        for (mode in NovelDisplayMode.entries) {
            val t = mapOf(0 to "第一章 起", 2 to "译得很长的一段话。".repeat(20))
            val c = repo.load(b, 0, t, mode, style, 1080, 900)
            assertTrue(c.pages.isNotEmpty())
            for (page in c.pages) {
                for (seg in page.segments) {
                    val full = c.displayOf(seg.paraIndex)
                    assertTrue(
                        "段 ${seg.paraIndex} 的区间 [${seg.charStart},${seg.charEnd}) 越界（长度 ${full.length}）",
                        seg.charStart in 0..full.length && seg.charEnd in seg.charStart..full.length,
                    )
                }
            }
        }
    }
}
