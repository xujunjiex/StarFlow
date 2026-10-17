package com.moe.starflow.translate

import com.moe.starflow.utils.LocalTranslationCoordinator
import org.junit.Assert.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import com.moe.starflow.manga.TranslationCancelledException
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LocalTranslationLifecycleTest {
    @Test fun stopWhileWaitingForLocalModelDoesNotWaitForTheOwner() = runBlocking {
        val owner = LocalTranslationCoordinator.tryAcquire()!!
        val stopped = AtomicBoolean()
        val waiting = async {
            try {
                LocalTranslationCoordinator.acquire { stopped.get() }.close()
                fail("Cancelled waiter must not acquire the model")
            } catch (_: TranslationCancelledException) { }
        }
        try {
            yield()
            stopped.set(true)
            withTimeout(2_000) { waiting.await() }
            assertNull(LocalTranslationCoordinator.tryAcquire())
        } finally { owner.close() }
        LocalTranslationCoordinator.tryAcquire()!!.close()
    }

    @Test fun lateCallbackCannotReleaseTheNextRequestsLease() {
        val first = LocalTranslationCoordinator.tryAcquire()!!
        first.close()
        val second = LocalTranslationCoordinator.tryAcquire()!!
        try {
            first.close()
            assertNull(LocalTranslationCoordinator.tryAcquire())
        } finally { second.close() }
        LocalTranslationCoordinator.tryAcquire()!!.close()
    }

    @Test fun cancellingNativeWorkDoesNotAdmitAnotherModelBeforeItReturns() {
        val entered = CountDownLatch(1)
        val nativeExit = CountDownLatch(1)
        val firstDone = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val first = LocalTranslationRequest { firstDone.countDown() }
        val second = LocalTranslationRequest { secondDone.countDown() }
        first.start {
            peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            entered.countDown()
            // JNI can finish later than cancellation; interruption alone is not completion.
            var returned = false
            while (!returned) try { nativeExit.await(); returned = true } catch (_: InterruptedException) { }
            running.decrementAndGet()
            TranslationResult.Success("old")
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            first.cancel()
            second.start {
                peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                secondEntered.countDown()
                running.decrementAndGet()
                TranslationResult.Success("new")
            }
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
        } finally { nativeExit.countDown() }
        assertTrue(firstDone.await(3, TimeUnit.SECONDS))
        assertTrue(secondDone.await(3, TimeUnit.SECONDS))
        assertEquals(1, peak.get())
    }

    @Test fun cancelledQueuedRequestCannotAbortTheActiveRequest() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstDone = CountDownLatch(1)
        val queuedDone = CountDownLatch(1)
        val queuedCalls = AtomicInteger()
        val first = LocalTranslationRequest { firstDone.countDown() }
        first.start { entered.countDown(); release.await(); TranslationResult.Success("active") }
        val queued = LocalTranslationRequest { queuedDone.countDown() }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            queued.start { queuedCalls.incrementAndGet(); TranslationResult.Success("queued") }
            queued.cancel()
            assertTrue(queuedDone.await(3, TimeUnit.SECONDS))
            assertFalse(first.isCancelled)
            assertEquals(0, queuedCalls.get())
        } finally { release.countDown() }
        assertTrue(firstDone.await(3, TimeUnit.SECONDS))
    }
}
