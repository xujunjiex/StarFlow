package com.moe.starflow.utils

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** 全局主题三态：跟随系统 / 浅色 / 暗色。存 CustomPreference 键 app_theme（system/light/dark）。
 *  Application.onCreate 与主页三态按钮共同调用；切换后 AppCompat 自动重建 AppCompatActivity。 */
object ThemeManager {
    const val KEY = "app_theme"
    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    fun currentMode(context: Context): String =
        CustomPreference.getInstance(context).getString(KEY, MODE_SYSTEM)

    fun setMode(context: Context, mode: String) {
        CustomPreference.getInstance(context).setString(KEY, mode)
        apply(context)
    }

    /** 循环：system → light → dark → system。 */
    fun cycle(context: Context): String {
        val next = when (currentMode(context)) {
            MODE_SYSTEM -> MODE_LIGHT
            MODE_LIGHT -> MODE_DARK
            else -> MODE_SYSTEM
        }
        setMode(context, next)
        return next
    }

    /** 应用到 AppCompatDelegate（未设置时默认跟随系统）。 */
    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (currentMode(context)) {
                MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}