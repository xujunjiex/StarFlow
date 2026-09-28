package com.moe.starflow.sr

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工序组合的回归守卫（纯 JVM）。
 *
 * [SuperResolutionEngines.resolveSteps] 是**唯一**决定"跑哪几道工序"的地方。
 * 2026-10 按用户口径从「二选一」改成「**可叠加**」—— 用户明确问过
 * 「为什么 Anime4K 不能和超分一起用」，所以这里既锁"各自独立"，也锁"能叠"。
 */
class SrRouteTest {

    @Test
    fun bothEnabled_bothStepsInOrderUpscaleThenSharpen() {
        // ⚠️ 顺序是契约：先放大、后描线。反过来 Anime4K 的结果会被放大糊掉，等于白跑。
        assertEquals(
            "两道工序都要跑，且先 SR 后 Anime4K",
            listOf(SrStep.SR_MODEL, SrStep.ANIME4K),
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
            Case(true, true, true, listOf(SrStep.SR_MODEL, SrStep.ANIME4K)),
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
     * 阅读器开关与截图开关**各自独立**（用户明确要求），
     * 总开关只管截图链路 —— 这条断言防止以后有人"顺手统一"成同一个开关。
     */
    @Test
    fun readerSwitchIsIndependentFromMasterSwitch() {
        val prefs = FakePrefs()
        assertEquals(false, SrSettings.isEnabled(prefs))
        assertEquals(false, SrSettings.isEnabledForReader(prefs))
        assertEquals(false, SrSettings.isEnabledForGame(prefs))
        assertEquals(false, SrSettings.isEnabledForComic(prefs))

        SrSettingsPut(prefs, SrSettings.KEY_READER_ENABLED, true)
        assertEquals("阅读器独立生效", true, SrSettings.isEnabledForReader(prefs))
        assertEquals("总开关仍关", false, SrSettings.isEnabled(prefs))
        assertEquals("游戏模式仍关", false, SrSettings.isEnabledForGame(prefs))
        assertEquals("漫画模式仍关", false, SrSettings.isEnabledForComic(prefs))
        assertEquals("没有任何截图链路在用", true, SrSettings.isUsedAnywhere(prefs))

        SrSettingsPut(prefs, SrSettings.KEY_ENABLED, true)
        SrSettingsPut(prefs, SrSettings.KEY_ENABLED_COMIC, false)
        assertEquals(true, SrSettings.isEnabledForGame(prefs))
        assertEquals(false, SrSettings.isEnabledForComic(prefs))
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
