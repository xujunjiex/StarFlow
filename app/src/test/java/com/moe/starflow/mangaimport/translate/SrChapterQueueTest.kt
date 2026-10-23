package com.moe.starflow.mangaimport.translate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class SrChapterQueueTest {
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun awaitIdle(queue: SrChapterQueue<*>) = withTimeout(4_000) {
        while (queue.jobs.value.isNotEmpty()) delay(10)
    }

    @Test
    fun chaptersAndPagesRunInSubmissionOrder_andWaitingChaptersAreNotPrepared() = runBlocking {
        val scope = scope()
        val release = CompletableDeferred<Unit>()
        try {
            val started = CompletableDeferred<Unit>()
            val prepared = Collections.synchronizedList(mutableListOf<String>())
            val pages = Collections.synchronizedList(mutableListOf<Pair<Int, String>>())
            val queue = SrChapterQueue(
                scope = scope,
                prepare = { request: String -> prepared += request; true },
                processPage = { page, request ->
                    pages += page to request
                    if (page == 10) {
                        started.complete(Unit)
                        release.await()
                    }
                },
            )
            assertTrue(queue.submit(7, listOf(10, 11), "model-A"))
            withTimeout(4_000) { started.await() }
            assertTrue(queue.submit(2, listOf(20, 21), "model-B"))
            assertTrue(queue.submit(5, listOf(30), "model-C"))
            delay(150)
            assertEquals(listOf("model-A"), prepared.toList())
            assertEquals(listOf(10 to "model-A"), pages.toList())
            assertEquals(listOf(false, true, true), queue.jobs.value.map { it.queued })
            assertEquals(listOf(7, 2, 5), queue.jobs.value.map { it.chapterIndex })
            release.complete(Unit)
            awaitIdle(queue)
            assertEquals(listOf(10 to "model-A", 11 to "model-A", 20 to "model-B", 21 to "model-B", 30 to "model-C"), pages.toList())
        } finally {
            release.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun duplicateChapterIsNotReplaced_andCancellingWaitingChapterDoesNotStopCurrentChapter() = runBlocking {
        val scope = scope()
        val release = CompletableDeferred<Unit>()
        try {
            val started = CompletableDeferred<Unit>()
            val pages = Collections.synchronizedList(mutableListOf<Int>())
            val queue = SrChapterQueue<Unit>(scope, processPage = { page, _ ->
                pages += page
                if (page == 1) { started.complete(Unit); release.await() }
            })
            assertTrue(queue.submit(0, listOf(1, 2), Unit))
            withTimeout(4_000) { started.await() }
            assertTrue(queue.submit(1, listOf(3), Unit))
            assertTrue(queue.submit(2, listOf(4), Unit))
            assertFalse(queue.submit(0, listOf(99), Unit))
            assertFalse(queue.submit(1, listOf(98), Unit))
            queue.cancel(1)
            assertEquals(listOf(0, 2), queue.jobs.value.map { it.chapterIndex })
            assertFalse(queue.jobs.value.first().stopping)
            release.complete(Unit)
            awaitIdle(queue)
            assertEquals(listOf(1, 2, 4), pages.toList())
        } finally {
            release.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun cancellingCurrentChapterWaitsForCleanup_thenStartsNextChapter() = runBlocking {
        val scope = scope()
        val cleanup = CompletableDeferred<Unit>()
        try {
            val started = CompletableDeferred<Unit>()
            val stopping = CompletableDeferred<Unit>()
            val pages = Collections.synchronizedList(mutableListOf<Int>())
            val queue = SrChapterQueue<Unit>(scope, processPage = { page, _ ->
                pages += page
                if (page == 1) {
                    started.complete(Unit)
                    try { CompletableDeferred<Unit>().await() }
                    finally {
                        withContext(NonCancellable) { stopping.complete(Unit); cleanup.await() }
                    }
                }
            })
            queue.submit(0, listOf(1, 2), Unit)
            withTimeout(4_000) { started.await() }
            queue.submit(1, listOf(3), Unit)
            queue.cancel(0)
            withTimeout(4_000) { stopping.await() }
            delay(150)
            assertTrue(queue.jobs.value.first().stopping)
            assertTrue(queue.jobs.value.last().queued)
            assertFalse(queue.submit(0, listOf(99), Unit))
            assertEquals(listOf(1), pages.toList())
            cleanup.complete(Unit)
            awaitIdle(queue)
            assertEquals(listOf(1, 3), pages.toList())
            assertTrue(queue.submit(0, listOf(4), Unit))
            awaitIdle(queue)
            assertEquals(listOf(1, 3, 4), pages.toList())
        } finally {
            cleanup.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun cancelAllDropsWaitingChapters_andJoinWaitsForNativeCleanup() = runBlocking {
        val scope = scope()
        val cleanup = CompletableDeferred<Unit>()
        try {
            val started = CompletableDeferred<Unit>()
            val stopping = CompletableDeferred<Unit>()
            val joined = CompletableDeferred<Unit>()
            val pages = Collections.synchronizedList(mutableListOf<Int>())
            val queue = SrChapterQueue<Unit>(scope, processPage = { page, _ ->
                pages += page
                started.complete(Unit)
                try { CompletableDeferred<Unit>().await() }
                finally { withContext(NonCancellable) { stopping.complete(Unit); cleanup.await() } }
            })
            queue.submit(0, listOf(1, 2), Unit)
            withTimeout(4_000) { started.await() }
            queue.submit(1, listOf(3), Unit)
            val shutdown = launch { queue.cancelAllAndJoin(); joined.complete(Unit) }
            withTimeout(4_000) { stopping.await() }
            assertEquals(listOf(0), queue.jobs.value.map { it.chapterIndex })
            assertTrue(queue.jobs.value.single().stopping)
            assertFalse(joined.isCompleted)
            cleanup.complete(Unit)
            withTimeout(4_000) { shutdown.join() }
            assertTrue(queue.jobs.value.isEmpty())
            assertEquals(listOf(1), pages.toList())
        } finally {
            cleanup.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun failedPreflightSkipsChapter_pageFailureContinues_andProgressCountsSettledPages() = runBlocking {
        val scope = scope()
        val begin = CompletableDeferred<Unit>()
        try {
            val pages = Collections.synchronizedList(mutableListOf<Int>())
            val failures = Collections.synchronizedList(mutableListOf<Int?>())
            val progress = Collections.synchronizedList(mutableListOf<SrChapterQueue.Progress>())
            val queue = SrChapterQueue(
                scope = scope,
                prepare = { request: String -> begin.await(); request != "missing" },
                processPage = { page, _ ->
                    pages += page
                    if (page == 2) error("page decode failed")
                },
                onFailure = { _, page, _ -> failures += page },
                onChanged = { jobs -> progress.addAll(jobs) },
            )
            queue.submit(0, listOf(1), "missing")
            queue.submit(1, listOf(2, 3), "valid")
            begin.complete(Unit)
            awaitIdle(queue)
            assertEquals(listOf(2, 3), pages.toList())
            assertEquals(listOf(2), failures.toList())
            assertTrue(progress.any { it.chapterIndex == 1 && it.done == 2 && it.total == 2 })
        } finally {
            begin.complete(Unit)
            scope.cancel()
        }
    }
}
