package com.moe.starflow.translate

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `onConfigurationChanged` 里「朝向翻转 → 清框回退」的**顺序不变量**。
 *
 * 这里曾出现一个把自己逻辑关掉的写法（实测两模式的回退+提示**完全失效**）：
 *
 * ```kotlin
 * DisplaySize.reportOrientation(nowLandscape)      // 先写入新朝向
 * val known = DisplaySize.currentOrientation()     // 再读回来 —— 必然等于刚写进去的值
 * if (known != nowLandscape) clearCropForScreenChange()   // 恒为假，永不执行
 * ```
 *
 * 先写后读同一个变量，比较就变成「新值 vs 新值」。**必须先读旧值、再上报新值。**
 * 本测试锁住这个顺序 —— 它是纯文本顺序问题，读代码很难一眼看出，
 * 而后果是整个方向变化处理链路静默失效。
 */
class OrientationHandlerOrderingTest {

    private val game = File("src/main/java/com/moe/starflow/translate/FloatingBallService.kt")
    private val manga = File("src/main/java/com/moe/starflow/manga/MangaFloatingService.kt")

    private fun configHandlerBody(f: File): String =
        f.readText().substringAfter("override fun onConfigurationChanged")
            .substringBefore("\n    private fun ")

    /** ⚠️ 核心：读取已知朝向必须出现在上报新朝向**之前**。 */
    @Test
    fun knownOrientationIsReadBeforeReportingNewOne() {
        for (f in listOf(game, manga)) {
            val body = configHandlerBody(f)
            val readIdx = body.indexOf("currentOrientation()")
            val writeIdx = body.indexOf("reportOrientation(")
            assertTrue("${f.name}: 回调体里应同时有读取与上报", readIdx >= 0 && writeIdx >= 0)
            assertTrue(
                "${f.name}: 必须先读 currentOrientation() 再 reportOrientation() —— " +
                    "反了的话比较恒为假，清框回退链路整个失效（实测踩过）",
                readIdx < writeIdx
            )
        }
    }

    /** ⚠️ 不得用「服务启动后从 null 开始的局部变量」当比较基准（会放过第一次转屏）。 */
    @Test
    fun doesNotUseLocalNullStartBaseline() {
        for (f in listOf(game, manga)) {
            val body = configHandlerBody(f)
            assertTrue(
                "${f.name}: 不得用局部 null 起始变量判断翻转 —— 服务启动后第一次转屏会被放过",
                !body.contains("val prev = lastConfigLandscape")
            )
        }
    }

    /**
     * ⚠️ `knownBefore == null`（还没有任何几何信息）时应**照清不误**。
     * 宁可多清一次让用户重框，也不要漏掉转屏导致按旧坐标出结果。
     */
    @Test
    fun nullKnownOrientationStillClears() {
        for (f in listOf(game, manga)) {
            val body = configHandlerBody(f)
            assertTrue(
                "${f.name}: knownBefore 为 null 时也必须清框（不得写成 `known != null &&` 的短路放过）",
                body.contains("knownBefore == null ||")
            )
        }
    }

    /** 两个模式都必须调 `clearCropForScreenChange()`（回退+提示的入口）。 */
    @Test
    fun bothModesCallClearCrop() {
        for (f in listOf(game, manga)) {
            assertTrue(
                "${f.name}: 朝向翻转时必须调 clearCropForScreenChange()",
                configHandlerBody(f).contains("clearCropForScreenChange()")
            )
        }
    }
}
