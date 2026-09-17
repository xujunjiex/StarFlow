package com.moe.starflow.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `DisplaySize.resolve` 的取值规则（**纯 JVM**）。
 *
 * ⚠️ 这里调的是**生产实现**（`resolve` 是 `DisplaySize` 里的纯函数），不是复刻 ——
 * 复刻的测试改坏实现也不会失败，等于没测（本轮踩过：先写了复刻版，故意改坏实现仍 BUILD SUCCESSFUL）。
 * `resolve` 之所以被抽出来，就是为了让这条规则可测：`size()` 需要 Context/WindowManager，
 * 本机 Robolectric 取屏幕尺寸不可靠。
 *
 * 锁的是历史上出过两次回归的规则：
 * ① 逐轴取 max 在方向不一致时拼出畸形尺寸；
 * ② 朝向闩锁把已是新方向的窗口读数对调 → 框选后必被判「屏幕变化」而清掉。
 */
class DisplaySizeResolveTest {

    private fun resolve(laid: Pair<Int, Int>, legacy: Pair<Int, Int>, landscape: Boolean?): Pair<Int, Int> {
        val r = DisplaySize.resolve(laid.first, laid.second, legacy.first, legacy.second, landscape)
        return r[0] to r[1]
    }

    // ---------- ① 窗口方向优先于朝向闩锁（本轮修复的核心） ----------

    /**
     * ⚠️ 实测 bug：进程内横屏过一次后闩锁永久为 true，竖屏下把窗口读数
     * 1080x2400 对调成 2400x1080 → 几何比对每次判「变了」→ **框选后必被清掉**，
     * 表现为「框选了就提示屏幕方向发生变化」，时有时无取决于本进程转过屏没有。
     */
    @Test
    fun portraitWindowIsNotSwappedByStaleLandscapeLatch() {
        val got = resolve(laid = 1080 to 2400, legacy = 1080 to 2400, landscape = true)
        assertEquals("竖屏窗口不得被横屏闩锁对调", 1080 to 2400, got)
    }

    @Test
    fun landscapeWindowIsNotSwappedByStalePortraitLatch() {
        val got = resolve(laid = 2400 to 1080, legacy = 2400 to 1080, landscape = false)
        assertEquals("横屏窗口不得被竖屏闩锁对调", 2400 to 1080, got)
    }

    // ---------- ② 窗口比屏幕小：逐轴取大补回 ----------

    /**
     * ⚠️ 实测 bug：`MATCH_PARENT` 窗口避开底部手势条只有 1220x2660，真实屏幕 1220x2712。
     * 直接用窗口值会让 VD 按 2660 建 → 帧「整屏缩放进 2660」→ 全屏译文纵向压缩并偏上。
     */
    @Test
    fun windowSmallerThanScreen_takesLargerPerAxis() {
        assertEquals(
            "窗口 2660 < 屏幕 2712 时应取屏幕值",
            1220 to 2712,
            resolve(laid = 1220 to 2660, legacy = 1220 to 2712, landscape = false)
        )
    }

    /** Display 读数是冻结的旧方向时，先按窗口方向配对再取大 —— 不得拼出畸形尺寸。 */
    @Test
    fun frozenLegacyIsAlignedBeforeMax() {
        // 窗口横屏 2712x1220；Display 冻结返回竖屏 1220x2712
        assertEquals(
            "冻结的竖屏读数不应参与横屏的逐轴 max",
            2712 to 1220,
            resolve(laid = 2712 to 1220, legacy = 1220 to 2712, landscape = true)
        )
    }

    // ---------- ③ 无窗口读数：退回 Display + 朝向配对 ----------

    @Test
    fun noWindowReading_usesLegacyAlignedToOrientation() {
        assertEquals(
            "已知横屏时，竖屏的 Display 读数应配对成横屏",
            2712 to 1220,
            resolve(laid = 0 to 0, legacy = 1220 to 2712, landscape = true)
        )
    }

    @Test
    fun noWindowReading_orientationUnknown_returnsRaw() {
        assertEquals(
            "朝向未知时不做任何猜测",
            1220 to 2712,
            resolve(laid = 0 to 0, legacy = 1220 to 2712, landscape = null)
        )
    }

    /** 已知朝向与 Display 读数一致时原样返回（不应凭空对调）。 */
    @Test
    fun noWindowReading_orientationMatches_keepsLegacy() {
        assertEquals(
            1220 to 2712,
            resolve(laid = 0 to 0, legacy = 1220 to 2712, landscape = false)
        )
    }

    /**
     * ⚠️ 实测 bug：`alignTo` 曾无论要哪个朝向都返回 `(max, min)`，于是「要竖屏」时配错，
     * 随后逐轴 max 把两个方向的分量撞成**正方形**：
     * 窗口 1080x2400、Display 读数 2400x1080 → **2400x2400**。
     * 拿它建 VirtualDisplay，帧就是废的（宽高比全错）。
     */
    @Test
    fun neverProducesSquareFromCrossOrientedSources() {
        val got = resolve(laid = 1080 to 2400, legacy = 2400 to 1080, landscape = false)
        assertTrue("不得拼出正方形尺寸：$got", got.first != got.second)
        assertEquals("应保持窗口的竖屏形状", 1080 to 2400, got)
    }

    @Test
    fun neverProducesSquare_landscapeWindow() {
        val got = resolve(laid = 2400 to 1080, legacy = 1080 to 2400, landscape = true)
        assertTrue("不得拼出正方形尺寸：$got", got.first != got.second)
        assertEquals("应保持窗口的横屏形状", 2400 to 1080, got)
    }

    // ---------- 退化输入 ----------

    @Test
    fun degenerateInputs_doNotProduceNegativeOrSwapped() {
        // 负/零尺寸不应被当成有效窗口读数
        assertEquals(100 to 200, resolve(laid = -1 to -1, legacy = 100 to 200, landscape = false))
        assertEquals(100 to 200, resolve(laid = 0 to 0, legacy = 100 to 200, landscape = false))
    }
}
