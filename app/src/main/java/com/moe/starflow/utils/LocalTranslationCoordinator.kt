package com.moe.starflow.utils

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import com.moe.starflow.manga.TranslationCancelledException
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicBoolean

/** Serializes local NLLB/LlamaCpp translation across reader controllers. */
object LocalTranslationCoordinator {
    val mutex = Mutex()

    class Lease internal constructor(private val owner: Any) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) mutex.unlock(owner) }
    }

    fun tryAcquire(): Lease? {
        val owner = Any()
        return if (mutex.tryLock(owner)) Lease(owner) else null
    }

    suspend fun acquire(isCancelled: (() -> Boolean)? = null): Lease {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (isCancelled?.invoke() == true) throw TranslationCancelledException()
            tryAcquire()?.let { return it }
            delay(50)
        }
    }

    // Coroutine cancellation can finish a caller before its JNI thread returns. This gate is
    // owned by that thread, so a subsequent request cannot enter the model during native cleanup.
    private val nativeGate = ReentrantLock(true)
    fun <T> runNative(block: () -> T): T {
        nativeGate.lockInterruptibly()
        try { return block() } finally { nativeGate.unlock() }
    }
}
