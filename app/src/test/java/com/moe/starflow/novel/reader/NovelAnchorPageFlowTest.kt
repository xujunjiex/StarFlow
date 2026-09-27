package com.moe.starflow.novel.reader

import com.moe.starflow.novel.translate.NovelParagraph
import com.moe.starflow.novel.translate.NovelParagraphType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「翻译完**回到前一页**」的复现与守卫（纯逻辑，不用设备）。
 *
 * 场景：屏幕顶部那一段是**跨页长段的后半截**（`keepParagraphsWhole` 默认关，段落会被从中间切开）。
 * 这批要翻的正是它 —— 译文比原文短，于是这一段的整体变短，锚点那一行被"挤"回了**上一页**，
 * `NovelAnchors.pageOf` 就带读者退回一页。这正是用户看到的现象。
 *
 * 修法：分页时把锚点当**强制分页点**（`NovelPaginator.paginate(anchor=…)`），
 * 锚点那一行永远落在页首 —— 重排前后屏幕上的内容完全一致。
 */
class NovelAnchorPageFlowTest {

    /** 每行 10 字、行高 50px、一页 500px = 10 行。 */
    private val lineHeight = 50f
    private val pageHeight = 500f
    private val charsPerLine = 10

    private fun para(index: Int, text: String) = NovelParagraph(index, NovelParagraphType.TEXT, text)

    /** 合成度量：每 [charsPerLine] 字一行、等行高。 */
    private fun metrics(paragraphs: List<NovelParagraph>): Pair<List<IntArray>, List<FloatArray>> {
        val visible = paragraphs.filter { it.type != NovelParagraphType.SKIP }
        val starts = visible.map { p ->
            val lines = (p.originalText.length + charsPerLine - 1) / charsPerLine
            IntArray(lines.coerceAtLeast(1)) { it * charsPerLine }
        }
        return starts to starts.map { a -> FloatArray(a.size) { lineHeight } }
    }

    /** 与生产同口径的排版参数：段间距 0、不整段保护（测试关心的是行/页边界）。 */
    private val style = NovelTextStyle(
        fontSizePx = lineHeight, lineSpacingMultiplier = 1f, paragraphSpacingPx = 0f, paddingPx = 0f,
        keepParagraphsWhole = false,
    )

    private fun paginate(paragraphs: List<NovelParagraph>, anchor: NovelAnchor?): List<NovelPage> {
        val (starts, heights) = metrics(paragraphs)
        val forceBreak = anchor?.let { a ->
            val vi = paragraphs.filter { it.type != NovelParagraphType.SKIP }
                .indexOfFirst { it.index == a.paraIndex }
            if (vi < 0) null else vi to (a.charOffsetOf(paragraphs[vi].originalText.length) / charsPerLine)
        }
        if (forceBreak == null) {
            return NovelPaginator.paginateByLines(
                paragraphs, starts, heights, 0f, pageHeight, keepParagraphsWhole = false,
            )
        }
        // 生产路径：锚点当强制分页点（前半段倒着填满，见 paginateAround）
        return NovelPaginator.paginateAround(paragraphs, starts, heights, style, pageHeight, forceBreak)
    }

    /**
     * **回归**：跨页长段被翻译变短后，读者不该退回上一页。
     *
     * 段 0 原文 300 字（30 行，跨 3 页），译文 180 字（18 行）——
     * 读者原来停在第 2 页（0 基下标 1，即 100 字处）。译文变短后，100 字处落到第 7 行（第 1 页），
     * 不强制分页就会**退回第 1 页**（0 基下标 0）。强制分页后锚点仍在原处、且在页首。
     */
    @Test
    fun `跨页长段变短后锚点仍留在同一页的页首`() {
        val short = para(1, "短段")
        val before = listOf(para(0, "字".repeat(300)), short)
        val after = listOf(para(0, "字".repeat(180)), short)

        // 读者停在原文的第 2 页（0 基下标 1）→ 锚点 = 段 0 的 100 字处
        val anchor = NovelAnchors.ofPage(paginate(before, null), mapOf(0 to before[0].originalText, 1 to "短段"), 1)
        assertEquals("锚点应是段 0 的 100 字处", NovelAnchor(0, 100f / 300f), anchor)

        // 译文到达后重排：不强制分页 → 锚点被挤回上一页（这就是用户看到的「回到前一页」）
        val natural = paginate(after, null)
        assertEquals(
            "不强制分页会退回上一页（0 基下标 0）",
            0,
            NovelAnchors.pageOf(natural, mapOf(0 to after[0].originalText, 1 to "短段"), anchor),
        )

        // 传锚点 → 锚点那一行另起一页，读者停在"锚点在页首"的那一页
        val forced = paginate(after, anchor)
        val landed = NovelAnchors.pageOf(forced, mapOf(0 to after[0].originalText, 1 to "短段"), anchor)
        val firstSeg = forced[landed].segments.first()
        assertEquals("落位页的第一段就是锚点段", 0, firstSeg.paraIndex)
        assertEquals(
            "锚点那一行必须正好是页首（屏幕上第一行不变）",
            anchor.charOffsetOf(after[0].originalText.length) / charsPerLine,
            firstSeg.lineStart,
        )
    }

    /** 强制分页不能丢字：每一段的 segment 仍要首尾相接覆盖整段。 */
    @Test
    fun `强制分页不丢字`() {
        val paras = listOf(para(0, "字".repeat(300)), para(1, "短段"), para(2, "字".repeat(120)))
        val anchor = NovelAnchor(0, 0.42f)
        val pages = paginate(paras, anchor)

        val byPara = linkedMapOf<Int, MutableList<PageSegment>>()
        for (seg in pages.flatMap { it.segments }) byPara.getOrPut(seg.paraIndex) { mutableListOf() }.add(seg)
        for (p in paras) {
            var cursor = 0
            for (s in byPara.getValue(p.index)) {
                assertEquals("段 ${p.index} 的 segment 不连续", cursor, s.charStart)
                cursor = s.charEnd
            }
            assertEquals("段 ${p.index} 被截断", p.originalText.length, cursor)
        }
        assertTrue("锚点段必须存在", byPara.containsKey(0))
    }
}
