package com.moe.starflow.novel.reader

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import com.moe.starflow.R

/**
 * 小说阅读器的偏好读写。**收敛成一处**，避免键名散落各处写错。
 *
 * ⚠️ **存的是与漫画阅读器同一份 `SharedPreferences`**（[PREFS_NAME]），
 * 背景/翻页动画两个键更是**直接共用**同一个 key —— 用户在漫画里把背景调成黑色，
 * 切到小说也该是黑的。两套存储会让「同一个软件」这个前提当场破功。
 *
 * ⚠️ 字号走 `MangaFontSize`（唯一来源），这里只转发 —— 漫画阅读器与悬浮窗也用同一套档位，
 * 另起一个键会让「设置页改了字号，小说没变」。
 */
object NovelPanelStyle {

    /** 与 `MangaReaderActivity` 同一个 prefs 文件。 */
    const val PREFS_NAME = "manga_reader"

    // ===== 阅读背景 / 翻页动画（键与漫画共用） =====

    private const val KEY_BG = "reader_background"
    private const val KEY_ANIM = "reader_animation"

    /** 翻页动画：0 无 / 1 滑动 / 2 覆盖。 */
    const val ANIM_NONE = 0
    const val ANIM_SLIDE = 1
    const val ANIM_COVER = 2

    fun animation(prefs: SharedPreferences): Int = prefs.getInt(KEY_ANIM, ANIM_SLIDE).coerceIn(0, 2)

    fun setAnimation(prefs: SharedPreferences, v: Int) =
        prefs.edit().putInt(KEY_ANIM, v.coerceIn(0, 2)).apply()

    /**
     * 阅读背景：0 默认 / 1 浅 / 2 深 / 3 白 / 4 黑 / 5 自动。
     * 值域与 [com.moe.starflow.mangaimport.reader.MangaReaderActivity] 完全一致。
     */
    fun background(prefs: SharedPreferences): Int = prefs.getInt(KEY_BG, 0).coerceIn(0, 5)

    fun setBackground(prefs: SharedPreferences, v: Int) =
        prefs.edit().putInt(KEY_BG, v.coerceIn(0, 5)).apply()

    /** 系统是否深色。⚠️ 读 `Resources.getSystem()`：不受 app 强制日夜主题影响（与漫画一致）。 */
    fun isSystemDark(): Boolean =
        (android.content.res.Resources.getSystem().configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** 背景是否深色（面板配色据此翻转）。 */
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
        1 -> 0xFFF0F0EE.toInt()          // Light
        2 -> 0xFF18181C.toInt()          // Dark
        3 -> android.graphics.Color.WHITE
        4 -> android.graphics.Color.BLACK
        5 -> if (isSystemDark()) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        else -> if (isSystemDark()) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }

    // ===== 开本与排版 =====

    const val LINE_SPACING_MIN = 10      // 行距倍率 ×10（10 = 1.0 倍）
    const val LINE_SPACING_MAX = 30
    const val PARAGRAPH_SPACING_MIN = 0  // 段间距 dp
    const val PARAGRAPH_SPACING_MAX = 40
    const val PADDING_MIN = 8            // 左右边距 dp
    const val PADDING_MAX = 48

    private const val KEY_LINE_SPACING = "novel_line_spacing"
    private const val KEY_PARA_SPACING = "novel_paragraph_spacing"
    private const val KEY_PADDING = "novel_reader_padding"
    private const val KEY_READER_MODE = "novel_reader_mode"
    private const val KEY_DISPLAY_MODE = "novel_display_mode"

    const val READER_PAGED = 0
    const val READER_SCROLL = 1

    fun lineSpacingStep(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_LINE_SPACING, 15).coerceIn(LINE_SPACING_MIN, LINE_SPACING_MAX)

    fun setLineSpacingStep(prefs: SharedPreferences, v: Int) =
        prefs.edit().putInt(KEY_LINE_SPACING, v.coerceIn(LINE_SPACING_MIN, LINE_SPACING_MAX)).apply()

    fun lineSpacingLabel(step: Int): String = String.format(java.util.Locale.US, "%.1f×", step / 10f)

    fun paragraphSpacingDp(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_PARA_SPACING, 14).coerceIn(PARAGRAPH_SPACING_MIN, PARAGRAPH_SPACING_MAX)

    fun setParagraphSpacingDp(prefs: SharedPreferences, v: Int) =
        prefs.edit().putInt(KEY_PARA_SPACING, v.coerceIn(PARAGRAPH_SPACING_MIN, PARAGRAPH_SPACING_MAX)).apply()

    fun paddingDp(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_PADDING, 20).coerceIn(PADDING_MIN, PADDING_MAX)

    fun setPaddingDp(prefs: SharedPreferences, v: Int) =
        prefs.edit().putInt(KEY_PADDING, v.coerceIn(PADDING_MIN, PADDING_MAX)).apply()

    fun readerMode(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_READER_MODE, READER_PAGED).coerceIn(0, 1)

    fun setReaderMode(prefs: SharedPreferences, v: Int) =
        prefs.edit().putInt(KEY_READER_MODE, v.coerceIn(0, 1)).apply()

    fun displayMode(prefs: SharedPreferences): NovelDisplayMode =
        NovelDisplayModeCodec.fromPref(prefs.getString(KEY_DISPLAY_MODE, null))

    fun setDisplayMode(prefs: SharedPreferences, mode: NovelDisplayMode) =
        prefs.edit().putString(KEY_DISPLAY_MODE, NovelDisplayModeCodec.toPref(mode)).apply()

    /** 用当前偏好拼出排版参数。dp → px 在这里换算，调用方只管传 density。 */
    fun textStyle(context: Context, prefs: SharedPreferences): NovelTextStyle {
        val density = context.resources.displayMetrics.scaledDensity
        val densityDpi = context.resources.displayMetrics.density
        return NovelTextStyle(
            fontSizePx = com.moe.starflow.utils.MangaFontSize.size(context) * density,
            lineSpacingMultiplier = lineSpacingStep(prefs) / 10f,
            paragraphSpacingPx = paragraphSpacingDp(prefs) * densityDpi,
            paddingPx = paddingDp(prefs) * densityDpi,
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
