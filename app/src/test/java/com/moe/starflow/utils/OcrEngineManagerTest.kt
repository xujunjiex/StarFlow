package com.moe.starflow.utils

import com.moe.starflow.manga.config.*
import androidx.preference.PreferenceManager
import com.moe.starflow.manga.config.OcrEngineGroup
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OcrEngineManagerTest {
    private fun prefs() = PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())

    @Test fun default_noLegacyPrefs_returnsV6() {
        val p = prefs()
        p.edit().clear().commit()
        assertEquals(OcrEngineGroup.PP_OCR_V6, OcrEngineManager.getOcrEngineGroup(p))
        // 迁移已写共享值
        assertEquals("ppocrv6", p.getString(OcrEngineManager.PREF_KEY, ""))
    }
    @Test fun legacy_mangaV6_migratesToV6() {
        val p = prefs()
        p.edit().clear().commit()
        // 漫画 v6：DetEngine.PP_OCR_V6=5, OcrEngine.PPOcrV6=5
        p.edit().putInt("Manga_Det_Model", 5).putInt("Manga_Rec_Model", 5).commit()
        assertEquals(OcrEngineGroup.PP_OCR_V6, OcrEngineManager.getOcrEngineGroup(p))
    }
    @Test fun setAndRead() {
        val p = prefs()
        p.edit().clear().commit()
        OcrEngineManager.setOcrEngineGroup(p, OcrEngineGroup.RT_MANGA)
        assertEquals(OcrEngineGroup.RT_MANGA, OcrEngineManager.getOcrEngineGroup(p))
    }

    // ========== cycleFloatingSourceLang（悬浮窗常用语言循环） ==========

    private fun setSource(p: android.content.SharedPreferences, code: String) {
        p.edit().putString("Source_Language", code).commit()
    }

    /**
     * V6 组：ko 不受支持（v6_noKoRu），循环 = [zh-TW, ja, en]。
     * 当前 ja → 下一个 en。
     */
    @Test fun cycleFloating_v6_ja_cyclesToEn() {
        val p = prefs()
        p.edit().clear().commit()
        OcrEngineManager.setOcrEngineGroup(p, OcrEngineGroup.PP_OCR_V6)
        setSource(p, "ja")
        assertEquals("en", OcrEngineManager.cycleFloatingSourceLang(p))
        assertEquals("en", p.getString("Source_Language", "ja"))
    }

    /** V6 组：当前 en → 回绕到 zh-TW（循环 [zh-TW, ja, en]，en 的下一项是 zh-TW） */
    @Test fun cycleFloating_v6_en_wrapsToZhTW() {
        val p = prefs()
        p.edit().clear().commit()
        OcrEngineManager.setOcrEngineGroup(p, OcrEngineGroup.PP_OCR_V6)
        setSource(p, "en")
        assertEquals("zh-TW", OcrEngineManager.cycleFloatingSourceLang(p))
    }

    /** V5 组：4 个常用语言全支持，ja→en→ko→zh-TW→ja 一整圈 */
    @Test fun cycleFloating_v5_fullCycleRoundTrip() {
        val p = prefs()
        p.edit().clear().commit()
        OcrEngineManager.setOcrEngineGroup(p, OcrEngineGroup.PP_OCR_V5)
        setSource(p, "ja")
        assertEquals("en", OcrEngineManager.cycleFloatingSourceLang(p))
        assertEquals("ko", OcrEngineManager.cycleFloatingSourceLang(p))
        assertEquals("zh-TW", OcrEngineManager.cycleFloatingSourceLang(p))
        assertEquals("ja", OcrEngineManager.cycleFloatingSourceLang(p))
    }

    /** 当前语言不在常用列表（主页选的 fr）→ 落到常用列表内（idx coerce 到 0 → cycle[1] = ja） */
    @Test fun cycleFloating_currentNotInCommon_fallsIntoCycle() {
        val p = prefs()
        p.edit().clear().commit()
        OcrEngineManager.setOcrEngineGroup(p, OcrEngineGroup.PP_OCR_V6)
        setSource(p, "fr")
        assertEquals("ja", OcrEngineManager.cycleFloatingSourceLang(p))
    }

    /** RT_MANGA 组仅支持 ja：无可用常用语言 → null，pref 不变 */
    @Test fun cycleFloating_mangaOnly_returnsNullAndKeepsPref() {
        val p = prefs()
        p.edit().clear().commit()
        OcrEngineManager.setOcrEngineGroup(p, OcrEngineGroup.RT_MANGA)
        setSource(p, "ja")
        assertEquals(null, OcrEngineManager.cycleFloatingSourceLang(p))
        assertEquals("ja", p.getString("Source_Language", "ja"))
    }
}
