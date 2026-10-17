package com.moe.starflow.translate

import com.moe.starflow.manga.TranslationCancelledException
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One cancellable callback bridge for both batch and single-bubble translation. */
object TranslationAwaiter {
    suspend fun await(
        translator: TranslationTextAPI,
        text: String,
        sourceLang: String,
        targetLang: String,
        onPhase: (String) -> Unit = {},
        onPartial: (String) -> Unit = {},
        isCancelled: (() -> Boolean)? = null,
        timeoutMs: Long = 35_000L,
        stallMs: Long = 30_000L,
        stallMessage: String = "Local translation stopped producing output",
    ): TranslationResult = coroutineScope {
        val finished = AtomicBoolean()
        val abandoned = AtomicBoolean()
        val request = AtomicReference<TranslationRequest?>()
        val continuation = AtomicReference<CancellableContinuation<TranslationResult>?>()
        val progress = AtomicLong(System.nanoTime())
        val started = System.nanoTime()
        val local = translator.isLocalHeavyEngine()
        fun fail(error: Exception) {
            if (!finished.compareAndSet(false, true)) return
            abandoned.set(true)
            request.get()?.cancel()
            continuation.getAndSet(null)?.let { if (it.isActive) it.resumeWithException(error) }
        }
        val watcher = launch {
            while (!finished.get()) {
                delay(100)
                when {
                    isCancelled?.invoke() == true -> fail(TranslationCancelledException())
                    local && (System.nanoTime() - progress.get()) / 1_000_000 >= stallMs ->
                        fail(IllegalStateException(stallMessage))
                    !local && (System.nanoTime() - started) / 1_000_000 >= timeoutMs ->
                        fail(IllegalStateException("Translation timeout (${timeoutMs / 1000}s)"))
                }
            }
        }
        try {
            suspendCancellableCoroutine { cont ->
                continuation.set(cont)
                cont.invokeOnCancellation {
                    abandoned.set(true)
                    finished.set(true)
                    continuation.set(null)
                    request.get()?.cancel()
                }
                try {
                    val handle = translator.startTranslation(
                        text, sourceLang, targetLang,
                        onPhase = { phase ->
                            if (!finished.get()) { progress.set(System.nanoTime()); onPhase(phase) }
                        },
                        onPartial = { partial ->
                            if (!finished.get()) { progress.set(System.nanoTime()); onPartial(partial) }
                        },
                        callback = { result ->
                            if (finished.compareAndSet(false, true))
                                continuation.getAndSet(null)?.let { if (it.isActive) it.resume(result) }
                        },
                    )
                    request.set(handle)
                    if (abandoned.get()) handle.cancel()
                } catch (e: Exception) { fail(e) }
            }
        } finally {
            finished.set(true)
            watcher.cancel()
        }
    }
}
