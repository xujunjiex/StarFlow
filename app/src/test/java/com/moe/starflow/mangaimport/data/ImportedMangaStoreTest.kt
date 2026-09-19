package com.moe.starflow.mangaimport.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedMangaStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun sample(id: Long) = ImportedManga(
        id = id,
        title = "漫画$id",
        localRoot = "/data/manga_import/$id",
        isArchive = id % 2 == 0L,
        coverPath = "/data/covers/$id.jpg",
        pageCount = 10,
        addedAt = 1000L + id
    )

    @org.junit.Before
    fun setUp() {
        // 每个测试方法独立 Application，但 SharedPreferences 需显式清空，避免方法间残留
        ImportedMangaStore.save(context, emptyList())
    }

    @Test
    fun saveThenLoad_roundTrip() {
        val list = listOf(sample(1), sample(2))
        ImportedMangaStore.save(context, list)
        val loaded = ImportedMangaStore.load(context)
        assertEquals(list, loaded)
    }

    @Test
    fun add_appendsToExisting() {
        ImportedMangaStore.save(context, listOf(sample(1)))
        ImportedMangaStore.add(context, sample(2))
        val loaded = ImportedMangaStore.load(context)
        assertEquals(listOf(sample(1), sample(2)), loaded)
    }

    @Test
    fun remove_deletesById() {
        ImportedMangaStore.save(context, listOf(sample(1), sample(2)))
        ImportedMangaStore.remove(context, 1)
        val loaded = ImportedMangaStore.load(context)
        assertEquals(listOf(sample(2)), loaded)
    }

    @Test
    fun update_replacesBySameId() {
        ImportedMangaStore.save(context, listOf(sample(1)))
        val updated = sample(1).copy(pageCount = 99, lastReadPage = 5)
        ImportedMangaStore.update(context, updated)
        val loaded = ImportedMangaStore.load(context)
        assertEquals(updated, loaded.single())
    }

    @Test
    fun load_emptyWhenNeverSaved() {
        ImportedMangaStore.save(context, emptyList())
        assertNull(ImportedMangaStore.load(context).firstOrNull())
    }

    /**
     * `importing` 系列是**瞬态**字段（导入中的占位卡片）：绝不能落盘。
     * 否则进程被杀/重启后书架会留下一条永远「导入中」、没有本地文件的僵尸条目。
     */
    @Test
    fun saveThenLoad_dropsTransientImportState() {
        val placeholder = sample(1).copy(
            localRoot = "",
            importing = true,
            importPhase = ImportPhase.COPYING,
            importPercent = 42
        )
        ImportedMangaStore.save(context, listOf(placeholder))

        val loaded = ImportedMangaStore.load(context).single()
        assertEquals(sample(1).copy(localRoot = ""), loaded)
        assertEquals(false, loaded.importing)
        assertNull(loaded.importPhase)
        assertEquals(-1, loaded.importPercent)
    }
}
