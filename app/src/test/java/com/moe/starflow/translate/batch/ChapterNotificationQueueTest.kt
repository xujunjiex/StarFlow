package com.moe.starflow.translate.batch

import org.junit.Assert.*
import org.junit.Test

class ChapterNotificationQueueTest {
    private fun job(chapter: Int, state: ChapterJobState = ChapterJobState.QUEUED, done: Int = 0, total: Int = 10) = ActiveChapterJob(
        TranslationJobKind.MANGA, 1, "Book", chapter, "Chapter $chapter", total, done, state,
    )

    @Test fun queueProgressCoversAllChapters_andRetainsCompletedChaptersAfterHandoff() {
        val queue = ChapterNotificationQueue()
        val first = job(0, ChapterJobState.RUNNING, 5)
        val second = job(1)
        val third = job(2)
        val initial = queue.update(listOf(first, second, third))
        assertEquals(first, initial.current)
        assertEquals(3_000, initial.maximum)
        assertEquals(500, initial.progress)
        val after = queue.update(listOf(second.copy(state = ChapterJobState.RUNNING, done = 2), third))
        assertEquals(3, after.count)
        assertEquals(1, after.finished)
        assertEquals(1_200, after.progress)
        assertTrue(after.multiple)
        val last = queue.update(listOf(third.copy(state = ChapterJobState.RUNNING, done = 5)))
        assertEquals(2_500, last.progress)
        assertEquals(3, last.count)
    }

    @Test fun unknownPreparationTotalDoesNotInventCompletion_andUsesKnownTotalWhenReady() {
        val queue = ChapterNotificationQueue()
        assertEquals(0, queue.update(listOf(job(0, total = 0))).progress)
        assertEquals(250, queue.update(listOf(job(0, ChapterJobState.RUNNING, done = 5, total = 20))).progress)
    }

    @Test fun runningChapterWinsOverEarlierQueuedPreparation_andCancellationCleanupWinsOverNextQueuedChapter() {
        val queue = ChapterNotificationQueue()
        val waiting = job(0).copy(hasInFlight = true)
        val running = job(1, ChapterJobState.RUNNING)
        assertEquals(running, queue.update(listOf(waiting, running)).current)
        val stopping = running.copy(state = ChapterJobState.CANCELLED, hasInFlight = true, stopping = true)
        assertEquals(stopping, queue.update(listOf(waiting, stopping)).current)
    }

    @Test fun onlyPausedChaptersRemainVisible_withoutDuplicatingQueueEntries() {
        val queue = ChapterNotificationQueue()
        val paused = job(0, ChapterJobState.PAUSED, 2)
        repeat(3) {
            val snapshot = queue.update(listOf(paused))
            assertEquals(1, snapshot.count)
            assertFalse(snapshot.multiple)
            assertEquals(paused, snapshot.current)
            assertEquals(200, snapshot.progress)
        }
    }
}
