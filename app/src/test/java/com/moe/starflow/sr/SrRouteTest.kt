package com.moe.starflow.sr

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工序组合的回归守卫（纯 JVM）。
 *
 * [SuperResolutionEngines.resolveSteps] 是**唯一**决定"跑哪几道工序"的地方。
 *
 * ⚠️ **2026-10 v2 口径：两者互斥**（用户：「默认用 Anime4K 无，开启超分禁用这个」）。
 * 曾经的「可叠加」是错的：`Anime4kEngine.MAX_INPUT_PIXELS = 1.5MP`，而 1MP 的页 2x 后
 * 就是 4MP → 在超分底图上跑 Anime4K **必被跳过**（抬上限则 RGBA16F 中间纹理必 OOM）。
 * ⇒ 语义是 **超分模型 > Anime4K**，Anime4K 只在"超分不可用"时兜底。
 */
class SrRouteTest {

    @Test
    fun bothEnabled_srWinsAndAnime4kIsDisabled() {
        // ⚠️ 互斥是契约：超分可用时 Anime4K 那一道**必须不跑**，否则它会因像素上限静默跳过，
        //    用户却在面板上看到"Anime4K 已开启" → 又一个"设置了没效果"。
        assertEquals(
            "超分可用时只跑超分，Anime4K 让位",
            listOf(SrStep.SR_MODEL),
            SuperResolutionEngines.resolveSteps(
                srEnabled = true, srModelUsable = true, anime4kEnabled = true
            )
        )
    }

    @Test
    fun anime4kAloneWorksWithoutSrSwitch() {
        // ⚠️ 这是「在调色面板开了 Anime4K 却毫无效果」那个 bug 的判据：
        // Anime4K 自己开就算数，**不**依赖超分开关，也不依赖有没有选模型。
        assertEquals(
            listOf(SrStep.ANIME4K),
            SuperResolutionEngines.resolveSteps(
                srEnabled = false, srModelUsable = false, anime4kEnabled = true
            )
        )
        assertEquals(
            "选了模型但超分开关没开时，Anime4K 仍要跑",
            listOf(SrStep.ANIME4K),
            SuperResolutionEngines.resolveSteps(
                srEnabled = false, srModelUsable = true, anime4kEnabled = true
            )
        )
    }

    @Test
    fun srModelAloneWhenAnime4kOff() {
        assertEquals(
            listOf(SrStep.SR_MODEL),
            SuperResolutionEngines.resolveSteps(
                srEnabled = true, srModelUsable = true, anime4kEnabled = false
            )
        )
    }

    @Test
    fun srSwitchOnButNoUsableModel_skipsSrStep() {
        // 开关开着但没选模型 / 模型文件被删 → 超分那一道跳过（不再像旧实现那样整条链路返回 null）
        assertEquals(
            listOf(SrStep.ANIME4K),
            SuperResolutionEngines.resolveSteps(
                srEnabled = true, srModelUsable = false, anime4kEnabled = true
            )
        )
        assertTrue(
            "两道都不成立时不能产出任何工序",
            SuperResolutionEngines.resolveSteps(
                srEnabled = true, srModelUsable = false, anime4kEnabled = false
            ).isEmpty()
        )
    }

    /** 全 8 种组合对表 —— 改判据就整体红 */
    @Test
    fun fullTruthTable() {
        data class Case(val sr: Boolean, val model: Boolean, val a4k: Boolean, val expect: List<SrStep>)

        val table = listOf(
            Case(true, true, true, listOf(SrStep.SR_MODEL)),
            Case(true, true, false, listOf(SrStep.SR_MODEL)),
            Case(true, false, true, listOf(SrStep.ANIME4K)),
            Case(true, false, false, emptyList()),
            Case(false, true, true, listOf(SrStep.ANIME4K)),
            Case(false, true, false, emptyList()),
            Case(false, false, true, listOf(SrStep.ANIME4K)),
            Case(false, false, false, emptyList())
        )
        assertEquals("组合漏了", 8, table.size)
        for (c in table) {
            assertEquals(
                "sr=${c.sr} model=${c.model} a4k=${c.a4k}",
                c.expect,
                SuperResolutionEngines.resolveSteps(c.sr, c.model, c.a4k)
            )
        }
    }

    /**
     * **超分只服务阅读器**（2026-10 用户口径）：只有一个开关，默认关，写它才生效。
     *
     * ⚠️ 这里同时钉死"截图链路不做超分"这个决定：曾经的三个开关
     * （总开关 / 游戏 / 漫画）已删除，`SrSettings` 里**不允许**再出现
     * `isEnabledForGame` / `isEnabledForComic` / `isEnabled` / `isUsedAnywhere` ——
     * 它们一旦回来，就说明有人把超分又接回了截屏/录屏链路。
     */
    @Test
    fun readerIsTheOnlySrConsumer() {
        val prefs = FakePrefs()
        assertEquals("默认必须关", false, SrSettings.isEnabledForReader(prefs))

        SrSettingsPut(prefs, SrSettings.KEY_READER_ENABLED, true)
        assertEquals(true, SrSettings.isEnabledForReader(prefs))

        // 老版本遗留的截图开关键必须被彻底无视（读到也不生效）
        SrSettingsPut(prefs, "sr_enabled", true)
        SrSettingsPut(prefs, "sr_enabled_game", true)
        SrSettingsPut(prefs, "sr_enabled_comic", true)
        SrSettingsPut(prefs, SrSettings.KEY_READER_ENABLED, false)
        assertEquals("遗留截图开关不得影响阅读器开关", false, SrSettings.isEnabledForReader(prefs))
    }

    // ---------- 极简 SharedPreferences 替身（只用 getBoolean/putBoolean） ----------

    private class FakePrefs : SharedPreferences {
        private val map = HashMap<String, Any?>()

        fun putBooleanRaw(key: String, value: Boolean) { map[key] = value }

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            map[key] as? Boolean ?: defValue

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
    }

    private fun SrSettingsPut(prefs: FakePrefs, key: String, value: Boolean) {
        prefs.putBooleanRaw(key, value)
    }
}
