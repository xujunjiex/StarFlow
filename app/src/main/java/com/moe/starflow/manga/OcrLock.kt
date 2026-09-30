package com.moe.starflow.manga

import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.state.*
import com.moe.starflow.manga.render.*
import com.moe.starflow.manga.merge.*

import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import com.moe.starflow.utils.LogCollector

/**
 * OCR 引擎互斥锁。
 *
 * PP-OCRv5 / MangaOCR / RT-DETR 等 ONNX 引擎是**进程级单例**，同时多线程调用会崩或出垃圾结果；
 * 正常翻译（`MangaFloatingService` / 阅读器内嵌翻译 / 小说翻译）与重翻（`MangaViewerActivity`）共用这把锁。
 *
 * ## ⚠️ 为什么加了「心跳 + 自愈」（2026-09-28）
 *
 * 它原来只是个裸布尔：`release()` **无条件**清零、也不记谁持有。于是任何一条"拿了锁但没走到
 * `finally`"的路径（最典型：`acquireLockWithWait()` 这种**挂起函数**里拿锁 —— 协程在**恢复点**
 * 被取消时，锁已经拿到、`try` 还没进去）都会让它**永久为 true**。
 * 后果不是崩溃，而是**之后所有翻译都静默跳过**：阅读器里点翻译/开自动/开增量全都没反应、
 * 连日志都没有（只留下 `runTranslate: OcrLock 被占用，跳过`）——
 * 用户报的"偶发、不知道什么时候触发、点了没反应"就是这个（排查时极难复现）。
 *
 * 现在：
 * - 每次 `tryAcquire()` 先看**持有时间**：超过 [STALE_TIMEOUT_MS] 且期间没有任何 [heartbeat] →
 *   认定持有者已死（异常路径漏放），**强制释放并记 W 级日志**，本次照常拿锁；
 * - 长任务可以调 [heartbeat]（可不调：超过阈值才会被判死，正常 OCR/翻译是秒级）。
 */
object OcrLock {

    private const val TAG = "OcrLock"

    /**
     * 多久没有心跳就认定持有者已死。
     *
     * 取值依据：单页 OCR + 翻译在本机是**秒级**（本地模型最慢也就几十秒，且本地推理那条路
     * 在 `TranslateUtils` 有自己的 30s 无输出看门狗）；30s 内没有任何心跳说明持有者早就不在了。
     */
    const val STALE_TIMEOUT_MS = 30_000L

    @Volatile
    var isRunning = false
        private set

    /**
     * 单调时钟。⚠️ 单测靠替换它来"快进"（不用真等 30s）—— 生产代码永远是 `System.nanoTime()`。
     */
    internal var clockNs: () -> Long = { System.nanoTime() }

    /** 最近一次拿到锁的时刻（`System.nanoTime()`，纯 JVM 单调时钟，单测里也能跑）。 */
    @Volatile
    private var heldSinceNs = 0L

    /** 最近一次心跳（拿锁时自动打一次）。 */
    @Volatile
    private var heartbeatNs = 0L

    /** 持有者令牌自增源 + 当前持有者令牌（见 [acquire] 的注释）。 */
    private val nextToken = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile
    private var ownerToken = 0L

    /** 自愈释放过几次（排查用：非 0 说明有地方漏放锁）。 */
    @Volatile
    var staleReleaseCount = 0
        private set

    /**
     * 拿锁并返回**持有者令牌**（0 = 没拿到）。
     *
     * ⚠️ 为什么需要令牌：自愈（[maybeRecoverStale]）会在超时时**强制释放**别人的锁；
     * 而那个"其实还活着"的旧持有者随后走到 `finally` 时，旧的 `release()` 会把**新持有者**的锁放掉
     * → 第三个调用方又能进 → 两个线程同时用单例 ONNX 引擎（正是这把锁要防的事）。
     * 带令牌放锁时，只有令牌仍是当前持有者才生效 ⇒ 误释放的后果被限制在"这一次超时"。
     */
    @Synchronized
    fun acquire(): Long {
        maybeRecoverStale()
        if (isRunning) return 0L
        isRunning = true
        val now = clockNs()
        heldSinceNs = now
        heartbeatNs = now
        ownerToken = nextToken.incrementAndGet()
        return ownerToken
    }

    @Synchronized
    fun tryAcquire(): Boolean = acquire() != 0L

    /** 只有**持有者自己**（令牌相符）才放得掉锁；令牌为 0 或已过期 = 空操作。 */
    @Synchronized
    fun release(token: Long) {
        if (token == 0L || token != ownerToken) return
        clearLocked()
    }

    /**
     * 无令牌放锁（旧调用点/短临界区兼容用）。
     * ⚠️ 长临界区（跨 OCR + 翻译）**必须**用 [release] 的令牌版，否则会被自愈竞态误伤。
     */
    @Synchronized
    fun release() = clearLocked()

    @Synchronized
    private fun clearLocked() {
        isRunning = false
        heldSinceNs = 0L
        heartbeatNs = 0L
        ownerToken = 0L
    }

    /**
     * 持有者"我还活着"。
     *
     * 长任务（整章批量、本地模型慢速推理）里**建议**在每页/每批结束时调一次，
     * 免得被 [STALE_TIMEOUT_MS] 误判成死锁（正常情况不调也没事：阈值远大于单页耗时）。
     */
    fun heartbeat() {
        if (isRunning) heartbeatNs = clockNs()
    }

    /** 带令牌的心跳：只有当前持有者才刷得动（旧持有者的孤儿 ticker 不会把别人的锁"养着"）。 */
    fun heartbeat(token: Long) {
        if (isRunning && token != 0L && token == ownerToken) heartbeatNs = clockNs()
    }

    /** 已被持有多久（毫秒；没持有返回 0）。 */
    fun heldMs(): Long {
        val since = heldSinceNs
        return if (isRunning && since > 0) (clockNs() - since) / 1_000_000 else 0L
    }

    /**
     * 超时自愈：锁被持有太久且期间没有心跳 → 强制释放。
     *
     * ⚠️ 这是**兜底**，不是正常路径：真触发说明有代码路径漏放锁（日志里会打 W + 堆栈无关的提示，
     * 看到就该去修那条路径，别把这里当"设计"）。
     */
    private fun maybeRecoverStale() {
        if (!isRunning) return
        val hb = heartbeatNs
        if (hb <= 0L) return
        val silentMs = (clockNs() - hb) / 1_000_000
        if (silentMs <= STALE_TIMEOUT_MS) return
        staleReleaseCount += 1
        LogCollector.w(
            TAG,
            "锁被持有 ${heldMs()}ms 且 ${silentMs}ms 无心跳 → 判定持有者已死，强制释放" +
                "（累计 $staleReleaseCount 次；说明有路径漏放锁，请查该路径）"
        )
        release()
    }

    /**
     * 安全地获取锁并执行代码块。获取失败时抛出 RejectedExecutionException。
     *
     * ⚠️ 只在**非挂起**代码里用它（`block` 是普通 lambda）：挂起函数里请走
     * 「先 `while (isRunning) delay()` 等锁 → 再 `tryAcquire()` → 立刻 `try/finally`」这套写法，
     * 否则协程在恢复点被取消时锁已经拿到、`finally` 还没进 → 漏放（本类头注释里那个坑）。
     */
    inline fun <T> use(block: () -> T): T {
        if (!tryAcquire()) {
            throw java.util.concurrent.RejectedExecutionException("OcrLock is busy")
        }
        try {
            return block()
        } finally {
            release()
        }
    }
}
