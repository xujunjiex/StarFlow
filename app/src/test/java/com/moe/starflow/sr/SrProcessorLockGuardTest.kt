package com.moe.starflow.sr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 超分共享锁接线守卫：登记实际执行 Job，使用令牌配对释放并启动锁托管的心跳。
 * 原生调用退出前不允许抢锁；生命周期与长任务行为由 OcrCancellationLifecycleTest 覆盖。
 */
class SrProcessorLockGuardTest {

    private fun src(): String {
        val f = java.io.File("src/main/java/com/moe/starflow/sr/SrProcessor.kt")
        assertTrue("找不到 SrProcessor 源码：${f.absolutePath}", f.exists())
        return f.readText()
    }

    @Test
    fun nativeOwnerUsesAnExecutingJobAndTokenRelease() {
        val s = src()
        assertTrue("超分必须登记执行 Job", s.contains("OcrLock.acquire(currentCoroutineContext()[Job]"))
        assertFalse("原生任务不能丢掉所有者令牌", s.contains("OcrLock.tryAcquire("))
        assertTrue("释放必须使用同一令牌", Regex("OcrLock\\.release\\(\\s*token\\s*\\)").containsMatchIn(s))
    }

    @Test
    fun heartbeatUsesTheOwnerBoundLifecycle() {
        val s = src()
        assertTrue("心跳统一交给 OcrLock 生命周期", s.contains("OcrLock.startHeartbeat(token)"))
        assertTrue("退出原生调用后停止心跳", s.contains("heartbeat?.cancel()"))
        assertTrue("native 退出前不得提前放锁", s.contains("finally {"))
    }
}
