package com.moe.starflow.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `DisplaySize.resolve` 的取值不变量（**纯 JVM**，调的是生产实现而非复刻）。
 *
 * ## 为什么把「时刻」作为入参
 *
 * 这一层反复出过同一类 bug 的两种面貌，根因不是「优先级选错了」，而是
 * **依赖了一个没有时间信息的全局缓存**：同一份状态有两个写入方（配置回调 / 窗口布局），
 * 谁对取决于**谁更晚写**。早期实现让其中之一无条件压过另一个，于是：
 * - 窗口优先 ⇒ 横屏框选后转回竖屏，陈旧窗口把帧建回横屏 → **译文位置全错**
 * - 朝向优先 ⇒ 回调漏掉时，陈旧朝向把新鲜窗口读数对调 → **框选后必被清**
 *
 * 记时刻后判断从「策略」变成「事实」。本文件按「谁更新」覆盖全部真实场景。
 */
class DisplaySizeResolveTest {

    private fun r(
        laid: Pair<Int, Int>, laidAt: Long,
        legacy: Pair<Int, Int>,
        orientation: Boolean?, orientationAt: Long
    ): Pair<Int, Int> {
        val out = DisplaySize.resolve(
            laid.first, laid.second, laidAt,
            legacy.first, legacy.second,
            orientation, orientationAt
        )
        return out[0] to out[1]
    }

    // ---------- ① 窗口比朝向旧：必须用朝向（实测 bug） ----------

    /**
     * ⚠️ 实测：横屏下框选 → 转回竖屏 → 帧仍建成横屏 → 全屏译文铺在竖屏上 → 位置完全错。
     *
     * `CropView` 在框选确认后被移除、不再上报，窗口读数冻结在横屏那一刻；
     * 而配置回调给出了新鲜的「竖屏」。此时**必须**采信朝向。
     */
    @Test
    fun staleWindowOlderThanOrientation_usesOrientation() {
        val got = r(
            laid = 2400 to 1036, laidAt = 100,
            legacy = 2400 to 1080,
            orientation = false, orientationAt = 200      // 朝向更新 → 竖屏
        )
        assertTrue("帧不得仍是横屏形状：$got", got.first < got.second)
        assertEquals("应回到竖屏 1080x2400", 1080 to 2400, got)
    }

    @Test
    fun stalePortraitWindowOlderThanOrientation_usesLandscape() {
        val got = r(
            laid = 1080 to 2356, laidAt = 100,
            legacy = 1080 to 2400,
            orientation = true, orientationAt = 200
        )
        assertTrue("帧不得仍是竖屏形状：$got", got.first > got.second)
        assertEquals(2400 to 1080, got)
    }

    // ---------- ② 窗口比朝向新：必须用窗口（回调漏掉时） ----------

    /**
     * ⚠️ 配置回调**并非总会到来**（多窗口/freeform 下可能被抑制）。
     * 窗口布局是 WMS 真值，比朝向更新时它就是权威 —— 不得因「朝向不一致」而弃用。
     * （早期「朝向优先」规则会在这里把新鲜窗口读数对调 → 框选后必被清。）
     */
    @Test
    fun windowNewerThanOrientation_windowWins() {
        val got = r(
            laid = 2400 to 1080, laidAt = 200,            // 窗口已重排为横屏
            legacy = 1080 to 2400,                        // Display 冻结在竖屏
            orientation = false, orientationAt = 100      // 陈旧朝向：竖屏
        )
        assertEquals("窗口更新时它就是权威，不得被陈旧朝向对调", 2400 to 1080, got)
    }

    // ---------- ③ 窗口比屏幕小：逐轴取大 ----------

    /**
     * `MATCH_PARENT` 窗口避开手势条只有 1080x2356，真实屏幕 1080x2400。
     * 直接用窗口值会让 VD 按 2356 建 → 帧「整屏缩放进 2356」→ 全屏译文纵向压缩并偏上。
     */
    @Test
    fun windowSmallerThanScreen_takesLargerPerAxis() {
        val got = r(
            laid = 1080 to 2356, laidAt = 200,
            legacy = 1080 to 2400,
            orientation = false, orientationAt = 100
        )
        assertEquals("窗口 2356 < 屏幕 2400 时应取屏幕值", 1080 to 2400, got)
    }

    /** Display 读数是旧方向时，先按窗口方向配对再取大 —— 不得拼出畸形尺寸。 */
    @Test
    fun frozenLegacyIsAlignedBeforeMax() {
        val got = r(
            laid = 2400 to 1080, laidAt = 200,
            legacy = 1080 to 2400,                        // 冻结的竖屏读数
            orientation = true, orientationAt = 100
        )
        assertEquals("冻结的竖屏读数不应参与横屏的逐轴 max", 2400 to 1080, got)
    }

    // ---------- ④ 无窗口读数：退回 Display + 朝向配对 ----------

    @Test
    fun noWindowReading_usesLegacyAlignedToOrientation() {
        assertEquals(
            2400 to 1080,
            r(laid = 0 to 0, laidAt = 0, legacy = 1080 to 2400, orientation = true, orientationAt = 200)
        )
    }

    @Test
    fun noWindowReading_orientationUnknown_returnsRaw() {
        assertEquals(
            1080 to 2400,
            r(laid = 0 to 0, laidAt = 0, legacy = 1080 to 2400, orientation = null, orientationAt = 0)
        )
    }

    @Test
    fun noWindowReading_orientationMatches_keepsLegacy() {
        assertEquals(
            1080 to 2400,
            r(laid = 0 to 0, laidAt = 0, legacy = 1080 to 2400, orientation = false, orientationAt = 200)
        )
    }

    // ---------- ⑤ 不得拼出畸形尺寸 ----------

    /**
     * ⚠️ 实测：`alignTo` 曾无论要哪个朝向都返回 `(max, min)`，「要竖屏」时配错，
     * 随后逐轴 max 把两个分量撞成**正方形**（1080x2400 vs 2400x1080 → 2400x2400）。
     */
    @Test
    fun neverProducesSquareFromCrossOrientedSources() {
        val got = r(
            laid = 1080 to 2400, laidAt = 100,
            legacy = 2400 to 1080,
            orientation = true, orientationAt = 200      // 朝向更新 → 横屏
        )
        assertTrue("不得拼出正方形尺寸：$got", got.first != got.second)
    }

    @Test
    fun neverProducesSquare_landscapeWindow() {
        val got = r(
            laid = 2400 to 1080, laidAt = 200,
            legacy = 1080 to 2400,
            orientation = true, orientationAt = 100
        )
        assertTrue("不得拼出正方形尺寸：$got", got.first != got.second)
        assertEquals(2400 to 1080, got)
    }

    // ---------- 退化输入 ----------

    @Test
    fun degenerateInputs_doNotProduceNegative() {
        assertEquals(
            100 to 200,
            r(laid = -1 to -1, laidAt = 200, legacy = 100 to 200, orientation = false, orientationAt = 100)
        )
        assertEquals(
            100 to 200,
            r(laid = 0 to 0, laidAt = 0, legacy = 100 to 200, orientation = false, orientationAt = 0)
        )
    }
}
