package com.moe.starflow.manga

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `OcrLock` 的**持有者令牌**语义（2026-09 `/review` 并发审查：自愈竞态会把新持有者的锁放掉）。
 *
 * 场景：持有者 A 干得太久（>30s 没心跳）→ 自愈强制释放 → B 拿到锁 → A 走到 `finally` 调
 * 旧的 `release()` → 把 **B 的锁**放掉 → C 又能进来 → 两个线程同时用单例 ONNX 引擎。
 * 令牌让「不是自己的锁」放不掉。
 */
class OcrLockTest {

    /** 可快进的单调时钟基准。单测里替换 `OcrLock.clockNs` 来"等 30 秒"，不真等。 */
    private val base = 1_000_000_000_000L

    @After
    fun cleanup() {
        // 恢复真实时钟 + 兜底：测试失败也不要污染后续用例
        OcrLock.clockNs = { System.nanoTime() }
        while (OcrLock.isRunning) OcrLock.release()
    }

    @Test
    fun onlyOwnerTokenCanRelease() {
        val a = OcrLock.acquire()
        assertTrue("第一次拿锁必须成功", a != 0L)
        assertTrue(OcrLock.isRunning)

        // 模拟自愈：强制释放（无令牌版），然后 B 拿锁
        OcrLock.release()
        assertFalse(OcrLock.isRunning)
        val b = OcrLock.acquire()
        assertTrue(b != 0L)
        assertNotEquals(a, b)

        // A 拿着**过期令牌**来放锁 → 必须是空操作（B 还在里面）
        OcrLock.release(a)
        assertTrue("旧持有者不许放掉新持有者的锁", OcrLock.isRunning)

        // B 自己放得掉
        OcrLock.release(b)
        assertFalse(OcrLock.isRunning)
    }

    @Test
    fun acquireFailsWhileHeld_andHeartbeatNeedsToken() {
        val token = OcrLock.acquire()
        assertEquals("已被持有 → 第二次拿不到（返回 0）", 0L, OcrLock.acquire())
        // 令牌不匹配的心跳不该刷（这里只验证不崩、不抛）
        OcrLock.heartbeat(token + 1)
        OcrLock.heartbeat(token)
        OcrLock.release(token)
        assertFalse(OcrLock.isRunning)
    }

    /**
     * **长临界区（超分那种）+ 持续心跳 = 不该被自愈抢走**（2026-10 阶段性审查 P1）。
     *
     * 超分推理（ncnn/Vulkan，重档位分钟级；CPU 回退、Real-ESRGAN 4x 更甚）远超
     * [OcrLock.STALE_TIMEOUT_MS]。持有者只要持续 `heartbeat(token)`，自愈就不得把它判死 ——
     * 判死就意味着 OCR 与超分同时用同一个单例 ONNX 引擎。
     */
    @Test
    fun longCriticalSectionWithHeartbeatSurvivesStaleTimeout() {
        val staleBefore = OcrLock.staleReleaseCount
        var elapsedMs = 0L
        OcrLock.clockNs = { base + elapsedMs * 1_000_000 }

        val token = OcrLock.acquire()
        assertTrue("第一次拿锁必须成功", token != 0L)
        // 模拟超分跑 90 秒，期间每 5 秒一次心跳（= SrProcessor.LOCK_HEARTBEAT_MS）
        repeat(18) {
            elapsedMs += 5_000
            OcrLock.heartbeat(token)
        }
        assertTrue("心跳够密就不该被判死（已过 ${elapsedMs}ms）", OcrLock.isRunning)
        assertEquals("持有期间别人一律拿不到", 0L, OcrLock.acquire())
        assertEquals("不该发生自愈", staleBefore, OcrLock.staleReleaseCount)
        OcrLock.release(token)
        assertFalse(OcrLock.isRunning)
    }

    /**
     * 反向用例：**同一个 90 秒，不打心跳就会被抢走** —— 这正是 `SrProcessor` 修复前的行为，
     * 也是"必须加心跳"的理由。顺带钉死旧持有者事后放锁是空操作。
     */
    @Test
    fun longCriticalSectionWithoutHeartbeatIsStolen_andOldReleaseIsNoop() {
        val staleBefore = OcrLock.staleReleaseCount
        var elapsedMs = 0L
        OcrLock.clockNs = { base + elapsedMs * 1_000_000 }

        val sr = OcrLock.acquire()             // 超分拿到锁，然后闷头跑
        elapsedMs = 90_000                     // 90 秒，一次心跳都没有
        val ocr = OcrLock.acquire()            // → maybeRecoverStale 强制释放，OCR 拿到新锁

        assertTrue("无心跳时自愈会放锁 —— 这就是必须打心跳的理由", ocr != 0L)
        assertNotEquals(sr, ocr)
        assertTrue("自愈计数要能看见", OcrLock.staleReleaseCount > staleBefore)
        assertTrue(OcrLock.isRunning)

        // 超分这时才跑完，用**自己的旧令牌**放锁 → 必须是空操作（OCR 还在里面）
        OcrLock.release(sr)
        assertTrue("旧持有者不许放掉新持有者的锁", OcrLock.isRunning)
        OcrLock.release(ocr)
        assertFalse(OcrLock.isRunning)
    }
}
