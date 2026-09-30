package com.moe.starflow.sr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SrProcessor` 的 **OcrLock 用法守卫**（源码级）。
 *
 * 背景（2026-10 阶段性审查 P1）：超分整段在 `OcrLock` 里跑推理，而它
 * ① **全程不打心跳**、② 收尾用的是**无令牌**的 `OcrLock.release()`。两条叠加：
 *
 * ```
 * 超分推理 >30s（ncnn/Vulkan 重档位；CPU 回退、Real-ESRGAN 4x 更够）
 *   → OcrLock.maybeRecoverStale() 判定"持有者已死" → 强制放锁
 *   → OCR 立刻拿到锁，**与超分同时用同一个单例 ONNX 引擎**（正是这把锁唯一的目的）
 *   → 超分收尾的无令牌 release 又把**新持有者**的锁清掉 → 第三个任务再进来
 * ```
 *
 * 修法：`acquire()` 拿令牌 + 锁内 5s 心跳 + `release(token)` 配对。
 *
 * ⚠️ **为什么用源码断言**：`SrProcessor` 是 object，跑通要 Context + 真模型 + 真推理，
 * 纯 JVM/Robolectric 覆盖不到"持锁超过 30 秒"那条路径（那一段的**时钟语义**由
 * `OcrLockTest` 用可注入的 `clockNs` 快进覆盖，两个用例一个证"有心跳活得住"、
 * 一个证"没心跳会被抢走"）。这类**接线错了就静默失去互斥**的问题只能盯源码钉死
 * —— 与 `ChapterTranslationCancelGuardTest` / `ReaderNoticeOutletTest` 同一手法。
 */
class SrProcessorLockGuardTest {

    private fun src(): String {
        val f = java.io.File("src/main/java/com/moe/starflow/sr/SrProcessor.kt")
        assertTrue("找不到 SrProcessor 源码：${f.absolutePath}", f.exists())
        return f.readText()
    }

    /** 拿锁/放锁都必须走**带令牌**的那一对。 */
    @Test
    fun usesTokenBasedAcquireAndRelease() {
        val s = src()
        assertTrue("必须用带令牌的 acquire()", s.contains("OcrLock.acquire()"))
        assertFalse(
            "不许再用 tryAcquire() —— 它把令牌丢掉，收尾就没法只放自己的锁",
            s.contains("OcrLock.tryAcquire("),
        )
        assertTrue(
            "必须用 release(lockToken) 配对（无令牌版会把新持有者的锁一起清掉）",
            Regex("OcrLock\\.release\\(\\s*lockToken\\s*\\)").containsMatchIn(s),
        )
    }

    /** 持锁期间必须持续打心跳，且心跳任务必须被收掉。 */
    @Test
    fun heartbeatsWhileHoldingTheLock_andCancelsTheTicker() {
        val s = src()
        assertTrue(
            "锁内必须 heartbeat(lockToken)，否则 30s 自愈会把活着的超分判死",
            s.contains("OcrLock.heartbeat(lockToken)"),
        )
        assertTrue(
            "心跳任务必须 cancel —— 漏掉的话 5s ticker 会活在应用级 scope 上永远打，30s 自愈再也不会触发",
            s.contains("heartbeatJob.cancel()"),
        )
        // 心跳间隔必须明显小于自愈阈值，否则等于没打
        val hb = Regex("LOCK_HEARTBEAT_MS\\s*=\\s*([0-9_]+)L").find(s)?.groupValues?.get(1)
            ?.replace("_", "")?.toLongOrNull()
        assertTrue("找不到 LOCK_HEARTBEAT_MS 常量", hb != null)
        assertTrue(
            "心跳间隔($hb ms)必须远小于 OcrLock.STALE_TIMEOUT_MS(${com.moe.starflow.manga.OcrLock.STALE_TIMEOUT_MS} ms)",
            hb!! * 4 < com.moe.starflow.manga.OcrLock.STALE_TIMEOUT_MS,
        )
    }
}
