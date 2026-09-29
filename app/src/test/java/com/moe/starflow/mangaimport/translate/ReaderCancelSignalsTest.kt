package com.moe.starflow.mangaimport.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [ReaderCancelSignals] 的回归守卫（纯 JVM）。
 *
 * 它存在的唯一理由是**修掉一个静默重故障**，所以这里锁的是那条故障本身：
 * `startChapterJob` 会先 `cancelEverything()` 去停阅读器队列，而章节页曾经读的是**同一个**
 * `cancelFlag` —— 于是「翻译本章」的每一页一进管线就被判"已取消"，
 * 任务正常跑完、进度条走完，**译文一条都没有**。
 */
class ReaderCancelSignalsTest {

    @Test
    fun freshFlagIsNeverBornCancelled() {
        val signals = ReaderCancelSignals()
        signals.cancelAll()
        // ⚠️ 这条就是那个故障的判据：取消之后**新建**的任务必须是干净的。
        // 旧实现共用一个 `cancelFlag` 且只有 `runTranslate` 会置回 false，
        // 章节页（从不置回）于是一直读到 true。
        assertFalse("取消之后新建的标志必须仍是 false", signals.newFlag().get())
    }

    @Test
    fun cancelAllOnlyHitsRegisteredFlags() {
        val signals = ReaderCancelSignals()
        val a = signals.newFlag()
        val b = signals.newFlag()
        val retired = signals.newFlag()
        signals.retire(retired)

        signals.cancelAll()

        assertTrue("在途的必须被取消", a.get())
        assertTrue(b.get())
        assertFalse("已注销的（任务早就收尾了）不该再被碰", retired.get())
        assertEquals("注销要真的从集合里摘掉，否则集合只涨不减", 2, signals.liveCount())
    }

    @Test
    fun retireIsIdempotent() {
        val signals = ReaderCancelSignals()
        val f = signals.newFlag()
        signals.retire(f)
        signals.retire(f)
        assertEquals(0, signals.liveCount())
    }

    @Test
    fun cancelIsVisibleAcrossThreads() {
        val signals = ReaderCancelSignals()
        val flag = signals.newFlag()
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        Thread {
            started.countDown()
            // 模拟翻译协程里的轮询（`TranslateUtils` 的 isCancelled 回调就是这么读的）
            while (!flag.get()) Thread.sleep(1)
            done.countDown()
        }.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        Thread.sleep(10)
        signals.cancelAll()
        assertTrue("取消必须能被在途任务看见", done.await(2, TimeUnit.SECONDS))
    }
}
