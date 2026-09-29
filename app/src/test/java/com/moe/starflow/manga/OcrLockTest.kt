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

    @After
    fun cleanup() {
        // 兜底：测试失败也不要污染后续用例
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
}
