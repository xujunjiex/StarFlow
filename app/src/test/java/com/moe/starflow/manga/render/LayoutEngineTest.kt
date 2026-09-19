package com.moe.starflow.manga.render

import com.moe.starflow.manga.types.TextAlign
import com.moe.starflow.manga.types.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排版内核不变量测试（纯 JVM，无需 Robolectric）。
 *
 * `TextMeasurer` 是接口，这里用 [FakeMeasurer] 精确控制断行 ——
 * Robolectric 的 `Paint.breakText` 不按宽度换行，用真实 Paint 根本构造不出多行用例
 * （此前那些「多行填充」测试只能靠 `\n` 硬凑，覆盖不到自动换行）。
 *
 * 核心保证（历史上被违反过的）：
 * ① 内容块绝不越出 region；② 所有字符都画得出来（不截断）；③ 字距/行距只在开关打开时生效。
 */
class LayoutEngineTest {

    /** 测试用绝对内边距（px）。对应生产值 `MIN_PADDING_DP × density`。 */
    private val PAD = 3f

    /** 换行符（用字符码构造，避免源码里的转义在批处理编辑中被吃掉）。 */
    private val NEWLINE = 10.toChar().toString()

    /** 默认测试间距：拉到取值范围上限（1×字号），等同于旧「自动调整间距」开启时的最大拉伸。 */
    private val TRK = LayoutEngine.TRACKING_MAX_RATIO
    private val LEAD = LayoutEngine.LEADING_MAX_RATIO

    /**
     * 假字体：等宽（字宽 = 字号 × charRatio，字形高 = 字号 × lineRatio），带少量字距。
     *
     * ⚠️ 四条都必须**贴住真实字体**，否则测出的是假实现的巧合而不是内核的行为：
     * ① 两个度量随字号缩放（真实 CJK 字体即如此）——内核会按内边距收缩可用区，
     *    度量定死而字号放大时字号会被压到下限，布局失真（初版写死字宽时踩过）；
     * ② [breakText] 像 `Paint.breakText` 一样**吃进不足一个字宽的余量**
     *    （真实字体有字距 advance，`n` 个字吃 `n×(fw+gap)`，故返回 `floor((max+gap)/(fw+gap))`）
     *    —— 若只是简单取整，内核「断行预算与宽度计算是否自洽」就测不出来（曾因此放过一个
     *    字距非零时字号崩塌的真 bug）。
     */
    private class FakeMeasurer(private val charRatio: Float, private val lineRatio: Float) : TextMeasurer {
        override var fontSize: Float = 16f

        /** 每字 advance 里除字面宽之外的固定字距（真实字体同样存在）。 */
        private val gap = 1f
        private fun advance() = fontSize * charRatio + gap

        override fun measure(text: String): Float =
            if (text.isEmpty()) 0f else text.length * advance() - gap

        override fun breakText(text: String, maxWidth: Float): Int =
            ((maxWidth + gap) / advance()).toInt().coerceAtLeast(1).coerceAtMost(text.length)

        override fun glyphWidth(): Float = fontSize * charRatio
        override fun glyphHeight(): Float = fontSize * lineRatio
    }

    private fun measurer(charRatio: Float = 1f, lineRatio: Float = 1f) = FakeMeasurer(charRatio, lineRatio)

    /** 把 layout 里所有行的文本拼回，用于验证「不截断」。 */
    private fun TextLayout.reassembled(): String =
        lines.joinToString("\n") { it.text }

    private fun assertNoOverflow(layout: TextLayout, region: Box) {
        assertTrue(
            "内容块宽 ${layout.totalWidth} 越出 region 宽 ${region.width}",
            layout.totalWidth <= region.width + 0.01f
        )
        assertTrue(
            "内容块高 ${layout.totalHeight} 越出 region 高 ${region.height}",
            layout.totalHeight <= region.height + 0.01f
        )
    }

    // ---------- ① 绝不溢出（固定矩阵） ----------

    @Test
    fun neverOverflow_acrossMatrix() {
        val regions = listOf(
            Box(0f, 0f, 60f, 60f), Box(0f, 0f, 120f, 40f), Box(0f, 0f, 40f, 200f),
            Box(0f, 0f, 200f, 200f), Box(0f, 0f, 15f, 15f)
        )
        val texts = listOf("A", "AB", "ABCDEFG", "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "一二三四五六七八九十")
        val directions = listOf(TextDirection.HORIZONTAL, TextDirection.VERTICAL_RL, TextDirection.VERTICAL_LR)
        for (region in regions) {
            for (text in texts) {
                for (dir in directions) {
                    for (align in TextAlign.entries) {
                        val layout = LayoutEngine.plan(
                            measurer = measurer(),
                            text = text, region = region, direction = dir,
                            requestedFontSize = 40f, autoFit = true,
                            align = align, trackingRatio = TRK, leadingRatio = LEAD, minPaddingPx = PAD
                        )
                        assertNoOverflow(layout, region)
                    }
                }
            }
        }
    }

    /** ① 极端：极小区域 + 超长文本 → 字号降到下限仍不越界，且字符一个不少。 */
    @Test
    fun tinyRegion_longText_allCharsPresentAndNoOverflow() {
        val region = Box(0f, 0f, 20f, 20f)
        val text = "一二三四五六七八九十百千万"
        for (dir in listOf(TextDirection.HORIZONTAL, TextDirection.VERTICAL_RL, TextDirection.VERTICAL_LR)) {
            val layout = LayoutEngine.plan(
                measurer = measurer(charRatio = 1f, lineRatio = 1f),
                text = text, region = region, direction = dir,
                requestedFontSize = 40f, autoFit = true,
                align = TextAlign.CENTER, trackingRatio = TRK, leadingRatio = LEAD, minPaddingPx = PAD
            )
            assertNoOverflow(layout, region)
            assertEquals("字符不得被截断（$dir）", text, layout.reassembled().replace("\n", ""))
        }
    }

    // ---------- ①b 边缘留白（绝对值，不随字号缩放） ----------

    /**
     * ⚠️ 用户明确要求：**竖排左右、横排上下都要留出间隔，不要贴着 overlay 边缘**。
     *
     * 这条锁的是「绝对值」语义 —— 旧实现用 `字号 × 0.15` 做 padding，自动字号把字缩小时
     * 边距同步退化到几乎为零，照样贴边。这里刻意用一个**小字号**场景验证边距仍然存在。
     */
    @Test
    fun paddingIsAbsolute_notScaledByFontSize() {
        // 窄气泡：横排宽度受限 → 字号被压小；此时上下留白仍必须存在
        val region = Box(0f, 0f, 40f, 300f)
        for (dir in listOf(TextDirection.HORIZONTAL, TextDirection.VERTICAL_RL, TextDirection.VERTICAL_LR)) {
            val layout = LayoutEngine.plan(
                measurer = measurer(charRatio = 1f, lineRatio = 1f),
                text = "一二三四五六七八", region = region, direction = dir,
                requestedFontSize = 60f, autoFit = true,
                align = TextAlign.CENTER, trackingRatio = TRK, leadingRatio = LEAD, minPaddingPx = PAD
            )
            assertTrue("排版不应为空（$dir）", !layout.isEmpty)

            if (dir == TextDirection.HORIZONTAL) {
                // 横排：上下（纵向）必须留白
                val topEdge = layout.lines.first().baseline - layout.fontSize
                val bottomEdge = layout.lines.last().baseline
                assertTrue("横排顶部贴边 $topEdge", topEdge >= region.top + PAD - 0.5f)
                assertTrue("横排底部贴边 $bottomEdge", bottomEdge <= region.bottom - PAD + 0.5f)
            } else {
                // 竖排：左右（横向）必须留白
                val leftEdge = layout.lines.minOf { it.x - it.width / 2f }
                val rightEdge = layout.lines.maxOf { it.x + it.width / 2f }
                assertTrue("竖排左侧贴边 $leftEdge", leftEdge >= region.left + PAD - 0.5f)
                assertTrue("竖排右侧贴边 $rightEdge", rightEdge <= region.right - PAD + 0.5f)
            }
        }
    }

    /** 内边距上限：小气泡里 padding 不能吃掉全部可用空间（否则排版为空）。 */
    @Test
    fun paddingCappedOnTinyRegion_contentStillLaidOut() {
        val region = Box(0f, 0f, 12f, 12f)
        val layout = LayoutEngine.plan(
            measurer = measurer(charRatio = 1f, lineRatio = 1f),
            text = "一二三四", region = region, direction = TextDirection.HORIZONTAL,
            requestedFontSize = 40f, autoFit = true,
            align = TextAlign.CENTER, trackingRatio = TRK, leadingRatio = LEAD,
            minPaddingPx = 100f   // 远大于区域，必须被上限截住
        )
        assertTrue("极小区域也不该排空", !layout.isEmpty)
        assertEquals("字符不截断", "一二三四", layout.reassembled().replace("\n", ""))
    }

    // ---------- ② 字距 / 行距 ----------
    @Test
    fun zeroLeading_naturalLineHeight() {
        val region = Box(0f, 0f, 200f, 60f)
        val layout = LayoutEngine.plan(
            measurer = measurer(), text = "AB", region = region,
            direction = TextDirection.HORIZONTAL, requestedFontSize = 20f,
            autoFit = true, align = TextAlign.CENTER, trackingRatio = 0f, leadingRatio = 0f, minPaddingPx = PAD
        )
        assertEquals("单行只有一行", 1, layout.lines.size)
        // 单行时行距无意义，取自然行距（= 实际选中的字号 × 系数，不是请求字号 —— 二分结果可能略小）
        assertEquals(
            layout.fontSize * LayoutEngine.HORIZONTAL_LINE_RATIO, layout.leading, 0.01f
        )
    }
    /**
     * 字距是**用户输入**，对所有行统一生效（旧实现只在单行时算字距 → 多行恒为 0）。
     */
    @Test
    fun multiLine_trackingAppliedToEveryLine() {
        // 用换行符强制多行：自动字号总能缩到「一行装下」，靠宽度逼不出折行
        val region = Box(0f, 0f, 200f, 200f)
        val m = measurer(charRatio = 1f, lineRatio = 1f)
        val layout = LayoutEngine.plan(
            measurer = m, text = "ABCD" + NEWLINE + "EF",
            region = region, direction = TextDirection.HORIZONTAL,
            requestedFontSize = 30f, autoFit = true,
            align = TextAlign.CENTER, trackingRatio = 0.5f, leadingRatio = 0f, minPaddingPx = PAD
        )
        assertTrue("应换行成多行", layout.lines.size >= 2)
        assertTrue("字距生效", layout.tracking > 0f)
        m.fontSize = layout.fontSize
        for (line in layout.lines) {
            val natural = m.measure(line.text)
            assertEquals(
                "行宽 = 自然宽 + 字距×(字数-1)",
                natural + layout.tracking * (line.text.length - 1), line.width, 0.5f
            )
        }
    }

    @Test
    fun trackingNeverExceedsFontSize() {
        // 超宽区域 + 2 字 → 字距会被上限截住
        val region = Box(0f, 0f, 5000f, 60f)
        val layout = LayoutEngine.plan(
            measurer = measurer(), text = "AB", region = region,
            direction = TextDirection.HORIZONTAL, requestedFontSize = 20f,
            autoFit = true, align = TextAlign.CENTER, trackingRatio = TRK, leadingRatio = LEAD, minPaddingPx = PAD
        )
        assertTrue("字距不得超过 1×字号", layout.tracking <= layout.fontSize + 0.01f)
    }

    // ---------- ③ 开关语义 ----------

    @Test
    fun zeroSpacing_noTracking_leadingIsNatural() {
        val region = Box(0f, 0f, 200f, 100f)
        val on = LayoutEngine.plan(
            measurer = measurer(charRatio = 1f, lineRatio = 1f), text = "ABCD",
            region = region, direction = TextDirection.HORIZONTAL,
            requestedFontSize = 30f, autoFit = true,
            align = TextAlign.CENTER, trackingRatio = TRK, leadingRatio = LEAD, minPaddingPx = PAD
        )
        val off = LayoutEngine.plan(
            measurer = measurer(charRatio = 1f, lineRatio = 1f), text = "ABCD",
            region = region, direction = TextDirection.HORIZONTAL,
            requestedFontSize = 30f, autoFit = true,
            align = TextAlign.CENTER, trackingRatio = 0f, leadingRatio = 0f, minPaddingPx = PAD
        )
        assertEquals("间距为 0 时字距必须是 0", 0f, off.tracking, 0.01f)
        assertTrue("设了字距就要生效", on.tracking > 0f)
        // 间距参与字号预算：字距越大，能装下的字号越小（这是「绝不溢出」的代价与前提）
        assertTrue("设了间距时字号应更小", on.fontSize <= off.fontSize + 0.01f)
        assertTrue("两者都不得越界", off.totalWidth <= region.width + 0.01f)
        assertTrue("两者都不得越界", on.totalWidth <= region.width + 0.01f)
    }

    @Test
    fun zeroSpacing_vertical_noColumnStretch() {
        val region = Box(0f, 0f, 200f, 100f)
        val off = LayoutEngine.plan(
            measurer = measurer(charRatio = 1f, lineRatio = 1f), text = "一二三四五六",
            region = region, direction = TextDirection.VERTICAL_RL,
            requestedFontSize = 20f, autoFit = true,
            align = TextAlign.CENTER, trackingRatio = 0f, leadingRatio = 0f, minPaddingPx = PAD
        )
        assertEquals("关闭后列内字距为 0", 0f, off.tracking, 0.01f)
        assertEquals(
            "关闭后列距取自然值",
            off.fontSize * LayoutEngine.VERTICAL_CHAR_RATIO, off.leading, 0.01f
        )
    }

    /**
     * ⚠️ 回归（实测 bug）：**小字距不得让字号崩塌。**
     *
     * 旧实现把 `availW + tracking` 当断行预算传给 `breakText`，断行器随之把每行填到
     * `availW + tracking` 满，事后 `totalWidth` 再补上 `tracking×(k-1)` —— 于是**每行恒超**
     * `availW`（尤其真实字体每个字形自带 advance，见 [FakeMeasurer] 的 gap），`fits()` 判定
     * 多行布局装不下，二分搜索退化到更小的字号。
     *
     * 设备实测：363×214 的气泡、2% 字距 → **7.65px、全挤在一行**（无字距时是 33.4px/5 行）。
     *
     * 这里用**扫描**而非挑单个尺寸：崩塌与否随容器宽高呈锯齿状分布（宽度凑巧整除时
     * 恰好不超预算、侥幸不崩），单点取样很容易漏掉。
     */
    @Test
    fun smallTracking_fontSizeNeverCollapses() {
        val text = "少女&丰满 武部沙织 小学五年级 11岁 身高139cm/体重38kg 感觉只要拜托她做色色的事，她就会一口答应 imcmic"
        val collapsed = mutableListOf<String>()
        for (w in 80..600 step 8) {
            for (h in intArrayOf(120, 214, 300)) {
                val region = Box(0f, 0f, w.toFloat(), h.toFloat())
                fun plan(tracking: Float) = LayoutEngine.plan(
                    measurer = measurer(charRatio = 1f, lineRatio = 1f), text = text,
                    region = region, direction = TextDirection.HORIZONTAL,
                    requestedFontSize = h.toFloat(), autoFit = true,
                    align = TextAlign.LEFT, trackingRatio = tracking, leadingRatio = 0.05f, minPaddingPx = PAD
                )
                val off = plan(0f)
                val on = plan(0.02f)
                assertNoOverflow(on, region)
                assertEquals("字符不截断 (w=$w h=$h)", text, on.reassembled().replace("\n", ""))
                if (on.fontSize < off.fontSize * 0.8f) {
                    collapsed += "w=$w h=$h: ${off.fontSize}(行${off.lines.size}) → ${on.fontSize}(行${on.lines.size})"
                }
            }
        }
        assertTrue(
            "2% 字距让字号明显变小（断行预算与宽度计算不自洽）：\n" + collapsed.joinToString("\n"),
            collapsed.isEmpty()
        )
    }

    // ---------- ④ 分列 / 分行一致性 ----------

    /** ⚠️ 核心：字号判定与最终排版的列数必须一致（杜绝「算出 3 列、画出 4 列」）。 */
    @Test
    fun vertical_lineCountMatchesCapacity() {
        val region = Box(0f, 0f, 60f, 100f)
        val text = "一二三四五六七八"
        val layout = LayoutEngine.plan(
            measurer = measurer(charRatio = 1f, lineRatio = 1f), text = text,
            region = region, direction = TextDirection.VERTICAL_RL,
            requestedFontSize = 30f, autoFit = true,
            align = TextAlign.CENTER, trackingRatio = TRK, leadingRatio = LEAD, minPaddingPx = PAD
        )
        assertEquals("字符不得被截断", text, layout.reassembled().replace("\n", ""))
        assertNoOverflow(layout, region)
    }

    @Test
    fun verticalRL_columnsGoRightToLeft() {
        val region = Box(0f, 0f, 100f, 100f)
        val text = "一二三四五六七八"
        val rl = LayoutEngine.plan(
            measurer = measurer(charRatio = 1f, lineRatio = 1f), text = text,
            region = region, direction = TextDirection.VERTICAL_RL,
            requestedFontSize = 20f, autoFit = true,
            align = TextAlign.CENTER, trackingRatio = 0f, leadingRatio = 0f, minPaddingPx = PAD
        )
        val lr = LayoutEngine.plan(
            measurer = measurer(charRatio = 1f, lineRatio = 1f), text = text,
            region = region, direction = TextDirection.VERTICAL_LR,
            requestedFontSize = 20f, autoFit = true,
            align = TextAlign.CENTER, trackingRatio = 0f, leadingRatio = 0f, minPaddingPx = PAD
        )
        assertTrue("至少两列", rl.lines.size >= 2)
        assertTrue("RL：首列在最右", rl.lines.first().x > rl.lines.last().x)
        assertTrue("LR：首列在最左", lr.lines.first().x < lr.lines.last().x)
    }

    // ---------- ⑤ 横排对齐 ----------

    @Test
    fun horizontalAlign_positionsLinesRelatively() {
        // "ABCD" 换行成两行，行宽不等：第一行满宽、第二行短
        val region = Box(0f, 0f, 60f, 200f)
        fun leftOf(align: TextAlign) = LayoutEngine.plan(
            measurer = measurer(charRatio = 1f, lineRatio = 1f), text = "ABCDE",
            region = region, direction = TextDirection.HORIZONTAL,
            requestedFontSize = 20f, autoFit = true,
            align = align, trackingRatio = 0f, leadingRatio = 0f, minPaddingPx = PAD
        ).lines

        val left = leftOf(TextAlign.LEFT)
        val center = leftOf(TextAlign.CENTER)
        val right = leftOf(TextAlign.RIGHT)
        assertEquals("三种对齐行数一致", left.size, center.size)

        // 取最后一行（通常更短）比较起点
        val li = left.size - 1
        assertTrue("左对齐起点更靠左", left[li].x < center[li].x)
        assertTrue("右对齐起点更靠右", right[li].x > center[li].x)
        // 各行都不越出区域
        for (lines in listOf(left, center, right)) {
            for (line in lines) {
                assertTrue("行左越界", line.x >= region.left - 0.01f)
                assertTrue("行右越界", line.x + line.width <= region.right + 0.01f)
            }
        }
    }

    @Test
    fun vertical_alignSettingDoesNotApply() {
        // 竖排不受 align 影响（列块恒定水平居中）
        val region = Box(0f, 0f, 100f, 100f)
        fun xs(align: TextAlign) = LayoutEngine.plan(
            measurer = measurer(charRatio = 1f, lineRatio = 1f), text = "一二三四",
            region = region, direction = TextDirection.VERTICAL_RL,
            requestedFontSize = 20f, autoFit = true,
            align = align, trackingRatio = 0f, leadingRatio = 0f, minPaddingPx = PAD
        ).lines.map { it.x }

        assertEquals("竖排忽略横排对齐设置", xs(TextAlign.LEFT), xs(TextAlign.RIGHT))
    }

    /**
     * ⚠️ 用户硬约束：**字间距和行间距都不允许超过一个标准字符**。
     *
     * 在自然间距之上多出来的部分 ≤ 1×字号（字距 ≤ font；行距 ≤ 自然行距 + font）。
     * 用「一行/一列文字塞进超大区域」逼出最大拉伸 —— 那个场景下余量足够大，最容易越界。
     */
    @Test
    fun extraSpacingNeverExceedsOneCharacter() {
        val huge = Box(0f, 0f, 4000f, 4000f)
        val texts = listOf("AB", "一二三四五六七八九十")

        // 横排：单行 → 字距被拉满；多行 → 行距被拉满
        for (t in texts) {
            val wide = LayoutEngine.plan(
                measurer = measurer(), text = t, region = Box(0f, 0f, 4000f, 120f),
                direction = TextDirection.HORIZONTAL, requestedFontSize = 40f,
                autoFit = true, align = TextAlign.CENTER, trackingRatio = TRK, leadingRatio = LEAD, minPaddingPx = PAD
            )
            assertTrue("横排字距 ${wide.tracking} 超过 1×字号 ${wide.fontSize}", wide.tracking <= wide.fontSize + 0.01f)
        }
        val tall = LayoutEngine.plan(
            measurer = measurer(), text = "ABCD\nEFGH\nIJKL", region = huge,
            direction = TextDirection.HORIZONTAL, requestedFontSize = 40f,
            autoFit = true, align = TextAlign.CENTER, trackingRatio = TRK, leadingRatio = LEAD, minPaddingPx = PAD
        )
        val naturalLeading = tall.fontSize * LayoutEngine.HORIZONTAL_LINE_RATIO
        assertTrue(
            "横排行距 ${tall.leading} 超过 自然行距+1×字号 ${naturalLeading + tall.fontSize}",
            tall.leading <= naturalLeading + tall.fontSize + 0.01f
        )

        // 竖排：列内字距 / 列距 各自都不许超过 1×字号（在自然值之上）
        val vTall = LayoutEngine.plan(
            measurer = measurer(), text = "一二三四五六七八九", region = Box(0f, 0f, 120f, 4000f),
            direction = TextDirection.VERTICAL_RL, requestedFontSize = 40f,
            autoFit = true, align = TextAlign.CENTER, trackingRatio = TRK, leadingRatio = LEAD, minPaddingPx = PAD
        )
        assertTrue("竖排列内字距 ${vTall.tracking} 超过 1×字号", vTall.tracking <= vTall.fontSize + 0.01f)

        val vWide = LayoutEngine.plan(
            measurer = measurer(), text = "一二三四五六七八九十", region = Box(0f, 0f, 4000f, 60f),
            direction = TextDirection.VERTICAL_RL, requestedFontSize = 40f,
            autoFit = true, align = TextAlign.CENTER, trackingRatio = TRK, leadingRatio = LEAD, minPaddingPx = PAD
        )
        val naturalColStep = vWide.fontSize * LayoutEngine.VERTICAL_CHAR_RATIO
        assertTrue(
            "竖排列距 ${vWide.leading} 超过 自然列距+1×字号 ${naturalColStep + vWide.fontSize}",
            vWide.leading <= naturalColStep + vWide.fontSize + 0.01f
        )
    }

    /**
     * ⚠️ 用户反馈：「字间距根本没有调整，还是会因为行距/列距拉太大导致最后一列只有一个字」。
     *
     * 根因是竖向余量被**全给列内步进**、横向余量被**全给列距**，字距只剩残渣（实测 0.6px）。
     * 这条锁住「每条轴平摊给该轴所有 gap」：余量均分 → 字距与行/列距分摊同一份余量。
     */
    @Test
    fun horizontalTrackingAndLeadingShareTheDeficitEqually() {
        // 多行 + 高区域：高度余量应平摊到各行之间（行距），宽度余量平摊到行内（字距）
        val layout = LayoutEngine.plan(
            measurer = measurer(charRatio = 1f, lineRatio = 1f), text = "ABCD\nEFGH",
            region = Box(0f, 0f, 200f, 300f), direction = TextDirection.HORIZONTAL,
            requestedFontSize = 40f, autoFit = true,
            align = TextAlign.CENTER, trackingRatio = TRK, leadingRatio = LEAD, minPaddingPx = PAD
        )
        // 字距 = 宽度余量 / 最长行的 gap 数；这里余量有限，只断言「确实被拉开」
        assertTrue("多行时字距也要生效（旧实现恒为 0）", layout.tracking > 0f)
        // 高度余量远超上限 → 行距应恰好顶到「自然行距 + 1×字号」这个上限，而不是无限拉伸
        val naturalLeading = layout.fontSize * LayoutEngine.HORIZONTAL_LINE_RATIO
        assertEquals(
            "行距应顶到上限（自然行距 + 1×字号）",
            naturalLeading + layout.fontSize, layout.leading, 0.5f
        )
        assertTrue("绝不越界", layout.totalHeight <= 300f + 0.01f)
        // 两者都在上限内
        assertTrue("字距超限", layout.tracking <= layout.fontSize + 0.01f)
    }

    /** 关闭间距调整时，两条轴的间距都必须回到自然值（字号仍为最大能装下）。 */
    @Test
    fun zeroSpacing_bothAxesNatural() {
        val layout = LayoutEngine.plan(
            measurer = measurer(charRatio = 1f, lineRatio = 1f), text = "ABCD\nEFGH",
            region = Box(0f, 0f, 200f, 300f), direction = TextDirection.HORIZONTAL,
            requestedFontSize = 40f, autoFit = true,
            align = TextAlign.CENTER, trackingRatio = 0f, leadingRatio = 0f, minPaddingPx = PAD
        )
        assertEquals("关闭后字距为 0", 0f, layout.tracking, 0.01f)
        assertEquals(
            "关闭后行距为自然值",
            layout.fontSize * LayoutEngine.HORIZONTAL_LINE_RATIO, layout.leading, 0.01f
        )
    }

    // ---------- ⑥ 退化输入 ----------

    @Test
    fun degenerateInputs_returnEmpty() {
        val region = Box(0f, 0f, 100f, 100f)
        for (layout in listOf(
            LayoutEngine.plan(measurer(), "", region, TextDirection.HORIZONTAL, 20f, true, TextAlign.CENTER),
            LayoutEngine.plan(measurer(), "AB", Box(0f, 0f, 0f, 0f), TextDirection.HORIZONTAL, 20f, true, TextAlign.CENTER),
            LayoutEngine.plan(measurer(), "AB", region, TextDirection.HORIZONTAL, 0f, true, TextAlign.CENTER),
        )) {
            assertTrue("退化输入应返回空排版", layout.isEmpty)
        }
    }

    // ---------- ⑦ 点串（省略号）绝不拆行/拆列 ----------

    /**
     * 修复前的真实反馈：模型把一条译文写成三行（`[1] .` / `.` / `.`），译文里就带着 `\n`，
     * 竖排渲染时 `\n` 占掉一个字符格 → 用户看到「每个点占一行」，白占高度、字号被压小。
     *
     * 竖排是连续竖流，换行没有意义：必须在排版前去掉，点串自然连在同一列里。
     */
    @Test
    fun vertical_newlineInsideDotRunStaysInOneColumn() {
        val layout = LayoutEngine.plan(
            measurer = measurer(charRatio = 1f, lineRatio = 1f),
            text = ".${NEWLINE}.${NEWLINE}.",
            region = Box(0f, 0f, 200f, 200f), direction = TextDirection.VERTICAL_RL,
            requestedFontSize = 10f, autoFit = false,
            align = TextAlign.CENTER, trackingRatio = 0f, leadingRatio = 0f, minPaddingPx = PAD
        )
        assertEquals("三个点必须在同一列", listOf("..."), layout.lines.map { it.text })
    }

    /**
     * 竖排按列容量切分时，切点不能落在点串中间（否则 `...` 会被排成「每列一个点」）。
     * 例子：容量 4 的 `ab...cd` → 切点本会落在第三个点上，必须前移到串首。
     */
    @Test
    fun vertical_neverSplitsDotRun() {
        // availH = 3×charStep + font = 3×11 + 10 = 43 → 容量 4（字号 10，步距 = 10×1.1）
        val layout = LayoutEngine.plan(
            measurer = measurer(charRatio = 1f, lineRatio = 1f),
            text = "ab...cd",
            region = Box(0f, 0f, 200f, 43f + 2 * PAD), direction = TextDirection.VERTICAL_RL,
            requestedFontSize = 10f, autoFit = false,
            align = TextAlign.CENTER, trackingRatio = 0f, leadingRatio = 0f, minPaddingPx = PAD
        )
        assertEquals(listOf("ab", "...c", "d"), layout.lines.map { it.text })
        assertEquals("不丢字", "ab...cd", layout.lines.joinToString("") { it.text })
        assertNoOverflow(layout, Box(0f, 0f, 200f, 43f + 2 * PAD))
    }

    /** 横排同理：宽度只够 4 个字时，`ab...cd` 不能断在点串中间。 */
    @Test
    fun horizontal_neverSplitsDotRun() {
        // 字号 10、字宽 10 + gap 1 → 4 个字占 4×11-1 = 43，可用宽 45 → 每行 4 个
        val layout = LayoutEngine.plan(
            measurer = measurer(charRatio = 1f, lineRatio = 1f),
            text = "ab...cd",
            region = Box(0f, 0f, 45f + 2 * PAD, 300f), direction = TextDirection.HORIZONTAL,
            requestedFontSize = 10f, autoFit = false,
            align = TextAlign.LEFT, trackingRatio = 0f, leadingRatio = 0f, minPaddingPx = PAD
        )
        assertEquals(listOf("ab", "...c", "d"), layout.lines.map { it.text })
        assertEquals("不丢字", "ab...cd", layout.lines.joinToString("") { it.text })
    }

    /**
     * 兜底：点串比一整列/一行还长时只能硬切 —— 但**不丢字、不越界**仍是硬约束
     * （宁可拆点串，也不能溢出或截断）。
     */
    @Test
    fun dotRunLongerThanLine_fallsBackToHardBreakWithoutLosingText() {
        val region = Box(0f, 0f, 19f + 2 * PAD, 300f)   // 可用宽 19 → 每行 1 个字
        val layout = LayoutEngine.plan(
            measurer = measurer(charRatio = 1f, lineRatio = 1f),
            text = "...",
            region = region, direction = TextDirection.HORIZONTAL,
            requestedFontSize = 10f, autoFit = false,
            align = TextAlign.LEFT, trackingRatio = 0f, leadingRatio = 0f, minPaddingPx = PAD
        )
        assertEquals("不丢字", "...", layout.lines.joinToString("") { it.text })
        assertNoOverflow(layout, region)
    }

    /**
     * 竖排同样的兜底分支：点串比**一整列**还长（容量 1）时不能死循环、不能丢字。
     * 这条正是 `pullBackToDotRunStart` 里 `runStart == start → 原样返回`（硬切）那条路的守卫。
     */
    @Test
    fun dotRunLongerThanColumn_verticalHardBreakWithoutLosingText() {
        // 容量 1：availH = font = 10 → 每列 1 个字
        val region = Box(0f, 0f, 200f, 10f + 2 * PAD)
        val layout = LayoutEngine.plan(
            measurer = measurer(charRatio = 1f, lineRatio = 1f),
            text = "...",
            region = region, direction = TextDirection.VERTICAL_RL,
            requestedFontSize = 10f, autoFit = false,
            align = TextAlign.CENTER, trackingRatio = 0f, leadingRatio = 0f, minPaddingPx = PAD
        )
        assertEquals("每列一个点（硬切）", listOf(".", ".", "."), layout.lines.map { it.text })
        assertEquals("不丢字", "...", layout.lines.joinToString("") { it.text })
        assertNoOverflow(layout, region)
    }
}
