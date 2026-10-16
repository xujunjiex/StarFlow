package com.moe.starflow.novel.reader

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import com.moe.starflow.R
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.FontSync
import java.io.File

enum class NovelHighlight(val key: String, val defaultColor: Int, val defaultBorder: Int) {
    SELECTION("novel_color_selection", NovelTextRenderer.COLOR_SELECTION, NovelImageControls.SELECTION_BORDER),
    ACTIVE("novel_color_active", NovelTextRenderer.COLOR_ACTIVE_BATCH, NovelTextRenderer.BORDER_ACTIVE_BATCH),
    FAILED("novel_color_failed", NovelTextRenderer.COLOR_FAILED_BATCH, NovelTextRenderer.BORDER_FAILED_BATCH),
    COMPLETED("novel_color_completed", NovelTextRenderer.COLOR_LAST_TRANSLATED, NovelTextRenderer.BORDER_LAST_TRANSLATED),
}

/** Backgrounds retain their translucent defaults; image frames retain their opaque defaults. */
data class NovelStatusColors(
    val colors: List<Int> = NovelHighlight.entries.map { it.defaultColor },
    val borders: List<Int> = NovelHighlight.entries.map { it.defaultBorder },
    val selectionFill: Int = NovelImageControls.SELECTION_FILL,
) {
    fun color(state: NovelHighlight): Int = colors[state.ordinal]
    fun border(state: NovelHighlight): Int = borders[state.ordinal]
}

/** Novel-only color preferences, stored beside the existing reader preferences. */
object NovelAppearance {
    const val CUSTOM_MODE = 6
    const val DEFAULT_BACKGROUND = 0xFFFFFFFF.toInt()
    const val DEFAULT_TEXT = 0xFF1A1A1A.toInt()
    private const val KEY_SELECTED = "novel_custom_background_selected"
    private const val KEY_BACKGROUND = "novel_custom_background_color"
    private const val KEY_TEXT = "novel_custom_text_color"

    fun customSelected(prefs: SharedPreferences) = prefs.getBoolean(KEY_SELECTED, false)
    fun selectCustom(prefs: SharedPreferences, selected: Boolean) {
        prefs.edit().putBoolean(KEY_SELECTED, selected).apply()
    }
    fun customBackground(prefs: SharedPreferences) = prefs.getInt(KEY_BACKGROUND, DEFAULT_BACKGROUND) or 0xFF000000.toInt()
    fun customText(prefs: SharedPreferences) = prefs.getInt(KEY_TEXT, DEFAULT_TEXT) or 0xFF000000.toInt()
    fun saveCustom(prefs: SharedPreferences, background: Int, text: Int) {
        prefs.edit().putInt(KEY_BACKGROUND, background or 0xFF000000.toInt())
            .putInt(KEY_TEXT, text or 0xFF000000.toInt()).putBoolean(KEY_SELECTED, true).apply()
    }
    fun statusColors(prefs: SharedPreferences) = NovelStatusColors(
        NovelHighlight.entries.map { prefs.getInt(it.key, it.defaultColor) },
        NovelHighlight.entries.map { if (prefs.contains(it.key)) prefs.getInt(it.key, it.defaultColor) else it.defaultBorder },
        prefs.getInt(NovelHighlight.SELECTION.key, NovelImageControls.SELECTION_FILL),
    )
    fun setStatusColor(prefs: SharedPreferences, state: NovelHighlight, color: Int?) {
        val editor = prefs.edit()
        if (color == null) editor.remove(state.key) else editor.putInt(state.key, color)
        editor.apply()
    }
    fun resetStatusColors(prefs: SharedPreferences) {
        val editor = prefs.edit()
        NovelHighlight.entries.forEach { editor.remove(it.key) }
        editor.apply()
    }
}

/** A stable font identity participates in both paragraph and chapter layout caches. */
object NovelReaderFont {
    data class Resolved(val key: String, val typeface: Typeface?)
    private var cached: Resolved? = null

    @Synchronized
    fun resolve(context: Context): Resolved {
        val name = CustomPreference.getInstance(context).getString("Custom_Result_Font", "")
        if (name.isEmpty()) return Resolved("", null)
        val file = File(context.getExternalFilesDir(null), "font/$name")
        val key = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        return cached?.takeIf { it.key == key } ?: Resolved(key, FontSync.loadTypeface(context)).also { cached = it }
    }

    fun label(context: Context): String = if (resolve(context).typeface == null) {
        context.getString(R.string.novel_font_default)
    } else CustomPreference.getInstance(context).getString("Custom_Result_Font", "")
}
