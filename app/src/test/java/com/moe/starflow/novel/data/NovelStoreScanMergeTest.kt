package com.moe.starflow.novel.data

import android.content.Context
import com.moe.starflow.novel.model.NovelFormat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelStoreScanMergeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun book(id: Long) = ImportedNovel(
        id = id, title = "Book $id", localRoot = File(NovelStorageDir.root(context), "$id.txt").path,
        format = NovelFormat.TXT, chapterCount = 1, addedAt = id,
    )

    @Before
    fun setUp() = NovelStore.save(context, emptyList())

    @Test
    fun mergePreservesLatestShelfEditsAndDeduplicatesCanonicalPaths() {
        val original = book(1)
        NovelStore.add(context, original)
        val edited = original.copy(title = "Edited", lastReadChapter = 3)
        NovelStore.update(context, edited)
        val duplicate = book(2).copy(localRoot = File(NovelStorageDir.root(context), "sub/../1.txt").path)

        assertEquals(1, NovelStore.mergeScanned(context, listOf(original, duplicate, book(3))))
        assertEquals(listOf("Edited", "Book 3"), NovelStore.load(context).map { it.title })
    }

    @Test
    fun concurrentManualImportsAndScanMergesLoseNoBooks() {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0..3).map { worker ->
                pool.submit {
                    start.await()
                    for (index in 1..20) {
                        val novel = book((worker * 20 + index).toLong())
                        if (worker % 2 == 0) NovelStore.add(context, novel)
                        else NovelStore.mergeScanned(context, listOf(novel))
                    }
                }
            }
            start.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
            assertEquals((1L..80L).toSet(), NovelStore.load(context).map { it.id }.toSet())
        } finally {
            pool.shutdownNow()
        }
    }
}
