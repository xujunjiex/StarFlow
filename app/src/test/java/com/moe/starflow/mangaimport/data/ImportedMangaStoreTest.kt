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

    /**
     * **页序迁移标记必须能落盘**（2026-10 审查发现的真 bug）。
     *
     * 原来 `toJson` 没写 `pageOrderVersion`、`toManga` 也没读 → 读回恒为 0 →
     * `MangaPageOrderMigrator` 的「已经迁过就跳过」判据永远为假 → **每次打开阅读器都会
     * 再跑一次页序置换**，而置换并不幂等 → 译文被一次次挪到别的图上。
     *
     * ⚠️ 这个用例必须用**非 0 的版本号**：全用默认值 0 的话 `0 == 0` 恒真，
     * 跟没测一样（原样本用的是默认值，所以这个 bug 一直在）。
     */
    @Test
    fun saveThenLoad_keepsPageOrderMigrationMarkers() {
        val migrated = sample(1).copy(pageOrderVersion = 1, pageOrderPlan = "1,2,0")
        ImportedMangaStore.save(context, listOf(migrated))

        val loaded = ImportedMangaStore.load(context).single()
        assertEquals("页序版本没落盘 → 每次打开都会重跑迁移", 1, loaded.pageOrderVersion)
        assertEquals("置换记录没落盘 → 中断后无法判断是否已施加过", "1,2,0", loaded.pageOrderPlan)
    }

    /**
     * `setChapters` 只改章节字段，**不能顺手把别的字段回滚**。
     *
     * 章节回填与阅读器自愈都是秒级长循环，用的是循环开始时的清单快照；以前它们走
     * `update(manga.copy(...))` 整条替换，会把这段时间里用户翻页写的 `lastReadPage`、
     * 改过的书名一起回滚（表现是「翻了几页后进度自己退回去」）。
     */
    @Test
    fun setChapters_onlyTouchesTheChaptersField() {
        ImportedMangaStore.save(context, listOf(sample(1).copy(title = "旧名", lastReadPage = 3)))

        // 模拟"回填进行中，用户翻了页并改了名"
        val live = ImportedMangaStore.load(context).single().copy(title = "新名", lastReadPage = 9)
        ImportedMangaStore.update(context, live)

        val chapters = listOf(MangaChapter(number = 1, title = "ch1", startPage = 0, pageCount = 5))
        ImportedMangaStore.setChapters(context, 1L, chapters)

        val loaded = ImportedMangaStore.load(context).single()
        assertEquals("阅读进度不能被章节回填回滚", 9, loaded.lastReadPage)
        assertEquals("书名不能被章节回填回滚", "新名", loaded.title)
        assertEquals(chapters, loaded.chapters)
    }
}
