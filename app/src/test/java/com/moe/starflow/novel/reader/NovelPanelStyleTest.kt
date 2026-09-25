package com.moe.starflow.novel.reader

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 阅读模式取值的迁移。
 *
 * 引入「上下翻页」时取值语义变过：`1` 从「连续滚动」变成了「上下翻页」、滚动挪到 `2`。
 * 不迁移的话，用户原来选的「滚动」会静默变成「上下翻页」—— 他会以为滚动模式坏了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelPanelStyleTest {

    private val prefs: SharedPreferences
        get() = RuntimeEnvironment.getApplication()
            .getSharedPreferences(NovelPanelStyle.PREFS_NAME, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `旧值 1 是滚动，迁移后仍是滚动而不是上下翻页`() {
        // 旧版本只写过 0/1，且 1 表示滚动；没有版本标记 = 还没迁移过
        prefs.edit().putInt("novel_reader_mode", 1).commit()
        assertEquals(NovelPanelStyle.READER_SCROLL, NovelPanelStyle.readerMode(prefs))
    }

    @Test
    fun `旧值 0 是分页，迁移后保持分页`() {
        prefs.edit().putInt("novel_reader_mode", 0).commit()
        assertEquals(NovelPanelStyle.READER_PAGED, NovelPanelStyle.readerMode(prefs))
    }

    @Test
    fun `迁移只做一次，之后写入的新值不被改写`() {
        prefs.edit().putInt("novel_reader_mode", 1).commit()
        assertEquals(NovelPanelStyle.READER_SCROLL, NovelPanelStyle.readerMode(prefs))
        // 迁移已落盘；此后用户选「上下翻页」必须原样保留
        NovelPanelStyle.setReaderMode(prefs, NovelPanelStyle.READER_VERTICAL)
        assertEquals(NovelPanelStyle.READER_VERTICAL, NovelPanelStyle.readerMode(prefs))
    }

    @Test
    fun `全新安装默认分页`() {
        assertEquals(NovelPanelStyle.READER_PAGED, NovelPanelStyle.readerMode(prefs))
    }

    @Test
    fun `越界值被夹到合法区间`() {
        NovelPanelStyle.setReaderMode(prefs, 99)
        assertEquals(NovelPanelStyle.READER_SCROLL, NovelPanelStyle.readerMode(prefs))
    }

    /** 动画取值同样扩过（3 = 仿真），旧值不该被夹掉。 */
    @Test
    fun `仿真动画取值可读回`() {
        NovelPanelStyle.setAnimation(prefs, NovelPanelStyle.ANIM_SIMULATION)
        assertEquals(NovelPanelStyle.ANIM_SIMULATION, NovelPanelStyle.animation(prefs))
    }

    // ===== 排版约束 =====

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    /**
     * **段距必须始终大于行距**（用户明确要求）。
     *
     * 判据是「视觉空隙」而不是数字：行距倍率给出的空隙 = 字号 × 1.15 × (倍率-1)，
     * 段距的下限必须超过它 —— 否则段落之间和行之间看起来一样，整页糊成一块。
     */
    @Test
    fun `段距下限恒大于行距给出的行间空隙`() {
        for (font in listOf(12f, 16f, 20f, 26f, 34f)) {
            for (step in NovelPanelStyle.LINE_SPACING_MIN..NovelPanelStyle.LINE_SPACING_MAX) {
                val gap = NovelPanelStyle.lineGapSp(font, step)
                val min = NovelPanelStyle.minParagraphSpacingDp(font, step)
                assertTrue(
                    "字号 ${font}sp 行距 ${step / 10f}×：段距下限 $min dp 必须大于行间空隙 $gap",
                    min > gap,
                )
            }
        }
    }

    /** 改行距后，原来合法的段距可能已经小于新下限 —— 必须被抬上去。 */
    @Test
    fun `改行距会把过小的段距抬到新下限`() {
        prefs.edit()
            .putFloat("novel_font_size", 20f)
            .putInt("novel_line_spacing", NovelPanelStyle.LINE_SPACING_MIN)
            .putInt("novel_paragraph_spacing", NovelPanelStyle.PARA_SPACING_ABS_MIN)
            .commit()

        NovelPanelStyle.setLineSpacingStep(prefs, NovelPanelStyle.LINE_SPACING_MAX, 20f)

        val min = NovelPanelStyle.minParagraphSpacingDp(20f, NovelPanelStyle.LINE_SPACING_MAX)
        assertTrue(
            "段距必须被抬到新下限（实际 ${NovelPanelStyle.paragraphSpacingDp(prefs, 20f)}，下限 $min）",
            NovelPanelStyle.paragraphSpacingDp(prefs, 20f) >= min,
        )
    }

    /**
     * 极限组合（最大字号 + 最大行距）下段距的下限会超过 48dp。
     *
     * ⚠️ 这条守的是**崩溃**不是显示：上限不跟着抬，`coerceIn(min, 48)` 就是空区间，
     * Kotlin 会抛 `IllegalArgumentException: Cannot coerce value to an empty range`。
     */
    @Test
    fun `极限字号与行距下读写段距不抛异常`() {
        val font = NovelPanelStyle.FONT_SIZE_MAX
        val step = NovelPanelStyle.LINE_SPACING_MAX
        NovelPanelStyle.setLineSpacingStep(prefs, step, font)
        NovelPanelStyle.setParagraphSpacingDp(prefs, 9999, font)
        val v = NovelPanelStyle.paragraphSpacingDp(prefs, font)
        assertTrue("段距必须仍满足下限", v >= NovelPanelStyle.minParagraphSpacingDp(font, step))
        assertTrue("上限必须被抬到下限之上", NovelPanelStyle.maxParagraphSpacingDp(font, step) > NovelPanelStyle.minParagraphSpacingDp(font, step))
    }

    /** 自动排版：上下间距必须**相等**（用户明确要求），且各间距都落在合法区间内。 */
    @Test
    fun `自动排版保证上下间距相等且各值合法`() {
        NovelPanelStyle.setFontSizeSp(prefs, 24f)
        NovelPanelStyle.setAutoLayout(ctx, prefs, true)

        assertEquals(
            "自动排版下上下间距必须相等",
            NovelPanelStyle.topPaddingDp(prefs), NovelPanelStyle.bottomPaddingDp(prefs),
        )
        val font = NovelPanelStyle.fontSizeSp(prefs)
        val step = NovelPanelStyle.lineSpacingStep(prefs)
        assertTrue(NovelPanelStyle.paragraphSpacingDp(prefs, font) >= NovelPanelStyle.minParagraphSpacingDp(font, step))
        assertTrue(NovelPanelStyle.paddingDp(prefs) in NovelPanelStyle.SIDE_PADDING_MIN..NovelPanelStyle.SIDE_PADDING_MAX)
        assertTrue(NovelPanelStyle.topPaddingDp(prefs) in NovelPanelStyle.VERTICAL_PADDING_MIN..NovelPanelStyle.VERTICAL_PADDING_MAX)
    }

    /**
     * 字号变大 → 左右边距必须随之**变小**（一行目标字数固定，字号大了行就占满了）。
     *
     * ⚠️ 这条要在**宽屏**下测：手机竖屏（~411dp）在 20sp 时一行本就只放得下 ~19 个汉字，
     * 边距早就顶到下限 16dp 了，字号再变也看不出差别 —— 那是**正确行为**（不该为了制造
     * 差异而白白浪费屏宽），只是没法在窄屏上验证这条关系。
     */
    @Test
    @Config(sdk = [34], qualifiers = "w800dp-h1280dp-xhdpi")
    fun `自动排版下字号越大左右边距越小`() {
        NovelPanelStyle.setFontSizeSp(prefs, 14f)
        NovelPanelStyle.applyAutoLayout(ctx, prefs)
        val small = NovelPanelStyle.paddingDp(prefs)

        NovelPanelStyle.setFontSizeSp(prefs, 32f)
        NovelPanelStyle.applyAutoLayout(ctx, prefs)
        val big = NovelPanelStyle.paddingDp(prefs)

        assertTrue("字号 14sp 边距 $small 应当大于字号 32sp 的边距 $big", small > big)
    }

    /** 自动排版关掉就不做整段保护（用户要求：不自动就不管段落完整）。 */
    @Test
    fun `关掉自动排版后不再做整段保护`() {
        NovelPanelStyle.setAutoLayout(ctx, prefs, true)
        assertTrue(NovelPanelStyle.keepParagraphsWhole(prefs))
        NovelPanelStyle.setAutoLayout(ctx, prefs, false)
        assertTrue("关掉自动排版后必须按行填满", !NovelPanelStyle.keepParagraphsWhole(prefs))
    }

    /** 恢复默认：字号回默认值，并重新打开自动排版。 */
    @Test
    fun `恢复默认会重置字号并打开自动排版`() {
        NovelPanelStyle.setFontSizeSp(prefs, 32f)
        NovelPanelStyle.setAutoLayout(ctx, prefs, false)

        NovelPanelStyle.resetTypography(ctx, prefs)

        assertEquals(NovelPanelStyle.FONT_SIZE_DEFAULT, NovelPanelStyle.fontSizeSp(prefs), 0.001f)
        assertTrue(NovelPanelStyle.isAutoLayout(prefs))
        assertTrue(NovelPanelStyle.keepParagraphsWhole(prefs))
    }
}
