package com.moe.starflow.sr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「阅读器这一页用哪种底图」+「渲染缓存 key 的底图签名」的回归守卫（纯 JVM）。
 *
 * 这两件事散在渲染路径里会出**静默**问题：关了超分画面还是超分图、换了模型显示的还是旧模型、
 * 超分做完了译文还贴在糊底图上 —— 都不崩不报错。所以抽成纯函数 + 真值表钉死。
 */
class SrDisplayBaseTest {

    // ---------- resolveBaseKind ----------

    @Test
    fun srModelUsable_beatsAnime4k() {
        // 与 resolveSteps 同源：超分可用时 Anime4K 让位（互斥）
        assertEquals(
            SrBaseKind.SR_MODEL,
            SrDisplayBase.resolveBaseKind(
                srVisualOn = true, srEnabled = true, srModelUsable = true, anime4kEnabled = true
            )
        )
    }

    @Test
    fun anime4kOnlyWhenSrUnusable() {
        // 超分开关关着 → Anime4K 顶上（这正是"零下载兜底"的定位）
        assertEquals(
            SrBaseKind.ANIME4K,
            SrDisplayBase.resolveBaseKind(
                srVisualOn = true, srEnabled = false, srModelUsable = false, anime4kEnabled = true
            )
        )
        // 开关开着但没模型 → 同上
        assertEquals(
            SrBaseKind.ANIME4K,
            SrDisplayBase.resolveBaseKind(
                srVisualOn = true, srEnabled = true, srModelUsable = false, anime4kEnabled = true
            )
        )
    }

    @Test
    fun userToggledToOriginal_disablesEverythingIncludingAnime4k() {
        // ⚠️ 用户点「切回原图」的意图就是**看原图** —— 再叠一层 Anime4K 锐化就不是原图了。
        for (a4k in listOf(true, false)) {
            assertEquals(
                "a4k=$a4k 时切回原图都不该有增强底图",
                SrBaseKind.ORIGINAL,
                SrDisplayBase.resolveBaseKind(
                    srVisualOn = false, srEnabled = true, srModelUsable = true, anime4kEnabled = a4k
                )
            )
        }
    }

    @Test
    fun everythingOff_isOriginal() {
        assertEquals(
            SrBaseKind.ORIGINAL,
            SrDisplayBase.resolveBaseKind(
                srVisualOn = true, srEnabled = false, srModelUsable = false, anime4kEnabled = false
            )
        )
    }

    /** 全 16 种组合对表 —— 改判据就整体红。 */
    @Test
    fun fullTruthTable() {
        data class Case(
            val visual: Boolean, val sr: Boolean, val model: Boolean, val a4k: Boolean,
            val expect: SrBaseKind,
        )

        val table = mutableListOf<Case>()
        for (visual in listOf(true, false)) {
            for (sr in listOf(true, false)) {
                for (model in listOf(true, false)) {
                    for (a4k in listOf(true, false)) {
                        val expect = when {
                            !visual -> SrBaseKind.ORIGINAL
                            sr && model -> SrBaseKind.SR_MODEL
                            a4k -> SrBaseKind.ANIME4K
                            else -> SrBaseKind.ORIGINAL
                        }
                        table += Case(visual, sr, model, a4k, expect)
                    }
                }
            }
        }
        assertEquals("组合漏了", 16, table.size)
        for (c in table) {
            assertEquals(
                "visual=${c.visual} sr=${c.sr} model=${c.model} a4k=${c.a4k}",
                c.expect,
                SrDisplayBase.resolveBaseKind(c.visual, c.sr, c.model, c.a4k),
            )
        }
    }

    // ---------- baseSignature ----------

    @Test
    fun originalSignatureIsStable() {
        assertEquals(
            "o",
            SrDisplayBase.baseSignature(SrBaseKind.ORIGINAL, "M1", "A", storedSrFile = true)
        )
    }

    @Test
    fun srModelSignatureCarriesModelName() {
        // 换模型 → 签名变 → 渲染缓存自动失配（不换的话屏幕上会留着旧模型的图）
        val a = SrDisplayBase.baseSignature(SrBaseKind.SR_MODEL, "M1", null, storedSrFile = true)
        val b = SrDisplayBase.baseSignature(SrBaseKind.SR_MODEL, "M2", null, storedSrFile = true)
        assertEquals("s:M1", a)
        assertEquals("s:M2", b)
        assertNotEquals("换模型必须让签名不同", a, b)
    }

    @Test
    fun srModelWithoutFile_fallsBackToOriginalSignature() {
        // ⚠️ 关键：该页**还没超分过**时签名必须是 "o"。
        //    否则自动超分完成后签名不变 → 旧渲染（糊底图 + 译文）不会被作废，用户看不到超分效果。
        assertEquals(
            "o",
            SrDisplayBase.baseSignature(SrBaseKind.SR_MODEL, "M1", null, storedSrFile = false)
        )
        assertEquals(
            "模型名为空也只能算原图",
            "o",
            SrDisplayBase.baseSignature(SrBaseKind.SR_MODEL, null, null, storedSrFile = true)
        )
    }

    @Test
    fun anime4kSignatureCarriesMode() {
        // 换档位 → 签名变 → 重渲染（与调色面板「切档没效果」那个 bug 同一条防线）
        assertEquals("a:ANIME4K_CA", SrDisplayBase.baseSignature(SrBaseKind.ANIME4K, null, "ANIME4K_CA", false))
        assertNotEquals(
            SrDisplayBase.baseSignature(SrBaseKind.ANIME4K, null, "ANIME4K_CA", false),
            SrDisplayBase.baseSignature(SrBaseKind.ANIME4K, null, "ANIME4K_B", false),
        )
        assertEquals("没有档位 id 时退化成原图", "o", SrDisplayBase.baseSignature(SrBaseKind.ANIME4K, null, null, false))
    }

    @Test
    fun signaturesOfDifferentKindsNeverCollide() {
        val sigs = listOf(
            SrDisplayBase.baseSignature(SrBaseKind.ORIGINAL, null, null, false),
            SrDisplayBase.baseSignature(SrBaseKind.SR_MODEL, "ANIME4K_CA", null, true),
            SrDisplayBase.baseSignature(SrBaseKind.ANIME4K, null, "ANIME4K_CA", false),
        )
        assertEquals("三种底图的签名必须互不相同（否则渲染缓存会串）", 3, sigs.toSet().size)
        assertTrue("前缀契约：s: / a: / o", sigs[1].startsWith("s:") && sigs[2].startsWith("a:"))
    }

    // ---------- Q2：翻译之后再超分，坐标必须跟着走 ----------

    /**
     * **「已经翻译过再超分」的坐标安全，是靠签名换 key 达成的**（用户口径 Q2）。
     *
     * 为什么不需要"更新已存的坐标"：`bubbleRects` **永远存在源图空间**（v2 里 OCR 恒吃原图），
     * 渲染时由 `OverlayRenderer` 用 `baseScale = 底图宽 / 坐标空间宽` 把它映射到底图上。
     * 所以底图从「原图」换成「2x 超分图」时，**坐标本身不用动**；
     * 真正必须发生的是**已渲染的那张译图作废**（否则屏幕上留着一张"原图底图 + 旧译文"的图）。
     *
     * 作废靠的就是下面这条：签名从 `o` 变成 `s:<模型>` → `renderKey` 变 → 旧缓存取不到 →
     * 必然重渲染。只要这条不成立（比如签名不含模型、或超分文件出现后签名不变），
     * 就会出现"超分完了译文还贴在糊底图上"或者"位置对不上"。
     */
    @Test
    fun q2_srFileAppearingChangesTheSignatureSoStaleOverlaysAreNeverReused() {
        val before = SrDisplayBase.baseSignature(
            SrBaseKind.SR_MODEL, "M1", null, storedSrFile = false,
        )
        val after = SrDisplayBase.baseSignature(
            SrBaseKind.SR_MODEL, "M1", null, storedSrFile = true,
        )
        assertEquals("还没超分时 = 原图签名", "o", before)
        assertEquals("超分文件出现后 = 超分签名", "s:M1", after)
        assertNotEquals(
            "签名必须变 —— 它是 renderKey 的一部分，不变就会复用「原图底图 + 旧译文」的缓存",
            before, after,
        )
    }

    /** 再来一次超分（换模型覆盖）也必须让签名变 —— 否则屏幕上还是上一个模型的图。 */
    @Test
    fun q3_overwritingWithAnotherModelAlsoChangesSignature() {
        val a = SrDisplayBase.baseSignature(SrBaseKind.SR_MODEL, "M1", null, storedSrFile = true)
        val b = SrDisplayBase.baseSignature(SrBaseKind.SR_MODEL, "M2", null, storedSrFile = true)
        assertNotEquals("换模型必须重渲染", a, b)
    }

    // ---------- SrSettings.KEY_AUTO（「翻译时自动超分」子开关） ----------

    @Test
    fun autoSwitch_defaultsOnButIsGatedByMaster() {
        val prefs = Prefs()
        // 默认值本身是开，但总开关默认关 → 整体仍是关
        assertTrue("默认值是开", SrSettings.DEFAULT_AUTO)
        assertEquals("总开关关着时，自动超分必须无效", false, SrSettings.isAutoEnabledForReader(prefs))

        prefs.map[SrSettings.KEY_READER_ENABLED] = true
        assertEquals("总开关开了、子开关默认开 → 生效", true, SrSettings.isAutoEnabledForReader(prefs))

        prefs.map[SrSettings.KEY_AUTO] = false
        assertEquals("显式关掉子开关 → 不自动超分", false, SrSettings.isAutoEnabledForReader(prefs))

        prefs.map[SrSettings.KEY_READER_ENABLED] = false
        prefs.map[SrSettings.KEY_AUTO] = true
        assertEquals("总开关一关，子开关再开也不生效", false, SrSettings.isAutoEnabledForReader(prefs))
    }

    private class Prefs : android.content.SharedPreferences {
        val map = HashMap<String, Any?>()

        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(
            listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
    }
}
