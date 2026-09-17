package com.moe.starflow.utils

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.preference.PreferenceManager
import com.moe.starflow.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `ThemeManager.dialogContext` —— 悬浮窗（Service）弹窗的主题上下文。
 *
 * Service 自己拿的是系统默认浅色主题（`<service>` 不能声明 android:theme，AppCompat 的
 * `setDefaultNightMode` 也只作用于 Activity 的 Resources），而弹窗背景 `dialog_background`
 * 用的 `@color/surface` 会随 uiMode 翻深 → 暗色下**深字压深底**。
 *
 * 这里锁死两条：① uiMode 由「应用主题设置」定死（不看 Service 自己那份配置）；
 * ② 弹窗主题的文字色与背景色同源（暗色 = 浅字 + 深底，浅色 = 深字 + 浅底）。
 */
@RunWith(RobolectricTestRunner::class)
class ThemeManagerTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    /** ⚠️ CustomPreference 是静态单例且持有 defaultSharedPreferences：Robolectric 每个用例
     *  新建 Application → 单例会缓存上一个 Application 的 prefs，先清空。 */
    @Before
    fun resetPreferenceSingleton() {
        CustomPreference::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit()
        CustomPreference.getInstance(ctx).getSharedPreferences().edit().clear().commit()
    }

    private fun setAppTheme(mode: String) {
        CustomPreference.getInstance(ctx).setString(ThemeManager.KEY, mode)
    }

    private fun nightOf(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** 弹窗主题里的正文色（Dark → 浅字、Light → 深字），即 AlertDialog 标题/正文取的颜色 */
    private fun themeTextColor(context: Context): Int {
        val tv = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)
        // textColorPrimary 可能是颜色也可能是 ColorStateList，统一按 CSL 取首个颜色
        return if (tv.resourceId != 0) {
            ContextCompat.getColorStateList(context, tv.resourceId)!!.defaultColor
        } else {
            tv.data
        }
    }

    @Test
    fun darkMode_forcesNight_lightTextOnDarkSurface() {
        setAppTheme(ThemeManager.MODE_DARK)
        val dialogCtx = ThemeManager.dialogContext(ctx)

        assertTrue("暗色设置下弹窗上下文必须是夜间模式", nightOf(dialogCtx))
        assertEquals(
            "弹窗背景必须取夜间 surface",
            0xFF121212.toInt(),
            ContextCompat.getColor(dialogCtx, R.color.surface)
        )
        assertTrue(
            "夜间弹窗文字必须是浅色（否则深字压深底）",
            ColorUtils.calculateLuminance(themeTextColor(dialogCtx)) > 0.5
        )
    }

    @Test
    fun lightMode_forcesDay_darkTextOnLightSurface() {
        setAppTheme(ThemeManager.MODE_LIGHT)
        val dialogCtx = ThemeManager.dialogContext(ctx)

        assertEquals(false, nightOf(dialogCtx))
        assertEquals(
            "弹窗背景必须取白天 surface",
            0xFFFFFFFF.toInt(),
            ContextCompat.getColor(dialogCtx, R.color.surface)
        )
        assertTrue(
            "白天弹窗文字必须是深色",
            ColorUtils.calculateLuminance(themeTextColor(dialogCtx)) < 0.5
        )
    }

    /** 应用设置浅色 + 系统暗色 → 必须听应用设置（Service 自己那份配置跟随系统，不能照抄） */
    @Test
    @Config(qualifiers = "night")
    fun lightMode_beatsSystemNight() {
        setAppTheme(ThemeManager.MODE_LIGHT)
        assertTrue("前置条件：基础上下文应是夜间", nightOf(ctx))

        val dialogCtx = ThemeManager.dialogContext(ctx)
        assertEquals(false, nightOf(dialogCtx))
        assertEquals(0xFFFFFFFF.toInt(), ContextCompat.getColor(dialogCtx, R.color.surface))
    }

    /** 跟随系统：系统暗色 → 弹窗夜间 */
    @Test
    @Config(qualifiers = "night")
    fun systemMode_followsSystemNight() {
        setAppTheme(ThemeManager.MODE_SYSTEM)
        val dialogCtx = ThemeManager.dialogContext(ctx)
        assertTrue(nightOf(dialogCtx))
        assertEquals(0xFF121212.toInt(), ContextCompat.getColor(dialogCtx, R.color.surface))
    }

    /** 跟随系统：系统浅色 → 弹窗白天 */
    @Test
    fun systemMode_followsSystemDay() {
        setAppTheme(ThemeManager.MODE_SYSTEM)
        val dialogCtx = ThemeManager.dialogContext(ctx)
        assertEquals(false, nightOf(dialogCtx))
        assertEquals(0xFFFFFFFF.toInt(), ContextCompat.getColor(dialogCtx, R.color.surface))
    }

    // ---------- isNight / dialogBackgroundRes：悬浮球等自绘组件与上下文同源 ----------

    @Test
    fun isNight_threeStates() {
        setAppTheme(ThemeManager.MODE_DARK)
        assertTrue(ThemeManager.isNight(ctx))
        setAppTheme(ThemeManager.MODE_LIGHT)
        assertEquals(false, ThemeManager.isNight(ctx))
    }

    /**
     * ⚠️ 这条是本次 bug 的根因锁：应用设浅色 + 系统暗色时，`isNight` 必须听应用设置。
     * 读 Service 自己的 `resources.configuration` 会答"暗色"，于是悬浮球按系统配色、
     * 旁边弹窗按应用主题配色，同一屏两种深浅。
     */
    @Test
    @Config(qualifiers = "night")
    fun isNight_appSettingBeatsSystem() {
        setAppTheme(ThemeManager.MODE_LIGHT)
        assertTrue("前置条件：Service 自己的 configuration 是夜间", nightOf(ctx))
        assertEquals("必须听应用主题设置，不能读 configuration", false, ThemeManager.isNight(ctx))
    }

    @Test
    @Config(qualifiers = "night")
    fun isNight_systemMode_followsSystem() {
        setAppTheme(ThemeManager.MODE_SYSTEM)
        assertTrue(ThemeManager.isNight(ctx))
    }

    /**
     * ⚠️ 弹窗背景**不能**用 `@drawable/dialog_background`：它内部的 `@color/surface` 按持有该
     * drawable 的 Context 解析，而 dialog.window 的 Context 是没主题的 Service → 无论应用主题
     * 如何都取到白色 surface，暗色下弹窗还是白底。这里同时锁住「深色时给深色 drawable」和
     * 「该 drawable 是固定深色（不引 @color/surface）」。
     */
    @Test
    fun dialogBackgroundRes_matchesTheme_andIsFixedColor() {
        setAppTheme(ThemeManager.MODE_DARK)
        assertEquals(R.drawable.dialog_background_dark, ThemeManager.dialogBackgroundRes(ctx))
        setAppTheme(ThemeManager.MODE_LIGHT)
        assertEquals(R.drawable.dialog_background_light, ThemeManager.dialogBackgroundRes(ctx))
    }
}
