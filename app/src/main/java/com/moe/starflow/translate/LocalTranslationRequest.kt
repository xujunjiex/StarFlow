package com.moe.starflow.translate

import com.moe.starflow.utils.LocalTranslationCoordinator
import com.moe.starflow.utils.NativeOperation
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

/** Keeps native exclusion until the worker actually exits, even after its consumer stops waiting. */
class LocalTranslationRequest(private val callback: (TranslationResult) -> Unit) : TranslationRequest {
    private val cancelled = AtomicBoolean()
    private val progress = AtomicLong(System.nanoTime())
    @Volatile private var worker: Thread? = null
    val isCancelled: Boolean get() = cancelled.get()
    fun reportProgress() { progress.set(System.nanoTime()) }

    override fun cancel() {
        cancelled.set(true)
        worker?.interrupt()
    }

    fun ensureActive() {
        if (isCancelled || Thread.currentThread().isInterrupted) throw CancellationException("Local translation cancelled")
    }

    fun <T> abortable(abort: () -> Unit, block: () -> T): T {
        reportProgress()
        return NativeOperation.run(isCancelled = { isCancelled }, abort = abort,
            timeoutMs = 30_000L, progressNs = { progress.get() }, block = block)
    }

    fun start(onFinished: () -> Unit = {}, block: (LocalTranslationRequest) -> TranslationResult) {
        val thread = Thread {
            val result = try {
                LocalTranslationCoordinator.runNative {
                    ensureActive()
                    block(this).also { ensureActive() }
                }
            } catch (e: Exception) {
                TranslationResult.Error(e)
            } catch (e: OutOfMemoryError) {
                TranslationResult.Error(IllegalStateException("Local translation memory allocation failed", e))
            } catch (e: LinkageError) {
                TranslationResult.Error(IllegalStateException("Local translation native library unavailable", e))
            } finally { onFinished() }
            try { callback(result) } catch (e: Exception) {
                com.moe.starflow.utils.LogCollector.e("LocalTranslation", "Translation callback failed", e)
            }
        }.apply { name = "Local-Translation"; priority = Thread.MAX_PRIORITY }
        worker = thread
        if (isCancelled) thread.interrupt()
        thread.start()
    }
}
