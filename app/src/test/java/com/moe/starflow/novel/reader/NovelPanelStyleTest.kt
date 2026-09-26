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
import com.moe.starflow.novel.translate.NovelQuota
import com.moe.starflow.novel.translate.NovelTranslateMode

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

    /**
     * 上下间距的**默认值必须相等**（用户明确要求），且要够避开上下浮层。
     *
     * ⚠️ 不相等的话，同一段文字在「上一页末尾 / 下一页开头」看起来会偏，用户第一眼就发现。
     */
    @Test
    fun `上下间距默认值相等且落在合法区间`() {
        val v = NovelPanelStyle.verticalPaddingDefaultDp()
        assertTrue(v in NovelPanelStyle.VERTICAL_PADDING_MIN..NovelPanelStyle.VERTICAL_PADDING_MAX)
        prefs.edit().clear().commit()
        assertEquals(
            "上下间距的默认值必须相等",
            NovelPanelStyle.topPaddingDp(prefs), NovelPanelStyle.bottomPaddingDp(prefs),
        )
    }

    /** 恢复默认：字号与**全部间距**一起复位（少复位一个，用户就会觉得"恢复默认没用"）。 */
    @Test
    fun `恢复默认会把字号与全部间距一起复位`() {
        NovelPanelStyle.setFontSizeSp(prefs, NovelPanelStyle.FONT_SIZE_MAX)
        NovelPanelStyle.setLineSpacingStep(prefs, NovelPanelStyle.LINE_SPACING_MAX, NovelPanelStyle.FONT_SIZE_MAX)
        NovelPanelStyle.setPaddingDp(prefs, NovelPanelStyle.SIDE_PADDING_MAX)
        NovelPanelStyle.setTopPaddingDp(prefs, NovelPanelStyle.VERTICAL_PADDING_MAX)
        NovelPanelStyle.setBottomPaddingDp(prefs, NovelPanelStyle.VERTICAL_PADDING_MIN)

        NovelPanelStyle.resetTypography(prefs)

        assertEquals(NovelPanelStyle.FONT_SIZE_DEFAULT, NovelPanelStyle.fontSizeSp(prefs), 0.001f)
        assertEquals(NovelPanelStyle.LINE_SPACING_DEFAULT, NovelPanelStyle.lineSpacingStep(prefs))
        assertEquals(NovelPanelStyle.SIDE_PADDING_DEFAULT, NovelPanelStyle.paddingDp(prefs))
        assertEquals(NovelPanelStyle.verticalPaddingDefaultDp(), NovelPanelStyle.topPaddingDp(prefs))
        assertEquals(
            "恢复默认后上下间距必须仍然相等",
            NovelPanelStyle.topPaddingDp(prefs), NovelPanelStyle.bottomPaddingDp(prefs),
        )
    }

    // ===== 翻译：批段数 / 增量配额 / 模式（用户明确要求的口径） =====

    /** 每批段数：默认 **3**，范围 1–10（原来是 8 / 1–20）。 */
    @Test
    fun `批段数默认 3 且范围 1 到 10`() {
        assertEquals(3, NovelPanelStyle.batchSize(prefs))
        NovelPanelStyle.setBatchSize(prefs, 99)
        assertEquals("上限必须是 10", NovelPanelStyle.BATCH_MAX, NovelPanelStyle.batchSize(prefs))
        NovelPanelStyle.setBatchSize(prefs, 0)
        assertEquals("下限必须是 1", NovelPanelStyle.BATCH_MIN, NovelPanelStyle.batchSize(prefs))
    }

    /**
     * 增量配额：默认 **5**，范围 **2–10**。
     *
     * ⚠️ 单位是**批**不是章：旧键 `novel_translate_ahead` 存的是章数（默认 3），
     * 换成新键 `novel_translate_ahead_batches` 就是为了不让「3 章」被当成「3 批」读进来。
     */
    @Test
    fun `增量配额默认 5 且范围 2 到 10`() {
        assertEquals(5, NovelPanelStyle.aheadBatches(prefs))
        NovelPanelStyle.setAheadBatches(prefs, 99)
        assertEquals(NovelQuota.MAX, NovelPanelStyle.aheadBatches(prefs))
        NovelPanelStyle.setAheadBatches(prefs, 0)
        assertEquals(NovelQuota.MIN, NovelPanelStyle.aheadBatches(prefs))
    }

    /** 旧键留在盘上也不该被当成批数读进来。 */
    @Test
    fun `旧的章数键不影响新的批配额`() {
        prefs.edit().putInt("novel_translate_ahead", 9).commit()
        assertEquals("新键缺失时必须用默认 5，而不是旧键的 9", 5, NovelPanelStyle.aheadBatches(prefs))
    }

    /**
     * 保持段落完整：**默认关闭**（用户明确要求）—— 关掉时按间距把页面填满，
     * 段落可能被切断；打开才做整段保护。
     */
    @Test
    fun `保持段落完整默认关闭且可开关`() {
        assertEquals(false, NovelPanelStyle.keepParagraphsWhole(prefs))
        NovelPanelStyle.setKeepParagraphsWhole(prefs, true)
        assertEquals(true, NovelPanelStyle.keepParagraphsWhole(prefs))
        NovelPanelStyle.setKeepParagraphsWhole(prefs, false)
        assertEquals(false, NovelPanelStyle.keepParagraphsWhole(prefs))
    }

    /** 恢复默认要把这个开关也关回去（否则「恢复默认」后排版还是整段保护）。 */
    @Test
    fun `恢复默认会关掉段落完整`() {
        NovelPanelStyle.setKeepParagraphsWhole(prefs, true)
        NovelPanelStyle.resetTypography(prefs)
        assertEquals(false, NovelPanelStyle.keepParagraphsWhole(prefs))
    }

    /** 翻译模式对外是枚举、底层仍是 0/1/2 —— 老用户存的「自动」不能被读成手动。 */
    @Test
    fun `翻译模式存取与默认值`() {
        assertEquals(NovelTranslateMode.MANUAL, NovelPanelStyle.translateMode(prefs))
        NovelPanelStyle.setTranslateMode(prefs, NovelTranslateMode.AHEAD)
        assertEquals(NovelTranslateMode.AHEAD, NovelPanelStyle.translateMode(prefs))
        NovelPanelStyle.setTranslateMode(prefs, NovelTranslateMode.AUTO)
        assertEquals(NovelTranslateMode.AUTO, NovelPanelStyle.translateMode(prefs))
        // 旧盘上的 int 值仍要能读出来（1=自动 2=增量）
        prefs.edit().putInt("novel_translate_mode", 2).commit()
        assertEquals(NovelTranslateMode.AHEAD, NovelPanelStyle.translateMode(prefs))
    }
}
