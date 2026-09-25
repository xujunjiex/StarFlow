package com.moe.starflow.novel.reader

import com.moe.starflow.novel.translate.NovelParagraph
import com.moe.starflow.novel.translate.NovelParagraphType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

class NovelPaginatorTest {

    private fun textPara(index: Int, text: String) =
        NovelParagraph(index, NovelParagraphType.TEXT, text)

    /** 合成「每段行起点」：每 [charsPerLine] 字一行。 */
    private fun lineStarts(paragraphs: List<NovelParagraph>, charsPerLine: Int): List<IntArray> =
        paragraphs.map { p ->
            val n = ((p.originalText.length + charsPerLine - 1) / charsPerLine).coerceAtLeast(1)
            IntArray(n) { i -> i * charsPerLine }
        }

    private val visible = { ps: List<NovelParagraph> -> ps.filter { it.type != NovelParagraphType.SKIP } }

    /**
     * 硬约束守卫：对**每一段**，其全部 segment 必须首尾相接且完整覆盖 `[0, 长度)`，无重叠无丢字。
     */
    private fun assertFullCoverage(paragraphs: List<NovelParagraph>, pages: List<NovelPage>) {
        val byPara = linkedMapOf<Int, MutableList<PageSegment>>()
        for (seg in pages.flatMap { it.segments }) {
            byPara.getOrPut(seg.paraIndex) { mutableListOf() }.add(seg)
        }
        val expected = visible(paragraphs)
        assertEquals("segment 覆盖的段数应与可见段数一致", expected.size, byPara.size)
        for (p in expected) {
            val segs = byPara[p.index] ?: error("段 ${p.index} 没有任何 segment")
            var cursor = 0
            for (s in segs) {
                assertEquals("段 ${p.index} 的 segment 起点必须接上一个终点", cursor, s.charStart)
                assertTrue("段 ${p.index} 出现空 segment", s.charEnd > s.charStart)
                cursor = s.charEnd
            }
            assertEquals("段 ${p.index} 的 segment 必须覆盖整段", p.originalText.length, cursor)
        }
        // 段落顺序不得回退（分页只允许向后推进）
        var lastPara = -1
        for (seg in pages.flatMap { it.segments }) {
            assertTrue("segment 顺序回退：${seg.paraIndex} 出现在 $lastPara 之后", seg.paraIndex >= lastPara)
            lastPara = seg.paraIndex
        }
    }

    /** 每页占用的行数不得超过容量。 */
    private fun assertPageCapacity(
        paragraphs: List<NovelParagraph>,
        lineStarts: List<IntArray>,
        pages: List<NovelPage>,
        lineHeightPx: Float,
        pageHeightPx: Float,
    ) {
        val capacity = floor(pageHeightPx / lineHeightPx + 0.01f).toInt().coerceAtLeast(1)
        val startByIndex = visible(paragraphs).mapIndexed { i, p -> p.index to lineStarts[i] }.toMap()
        for ((pi, page) in pages.withIndex()) {
            val lines = page.segments.sumOf { seg ->
                val starts = startByIndex.getValue(seg.paraIndex)
                starts.count { it >= seg.charStart && it < seg.charEnd }
            }
            assertTrue("第 $pi 页占了 $lines 行，超过容量 $capacity", lines <= capacity)
        }
    }

    @Test
    fun `字符区间完整覆盖无丢字无重叠`() {
        val paras = listOf(
            textPara(0, "一二三四五六七八九十"),
            textPara(1, "甲乙丙丁戊"),
            textPara(2, "ABCDEFGHIJKLMNOP"),
        )
        val pages = NovelPaginator.paginateByLines(
            paras, lineStarts(paras, charsPerLine = 5),
            lineHeightPx = 50f, paragraphSpacingPx = 20f, pageHeightPx = 160f,
        )
        assertFullCoverage(paras, pages)
    }

    @Test
    fun `短文本一页放下时每段恰好一个 segment`() {
        val paras = listOf(textPara(0, "一二三四五"), textPara(1, "六七八九十"))
        val pages = NovelPaginator.paginateByLines(
            paras, lineStarts(paras, charsPerLine = 5),
            lineHeightPx = 50f, paragraphSpacingPx = 0f, pageHeightPx = 200f,
        )
        assertEquals(1, pages.size)
        assertEquals(listOf(0, 1), pages[0].segments.map { it.paraIndex })
        assertFullCoverage(paras, pages)
    }

    @Test
    fun `超长段被拆成多个 segment 且区间连续`() {
        val paras = listOf(textPara(0, "字".repeat(100)))   // 20 行 @5字/行
        val pages = NovelPaginator.paginateByLines(
            paras, lineStarts(paras, charsPerLine = 5),
            lineHeightPx = 50f, paragraphSpacingPx = 0f, pageHeightPx = 200f,   // 4 行/页
        )
        assertTrue("应切出多页，实际 ${pages.size}", pages.size >= 5)
        assertFullCoverage(paras, pages)
        assertEquals(0, pages.first().segments.first().charStart)
        assertEquals(100, pages.last().segments.last().charEnd)
    }

    @Test
    fun `页高不足一行时仍放一行不死循环`() {
        val paras = listOf(textPara(0, "一二三"), textPara(1, "四五六"))
        val pages = NovelPaginator.paginateByLines(
            paras, lineStarts(paras, charsPerLine = 3),
            lineHeightPx = 500f, paragraphSpacingPx = 100f, pageHeightPx = 50f,
        )
        assertEquals(2, pages.size)
        assertTrue(pages.all { it.segments.isNotEmpty() })
        assertFullCoverage(paras, pages)
    }

    @Test
    fun `SKIP 段不进分页`() {
        val paras = listOf(
            textPara(0, "正文一"),
            NovelParagraph(1, NovelParagraphType.SKIP, "……"),
            textPara(2, "正文二"),
        )
        val pages = NovelPaginator.paginateByLines(
            paras, lineStarts(paras, charsPerLine = 5),
            lineHeightPx = 50f, paragraphSpacingPx = 0f, pageHeightPx = 500f,
        )
        assertEquals(listOf(0, 2), pages.single().segments.map { it.paraIndex })
        assertFullCoverage(paras, pages)
    }

    @Test
    fun `空段落列表得到空页表`() {
        assertTrue(NovelPaginator.paginateByLines(emptyList(), emptyList(), 50f, 0f, 200f).isEmpty())
        // 全是 SKIP 也等于没有可显示内容
        val onlySkip = listOf(NovelParagraph(0, NovelParagraphType.SKIP, "……"))
        assertTrue(
            NovelPaginator.paginateByLines(onlySkip, listOf(intArrayOf(0)), 50f, 0f, 200f).isEmpty()
        )
    }

    @Test
    fun `页高足够时不无谓地多切页`() {
        val paras = (0 until 3).map { textPara(it, "字".repeat(10)) }   // 每段 2 行
        val pages = NovelPaginator.paginateByLines(
            paras, lineStarts(paras, charsPerLine = 5),
            lineHeightPx = 50f, paragraphSpacingPx = 0f, pageHeightPx = 500f,   // 10 行容量
        )
        assertEquals("6 行应放得进一页", 1, pages.size)
        assertFullCoverage(paras, pages)
    }

    @Test
    fun `每页不超过容量`() {
        val paras = (0 until 10).map { textPara(it, "字".repeat(30)) }   // 每段 3 行
        val starts = lineStarts(paras, charsPerLine = 10)
        val pages = NovelPaginator.paginateByLines(
            paras, starts, lineHeightPx = 50f, paragraphSpacingPx = 20f, pageHeightPx = 260f,
        )
        assertFullCoverage(paras, pages)
        assertPageCapacity(paras, starts, pages, lineHeightPx = 50f, pageHeightPx = 260f)
        assertTrue("10 段共 30 行，应切出多页，实际 ${pages.size}", pages.size >= 6)
    }

    @Test
    fun `行起点缺失时按整段一行处理而不是丢段`() {
        val paras = listOf(textPara(0, "内容一"), textPara(1, "内容二"))
        val pages = NovelPaginator.paginateByLines(
            paras, listOf(intArrayOf(0), intArrayOf(0)),
            lineHeightPx = 50f, paragraphSpacingPx = 0f, pageHeightPx = 60f,   // 1 行/页
        )
        assertEquals(2, pages.size)
        assertFullCoverage(paras, pages)
    }

    @Test
    fun `各种参数组合下覆盖率恒成立`() {
        val paras = listOf(
            textPara(0, "短"),
            textPara(1, "字".repeat(37)),
            textPara(2, "中等长度的一段文字"),
            textPara(3, "字".repeat(120)),
            textPara(4, "结尾段落"),
        )
        for (charsPerLine in listOf(3, 7, 20)) {
            for (pageHeight in listOf(40f, 120f, 300f, 1000f)) {
                for (spacing in listOf(0f, 10f, 60f)) {
                    val pages = NovelPaginator.paginateByLines(
                        paras, lineStarts(paras, charsPerLine),
                        lineHeightPx = 50f, paragraphSpacingPx = spacing, pageHeightPx = pageHeight,
                    )
                    assertFullCoverage(paras, pages)
                    assertPageCapacity(paras, lineStarts(paras, charsPerLine), pages, 50f, pageHeight)
                }
            }
        }
    }

    // ===== 整段优先（「每页翻译完整、句子不跨页中断」的前提） =====

    /**
     * 放不下的段要**整体**挪到下一页，不能从中间切开。
     *
     * 切开的后果不是"排版难看"：同一段被拆到两页上，翻译也只能按半段来，
     * 读者看到的是半句话（用户明确要求「保证当前段落句子完整，不会出现句子中断」）。
     */
    @Test
    fun `整段优先：放不下的段落整体挪到下一页而不是切开`() {
        val paras = listOf(
            textPara(0, "a".repeat(40)),
            textPara(1, "b".repeat(40)),
            textPara(2, "c".repeat(40)),
        )
        // 每段 4 行（40 字 / 每行 10 字），每页 10 行
        val pages = NovelPaginator.paginateByLines(
            paras, lineStarts(paras, 10),
            lineHeightPx = 10f, paragraphSpacingPx = 0f, pageHeightPx = 100f,
        )
        assertEquals("页1 放 p0+p1（8 行），p2 放不下 → 整段进页2", 2, pages.size)
        assertEquals(listOf(0, 1), pages[0].segments.map { it.paraIndex })
        assertEquals(listOf(2), pages[1].segments.map { it.paraIndex })
        for (page in pages) {
            for (seg in page.segments) {
                assertEquals("段落被切开了（起点不是 0）", 0, seg.charStart)
                assertEquals("段落被切开了（终点不是段长）", 40, seg.charEnd)
            }
        }
        assertFullCoverage(paras, pages)
    }

    /** 比一整页还长的段无路可走，只能按行切 —— 但不能因此丢字。 */
    @Test
    fun `超过一整页的段落仍会被按行切分且不丢字`() {
        val paras = listOf(textPara(0, "a".repeat(250)))
        val pages = NovelPaginator.paginateByLines(
            paras, lineStarts(paras, 10),
            lineHeightPx = 10f, paragraphSpacingPx = 0f, pageHeightPx = 100f,
        )
        assertEquals("25 行 / 每页 10 行 = 3 页", 3, pages.size)
        assertTrue("必须被切成多段", pages.all { it.segments.size == 1 })
        assertFullCoverage(paras, pages)
    }

    /** 关掉整段优先时行为与老实现一致（按行填满，段可以被切开）。 */
    @Test
    fun `关闭整段优先后按行填满`() {
        // 每段 6 行、每页 10 行：整段优先 → 一段一页共 3 页；按行填满 → 2 页且必有段被切开
        val paras = listOf(
            textPara(0, "a".repeat(60)),
            textPara(1, "b".repeat(60)),
            textPara(2, "c".repeat(60)),
        )
        val starts = lineStarts(paras, 10)

        val whole = NovelPaginator.paginateByLines(paras, starts, 10f, 0f, 100f)
        assertEquals("整段优先：每段独占一页", 3, whole.size)
        assertTrue("整段优先下不允许有任何段被切开", whole.all { it.segments.all { s -> s.charStart == 0 } })

        val filled = NovelPaginator.paginateByLines(paras, starts, 10f, 0f, 100f, keepParagraphsWhole = false)
        assertEquals("按行填满：12 行装进 10 行的页 → 2 页", 2, filled.size)
        assertTrue(
            "按行填满应当出现被切开的段",
            filled.flatMap { it.segments }.any { it.charStart != 0 },
        )
        assertFullCoverage(paras, filled)
    }
}
