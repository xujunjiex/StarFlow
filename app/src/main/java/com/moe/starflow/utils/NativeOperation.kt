package com.moe.starflow.utils

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/** Watches blocking native work without freeing its resources before the call returns. */
object NativeOperation {
    private val monitor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Native-Cancellation").apply { isDaemon = true }
    }

    fun <T> run(
        isCancelled: () -> Boolean,
        abort: () -> Unit,
        timeoutMs: Long = 0L,
        progressNs: (() -> Long)? = null,
        description: String = "native inference",
        discard: (T) -> Unit = {},
        block: () -> T,
    ): T {
        if (isCancelled()) throw CancellationException("Native operation cancelled before start")
        val gate = Any()
        var running = true
        val timedOut = AtomicBoolean()
        var cancellationLogged = false
        var abortFailureLogged = false
        val started = System.nanoTime()
        val watcher = monitor.scheduleWithFixedDelay({
            synchronized(gate) {
                if (running) {
                    if (timeoutMs > 0 && (System.nanoTime() - (progressNs?.invoke() ?: started)) / 1_000_000 >= timeoutMs &&
                        timedOut.compareAndSet(false, true))
                        LogCollector.e("NativeOperation", "$description stalled for ${timeoutMs}ms; requesting native termination")
                    if (isCancelled() || timedOut.get()) {
                        if (!cancellationLogged) {
                            cancellationLogged = true
                            LogCollector.w("NativeOperation", "$description: stopping, waiting for native return")
                        }
                        try { abort() } catch (e: Exception) {
                            if (!abortFailureLogged) {
                                abortFailureLogged = true
                                LogCollector.e("NativeOperation", "$description: native termination failed", e)
                            }
                        }
                    }
                }
            }
        }, 0, 20, TimeUnit.MILLISECONDS)
        try {
            val result = try { block() } catch (e: Exception) {
                if (isCancelled()) throw CancellationException("Native operation cancelled").also { it.initCause(e) }
                if (timedOut.get()) throw IllegalStateException("Native operation timed out (${timeoutMs}ms)", e)
                throw e
            }
            if (isCancelled() || timedOut.get()) {
                discard(result)
                if (isCancelled()) throw CancellationException("Native operation cancelled")
                throw IllegalStateException("Native operation timed out (${timeoutMs}ms)")
            }
            return result
        } finally {
            // Serialize against abort before the caller closes RunOptions/model handles.
            synchronized(gate) { running = false }
            watcher.cancel(false)
        }
    }
}
