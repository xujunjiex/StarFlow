package com.moe.starflow.manga.config

import android.content.SharedPreferences

/** 漫画字间距、行／列间距的百分比、默认值及旧默认值迁移。 */
object MangaTextSpacing {
    const val KEY_TRACKING = "manga_tracking"
    const val KEY_LEADING = "manga_leading"
    const val DEFAULT_PERCENT = 20
    const val DEFAULT_RATIO = DEFAULT_PERCENT / 100f
    private const val KEY_VERSION = "Manga_Spacing_Version"
    private const val VERSION = 1

    data class Spacing(val trackingPercent: Int, val leadingPercent: Int) {
        val trackingRatio: Float get() = trackingPercent / 100f
        val leadingRatio: Float get() = leadingPercent / 100f
    }

    @Synchronized
    fun read(prefs: SharedPreferences): Spacing {
        if (prefs.getInt(KEY_VERSION, 0) < VERSION) {
            val edit = prefs.edit().putInt(KEY_VERSION, VERSION)
            // 旧版 0 表示带隐藏间距的默认排版；只迁移一次，之后明确设置的 0 必须保留。
            for (key in listOf(KEY_TRACKING, KEY_LEADING)) {
                if (prefs.contains(key) && prefs.getInt(key, 0) == 0) {
                    edit.putInt(key, DEFAULT_PERCENT)
                }
            }
            edit.apply()
        }
        return Spacing(
            prefs.getInt(KEY_TRACKING, DEFAULT_PERCENT).coerceIn(0, 100),
            prefs.getInt(KEY_LEADING, DEFAULT_PERCENT).coerceIn(0, 100)
        )
    }

    fun percent(prefs: SharedPreferences, key: String): Int = read(prefs).let {
        when (key) {
            KEY_TRACKING -> it.trackingPercent
            KEY_LEADING -> it.leadingPercent
            else -> error("Unknown manga spacing key: $key")
        }
    }
}
