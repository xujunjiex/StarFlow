package com.moe.starflow.manga.engine

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class OcrExecutionTest {
    @Test fun stopSignalReachesSynchronousDecoderOnAnotherDispatcher() = runBlocking {
        val stopped = AtomicBoolean()
        var decoded = 0
        try {
            withContext(OcrExecution.context { stopped.get() }) {
                withContext(Dispatchers.Default + OcrExecution.context()) {
                    repeat(100) {
                        if (decoded == 3) stopped.set(true)
                        OcrExecution.checkCancellation()
                        decoded++
                    }
                }
            }
            fail("Decoder should stop at the next iteration")
        } catch (_: CancellationException) { }
        assertEquals(3, decoded)
        // Restoring the context must not poison the next independent request on the same thread.
        OcrExecution.cancellable { OcrExecution.checkCancellation() }
    }

    @Test fun cancellingOneDecoderStopsItsNativeLoopWithoutCancellingTheParent() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = AtomicBoolean()
        withContext(OcrExecution.context()) {
            val decoder = launch(Dispatchers.Default) {
                try {
                    OcrExecution.cancellable {
                        started.complete(Unit)
                        while (true) OcrExecution.checkCancellation()
                    }
                } finally { stopped.set(true) }
            }
            started.await()
            decoder.cancel()
            withTimeout(2_000) { decoder.join() }
            assertTrue(stopped.get())
            OcrExecution.checkCancellation()
        }
    }

    @Test fun nestedPipelineCannotDiscardAnInheritedStopSignal() = runBlocking {
        try {
            withContext(OcrExecution.context { true }) {
                withContext(OcrExecution.context { false }) { OcrExecution.checkCancellation() }
            }
            fail("Inherited cancellation must remain visible")
        } catch (_: CancellationException) { }
    }
}
