package com.moe.starflow.manga

import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Process-wide OCR/SR exclusion. Cancellation does not imply that native code has returned. */
object OcrLock {
    private const val TAG = "OcrLock"
    const val STALE_TIMEOUT_MS = 30_000L
    internal var clockNs: () -> Long = { System.nanoTime() }
    private val nextToken = AtomicLong()
    private val ocrDemand = AtomicInteger()
    private var running = false
    private var ownerToken = 0L
    private var ownerJob: Job? = null
    private var ownerName = ""
    private var ownerCompletion: DisposableHandle? = null
    private var ticker: Job? = null
    private var legacyRecovery = false
    private var heldSinceNs = 0L
    private var heartbeatNs = 0L

    var staleReleaseCount = 0
        private set

    val isRunning: Boolean
        @Synchronized get() {
            recoverFinishedOwner()
            return running
        }

    val isStopping: Boolean
        @Synchronized get() = running && ownerJob?.isCancelled == true

    fun beginOcrDemand() { ocrDemand.incrementAndGet() }
    fun endOcrDemand() { ocrDemand.updateAndGet { (it - 1).coerceAtLeast(0) } }
    fun hasOcrDemand(): Boolean = ocrDemand.get() > 0

    /** Native callers supply their executing Job, never the application/root scope Job. */
    @Synchronized
    fun acquire(job: Job? = null, source: String = "legacy"): Long {
        recoverFinishedOwner()
        if (running || job?.isActive == false) return 0L
        return acquireLocked(job, source, allowLegacyRecovery = job == null)
    }

    private fun acquireLocked(job: Job?, source: String, allowLegacyRecovery: Boolean): Long {
        val token = nextToken.incrementAndGet()
        running = true
        ownerToken = token
        ownerJob = job
        ownerName = source
        legacyRecovery = allowLegacyRecovery
        heldSinceNs = clockNs()
        heartbeatNs = heldSinceNs
        // Completion happens after the body and its native/finally work, unlike isCancelled.
        val completion = job?.invokeOnCompletion { release(token) }
        if (ownerToken == token) ownerCompletion = completion else completion?.dispose()
        return if (ownerToken == token) token else 0L
    }

    /** Teardown must never evict an operation merely because its heartbeat was delayed. */
    fun tryRunExclusive(block: () -> Unit): Boolean {
        val token = synchronized(this) {
            if (running) return false
            acquireLocked(null, "engine lifecycle", allowLegacyRecovery = false)
        }
        try { block(); return true } finally { release(token) }
    }

    /** Survives cancellation while native code drains, but cannot outlive its lock/owner. */
    fun startHeartbeat(token: Long, intervalMs: Long = 5_000L): Job {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (isActive && owns(token)) {
                    heartbeat(token)
                    delay(intervalMs)
                }
            } finally { scope.cancel() }
        }
        synchronized(this) {
            if (token != 0L && ownerToken == token) {
                ticker?.cancel()
                ticker = task
            } else task.cancel()
        }
        task.start()
        return task
    }

    @Synchronized private fun owns(token: Long) = running && token != 0L && ownerToken == token
    @Synchronized fun tryAcquire(): Boolean = acquire() != 0L

    @Synchronized
    fun release(token: Long) {
        if (token != 0L && token == ownerToken) clearLocked()
    }

    /** Compatibility for synchronous use/tests. Native operations always release their token. */
    @Synchronized fun release() = clearLocked()

    private fun clearLocked() {
        running = false
        ownerToken = 0L
        ownerJob = null
        ownerName = ""
        heldSinceNs = 0L
        heartbeatNs = 0L
        ownerCompletion?.dispose()
        ownerCompletion = null
        ticker?.cancel()
        ticker = null
    }

    @Synchronized fun heartbeat() { if (running) heartbeatNs = clockNs() }
    @Synchronized fun heartbeat(token: Long) { if (owns(token)) heartbeatNs = clockNs() }
    @Synchronized fun heldMs(): Long = if (running) (clockNs() - heldSinceNs) / 1_000_000 else 0L

    @Synchronized
    fun describeOwner(): String = if (!running) "idle" else
        "owner=$ownerName token=$ownerToken held=${heldMs()}ms stopping=${ownerJob?.isCancelled == true}"

    private fun recoverFinishedOwner() {
        if (!running) return
        val job = ownerJob
        // An active or cancelling Job may still be inside native code. Never steal its lock.
        val finished = job?.isCompleted == true
        val abandonedLegacy = job == null && legacyRecovery &&
            (clockNs() - heartbeatNs) / 1_000_000 > STALE_TIMEOUT_MS
        if (!finished && !abandonedLegacy) return
        staleReleaseCount++
        LogCollector.w(TAG, "回收已结束任务的 OCR 占用：${describeOwner()} legacy=$abandonedLegacy")
        clearLocked()
    }

    inline fun <T> use(block: () -> T): T {
        val token = acquire()
        if (token == 0L) throw java.util.concurrent.RejectedExecutionException("OcrLock is busy")
        try { return block() } finally { release(token) }
    }
}
