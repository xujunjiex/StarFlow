package com.moe.starflow.translate

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TranslationAwaiterTest {
    private class Backend : TranslationTextAPI {
        var callback: ((TranslationResult) -> Unit)? = null
        val cancelledRequests = AtomicInteger()
        val cancelledEngines = AtomicInteger()
        var answerImmediately = false
        override fun getTranslation(text: String, sourceLanguage: String, targetLanguage: String, callback: (TranslationResult) -> Unit) = Unit
        override fun startTranslation(text: String, sourceLanguage: String, targetLanguage: String,
            onPhase: (String) -> Unit, onPartial: (String) -> Unit, callback: (TranslationResult) -> Unit): TranslationRequest {
            this.callback = callback
            if (answerImmediately) {
                callback(TranslationResult.Success("first"))
                callback(TranslationResult.Success("duplicate"))
            }
            return TranslationRequest { cancelledRequests.incrementAndGet() }
        }
        override fun cancelTranslation() { cancelledEngines.incrementAndGet() }
        override fun release() = Unit
    }

    @Test fun cancellationCancelsOnlyThisRequestAndLateCallbacksCannotReviveIt() = runBlocking {
        val backend = Backend()
        val task = launch(start = CoroutineStart.UNDISPATCHED) { TranslationAwaiter.await(backend, "a", "ja", "zh") }
        task.cancelAndJoin()
        assertEquals(1, backend.cancelledRequests.get())
        assertEquals(0, backend.cancelledEngines.get())
        backend.callback!!(TranslationResult.Success("late"))
        backend.answerImmediately = true
        assertEquals(TranslationResult.Success("first"), TranslationAwaiter.await(backend, "b", "ja", "zh"))
        assertEquals("成功回调不能被当成取消", 1, backend.cancelledRequests.get())
    }

    @Test fun missingTerminalCallbackTimesOutAndReleasesTheWaiter() = runBlocking {
        val backend = Backend()
        try {
            withTimeout(3_000) { TranslationAwaiter.await(backend, "a", "ja", "zh", timeoutMs = 10) }
            fail("Expected timeout")
        } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("timeout")) }
        assertEquals(1, backend.cancelledRequests.get())
    }

    @Test fun cancelledSlotBeforeStartReturnsStillCancelsTheActualHandle() {
        val slot = TranslationRequestSlot()
        val cancellations = AtomicInteger()
        slot.cancel()
        slot.attach(TranslationRequest { cancellations.incrementAndGet() })
        assertEquals(1, cancellations.get())
    }

    @Test fun synchronousCompletionDoesNotCancelTheFinishedRequest() {
        val slot = TranslationRequestSlot()
        val cancellations = AtomicInteger()
        slot.finish()
        slot.attach(TranslationRequest { cancellations.incrementAndGet() })
        slot.cancel()
        assertEquals(0, cancellations.get())
    }
}
