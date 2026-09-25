package com.moe.starflow.novel.reader

import com.moe.starflow.novel.translate.NovelParagraph
import com.moe.starflow.novel.translate.NovelParagraphType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// 页定位那两条要构造 NovelChapterRepository（内部用 Android 的 LruCache）
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelDisplayModeTest {

    // ===== 显示文本（分页与绘制的唯一共同来源） =====

    @Test
    fun `译文模式显示译文`() {
        assertEquals("译文", NovelPageBilingual.displayText("原文", "译文", NovelDisplayMode.TRANSLATED))
    }

    @Test
    fun `原文模式显示原文`() {
        assertEquals("原文", NovelPageBilingual.displayText("原文", "译文", NovelDisplayMode.ORIGINAL))
    }

    @Test
    fun `双语模式原文在前译文在后`() {
        assertEquals(
            "原文" + NovelPageBilingual.BILINGUAL_SEPARATOR + "译文",
            NovelPageBilingual.displayText("原文", "译文", NovelDisplayMode.BILINGUAL),
        )
    }

    /** 译文分批增量回写，未翻的段必须回落原文 —— 显示空白会让用户以为内容丢了。 */
    @Test
    fun `未翻译的段落在任何模式下都显示原文`() {
        for (mode in NovelDisplayMode.entries) {
            assertEquals("原文", NovelPageBilingual.displayText("原文", null, mode))
            assertEquals("原文", NovelPageBilingual.displayText("原文", "", mode))
        }
    }

    // ===== prefs 编解码 =====

    @Test
    fun `prefs 编解码往返一致`() {
        for (m in NovelDisplayMode.entries) {
            assertEquals(m, NovelDisplayModeCodec.fromPref(NovelDisplayModeCodec.toPref(m)))
        }
    }

    @Test
    fun `坏 prefs 值回退译文模式`() {
        assertEquals(NovelDisplayMode.TRANSLATED, NovelDisplayModeCodec.fromPref(null))
        assertEquals(NovelDisplayMode.TRANSLATED, NovelDisplayModeCodec.fromPref(""))
        assertEquals(NovelDisplayMode.TRANSLATED, NovelDisplayModeCodec.fromPref("bilingual"))
        assertEquals(NovelDisplayMode.BILINGUAL, NovelDisplayModeCodec.fromPref("2"))
    }

    @Test
    fun `切换是循环的且能走遍三态`() {
        var m = NovelDisplayMode.TRANSLATED
        val seen = mutableListOf(m)
        repeat(3) {
            m = NovelDisplayModeCodec.next(m)
            seen.add(m)
        }
        assertEquals(
            listOf(
                NovelDisplayMode.TRANSLATED,
                NovelDisplayMode.ORIGINAL,
                NovelDisplayMode.BILINGUAL,
                NovelDisplayMode.TRANSLATED,
            ),
            seen,
        )
    }

    // ===== 滚动模式的位置映射 =====

    private val content = ChapterContent(
        chapterIndex = 0,
        title = "第一章",
        paragraphs = listOf(
            NovelParagraph(0, NovelParagraphType.TEXT, "第一段"),
            NovelParagraph(1, NovelParagraphType.SKIP, "……"),
            NovelParagraph(2, NovelParagraphType.TEXT, "第二段"),
            NovelParagraph(3, NovelParagraphType.IMAGE, "📷 [图片]"),
            NovelParagraph(4, NovelParagraphType.TEXT, "第三段"),
        ),
        displayTexts = emptyMap(),
        pages = emptyList(),
    )

    @Test
    fun `滚动列表排除 SKIP 段但保留 IMAGE 占位`() {
        assertEquals(
            listOf(0, 2, 3, 4),
            NovelScrollMapping.visibleParagraphs(content).map { it.index },
        )
    }

    @Test
    fun `段落号映射到 item 下标`() {
        assertEquals(0, NovelScrollMapping.positionOf(content, 0))
        assertEquals(1, NovelScrollMapping.positionOf(content, 2))
        assertEquals(3, NovelScrollMapping.positionOf(content, 4))
    }

    /** 落在被排除的段上时，应定位到**下一个可见段**，而不是回退到开头。 */
    @Test
    fun `段落号落在被跳过的段上时定位到下一段`() {
        assertEquals("段 1 是 SKIP，应落到段 2", 1, NovelScrollMapping.positionOf(content, 1))
    }

    @Test
    fun `超出范围时定位到末段`() {
        assertEquals(3, NovelScrollMapping.positionOf(content, 999))
    }

    @Test
    fun `item 下标反查段落号`() {
        assertEquals(0, NovelScrollMapping.paraIndexOf(content, 0))
        assertEquals(2, NovelScrollMapping.paraIndexOf(content, 1))
        assertNull(NovelScrollMapping.paraIndexOf(content, 99))
    }

    @Test
    fun `空内容不崩`() {
        val empty = ChapterContent(0, "", emptyList(), emptyMap(), emptyList())
        assertTrue(NovelScrollMapping.visibleParagraphs(empty).isEmpty())
        assertEquals(0, NovelScrollMapping.positionOf(empty, 5))
        assertNull(NovelScrollMapping.paraIndexOf(empty, 0))
    }

    // ===== 段落号定位页 =====

    @Test
    fun `段落号定位到包含它的页`() {
        val repo = NovelChapterRepository()
        val pages = listOf(
            NovelPage(listOf(PageSegment(0, 0, 5), PageSegment(2, 0, 3))),
            NovelPage(listOf(PageSegment(2, 3, 6))),
            NovelPage(listOf(PageSegment(9, 0, 4))),
        )
        assertEquals(0, repo.pageOfParagraph(pages, 0))
        assertEquals(0, repo.pageOfParagraph(pages, 2))
        assertEquals(2, repo.pageOfParagraph(pages, 9))
        assertEquals("超出末段时取末页", 2, repo.pageOfParagraph(pages, 100))
        assertEquals("空页表返回 0", 0, repo.pageOfParagraph(emptyList(), 3))
    }
}
