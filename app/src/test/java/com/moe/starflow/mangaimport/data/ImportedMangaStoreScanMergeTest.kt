package com.moe.starflow.mangaimport.data

import android.content.Context
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
class ImportedMangaStoreScanMergeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun manga(id: Long) = ImportedManga(
        id = id, title = "Manga $id", localRoot = File(StorageDirStore.root(context), "$id.cbz").path,
        isArchive = true, coverPath = null, pageCount = 4, addedAt = id,
    )

    @Before
    fun setUp() = ImportedMangaStore.save(context, emptyList())

    @Test
    fun mergePreservesLatestShelfEditsAndDeduplicatesCanonicalPaths() {
        val original = manga(1)
        ImportedMangaStore.add(context, original)
        val edited = original.copy(title = "Edited", lastReadPage = 3)
        ImportedMangaStore.update(context, edited)
        val duplicate = manga(2).copy(localRoot = File(StorageDirStore.root(context), "sub/../1.cbz").path)

        assertEquals(1, ImportedMangaStore.mergeScanned(context, listOf(original, duplicate, manga(3))))
        assertEquals(listOf("Edited", "Manga 3"), ImportedMangaStore.load(context).map { it.title })
    }

    @Test
    fun concurrentManualImportsAndScanMergesLoseNoManga() {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0..3).map { worker ->
                pool.submit {
                    start.await()
                    for (index in 1..20) {
                        val entry = manga((worker * 20 + index).toLong())
                        if (worker % 2 == 0) ImportedMangaStore.add(context, entry)
                        else ImportedMangaStore.mergeScanned(context, listOf(entry))
                    }
                }
            }
            start.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
            assertEquals((1L..80L).toSet(), ImportedMangaStore.load(context).map { it.id }.toSet())
        } finally {
            pool.shutdownNow()
        }
    }
}
