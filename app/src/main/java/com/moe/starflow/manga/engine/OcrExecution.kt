package com.moe.starflow.manga.engine

import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtSession
import com.moe.starflow.utils.NativeOperation
import kotlinx.coroutines.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Carries the executing request into synchronous OCR helpers, including decoder worker threads. */
object OcrExecution {
    private val current = ThreadLocal<Context?>()

    private class Context(val job: Job?, val stopped: () -> Boolean) : ThreadContextElement<Context?>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Context>
        override fun updateThreadContext(context: CoroutineContext): Context? = current.get().also { current.set(this) }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Context?) { current.set(oldState) }
    }

    suspend fun context(stopped: (() -> Boolean)? = null): CoroutineContext {
        val context = currentCoroutineContext()
        val inherited = context[Context]?.stopped
        return Context(context[Job]) { inherited?.invoke() == true || stopped?.invoke() == true }
    }
    suspend fun <T> cancellable(block: suspend () -> T): T = withContext(context()) {
        currentCoroutineContext().ensureActive()
        block()
    }

    fun checkCancellation() {
        current.get()?.job?.ensureActive()
        if (current.get()?.stopped?.invoke() == true) throw CancellationException("OCR request cancelled")
        if (Thread.currentThread().isInterrupted) throw CancellationException("OCR/native worker cancelled")
    }

    fun cancellationSignal(): () -> Boolean {
        val execution = current.get()
        return { execution?.job?.isActive == false || execution?.stopped?.invoke() == true }
    }

    @JvmOverloads
    fun run(
        session: OrtSession,
        inputs: Map<String, OnnxTensorLike>,
        pinnedOutputs: Map<String, OnnxValue>? = null,
    ): OrtSession.Result {
        return runRequested(session, inputs, null, pinnedOutputs)
    }

    @JvmOverloads
    fun runRequested(
        session: OrtSession,
        inputs: Map<String, OnnxTensorLike>,
        requestedOutputs: Set<String>?,
        pinnedOutputs: Map<String, OnnxValue>? = null,
    ): OrtSession.Result {
        val execution = current.get()
        val thread = Thread.currentThread()
        checkCancellation()
        return OrtSession.RunOptions().use { options ->
            NativeOperation.run(
                isCancelled = { execution?.job?.isActive == false || execution?.stopped?.invoke() == true || thread.isInterrupted },
                abort = { options.setTerminate(true) },
                timeoutMs = 120_000L,
                discard = { it: OrtSession.Result -> it.close() },
            ) {
                when {
                    pinnedOutputs != null -> session.run(inputs, requestedOutputs ?: emptySet(), pinnedOutputs, options)
                    requestedOutputs != null -> session.run(inputs, requestedOutputs, options)
                    else -> session.run(inputs, options)
                }
            }
        }
    }
}
