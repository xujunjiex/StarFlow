package com.moe.starflow.novel.translate

import android.app.Application
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.model.NovelFormat
import com.moe.starflow.translate.batch.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NovelChapterPreparationTest {
    private fun host(scope: CoroutineScope, load: suspend (Int) -> List<NovelParagraph>) = NovelChapterJobHost(
        RuntimeEnvironment.getApplication(),
        ImportedNovel(42, "Book", localRoot = "unused", format = NovelFormat.TXT, chapterCount = 3, addedAt = 1),
        scope, load,
    )

    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(4_000) {
        while (!condition()) delay(10)
    }

    @Test
    fun slowPreparationIsImmediatelyVisibleAndRetainedWithoutReader_thenPauseTransfersToRunner() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val host = host(scope) { started.complete(Unit); release.await(); listOf(NovelParagraph(0, NovelParagraphType.TEXT, "A full paragraph for translation")) }
        try {
            host.startChapter(0, "Chapter 1", retranslateAll = true)
            assertTrue(host.isRunning())
            assertFalse(host.uiAttached)
            assertEquals(ChapterJobState.QUEUED, host.jobOf(0)!!.state)
            val preparing = host.snapshot().single()
            assertTrue(preparing.isActive)
            assertTrue(preparing.needsCpu)
            assertEquals(0, preparing.total)
            assertFalse(preparing.stageText.isNullOrBlank())
            withTimeout(4_000) { started.await() }
            host.pause(0)
            release.complete(Unit)
            awaitCondition { host.chapterPreparation.value.isEmpty() }
            assertEquals(ChapterJobState.PAUSED, host.jobOf(0)!!.state)
            assertEquals(1, host.jobOf(0)!!.total)
            assertTrue(host.isRunning())
        } finally {
            host.shutdownAndJoin()
            scope.cancel()
        }
    }

    @Test
    fun cancellingPreparationWaitsForCleanupAndCannotSubmitLateWork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val host = host(scope) {
            withContext(NonCancellable) { started.complete(Unit); release.await() }
            listOf(NovelParagraph(0, NovelParagraphType.TEXT, "A paragraph that must never be submitted"))
        }
        try {
            host.startChapter(0, "Chapter 1", retranslateAll = true)
            withTimeout(4_000) { started.await() }
            host.cancel(0)
            assertTrue(host.isRunning())
            assertEquals(ChapterJobState.CANCELLED, host.snapshot().single().state)
            assertTrue(host.snapshot().single().hasInFlight)
            release.complete(Unit)
            awaitCondition { !host.isRunning() }
            assertNull(host.jobOf(0))
            assertTrue(host.snapshot().isEmpty())
        } finally {
            release.complete(Unit)
            host.shutdownAndJoin()
            scope.cancel()
        }
    }

    @Test
    fun duplicateSubmissionDoesNotStartASecondPreparation_andQueuedChapterCanBeCancelled() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val host = host(scope) { index -> calls += index; started.complete(Unit); release.await(); emptyList() }
        try {
            host.startChapter(0, "One", retranslateAll = true)
            withTimeout(4_000) { started.await() }
            host.startChapter(0, "One again", retranslateAll = true)
            host.startChapter(1, "Two", retranslateAll = true)
            assertEquals(listOf(0, 1), host.snapshot().map { it.chapterIndex })
            host.cancel(1)
            release.complete(Unit)
            awaitCondition { !host.isRunning() }
            assertEquals(listOf(0), calls.toList())
        } finally {
            release.complete(Unit)
            host.shutdownAndJoin()
            scope.cancel()
        }
    }

    @Test
    fun cancellingMiddleChapterKeepsLaterPreparationBehindFirstChapter() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val host = host(scope) { chapter ->
            calls += chapter
            if (chapter == 0) { started.complete(Unit); release.await() }
            emptyList()
        }
        try {
            host.startChapter(0, "One", retranslateAll = true)
            withTimeout(4_000) { started.await() }
            host.startChapter(1, "Two", retranslateAll = true)
            host.startChapter(2, "Three", retranslateAll = true)
            host.cancel(1)
            assertNull(withTimeoutOrNull(300) { while (2 !in calls) delay(10); true })
            assertEquals(listOf(0), calls.toList())
            release.complete(Unit)
            awaitCondition { !host.isRunning() }
            assertEquals(listOf(0, 2), calls.toList())
        } finally {
            release.complete(Unit)
            host.shutdownAndJoin()
            scope.cancel()
        }
    }

    @Test
    fun shutdownReleasesCollectorsWithoutCancellingTheApplicationScope() = runBlocking {
        val applicationJob = SupervisorJob()
        val scope = CoroutineScope(applicationJob + Dispatchers.Default)
        val host = host(scope) { emptyList() }
        try {
            val collectors = applicationJob.children.toList()
            assertTrue(collectors.isNotEmpty())
            host.shutdownAndJoin()
            assertTrue(collectors.all { it.isCompleted })
            assertTrue(applicationJob.isActive)
        } finally { scope.cancel() }
    }
}
