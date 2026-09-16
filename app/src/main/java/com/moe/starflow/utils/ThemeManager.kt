package com.moe.starflow.utils

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ContextThemeWrapper
import com.moe.starflow.R

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

    /**
     * 悬浮窗（Service）弹窗用的主题上下文。
     *
     * ⚠️ Service 拿不到应用主题：`AppCompatDelegate.setDefaultNightMode` 只更新 Activity / AppCompat
     * 对话框自己的 Resources（见 `AppCompatDelegateImpl.updateResourcesConfiguration`），`<service>`
     * 又不能声明 android:theme → Service 上下文是**系统默认浅色主题**，标题/正文恒为深色字；
     * 而弹窗背景 `dialog_background` 的 `@color/surface` 随 uiMode 翻成深色 → 暗色下深字压深底看不清。
     * 这里显式套上应用主题 + 按「应用主题设置」定死 uiMode，让文字色与背景色同源。
     */
    fun dialogContext(context: Context): Context {
        val night = when (currentMode(context)) {
            MODE_DARK -> true
            MODE_LIGHT -> false
            else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        val config = Configuration(context.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        return ContextThemeWrapper(context.createConfigurationContext(config), R.style.Base_Theme_MT)
    }
}