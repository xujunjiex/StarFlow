package com.moe.starflow.novel.reader

import android.content.Context
import com.moe.starflow.R
import com.moe.starflow.utils.CustomPreference

/**
 * 小说阅读器的样式 / 翻译偏好读写。**收敛成一处**，避免键名散落各处写错。
 *
 * ⚠️ 字号走 `MangaFontSize`（唯一来源），这里只转发 —— 漫画阅读器与悬浮窗也用同一套档位，
 * 另起一个键会让「设置页改了字号，小说没变」。
 */
object NovelPanelStyle {

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

    fun lineSpacingStep(prefs: CustomPreference): Int =
        prefs.getInt(KEY_LINE_SPACING, 15).coerceIn(LINE_SPACING_MIN, LINE_SPACING_MAX)

    fun setLineSpacingStep(prefs: CustomPreference, v: Int) =
        prefs.setInt(KEY_LINE_SPACING, v.coerceIn(LINE_SPACING_MIN, LINE_SPACING_MAX))

    fun lineSpacingLabel(step: Int): String = String.format(java.util.Locale.US, "%.1f×", step / 10f)

    fun paragraphSpacingDp(prefs: CustomPreference): Int =
        prefs.getInt(KEY_PARA_SPACING, 14).coerceIn(PARAGRAPH_SPACING_MIN, PARAGRAPH_SPACING_MAX)

    fun setParagraphSpacingDp(prefs: CustomPreference, v: Int) =
        prefs.setInt(KEY_PARA_SPACING, v.coerceIn(PARAGRAPH_SPACING_MIN, PARAGRAPH_SPACING_MAX))

    fun paddingDp(prefs: CustomPreference): Int =
        prefs.getInt(KEY_PADDING, 20).coerceIn(PADDING_MIN, PADDING_MAX)

    fun setPaddingDp(prefs: CustomPreference, v: Int) =
        prefs.setInt(KEY_PADDING, v.coerceIn(PADDING_MIN, PADDING_MAX))

    fun readerMode(prefs: CustomPreference): Int = prefs.getInt(KEY_READER_MODE, READER_PAGED).coerceIn(0, 1)

    fun setReaderMode(prefs: CustomPreference, v: Int) = prefs.setInt(KEY_READER_MODE, v.coerceIn(0, 1))

    fun displayMode(prefs: CustomPreference): NovelDisplayMode =
        NovelDisplayModeCodec.fromPref(prefs.getSharedPreferences().getString(KEY_DISPLAY_MODE, null))

    fun setDisplayMode(prefs: CustomPreference, mode: NovelDisplayMode) =
        prefs.setString(KEY_DISPLAY_MODE, NovelDisplayModeCodec.toPref(mode))

    /** 用当前偏好拼出排版参数。dp → px 在这里换算，调用方只管传 density。 */
    fun textStyle(context: Context, prefs: CustomPreference): NovelTextStyle {
        val density = context.resources.displayMetrics.scaledDensity
        val densityDpi = context.resources.displayMetrics.density
        return NovelTextStyle(
            fontSizePx = com.moe.starflow.utils.MangaFontSize.size(context) * density,
            lineSpacingMultiplier = lineSpacingStep(prefs) / 10f,
            paragraphSpacingPx = paragraphSpacingDp(prefs) * densityDpi,
            paddingPx = paddingDp(prefs) * densityDpi,
        )
    }

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

    fun translateMode(prefs: CustomPreference): Int =
        prefs.getInt(KEY_TRANSLATE_MODE, MODE_MANUAL).coerceIn(0, 2)

    fun setTranslateMode(prefs: CustomPreference, v: Int) =
        prefs.setInt(KEY_TRANSLATE_MODE, v.coerceIn(0, 2))

    fun debounceMs(prefs: CustomPreference): Int =
        prefs.getInt(KEY_DEBOUNCE, 500).coerceIn(DEBOUNCE_MIN, DEBOUNCE_MAX)

    fun setDebounceMs(prefs: CustomPreference, v: Int) =
        prefs.setInt(KEY_DEBOUNCE, v.coerceIn(DEBOUNCE_MIN, DEBOUNCE_MAX))

    fun aheadChapters(prefs: CustomPreference): Int =
        prefs.getInt(KEY_AHEAD, 3).coerceIn(AHEAD_MIN, AHEAD_MAX)

    fun setAheadChapters(prefs: CustomPreference, v: Int) =
        prefs.setInt(KEY_AHEAD, v.coerceIn(AHEAD_MIN, AHEAD_MAX))

    /** 每批段数。小说段落长，批次比漫画小。 */
    fun batchSize(prefs: CustomPreference): Int = prefs.getInt(KEY_BATCH, 8).coerceIn(1, 20)

    fun setBatchSize(prefs: CustomPreference, v: Int) = prefs.setInt(KEY_BATCH, v.coerceIn(1, 20))

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
