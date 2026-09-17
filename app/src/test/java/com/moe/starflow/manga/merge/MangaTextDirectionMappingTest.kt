package com.moe.starflow.manga.merge

import com.moe.starflow.manga.types.TextDirection
import com.moe.starflow.manga.types.VerticalFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `Manga_Text_Direction` → 排序方向的**持久化映射**不变量（纯 JVM，无 Robolectric）。
 *
 * 这条映射是三层配置链的第一环：prefs 值 → [VerticalFlow] → [TextDirection] → 排序实现。
 * 它决定了「用户选的到底是不是生效的那一个」，且 `values/arrays.xml` 里
 * 只允许出现 `0` / `1` 两个值 —— 两者必须对得上。
 */
class MangaTextDirectionMappingTest {

    @Test
    fun zeroMapsToRl() {
        assertEquals(VerticalFlow.RL, VerticalFlow.fromPref("0"))
        assertEquals(TextDirection.VERTICAL_RL, VerticalFlow.fromPref("0").toTextDirection())
    }

    @Test
    fun oneMapsToLr() {
        assertEquals(VerticalFlow.LR, VerticalFlow.fromPref("1"))
        assertEquals(TextDirection.VERTICAL_LR, VerticalFlow.fromPref("1").toTextDirection())
    }

    /** 缺失 / 脏值一律落 RL —— 与 `ListPreference` 的 `defaultValue="0"` 保持一致。 */
    @Test
    fun missingOrUnknownFallsBackToRl() {
        for (v in listOf(null, "", "2", "-1", "true", "RL")) {
            assertEquals("pref=$v 应落 RL", VerticalFlow.RL, VerticalFlow.fromPref(v))
        }
    }

    /**
     * ⚠️ 回环一致性：`VerticalFlow → TextDirection → VerticalFlow` 必须恒等。
     * `MangaModeConfig.verticalFlow` 就是从 `TextDirection` 反推的（见
     * `MangaModeConfig.kt` 的 `TextDirection.VERTICAL_LR -> "1"`），
     * 这条映射若不对称，配置项会在写回时被静默改掉。
     */
    @Test
    fun flowRoundTripsThroughTextDirection() {
        for (flow in VerticalFlow.entries) {
            val dir = flow.toTextDirection()
            val back = VerticalFlow.fromPref(if (dir == TextDirection.VERTICAL_LR) "1" else "0")
            assertEquals("$flow 经 TextDirection 回环后必须不变", flow, back)
        }
    }

    /** 竖排流向不得映射出横排方向（否则排序会走成横排分支）。 */
    @Test
    fun verticalFlowNeverProducesHorizontal() {
        for (flow in VerticalFlow.entries) {
            assert(flow.toTextDirection() != TextDirection.HORIZONTAL) {
                "$flow 不应映射到 HORIZONTAL"
            }
        }
    }
}
