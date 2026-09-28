package com.moe.starflow.manga

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `OcrLock` 的互斥 + **超时自愈** 守卫。
 *
 * 背景（2026-09-28 用户报的"偶发无法翻译"）：它原本是个裸布尔、`release()` 无条件清零，
 * 任何一条"拿了锁没走到 finally"的路径都会让它**永久为 true** → 之后所有翻译静默跳过
 * （阅读器里点翻译没反应、连日志都没有）。现在超过 `STALE_TIMEOUT_MS` 没心跳就自愈。
 *
 * 用 [OcrLock.clockNs] 的测试缝"快进"，不真等 30s。
 */
class OcrLockTest {

    private var fakeNow = 0L

    private fun installFakeClock() {
        fakeNow = 1_000_000_000L
        OcrLock.clockNs = { fakeNow }
        // 干净起点：前面的用例可能留下状态
        OcrLock.release()
    }

    @After
    fun tearDown() {
        OcrLock.release()
        OcrLock.clockNs = { System.nanoTime() }
    }

    @Test
    fun `互斥：拿到之后别人拿不到，释放后能再拿`() {
        installFakeClock()
        assertTrue(OcrLock.tryAcquire())
        assertTrue(OcrLock.isRunning)
        assertFalse("持有期间不允许第二次获取", OcrLock.tryAcquire())
        OcrLock.release()
        assertFalse(OcrLock.isRunning)
        assertTrue("释放后应能再拿", OcrLock.tryAcquire())
    }

    @Test
    fun `心跳会让锁一直有效（长任务不会被误判为死锁）`() {
        installFakeClock()
        assertTrue(OcrLock.tryAcquire())
        // 持有 5 分钟，但每 10s 打一次心跳
        repeat(30) {
            fakeNow += 10_000L * 1_000_000
            OcrLock.heartbeat()
        }
        assertFalse("有心跳就不该被判死", OcrLock.tryAcquire())
        assertTrue(OcrLock.isRunning)
    }

    @Test
    fun `超时无心跳 → 自愈释放，后续仍能正常获取`() {
        installFakeClock()
        val before = OcrLock.staleReleaseCount
        assertTrue(OcrLock.tryAcquire())
        // 30s 没心跳（阈值是 >，等于不算，所以多推 1ms）
        fakeNow += OcrLock.STALE_TIMEOUT_MS * 1_000_000 + 1_000_000
        assertTrue("超时后应能拿到锁（自愈）", OcrLock.tryAcquire())
        assertTrue(OcrLock.isRunning)
        assertEquals("自愈计数要 +1（排查用）", before + 1, OcrLock.staleReleaseCount)
    }

    @Test
    fun `未超时不会自愈（阈值内仍互斥）`() {
        installFakeClock()
        assertTrue(OcrLock.tryAcquire())
        fakeNow += (OcrLock.STALE_TIMEOUT_MS - 1_000) * 1_000_000
        assertFalse("没到阈值就不该抢到", OcrLock.tryAcquire())
    }

    @Test
    fun `heldMs 反映持有时长`() {
        installFakeClock()
        assertTrue(OcrLock.tryAcquire())
        assertEquals(0L, OcrLock.heldMs())
        fakeNow += 5_000L * 1_000_000
        assertEquals(5_000L, OcrLock.heldMs())
        OcrLock.release()
        assertEquals("没持有时恒 0", 0L, OcrLock.heldMs())
    }
}
