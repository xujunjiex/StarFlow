package com.moe.starflow.novel.reader

import com.moe.starflow.novel.translate.NovelParagraph
import com.moe.starflow.novel.translate.NovelParagraphType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelPaginatorTest {

    private fun textPara(index: Int, text: String) =
        NovelParagraph(index, NovelParagraphType.TEXT, text)

    /**
     * 合成度量：`lineStarts[i]` / `lineHeights[i]` 是第 i 个**可见段**每行的起点与高度。
     *
     * 真机上这两个数组由 `StaticLayout` 量出来（见 `NovelPaginator.paginate`）；
     * Robolectric 的文本引擎是桩，所以测试自己合成 —— 分页逻辑与文本引擎解耦，正是为了这个。
     */
    private class Measured(val starts: List<IntArray>, val heights: List<FloatArray>)

    /** 每 [charsPerLine] 字一行、每行 [lineHeightPx] 高（均匀行高）。 */
    private fun measured(
        paragraphs: List<NovelParagraph>,
        charsPerLine: Int,
        lineHeightPx: Float = 50f,
    ): Measured {
        val visible = paragraphs.filter { it.type != NovelParagraphType.SKIP }
        val starts = visible.map { p ->
            val n = ((p.originalText.length + charsPerLine - 1) / charsPerLine).coerceAtLeast(1)
            IntArray(n) { i -> i * charsPerLine }
        }
        return Measured(starts, starts.map { a -> FloatArray(a.size) { lineHeightPx } })
    }

    /** 直接给每段的**逐行**高度（测「某一行更高」这类非均匀行高）。 */
    private fun measuredByLines(
        paragraphs: List<NovelParagraph>,
        charsPerLine: Int,
        vararg lineHeights: Float,
    ): Measured {
        val visible = paragraphs.filter { it.type != NovelParagraphType.SKIP }
        val starts = visible.map { p ->
            val n = ((p.originalText.length + charsPerLine - 1) / charsPerLine).coerceAtLeast(1)
            IntArray(n) { i -> i * charsPerLine }
        }
        return Measured(
            starts,
            starts.map { a ->
                FloatArray(a.size) { i -> lineHeights.getOrElse(i) { lineHeights.last() } }
            },
        )
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

    /**
     * **锚点之前的内容从下往上填**：锚点顶到页首之后，前面每一页仍然要满。
     *
     * 上一版是"从头往下填、填到锚点就提前收页"，那会让锚点前一页只剩小半页、底部一大片空白
     * （用户报的「有的页面提前莫名其妙分页，底部预留出大片空白」，双语↔译文/原文来回切时最明显）。
     */
    @Test
    fun `锚点之前的内容从下往上填满`() {
        // 一段 10 行、每行 100px；一页 500px = 5 行
        val paras = listOf(textPara(0, "字".repeat(100)))
        val m = measured(paras, charsPerLine = 10, lineHeightPx = 100f)
        val style = NovelTextStyle(50f, 1.2f, 0f, 0f)

        // 锚点在第 7 行（下标 7）
        val pages = NovelPaginator.paginateAround(paras, m.starts, m.heights, style, 500f, 0 to 7)

        assertEquals("开头那 2 行自成第一页（章首本来就填不满）", 2, pages[0].segments.sumOf { it.lineEnd - it.lineStart })
        assertEquals("锚点前的最后一页必须是满的（5 行）", 5, pages[1].segments.sumOf { it.lineEnd - it.lineStart })
        assertEquals("锚点行必须正好是新一页的第一行", 7, pages[2].segments.first().lineStart)
        assertFullCoverage(paras, pages)
        assertLineCoverage(pages)
    }

    /**
     * **倒着填也要尊重「保持段落完整」**：开着这个开关时，锚点之前那一页不能把段落从中间切开
     * （正向分页有这条规则，倒着填漏掉的话，同一本书前后两半的排版规则就不一致了）。
     */
    @Test
    fun `倒着填时整段优先仍然生效`() {
        // 三段各 2 行、行高 100px；一页 300px → 装得下一段 + 另一段的一行，但不是整段
        val paras = (0 until 3).map { textPara(it, "字".repeat(20)) }
        val m = measured(paras, charsPerLine = 10, lineHeightPx = 100f)
        val style = NovelTextStyle(50f, 1f, 0f, 0f, keepParagraphsWhole = true)

        // 锚点 = 第 3 段（下标 2）的第 0 行 → 前两段要倒着填
        val pages = NovelPaginator.paginateAround(paras, m.starts, m.heights, style, 300f, 2 to 0)

        val segsOf0 = pages.flatMap { it.segments }.filter { it.paraIndex == 0 }
        assertEquals("第 0 段被拆开了：$segsOf0", 1, segsOf0.size)
        assertEquals(0, segsOf0.first().lineStart)
        assertEquals(2, segsOf0.first().lineEnd)
        assertFullCoverage(paras, pages)
        assertLineCoverage(pages)
    }

    /**
     * **值不值得强行分页**：锚点在页面上半部分就别强分 —— 强分会让**上一页提前结束**、
     * 底部留一大片空白（用户报的「三态切换后翻页，有页面提前分页、底部大片空白」）。
     * 在下半部分才强分（否则读者要往回跳将近一屏）。
     */
    @Test
    fun `锚点在上半页不强分在下半页才强分`() {
        // 一段 100 字 = 10 行、每行 100px；一页 400px = 4 行
        val paras = listOf(textPara(0, "字".repeat(100)))
        val m = measured(paras, charsPerLine = 10, lineHeightPx = 100f)
        val pages = NovelPaginator.paginateByLines(paras, m.starts, m.heights, 0f, 400f)
        val visOf = mapOf(0 to 0)

        // 第 1 行 = 离页顶 100px（25%）→ 不强分
        assertEquals(
            false,
            NovelPaginator.shouldForceBreak(pages, visOf, 0, 1, m.heights, 0f, 400f),
        )
        // 第 3 行 = 离页顶 300px（75%）→ 强分
        assertEquals(
            true,
            NovelPaginator.shouldForceBreak(pages, visOf, 0, 3, m.heights, 0f, 400f),
        )
        // 锚点不在页表里（段被跳过）→ 不强分，别制造莫须有的分页
        assertEquals(
            false,
            NovelPaginator.shouldForceBreak(pages, visOf, 99, 0, m.heights, 0f, 400f),
        )
    }

    /**
     * 行区间必须**首尾相接**地覆盖整段 —— 行区间是渲染的唯一几何来源，
     * 这里断了的话画出来就少一行或多一行。
     */
    private fun assertLineCoverage(pages: List<NovelPage>) {
        val byPara = linkedMapOf<Int, MutableList<PageSegment>>()
        for (seg in pages.flatMap { it.segments }) {
            byPara.getOrPut(seg.paraIndex) { mutableListOf() }.add(seg)
        }
        for ((paraIndex, segs) in byPara) {
            var cursor = 0
            for (s in segs) {
                assertEquals("段 $paraIndex 的行区间不连续", cursor, s.lineStart)
                assertTrue("段 $paraIndex 出现空行区间", s.lineEnd > s.lineStart)
                cursor = s.lineEnd
            }
        }
    }

    /** 每页占用的高度（按**逐行真实高度**累加）不得超过容量。 */
    private fun assertPageHeight(
        measured: Measured,
        pages: List<NovelPage>,
        paragraphSpacingPx: Float,
        pageHeightPx: Float,
    ) {
        val indexOf = visibleIndex(measured)
        // 兜底：一整行比整页还高时只能硬放一行（否则死循环），这一行必然"超框"，
        // 那不是记账错误。所以容量在这种极端配置下放宽到「一行的高度」。
        val tallestLine = measured.heights.maxOf { it.maxOrNull() ?: 0f }
        val limit = maxOf(pageHeightPx, tallestLine)
        for ((pi, page) in pages.withIndex()) {
            var used = 0f
            for ((i, seg) in page.segments.withIndex()) {
                val heights = indexOf(seg.paraIndex)
                for (line in seg.lineStart until seg.lineEnd) used += heights[line]
                if (i != page.segments.lastIndex) used += paragraphSpacingPx
            }
            assertTrue(
                "第 ${pi + 1} 页占了 $used px，超过容量 $limit",
                used <= limit + 0.01f,
            )
        }
    }

    /** 段落号 → 该段的逐行高度（测试里段落号与可见下标一一对应）。 */
    private fun visibleIndex(measured: Measured): (Int) -> FloatArray = { paraIndex ->
        measured.heights[paraIndex]
    }

    @Test
    fun `字符区间完整覆盖无丢字无重叠`() {
        val paras = listOf(
            textPara(0, "一二三四五六七八九十"),
            textPara(1, "甲乙丙丁戊"),
            textPara(2, "ABCDEFGHIJKLMNOP"),
        )
        val m = measured(paras, charsPerLine = 5)
        val pages = NovelPaginator.paginateByLines(paras, m.starts, m.heights, 20f, 160f)
        assertFullCoverage(paras, pages)
        assertLineCoverage(pages)
    }

    @Test
    fun `短文本一页放下时每段恰好一个 segment`() {
        val paras = listOf(textPara(0, "一二三四五"), textPara(1, "六七八九十"))
        val m = measured(paras, charsPerLine = 5)
        val pages = NovelPaginator.paginateByLines(paras, m.starts, m.heights, 0f, 200f)
        assertEquals(1, pages.size)
        assertEquals(listOf(0, 1), pages[0].segments.map { it.paraIndex })
        assertFullCoverage(paras, pages)
    }

    @Test
    fun `超长段被拆成多个 segment 且区间连续`() {
        val paras = listOf(textPara(0, "字".repeat(100)))   // 20 行 @5字/行
        val m = measured(paras, charsPerLine = 5)           // 行高 50 → 每页 4 行
        val pages = NovelPaginator.paginateByLines(paras, m.starts, m.heights, 0f, 200f)
        assertTrue("应切出多页，实际 ${pages.size}", pages.size >= 5)
        assertFullCoverage(paras, pages)
        assertLineCoverage(pages)
        assertEquals(0, pages.first().segments.first().charStart)
        assertEquals(100, pages.last().segments.last().charEnd)
    }

    @Test
    fun `页高不足一行时仍放一行不死循环`() {
        val paras = listOf(textPara(0, "一二三"), textPara(1, "四五六"))
        val m = measured(paras, charsPerLine = 3, lineHeightPx = 500f)
        val pages = NovelPaginator.paginateByLines(paras, m.starts, m.heights, 100f, 50f)
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
        val m = measured(paras, charsPerLine = 5)
        val pages = NovelPaginator.paginateByLines(paras, m.starts, m.heights, 0f, 500f)
        assertEquals(listOf(0, 2), pages.single().segments.map { it.paraIndex })
        assertFullCoverage(paras, pages)
    }

    @Test
    fun `空段落列表得到空页表`() {
        assertTrue(NovelPaginator.paginateByLines(emptyList(), emptyList(), emptyList(), 50f, 200f).isEmpty())
        // 全是 SKIP 也等于没有可显示内容
        val onlySkip = listOf(NovelParagraph(0, NovelParagraphType.SKIP, "……"))
        assertTrue(
            NovelPaginator.paginateByLines(onlySkip, listOf(intArrayOf(0)), listOf(floatArrayOf(50f)), 0f, 200f)
                .isEmpty()
        )
    }

    @Test
    fun `页高足够时不无谓地多切页`() {
        val paras = (0 until 3).map { textPara(it, "字".repeat(10)) }   // 每段 2 行
        val m = measured(paras, charsPerLine = 5)                      // 共 6 行 = 300px
        val pages = NovelPaginator.paginateByLines(paras, m.starts, m.heights, 0f, 500f)
        assertEquals("6 行应放得进一页", 1, pages.size)
        assertFullCoverage(paras, pages)
    }

    @Test
    fun `每页不超过容量`() {
        val paras = (0 until 10).map { textPara(it, "字".repeat(30)) }   // 每段 3 行
        val m = measured(paras, charsPerLine = 10)
        val pages = NovelPaginator.paginateByLines(paras, m.starts, m.heights, 20f, 260f)
        assertFullCoverage(paras, pages)
        assertLineCoverage(pages)
        assertPageHeight(m, pages, 20f, 260f)
        assertTrue("10 段共 30 行，应切出多页，实际 ${pages.size}", pages.size >= 6)
    }

    @Test
    fun `行起点缺失时按整段一行处理而不是丢段`() {
        val paras = listOf(textPara(0, "内容一"), textPara(1, "内容二"))
        val pages = NovelPaginator.paginateByLines(
            paras, listOf(intArrayOf(0), intArrayOf(0)), listOf(floatArrayOf(50f), floatArrayOf(50f)),
            0f, 60f,
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
                    val m = measured(paras, charsPerLine)
                    val pages = NovelPaginator.paginateByLines(paras, m.starts, m.heights, spacing, pageHeight)
                    assertFullCoverage(paras, pages)
                    assertLineCoverage(pages)
                    assertPageHeight(m, pages, spacing, pageHeight)
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
        val m = measured(paras, charsPerLine = 10, lineHeightPx = 10f)
        val pages = NovelPaginator.paginateByLines(paras, m.starts, m.heights, 0f, 100f)
        assertEquals("页1 放 p0+p1（8 行），p2 放不下 → 整段进页2", 2, pages.size)
        assertEquals(listOf(0, 1), pages[0].segments.map { it.paraIndex })
        assertEquals(listOf(2), pages[1].segments.map { it.paraIndex })
        for (page in pages) {
            for (seg in page.segments) {
                assertEquals("段落被切开了（起点不是 0）", 0, seg.charStart)
                assertEquals("段落被切开了（终点不是段长）", 40, seg.charEnd)
                assertEquals("段落被切开了（起点不是第 0 行）", 0, seg.lineStart)
            }
        }
        assertFullCoverage(paras, pages)
    }

    /** 比一整页还长的段无路可走，只能按行切 —— 但不能因此丢字。 */
    @Test
    fun `超过一整页的段落仍会被按行切分且不丢字`() {
        val paras = listOf(textPara(0, "a".repeat(250)))
        val m = measured(paras, charsPerLine = 10, lineHeightPx = 10f)
        val pages = NovelPaginator.paginateByLines(paras, m.starts, m.heights, 0f, 100f)
        assertEquals("25 行 / 每页 10 行 = 3 页", 3, pages.size)
        assertTrue("必须被切成多段", pages.all { it.segments.size == 1 })
        assertFullCoverage(paras, pages)
        assertLineCoverage(pages)
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
        val m = measured(paras, charsPerLine = 10, lineHeightPx = 10f)

        val whole = NovelPaginator.paginateByLines(paras, m.starts, m.heights, 0f, 100f)
        assertEquals("整段优先：每段独占一页", 3, whole.size)
        assertTrue("整段优先下不允许有任何段被切开", whole.all { it.segments.all { s -> s.charStart == 0 } })

        val filled = NovelPaginator.paginateByLines(paras, m.starts, m.heights, 0f, 100f, keepParagraphsWhole = false)
        assertEquals("按行填满：12 行装进 10 行的页 → 2 页", 2, filled.size)
        assertTrue(
            "按行填满应当出现被切开的段",
            filled.flatMap { it.segments }.any { it.charStart != 0 },
        )
        assertFullCoverage(paras, filled)
    }

    // ===== 高度口径：只认量出来的行高，不做任何估算 =====

    /**
     * **逐行真实高度必须被逐行使用** —— 这是「底部被裁切」的根治点。
     *
     * 早期实现拿一个探针量出**单一**行高再乘行数：只要某一行比探针高（换了字体/表情/
     * 全角标点落在另的字体上），那一页就被多塞一行、底部的字被画到框外。
     * 现在高度直接来自 `StaticLayout.getLineBottom(i) - getLineTop(i)`，逐行累加。
     *
     * 判据：中间那行更高时，本页必须**放不下**它 —— 而不是按其它行的高度把它算进来。
     */
    @Test
    fun `逐行真实高度：某一行更高时不得按其它行的高度记账`() {
        val paras = listOf(textPara(0, "字".repeat(30)))   // 3 行 @10 字/行
        val m = measuredByLines(paras, charsPerLine = 10, 100f, 100f, 200f)
        val pageHeight = 300f

        val pages = NovelPaginator.paginateByLines(paras, m.starts, m.heights, 0f, pageHeight)

        // 100 + 100 = 200 放得下；再加 200 就是 400 > 300 → 第 3 行必须进下一页
        assertEquals("第 3 行（高 200）必须被挤到下一页", 2, pages.size)
        assertEquals(listOf(0 to 2), pages[0].segments.map { it.lineStart to it.lineEnd })
        assertEquals(listOf(2 to 3), pages[1].segments.map { it.lineStart to it.lineEnd })
        assertPageHeight(m, pages, 0f, pageHeight)
        assertLineCoverage(pages)
    }

    /** 段高也用整段的**真实总高**（各行之和），不是「行数 × 某个估算行高」。 */
    @Test
    fun `整段高度按真实逐行高度求和`() {
        // 2 行：100 + 400 = 500 > 页高 400 → 整段优先不成立，必须被切开
        val paras = listOf(textPara(0, "ab"))
        val m = measuredByLines(paras, charsPerLine = 1, 100f, 400f)
        val pages = NovelPaginator.paginateByLines(paras, m.starts, m.heights, 0f, 400f)
        assertEquals("整段 500px 放不进 400px 的页 → 切成两页", 2, pages.size)
        assertFullCoverage(paras, pages)
        assertPageHeight(m, pages, 0f, 400f)
    }

    /** 段间距不能被量化成整行（旧实现把它折算成整整一行，每段凭空多占几十 px）。 */
    @Test
    fun `段间距不按整行量化：每页剩余空间放不下再多一段`() {
        val lineHeight = 100f
        val spacing = 30f      // 不足一行 —— 旧实现会把它兜成整整一行 100px
        val pageHeight = 1000f
        val paras = (0 until 40).map { textPara(it, "字".repeat(10)) }
        val m = measured(paras, charsPerLine = 10, lineHeightPx = lineHeight)
        val pages = NovelPaginator.paginateByLines(paras, m.starts, m.heights, spacing, pageHeight)

        // 每段 1 行 100px；一段的成本 = 行高 + 段间距（首段无段间距）
        val paraCost = { n: Int -> n * lineHeight + (n - 1) * spacing }
        val perPage = pages.first().segments.size
        println("PXRULE 每页 $perPage 段，一页用 ${paraCost(perPage)} px / 共 $pageHeight px，剩余 ${pageHeight - paraCost(perPage)} px")

        for ((i, page) in pages.withIndex()) {
            val n = page.segments.size
            val used = paraCost(n)
            assertTrue("第 ${i + 1} 页超出容量", used <= pageHeight + 0.5f)
            // 只剩最后一页时不需要满足：末尾本来就会留白
            if (i < pages.size - 1) {
                assertTrue(
                    "第 ${i + 1} 页剩余 ${pageHeight - used} px 还能再放一段（${paraCost(n + 1) - used} px）—— 白留了",
                    pageHeight - used < paraCost(n + 1) - used,
                )
            }
        }
    }
}
