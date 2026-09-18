package com.moe.starflow.utils

import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.moe.starflow.R

/**
 * 漫画翻译结果**字号**的唯一读写入口。
 *
 * 此前字号散在三处各写各的（个性化设置页没有这一项、悬浮窗菜单弹窗直接写 prefs、
 * 阅读器翻译面板没有入口），任何一处都读不到对方改的值。现在统一收在这里：
 *
 * - **设置页**（个性化 → 漫画翻译结果设置）是「最初始的位置」，也是用户心里的那张总表；
 * - **悬浮窗**的「字体大小」菜单弹窗改为调用 [setAuto] / [setSize]；
 * - **阅读器翻译面板**的字号组件同样调用它们。
 *
 * 三者读写的是同一对 prefs，所以任何一处改完，另外两处立刻一致。
 *
 * ⚠️ 存储键沿用历史的 `Manga_Font_Size` / `Manga_Auto_Font_Size`（PascalCase，见
 * CLAUDE.md「偏好键名规范」）。`TranslationCacheManager.getOverlayConfig` 与
 * `MangaFloatingService.loadConfig` 直接读这两个键 —— 不要另起新键，否则老用户设置会"消失"。
 */
object MangaFontSize {

    /** 固定字号（sp/px，取值即预置档位里的数值）。 */
    const val KEY_SIZE = "Manga_Font_Size"

    /** 自动字号开关：true = 由排版按气泡大小自适应，此时 [KEY_SIZE] 不生效。 */
    const val KEY_AUTO = "Manga_Auto_Font_Size"

    const val DEFAULT_SIZE = 16f
    const val DEFAULT_AUTO = true

    /**
     * 预置档位，与悬浮窗弹窗里的列表一一对应。
     * ⚠️ 顺序即弹窗里的顺序，也是 [indexOf] 的依据 —— 改顺序会让老用户选中的档位指向别的值。
     */
    val PRESET_SIZES = listOf(8, 10, 12, 14, 16, 18, 20, 24, 28, 32, 40, 48)

    /** 字号下限/上限（滑块与档位都需要）。 */
    const val MIN_SIZE = 8
    const val MAX_SIZE = 48

    private fun prefs(context: android.content.Context): SharedPreferences =
        CustomPreference.getInstance(context).getSharedPreferences()

    /** 是否自动字号。 */
    fun isAuto(context: android.content.Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO, DEFAULT_AUTO)

    /** 固定字号（仅在 [isAuto] = false 时有意义）。 */
    fun size(context: android.content.Context): Float =
        prefs(context).getFloat(KEY_SIZE, DEFAULT_SIZE)

    /** 一句话描述当前设置，供设置页 summary 与面板显示（自动 / 16 sp）。 */
    fun summary(context: android.content.Context): String =
        if (isAuto(context)) context.getString(R.string.manga_font_size_auto)
        else "${size(context).toInt()} sp"

    /** 是否与预置档位完全相等（不等说明是自定义值）。 */
    fun isPreset(context: android.content.Context): Boolean =
        PRESET_SIZES.contains(size(context).toInt())

    /** 切换自动字号。**会同时写两个键**：只写 AUTO 会让字号残留在旧值上，切回来时对不上。 */
    fun setAuto(context: android.content.Context, auto: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, auto).apply()
    }

    /** 设置固定字号（自动字号同时关掉 —— 用户选了数值就是要这个数值）。 */
    fun setSize(context: android.content.Context, sp: Float) {
        prefs(context).edit()
            .putFloat(KEY_SIZE, sp.coerceIn(MIN_SIZE.toFloat(), MAX_SIZE.toFloat()))
            .putBoolean(KEY_AUTO, false)
            .apply()
    }

    /** 档位列表里的下标（不在档位内时回退到默认 16 的下标）。 */
    fun presetIndexOf(context: android.content.Context): Int {
        val idx = PRESET_SIZES.indexOf(size(context).toInt())
        return if (idx >= 0) idx else PRESET_SIZES.indexOf(DEFAULT_SIZE.toInt())
    }

    /**
     * 设置页用的是 `PreferenceManager.getDefaultSharedPreferences` —— 与 [CustomPreference]
     * 是同一个文件（`CustomPreference` 内部就是它），这里显式取一次便于在设置页做 summary 刷新。
     */
    fun defaultPrefs(context: android.content.Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)
}
