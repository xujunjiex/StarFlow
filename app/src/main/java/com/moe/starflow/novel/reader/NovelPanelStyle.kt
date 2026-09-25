package com.moe.starflow.novel.reader

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import com.moe.starflow.R

/**
 * 小说阅读器的偏好读写。**收敛成一处**，避免键名散落各处写错。
 *
 * ⚠️ 背景 / 翻页动画 / 旋转 存的是与漫画阅读器同一份 `SharedPreferences`（[PREFS_NAME]），
 * 那几个键更是直接共用 —— 用户在漫画里把背景调成黑色，切到小说也该是黑的。
 * 两套存储会让「同一个软件」这个前提当场破功。
 *
 * ⚠️ 但**字号是小说自己一份**（[fontSizeSp]）：漫画的字号是按气泡图尺寸推的
 * （它还有「自动字号」），与正文排版没有关系；共用会让「恢复默认字号」连带改掉漫画，
 * 而正文默认 16sp 明显偏小。排版参数（行距/段距/边距/间距）本来就是小说独有的。
 */
object NovelPanelStyle {

    /** 与 `MangaReaderActivity` 同一个 prefs 文件。 */
    const val PREFS_NAME = "manga_reader"

    // ===== 上下浮层需要避开的尺寸 =====

    /**
     * 上下浮层占掉的纵向空间（摸清后写死，供默认值参考）：
     * 顶部：返回/菜单圆形钮 34~74dp，章节胶囊 38~64dp → 留 80dp 就够。
     * 底部：胶囊 10~58dp → 80dp 也够；右下翻译浮层组在 62~114dp，只压住**最右一行的末端**。
     */
    private const val CHROME_TOP_DP = 80
    private const val CHROME_BOTTOM_DP = 80

    // ===== 字号（小说独立一份） =====

    const val FONT_SIZE_MIN = 12f
    const val FONT_SIZE_MAX = 34f
    const val FONT_SIZE_DEFAULT = 20f

    private const val KEY_FONT_SIZE = "novel_font_size"

    fun fontSizeSp(prefs: SharedPreferences): Float =
        prefs.getFloat(KEY_FONT_SIZE, FONT_SIZE_DEFAULT).coerceIn(FONT_SIZE_MIN, FONT_SIZE_MAX)

    fun setFontSizeSp(prefs: SharedPreferences, sp: Float) =
        prefs.edit().putFloat(KEY_FONT_SIZE, sp.coerceIn(FONT_SIZE_MIN, FONT_SIZE_MAX)).apply()

    // ===== 背景 / 翻页动画（键与漫画共用） =====

    private const val KEY_BG = "reader_background"
    private const val KEY_ANIM = "reader_animation"

    /** 翻页动画：0 无 / 1 滑动 / 2 覆盖 / 3 仿真（折页）。**四项与漫画一一对应**。 */
    const val ANIM_NONE = 0
    const val ANIM_SLIDE = 1
    const val ANIM_COVER = 2
    const val ANIM_SIMULATION = 3

    fun animation(prefs: SharedPreferences): Int = prefs.getInt(KEY_ANIM, ANIM_SLIDE).coerceIn(0, 3)

    fun setAnimation(prefs: SharedPreferences, v: Int) =
        prefs.edit().putInt(KEY_ANIM, v.coerceIn(0, 3)).apply()

    /**
     * 阅读背景：0 默认 / 1 浅 / 2 深 / 3 白 / 4 黑 / 5 自动。
     * 值域与 `MangaReaderActivity` 完全一致。
     */
    fun background(prefs: SharedPreferences): Int = prefs.getInt(KEY_BG, 0).coerceIn(0, 5)

    fun setBackground(prefs: SharedPreferences, v: Int) =
        prefs.edit().putInt(KEY_BG, v.coerceIn(0, 5)).apply()

    /** 系统是否深色。⚠️ 读 `Resources.getSystem()`：不受 app 强制日夜主题影响（与漫画一致）。 */
    fun isSystemDark(): Boolean =
        (android.content.res.Resources.getSystem().configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun isDarkBackground(bg: Int): Boolean = when (bg) {
        2, 4 -> true
        3 -> false
        1 -> false
        else -> isSystemDark()
    }

    fun isDarkBackground(context: Context, prefs: SharedPreferences): Boolean =
        isDarkBackground(background(prefs))

    /** 背景色值。与漫画的 `resolveBgColor()` 同一套取值。 */
    fun backgroundColor(bg: Int): Int = when (bg) {
        1 -> 0xFFF0F0EE.toInt()
        2 -> 0xFF18181C.toInt()
        3 -> android.graphics.Color.WHITE
        4 -> android.graphics.Color.BLACK
        5 -> if (isSystemDark()) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        else -> if (isSystemDark()) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }

    // ===== 排版间距 =====

    /**
     * 字号 → 行高的经验系数。
     *
     * `StaticLayout` 的行高来自字体的 `top..bottom`，对常见中文字体约为字号的 1.15 倍。
     * 这里用它把「行距倍率」换算成**真实的行间空隙**（`字号 × 系数 × (倍率 - 1)`），
     * 后面「段距必须大于行距」的约束全靠这个换算。
     */
    private const val FONT_LINE_FACTOR = 1.15f

    /** 行距倍率 ×10。范围 1.0× ~ 2.2×：再小中文会挤，再大就散架了。 */
    const val LINE_SPACING_MIN = 10
    const val LINE_SPACING_MAX = 22
    const val LINE_SPACING_DEFAULT = 15

    /** 段间距 dp。**下限是动态算出来的**（见 [minParagraphSpacingDp]），这里的 MAX 是硬上限。 */
    const val PARA_SPACING_MAX = 48
    const val PARA_SPACING_DEFAULT = 18

    /** 段落间距与行间距的最小倍数关系：段距给的空隙至少是行间空隙的 1.2 倍。 */
    private const val PARA_OVER_LINE_RATIO = 1.2f

    /** 段距的绝对下限：即使行距为 1.0×（行间空隙为 0），段距也得看得出是分段。与面板 `sb_para_spacing` 的 min 一致。 */
    const val PARA_SPACING_ABS_MIN = 6

    const val SIDE_PADDING_MIN = 16
    const val SIDE_PADDING_MAX = 64
    const val SIDE_PADDING_DEFAULT = 20

    const val VERTICAL_PADDING_MIN = 16
    const val VERTICAL_PADDING_MAX = 160

    private const val KEY_LINE_SPACING = "novel_line_spacing"
    private const val KEY_PARA_SPACING = "novel_paragraph_spacing"
    private const val KEY_PADDING = "novel_reader_padding"
    private const val KEY_TOP_PADDING = "novel_reader_top_padding"
    private const val KEY_BOTTOM_PADDING = "novel_reader_bottom_padding"
    private const val KEY_READER_MODE_VERSION = "novel_reader_mode_version"
    private const val KEY_READER_MODE = "novel_reader_mode"
    private const val READER_MODE_VERSION = 1

    fun lineSpacingStep(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_LINE_SPACING, LINE_SPACING_DEFAULT).coerceIn(LINE_SPACING_MIN, LINE_SPACING_MAX)

    fun lineSpacingLabel(step: Int): String = String.format(java.util.Locale.US, "%.1f×", step / 10f)

    /** 行距换算出的**真实行间空隙**（sp）。 */
    fun lineGapSp(fontSizeSp: Float, lineStep: Int): Float =
        fontSizeSp * FONT_LINE_FACTOR * (lineStep / 10f - 1f)

    /**
     * 段落间距的下限（dp）。
     *
     * ⚠️ **段距必须始终大于行距**，否则段落之间看起来和行之间一样，整页糊成一块、分不出段。
     * 判据不是「数字上比行距大」而是「视觉空隙更大」：行距倍率给出的空隙是
     * `字号 × 1.15 × (倍率-1)`，段距必须比它再大一截（[PARA_OVER_LINE_RATIO]）。
     * 行距调到 1.0× 时行间空隙为 0，此时用绝对下限 [PARA_SPACING_ABS_MIN] 兜底。
     */
    fun minParagraphSpacingDp(fontSizeSp: Float, lineStep: Int): Int {
        val gapDp = lineGapSp(fontSizeSp, lineStep) * PARA_OVER_LINE_RATIO
        return maxOf(kotlin.math.ceil(gapDp).toInt(), PARA_SPACING_ABS_MIN)
    }

    /**
     * 段距的**上限**。通常是 [PARA_SPACING_MAX]，但当字号/行距很大时，下限会顶到甚至超过 48dp
     * （34sp × 2.2× 时下限 ≈ 57dp）—— 那时必须把上限抬上去。
     *
     * ⚠️ 不抬的话 `coerceIn(min, 48)` 会变成 `min > max` 的空区间，**Kotlin 直接抛
     * IllegalArgumentException**（"Cannot coerce value to an empty range"）。这是能把阅读器
     * 打崩的那种 bug，不是显示问题。
     */
    fun maxParagraphSpacingDp(fontSizeSp: Float, lineStep: Int): Int =
        maxOf(PARA_SPACING_MAX, minParagraphSpacingDp(fontSizeSp, lineStep) + 4)

    /** 读段距时也按当前字号/行距夹一次：改了行距之后，旧段距可能已经不满足「大于行距」。 */
    fun paragraphSpacingDp(prefs: SharedPreferences, fontSizeSp: Float): Int {
        val step = lineSpacingStep(prefs)
        return prefs.getInt(KEY_PARA_SPACING, PARA_SPACING_DEFAULT)
            .coerceIn(minParagraphSpacingDp(fontSizeSp, step), maxParagraphSpacingDp(fontSizeSp, step))
    }

    fun setParagraphSpacingDp(prefs: SharedPreferences, v: Int, fontSizeSp: Float) {
        val step = lineSpacingStep(prefs)
        prefs.edit()
            .putInt(
                KEY_PARA_SPACING,
                v.coerceIn(minParagraphSpacingDp(fontSizeSp, step), maxParagraphSpacingDp(fontSizeSp, step)),
            )
            .apply()
    }

    /** 改行距：顺手把段距抬到新下限，避免出现「段距小于行距」的非法组合。 */
    fun setLineSpacingStep(prefs: SharedPreferences, v: Int, fontSizeSp: Float) {
        val step = v.coerceIn(LINE_SPACING_MIN, LINE_SPACING_MAX)
        prefs.edit().putInt(KEY_LINE_SPACING, step).apply()
        val min = minParagraphSpacingDp(fontSizeSp, step)
        if (paragraphSpacingDpRaw(prefs) < min) {
            prefs.edit().putInt(KEY_PARA_SPACING, min).apply()
        }
    }

    private fun paragraphSpacingDpRaw(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_PARA_SPACING, PARA_SPACING_DEFAULT)

    fun paddingDp(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_PADDING, SIDE_PADDING_DEFAULT).coerceIn(SIDE_PADDING_MIN, SIDE_PADDING_MAX)

    fun setPaddingDp(prefs: SharedPreferences, v: Int) =
        prefs.edit().putInt(KEY_PADDING, v.coerceIn(SIDE_PADDING_MIN, SIDE_PADDING_MAX)).apply()

    fun topPaddingDp(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_TOP_PADDING, verticalPaddingDefaultDp())
            .coerceIn(VERTICAL_PADDING_MIN, VERTICAL_PADDING_MAX)

    fun bottomPaddingDp(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_BOTTOM_PADDING, verticalPaddingDefaultDp())
            .coerceIn(VERTICAL_PADDING_MIN, VERTICAL_PADDING_MAX)

    fun setTopPaddingDp(prefs: SharedPreferences, v: Int) = prefs.edit()
        .putInt(KEY_TOP_PADDING, v.coerceIn(VERTICAL_PADDING_MIN, VERTICAL_PADDING_MAX)).apply()

    fun setBottomPaddingDp(prefs: SharedPreferences, v: Int) = prefs.edit()
        .putInt(KEY_BOTTOM_PADDING, v.coerceIn(VERTICAL_PADDING_MIN, VERTICAL_PADDING_MAX)).apply()

    /** 上下间距的默认值：**上下取同一个数**（用户明确要求），且要够避开上下浮层。 */
    fun verticalPaddingDefaultDp(): Int =
        maxOf(CHROME_TOP_DP, CHROME_BOTTOM_DP).coerceIn(VERTICAL_PADDING_MIN, VERTICAL_PADDING_MAX)

    /**
     * **整段保护**：分页不在段落中间切断（放不下的段整段挪到下一页）。
     *
     * ⚠️ 这是**固定行为**，不再挂开关。曾经把它绑在「自动排版」上（关了按行填满），
     * 现在自动排版已删除，取舍重新明确一次：句子被从中间切断，翻译也只能按半句来，
     * 读者看到的是半句话 —— 宁可页面底部剩不到一段的空白，也不切句子。
     */
    const val KEEP_PARAGRAPHS_WHOLE = true

    // ===== 恢复默认 =====

    /** 恢复默认：字号与全部间距回到默认值。 */
    fun resetTypography(prefs: SharedPreferences) {
        val vertical = verticalPaddingDefaultDp()
        prefs.edit()
            .putFloat(KEY_FONT_SIZE, FONT_SIZE_DEFAULT)
            .putInt(KEY_LINE_SPACING, LINE_SPACING_DEFAULT)
            .putInt(KEY_PARA_SPACING, PARA_SPACING_DEFAULT)
            .putInt(KEY_PADDING, SIDE_PADDING_DEFAULT)
            .putInt(KEY_TOP_PADDING, vertical)
            .putInt(KEY_BOTTOM_PADDING, vertical)
            .apply()
    }

    // ===== 阅读模式 =====

    /** 阅读模式：0 左右翻页 / 1 上下翻页 / 2 连续滚动。 */
    const val READER_PAGED = 0
    const val READER_VERTICAL = 1
    const val READER_SCROLL = 2

    private const val KEY_DISPLAY_MODE = "novel_display_mode"

    fun readerMode(prefs: SharedPreferences): Int {
        migrateReaderMode(prefs)
        return prefs.getInt(KEY_READER_MODE, READER_PAGED).coerceIn(0, 2)
    }

    fun setReaderMode(prefs: SharedPreferences, v: Int) {
        prefs.edit()
            .putInt(KEY_READER_MODE, v.coerceIn(0, 2))
            .putInt(KEY_READER_MODE_VERSION, READER_MODE_VERSION)
            .apply()
    }

    /**
     * 阅读模式取值的一次性迁移。
     *
     * 引入「上下翻页」之前只有 `0 分页 / 1 滚动`；现在 1 变成了「上下翻页」、滚动挪到 2。
     * 不迁移的话，用户原来选的「滚动」会**静默变成「上下翻页」**，他只会觉得"滚动模式坏了"。
     */
    private fun migrateReaderMode(prefs: SharedPreferences) {
        if (prefs.getInt(KEY_READER_MODE_VERSION, 0) >= READER_MODE_VERSION) return
        val old = prefs.getInt(KEY_READER_MODE, READER_PAGED)
        val migrated = when (old) {
            1 -> READER_SCROLL
            2 -> READER_VERTICAL
            else -> READER_PAGED
        }
        prefs.edit()
            .putInt(KEY_READER_MODE, migrated)
            .putInt(KEY_READER_MODE_VERSION, READER_MODE_VERSION)
            .apply()
    }

    fun displayMode(prefs: SharedPreferences): NovelDisplayMode =
        NovelDisplayModeCodec.fromPref(prefs.getString(KEY_DISPLAY_MODE, null))

    fun setDisplayMode(prefs: SharedPreferences, mode: NovelDisplayMode) =
        prefs.edit().putString(KEY_DISPLAY_MODE, NovelDisplayModeCodec.toPref(mode)).apply()

    /**
     * 用当前偏好拼出排版参数。dp → px 在这里换算，调用方只管传 density。
     */
    fun textStyle(context: Context, prefs: SharedPreferences): NovelTextStyle {
        val density = context.resources.displayMetrics.scaledDensity
        val densityDpi = context.resources.displayMetrics.density
        val fontSp = fontSizeSp(prefs)
        return NovelTextStyle(
            fontSizePx = fontSp * density,
            lineSpacingMultiplier = lineSpacingStep(prefs) / 10f,
            paragraphSpacingPx = paragraphSpacingDp(prefs, fontSp) * densityDpi,
            paddingPx = paddingDp(prefs) * densityDpi,
            topPaddingPx = topPaddingDp(prefs) * densityDpi,
            bottomPaddingPx = bottomPaddingDp(prefs) * densityDpi,
            keepParagraphsWhole = KEEP_PARAGRAPHS_WHOLE,
        )
    }

    /**
     * 正文文字颜色：跟随阅读背景深浅。
     *
     * 背景是深色时用浅灰而不是纯白 —— 纯白压在纯黑上对比过强，长段落读久了刺眼。
     */
    fun textColor(bg: Int): Int = if (isDarkBackground(bg)) 0xFFD8D8DC.toInt() else 0xFF1A1A1A.toInt()

    // ===== 翻译 =====

    const val MODE_MANUAL = 0
    const val MODE_AUTO_CHAPTER = 1
    const val MODE_AUTO_AHEAD = 2

    const val DEBOUNCE_MIN = 200
    const val DEBOUNCE_MAX = 2000
    const val AHEAD_MIN = 1
    const val AHEAD_MAX = 10

    private const val KEY_TRANSLATE_MODE = "novel_translate_mode"
    private const val KEY_DEBOUNCE = "novel_translate_debounce"
    private const val KEY_AHEAD = "novel_translate_ahead"
    private const val KEY_BATCH = "novel_translate_batch"

    fun translateMode(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_TRANSLATE_MODE, MODE_MANUAL).coerceIn(0, 2)

    fun setTranslateMode(prefs: SharedPreferences, v: Int) =
        prefs.edit().putInt(KEY_TRANSLATE_MODE, v.coerceIn(0, 2)).apply()

    fun debounceMs(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_DEBOUNCE, 500).coerceIn(DEBOUNCE_MIN, DEBOUNCE_MAX)

    fun setDebounceMs(prefs: SharedPreferences, v: Int) =
        prefs.edit().putInt(KEY_DEBOUNCE, v.coerceIn(DEBOUNCE_MIN, DEBOUNCE_MAX)).apply()

    fun aheadChapters(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_AHEAD, 3).coerceIn(AHEAD_MIN, AHEAD_MAX)

    fun setAheadChapters(prefs: SharedPreferences, v: Int) =
        prefs.edit().putInt(KEY_AHEAD, v.coerceIn(AHEAD_MIN, AHEAD_MAX)).apply()

    /** 每批段数。小说段落长，批次比漫画小。 */
    fun batchSize(prefs: SharedPreferences): Int = prefs.getInt(KEY_BATCH, 8).coerceIn(1, 20)

    fun setBatchSize(prefs: SharedPreferences, v: Int) =
        prefs.edit().putInt(KEY_BATCH, v.coerceIn(1, 20)).apply()

    fun modeLabel(context: Context, mode: Int): String = context.getString(
        when (mode) {
            MODE_AUTO_CHAPTER -> R.string.novel_translate_mode_auto_chapter
            MODE_AUTO_AHEAD -> R.string.novel_translate_mode_auto_ahead
            else -> R.string.novel_translate_mode_manual
        }
    )

    fun displayModeLabel(context: Context, mode: NovelDisplayMode): String = context.getString(
        when (mode) {
            NovelDisplayMode.ORIGINAL -> R.string.novel_display_original
            NovelDisplayMode.BILINGUAL -> R.string.novel_display_bilingual
            else -> R.string.novel_display_translated
        }
    )
}
