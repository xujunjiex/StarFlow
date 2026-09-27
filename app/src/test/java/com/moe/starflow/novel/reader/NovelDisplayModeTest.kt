package com.moe.starflow.novel.reader

import com.moe.starflow.R
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
            NovelPage(listOf(PageSegment(0, 0, 5, 0, 1), PageSegment(2, 0, 3, 0, 1))),
            NovelPage(listOf(PageSegment(2, 3, 6, 1, 2))),
            NovelPage(listOf(PageSegment(9, 0, 4, 0, 1))),
        )
        assertEquals(0, repo.pageOfParagraph(pages, 0))
        assertEquals(0, repo.pageOfParagraph(pages, 2))
        assertEquals(2, repo.pageOfParagraph(pages, 9))
        assertEquals("超出末段时取末页", 2, repo.pageOfParagraph(pages, 100))
        assertEquals("空页表返回 0", 0, repo.pageOfParagraph(emptyList(), 3))
    }

    // ===== 已翻译页（底部进度条绿块） =====

    private fun seg(paraIndex: Int) = PageSegment(paraIndex, 0, 1, 0, 1)

    /**
     * 已翻译页 = **整页的段都有译文**；而且页边界一变（改字号/行距/边距）必须**重算**。
     *
     * ⚠️ 不重算的话绿块会停在旧位置：用户改了字号，进度条上"翻到哪"就骗人了。
     */
    @Test
    fun `已翻译页随页边界变化重算`() {
        val translated = setOf(0, 1, 2, 3)
        val wide = listOf(
            NovelPage(listOf(seg(0), seg(1))),
            NovelPage(listOf(seg(2), seg(3))),
        )
        val narrow = listOf(
            NovelPage(listOf(seg(0))),
            NovelPage(listOf(seg(1))),
            NovelPage(listOf(seg(2))),
            NovelPage(listOf(seg(3))),
        )
        assertEquals(setOf(0, 1), translatedPagesOf(wide, setOf(0, 1, 2, 3), translated))
        assertEquals(
            "页变窄后每一页都各自成页",
            setOf(0, 1, 2, 3),
            translatedPagesOf(narrow, setOf(0, 1, 2, 3), translated),
        )
    }

    /**
     * **图片段不参与「这页翻完没有」的判据**（回归）。
     *
     * `📷 [图片]` 占位段按设计永远不翻译，要求它「有译文」的话，插图版 EPUB 的每一页
     * 都永远变不绿 —— 底部进度条对这类书永远差一截，用户以为译文丢了。
     */
    @Test
    fun `含图片段的一页只要可翻译段都翻了就算翻好`() {
        val withImage = listOf(NovelPage(listOf(seg(0), seg(1), seg(2))))
        // 段 1 是图片段（不在可翻译集里）→ 只有 0 与 2 需要译文
        assertEquals(setOf(0), translatedPagesOf(withImage, setOf(0, 2), setOf(0, 2)))

        assertEquals(
            "可翻译段缺一半就不算翻好",
            emptySet<Int>(),
            translatedPagesOf(withImage, setOf(0, 2), setOf(0)),
        )
    }

    /** 整页都是不可翻译段（只有图片）→ 没有待办，算翻好。 */
    @Test
    fun `整页都是图片段算翻好`() {
        val onlyImage = listOf(NovelPage(listOf(seg(0))))
        assertEquals(setOf(0), translatedPagesOf(onlyImage, emptySet(), emptySet()))
    }

    /** 半页译文**不算**这页翻好了：否则用户以为整页都翻完了。 */
    @Test
    fun `半页译文不画绿`() {
        val wide = listOf(
            NovelPage(listOf(seg(0), seg(1))),
            NovelPage(listOf(seg(2), seg(3))),
        )
        assertEquals(setOf(0), translatedPagesOf(wide, setOf(0, 1, 2, 3), setOf(0, 1, 2)))
    }

    /**
     * 章标题规则：**有标题就只显示标题**。
     *
     * ⚠️ 没有章节标记的书会被兜底切成「第1节」「第2节」…；再在前面拼「第N章」就变成
     * 「第1章 第1节」的双编号，用户看到只会觉得莫名其妙。
     */
    @Test
    fun `有标题时章标题不拼第N章`() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        assertEquals("第1节", chapterDisplayTitle(ctx, 0, "第1节"))
        assertEquals("雾港的清晨", chapterDisplayTitle(ctx, 0, "雾港的清晨"))
        assertEquals("有空白也只算没标题", ctx.getString(R.string.novel_chapter_label, 1), chapterDisplayTitle(ctx, 0, "   "))
        assertEquals("没标题才用第N章兜底", ctx.getString(R.string.novel_chapter_label, 1), chapterDisplayTitle(ctx, 0, null))
    }

    /** 空页不算「已翻译」（否则进度条上会凭空多一段绿）。 */
    @Test
    fun `空页不画绿`() {
        assertEquals(emptySet<Int>(), translatedPagesOf(listOf(NovelPage(emptyList())), setOf(0, 1), setOf(0)))
    }
}
