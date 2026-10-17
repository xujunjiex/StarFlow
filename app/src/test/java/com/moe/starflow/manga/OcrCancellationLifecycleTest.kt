package com.moe.starflow.manga

import com.moe.starflow.utils.NativeOperation
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OcrCancellationLifecycleTest {
    @After fun cleanup() { OcrLock.release(); OcrLock.clockNs = { System.nanoTime() } }

    @Test fun cancellationRetainsNativeExclusionUntilTheCallActuallyReturns() = runBlocking {
        val entered = CountDownLatch(1)
        val aborted = CountDownLatch(1)
        val nativeExit = CountDownLatch(1)
        var now = 1_000_000_000_000L
        OcrLock.clockNs = { now }
        val owner = launch(Dispatchers.IO) {
            val token = OcrLock.acquire(currentCoroutineContext()[Job], "test native OCR")
            OcrLock.startHeartbeat(token, 10)
            try {
                NativeOperation.run(isCancelled = { !isActive }, abort = { aborted.countDown() }) {
                    entered.countDown()
                    nativeExit.await()
                }
            } finally { OcrLock.release(token) }
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            owner.cancel()
            assertTrue(aborted.await(3, TimeUnit.SECONDS))
            now += 90_000_000_000L
            assertTrue(OcrLock.isStopping)
            assertTrue(OcrLock.isRunning)
            assertEquals(0L, OcrLock.acquire(Job(), "second OCR"))
            assertFalse(owner.isCompleted)
        } finally { nativeExit.countDown(); owner.join() }
        assertFalse(OcrLock.isRunning)
        val next = OcrLock.acquire(Job(), "next OCR")
        assertTrue(next != 0L)
        OcrLock.release(next)
    }

    @Test fun completedOwnerReleasesForgottenLockAndDetachedHeartbeat() = runBlocking {
        var ticker: Job? = null
        val owner = launch(Dispatchers.IO) {
            val token = OcrLock.acquire(currentCoroutineContext()[Job], "early exit")
            ticker = OcrLock.startHeartbeat(token, 10)
            // Simulate a return/exception before the caller reaches its own finally.
        }
        owner.join()
        assertFalse(OcrLock.isRunning)
        withTimeout(3_000) { ticker!!.join() }
        assertTrue(ticker!!.isCancelled)
    }

    @Test fun busyReadRecoversAbandonedLegacyLockWithoutAnotherAcquire() {
        var now = 1_000_000_000_000L
        OcrLock.clockNs = { now }
        OcrLock.acquire()
        now += 90_000_000_000L
        assertFalse(OcrLock.isRunning)
    }

    @Test fun nativeAbortCannotRunAfterItsResourcesAreClosed() {
        val closed = AtomicBoolean()
        val invalidAbort = AtomicBoolean()
        val cancelled = AtomicBoolean()
        NativeOperation.run(isCancelled = { cancelled.get() }, abort = { if (closed.get()) invalidAbort.set(true) }) { Unit }
        closed.set(true)
        cancelled.set(true)
        Thread.sleep(60)
        assertFalse(invalidAbort.get())
    }

    @Test fun stalledNativeCallReceivesTerminationAndItsResultIsDiscarded() {
        val aborted = CountDownLatch(1)
        val discarded = AtomicBoolean()
        try {
            NativeOperation.run(isCancelled = { false }, abort = { aborted.countDown() }, timeoutMs = 20,
                discard = { _: String -> discarded.set(true) }) {
                check(aborted.await(3, TimeUnit.SECONDS))
                "late native result"
            }
            fail("Stalled native work must terminate as a failure")
        } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("timed out")) }
        assertTrue(discarded.get())
    }
}
