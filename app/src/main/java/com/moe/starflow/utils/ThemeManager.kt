package com.moe.starflow.utils

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils
import com.moe.starflow.R

/** 全局主题三态：跟随系统 / 浅色 / 暗色。存 CustomPreference 键 app_theme（system/light/dark）。
 *  Application.onCreate 与主页三态按钮共同调用；切换后 AppCompat 自动重建 AppCompatActivity。
 *
 *  ⚠️ **Activity 之外的窗口（悬浮球 / 悬浮窗弹窗）拿不到这个主题**：`<service>` 不能声明
 *  android:theme，`setDefaultNightMode` 也只作用于 Activity 与 AppCompat 对话框自己的 Resources，
 *  Service 的 configuration 始终跟随系统。这类组件一律走 [dialogContext] / [isNight] /
 *  [dialogBackgroundRes]，不要直接读 `resources.configuration`。`KEY` 加入 Service 的 prefs
 *  watcher 即可在设置页切主题时重建在屏窗口（见 `MangaFloatingService.rebuildThemedWindows`）。 */
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
        val config = Configuration(context.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (isNight(context)) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        return ContextThemeWrapper(context.createConfigurationContext(config), R.style.Base_Theme_MT)
    }

    /**
     * 当前应用主题是否为暗色。
     *
     * 悬浮窗这类**自己实现深浅切换**的组件（阅读器除外）用它统一判定，而不是各自去读
     * `resources.configuration` —— Service 的 configuration 跟随系统，读它会拿到与应用主题
     * 相反的答案（应用设浅色 + 系统暗色时，悬浮球按系统选了浅色，旁边的弹窗按应用主题还是深字，
     * 同一屏两种配色）。`dialogContext` 和 [dialogBackgroundRes] 共用它，保证上下文与背景同源。
     */
    fun isNight(context: Context): Boolean = when (currentMode(context)) {
        MODE_DARK -> true
        MODE_LIGHT -> false
        else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * 悬浮窗弹窗的窗口背景 drawable（圆角 + 底色）。
     *
     * ⚠️ **不能用 `@drawable/dialog_background`**：它内部的 `@color/surface` 是按**持有该
     * drawable 的 Context** 解析的，而 `dialog.window` 的 Context 是 Service 自带的、没有主题的
     * 那个 → 无论应用主题如何都取到 `values/colors.xml` 的白色 `surface`，暗色下弹窗还是白底。
     * 深浅两张都用**固定色**（不引 `@color/`），从根上避免这个解析歧义。
     */
    @DrawableRes
    fun dialogBackgroundRes(context: Context): Int =
        if (isNight(context)) R.drawable.dialog_background_dark else R.drawable.dialog_background_light

    /**
     * 悬浮球图标的底色盘（圆角由 shape 保证是正圆），颜色取语义色 `surface`。
     *
     * 悬浮球图标是用户选的 PNG/webp，本身不能被主题染色 —— 浅色图配上暗色主题时，画面里就剩
     * 一块刺眼的白色贴纸。给它垫一层跟随主题的底色 + 一圈描边，深色主题下才压得住。
     *
     * ⚠️ 用 [ColorUtils.setAlphaComponent] 显式给 alpha，**不能做成 `@color/surface` + `android:alpha`
     * 的层叠 drawable** —— `android:alpha` 会把描边一起调淡。
     */
    fun ballPlateDrawable(context: Context): android.graphics.drawable.Drawable {
        val surface = androidx.core.content.ContextCompat.getColor(context, R.color.surface)
        val border = androidx.core.content.ContextCompat.getColor(context, R.color.divider)
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            // 87% 不透明：既挡住底下游戏画面，又不会像纯色块那样突兀
            setColor(ColorUtils.setAlphaComponent(surface, 0xE0))
            setStroke(
                (1.5f * context.resources.displayMetrics.density).toInt(),
                ColorUtils.setAlphaComponent(border, 0xB0)
            )
        }
    }
}