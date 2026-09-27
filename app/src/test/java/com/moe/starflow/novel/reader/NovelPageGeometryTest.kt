package com.moe.starflow.novel.reader

import com.moe.starflow.novel.translate.NovelParagraph
import com.moe.starflow.novel.translate.NovelParagraphType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import android.view.View

/**
 * 「逐段累加行高 + 段间距只补在段之间」这套走法**只有一个实现**（[NovelPageGeometry]），
 * 三处调用者（绘制 / 命中测试 / 分页量锚点偏移）必须得到同一份结果。
 *
 * ### 为什么这条测试值得写
 * 三处以前各写一遍、各挂一句"必须与绘制同一套走法"的注释。约定靠自觉，改错一处只有真机上
 * 看图才发现（见 `novel/CLAUDE.md`：Robolectric 的文本引擎是桩，几何错误盖不住）。
 * 这里伪造一页度量把三者的**算术**钉死：度量是合成的，走法是纯函数，与文本引擎无关。
 *
 * ⚠️ `NovelPageView.paraIndexAt` 那条是真 `View`（Robolectric 下能 inflate/measure/layout），
 * 它内部自己排 layout —— 断言用的期望值由测试**另排一份同一输入**的 layout 推出。两边只要
 * 走法不同就会错位，所以这条断言有效（不是把实现拿来跟自己对答案）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelPageGeometryTest {

    // ===== 合成度量：一页三段，段高不等、段间距非 0 =====

    /** 第 0 段 2 行 / 第 1 段 1 行（更高）/ 第 2 段 3 行（高矮不一）。 */
    private val heights = mapOf(
        0 to floatArrayOf(100f, 100f),
        1 to floatArrayOf(200f),
        2 to floatArrayOf(50f, 60f, 70f),
    )

    private val segments = listOf(
        PageSegment(paraIndex = 0, charStart = 0, charEnd = 2, lineStart = 0, lineEnd = 2),
        PageSegment(paraIndex = 1, charStart = 0, charEnd = 1, lineStart = 0, lineEnd = 1),
        PageSegment(paraIndex = 2, charStart = 0, charEnd = 3, lineStart = 0, lineEnd = 3),
    )

    private val spacing = 30f

    /** 走法算出的 `(段号, top, bottom)` 序列。 */
    private fun walkTops(
        segs: List<PageSegment> = segments,
        startY: Float = 0f,
    ): List<Triple<Int, Float, Float>> {
        val out = mutableListOf<Triple<Int, Float, Float>>()
        NovelPageGeometry.walk(segs, { heights[it] }, spacing, startY) { seg, top, bottom ->
            out += Triple(seg.paraIndex, top, bottom)
        }
        return out
    }

    /**
     * 段高 = 该段行区间内**逐行高度之和**，段间距只在段与段之间补一次 ——
     * 页内最后一段之后不补（补了最后一行的下沿就会顶出正文框）。
     */
    @Test
    fun `逐段累加行高 段间距只补在段之间`() {
        assertEquals(
            listOf(
                Triple(0, 0f, 200f),      // 100 + 100
                Triple(1, 230f, 430f),    // +30 段间距，200 单行
                Triple(2, 460f, 640f),    // +30 段间距，50+60+70；页内最后一段，之后不再补
            ),
            walkTops(),
        )
    }

    /** 起始 y 只是整体平移（绘制端给的是正文框顶，分页端给 0）。 */
    @Test
    fun `起始 y 整体平移每段的 top`() {
        assertEquals(
            listOf(Triple(0, 77f, 277f), Triple(1, 307f, 507f), Triple(2, 537f, 717f)),
            walkTops(startY = 77f),
        )
    }

    /**
     * 没有度量的 segment **整个跳过**：既不算高度也不补段间距 ——
     * 补了的话它后面的段会凭空调几十 px、绘制端最后一行被画到框外。
     */
    @Test
    fun `没有度量的段整段跳过 不留下段间距`() {
        val segs = listOf(
            segments[0],
            PageSegment(paraIndex = 99, charStart = 0, charEnd = 1, lineStart = 0, lineEnd = 1),
            segments[1],
        )
        assertEquals(
            listOf(Triple(0, 0f, 200f), Triple(1, 230f, 430f)),
            walkTops(segs),
        )
    }

    /** 行区间越界/为空 → 该段不参与排版（夹取也是走法的一部分）。 */
    @Test
    fun `行区间被夹到该段行数之内`() {
        assertNull("没有度量", NovelPageGeometry.lineRange(segments[2], null))
        assertNull("度量是空表", NovelPageGeometry.lineRange(segments[0], FloatArray(0)))
        assertEquals("lineEnd=3 越过了 2 行的度量 → 夹到 0 until 2", 0 until 2, NovelPageGeometry.lineRange(segments[2], floatArrayOf(1f, 2f)))

        // 夹完之后按 2 行算高：10 + 20，而不是按 segment 里写的 3 行
        val out = mutableListOf<Triple<Int, Float, Float>>()
        NovelPageGeometry.walk(listOf(segments[2]), { floatArrayOf(10f, 20f) }, spacing) { seg, top, bottom ->
            out += Triple(seg.paraIndex, top, bottom)
        }
        assertEquals(listOf(Triple(2, 0f, 30f)), out)
    }

    // ===== 分页端：offsetInPage 必须与走法同源 =====

    /** 直接给每段的逐行高度（不经过文本引擎），行起点一律 0、1、2…（每行一个字）。 */
    private fun measured(
        texts: List<String>,
        heights: List<FloatArray>,
    ): Pair<List<IntArray>, List<FloatArray>> {
        val starts = texts.map { t -> IntArray(t.length) { it } }
        return starts to heights
    }

    private fun textPara(index: Int, text: String) = NovelParagraph(index, NovelParagraphType.TEXT, text)

    private fun parasOf(vararg texts: String) = texts.mapIndexed { i, t -> textPara(i, t) }

    /**
     * `offsetInPage` = **该段 top + 该段前 k 行高度之和**（k = 锚点在段内的行号）。
     *
     * 期望值是手算的常量（不是把走法拿来跟自己对答案），页内每段只有一个 segment。
     */
    @Test
    fun `offsetInPage 给出的偏移等于段顶加段内前 k 行高度`() {
        val paras = parasOf("ab", "c", "def")
        val (starts, hs) = measured(listOf("ab", "c", "def"), listOf(heights[0]!!, heights[1]!!, heights[2]!!))
        val pages = NovelPaginator.paginateByLines(paras, starts, hs, spacing, 1000f)
        assertEquals("三段应装进同一页", 1, pages.size)

        val visOf = mapOf(0 to 0, 1 to 1, 2 to 2)
        fun off(vis: Int, line: Int) = NovelPaginator.offsetInPage(pages, visOf, vis, line, hs, spacing)

        // 段 top：0 / 230 / 460（见上一条测试）
        assertEquals(0f, off(0, 0)!!, 0f)
        assertEquals(100f, off(0, 1)!!, 0f)       // 段 0 第 1 行 = 段顶 + 第 0 行高
        assertEquals(230f, off(1, 0)!!, 0f)       // 段 1 的行区间从 30px 的段间距之后开始
        assertEquals(460f, off(2, 0)!!, 0f)
        assertEquals(510f, off(2, 1)!!, 0f)       // 460 + 50
        assertEquals(570f, off(2, 2)!!, 0f)       // 460 + 50 + 60
        // 段内没有这一行（越界）→ 找不到，不是「近似到最近的段」
        assertNull(off(0, 2))
        assertNull(off(1, 1))
        assertNull(off(2, 3))
        // 段号根本不在页表里 → 同样返回 null（不改动任何一个段的记账）
        assertNull(off(99, 0))
    }

    /** 多页时偏移是**页内**偏移：翻到哪一页，y 都从该页页顶重新起算。 */
    @Test
    fun `offsetInPage 是页内偏移 每页从页顶重新起算`() {
        val paras = parasOf("ab", "c", "def")
        val (starts, hs) = measured(listOf("ab", "c", "def"), listOf(heights[0]!!, heights[1]!!, heights[2]!!))
        val pages = NovelPaginator.paginateByLines(paras, starts, hs, spacing, 300f)
        assertEquals("每段独占一页", 3, pages.size)

        val visOf = mapOf(0 to 0, 1 to 1, 2 to 2)
        // 每段独占一页 → 偏移只算**本页内**的：段 0 的第 1 行是 100（不是 130，页首没有段间距），
        // 段 2 的第 1 行是 50（不是 510 —— 段 1 与段间距都在上一页）
        assertEquals(0f, NovelPaginator.offsetInPage(pages, visOf, 0, 0, hs, spacing)!!, 0f)
        assertEquals(100f, NovelPaginator.offsetInPage(pages, visOf, 0, 1, hs, spacing)!!, 0f)
        assertEquals(0f, NovelPaginator.offsetInPage(pages, visOf, 1, 0, hs, spacing)!!, 0f)
        assertEquals(0f, NovelPaginator.offsetInPage(pages, visOf, 2, 0, hs, spacing)!!, 0f)
        assertEquals(50f, NovelPaginator.offsetInPage(pages, visOf, 2, 1, hs, spacing)!!, 0f)
    }

    // ===== 绘制端：与上面同一条走法（真 View，Robolectric） =====

    /** 一页三段，每段文本都带换行（行数可控），段高因此不等。 */
    private val viewTexts = mapOf(
        0 to "第一段第一行\n第一段第二行",
        1 to "第二段只有一行\n但这一行更长一点\n再来一行",
        2 to "第三段\n第二行\n第三行\n第四行",
    )

    private val viewStyle = NovelTextStyle(
        fontSizePx = 42f,
        lineSpacingMultiplier = 1.5f,
        paragraphSpacingPx = 30f,
        paddingPx = 24f,
        topPaddingPx = 100f,
        bottomPaddingPx = 80f,
    )

    private val viewWidth = 600

    /**
     * `NovelPageView.paraIndexAt` 与走法必须给出同一张"y → 段"的表：
     * - 每段中点 → 该段
     * - 段与段之间的空隙 → **上一段**（空隙只有段间距几十像素，归"空白"太容易误退出选择模式）
     * - 正文上下之外 → null（真正的空白，点它退出选择模式）
     */
    @Test
    fun `paraIndexAt 与走法一致 空隙归上一段 上下之外为 null`() {
        val cw = viewStyle.contentWidthPx(viewWidth)
        // ⚠️ 与 NovelPageView.ensureLayouts 同一套输入（同文本/同 style/同宽度）→ 同一份排版。
        // 期望值从这份"测试自己排的" layout 推出，所以两边走法不同就会错位。
        val layouts = viewTexts.mapValues { (_, text) -> NovelTextRenderer.build(text, viewStyle, cw) }
        val hs = layouts.mapValues { (_, l) ->
            FloatArray(l.lineCount) { (l.getLineBottom(it) - l.getLineTop(it)).toFloat() }
        }
        val segs = viewTexts.keys.sorted().map { pi ->
            PageSegment(pi, 0, viewTexts.getValue(pi).length, 0, layouts.getValue(pi).lineCount)
        }
        val page = NovelPage(segs)
        val content = ChapterContent(0, "测试章", emptyList(), viewTexts, listOf(page))

        val view = NovelPageView(RuntimeEnvironment.getApplication())
        view.bind(content, page, viewStyle, NovelPageAdapter.DEFAULT_TEXT_COLOR)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(viewWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, viewWidth, 2000)

        val tops = mutableListOf<Triple<Int, Float, Float>>()
        NovelPageGeometry.walk(segs, { hs[it] }, viewStyle.paragraphSpacingPx, viewStyle.topPaddingPx) { seg, top, bottom ->
            tops += Triple(seg.paraIndex, top, bottom)
        }
        assertEquals("三段都要参与排版", 3, tops.size)

        // ① 每段中点、段顶 → 该段
        for ((paraIndex, top, bottom) in tops) {
            assertTrue("合成度量下段高应当为正（否则中点断言没有意义）", bottom > top)
            assertEquals("段中点的段号", paraIndex, view.paraIndexAt((top + bottom) / 2f))
            assertEquals("段顶那一像素算这一段的", paraIndex, view.paraIndexAt(top))
        }
        // ② 段间空隙（段间距 30px，取 5px 处）→ 上一段
        for (i in 0 until tops.size - 1) {
            val (paraIndex, _, bottom) = tops[i]
            assertTrue("段间距应当是正的（否则没有空隙可测）", tops[i + 1].second - bottom > 10f)
            assertEquals("段间空隙归上一段", paraIndex, view.paraIndexAt(bottom + 5f))
        }
        // ③ 正文上下之外 → null：顶部内边距之内、最后一段下沿
        assertNull("正文框顶之上是空白", view.paraIndexAt(viewStyle.topPaddingPx - 1f))
        assertNull("最后一段下沿之外是空白", view.paraIndexAt(tops.last().third))
        assertNull(view.paraIndexAt(1e6f))

        // ④ 段高口径：走法逐行相加 == 绘制端 `getLineBottom(to-1) - getLineTop(from)`。
        //    `Layout.getLineBottom(line)` 的实现就是 `getLineTop(line + 1)`（行与行首尾相接），
        //    所以两种取法等价 —— 这次收敛成逐行相加不会挪动任何一个像素。
        segs.forEachIndexed { i, seg ->
            val l = layouts.getValue(seg.paraIndex)
            val from = seg.lineStart.coerceIn(0, l.lineCount)
            val to = seg.lineEnd.coerceIn(from, l.lineCount)
            assertEquals(
                "段 ${seg.paraIndex} 的逐行高度之和应等于行区间的跨度",
                (l.getLineBottom(to - 1) - l.getLineTop(from)).toFloat(),
                tops[i].third - tops[i].second,
                0f,
            )
        }
    }
}
