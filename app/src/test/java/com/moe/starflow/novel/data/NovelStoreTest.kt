package com.moe.starflow.novel.data

import android.content.Context
import com.moe.starflow.mangaimport.data.ImportPhase
import com.moe.starflow.novel.model.NovelFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelStoreTest {

    // 项目里没有 androidx.test.core，既有测试统一用 Robolectric 的 RuntimeEnvironment
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun novel(id: Long, at: Long) = ImportedNovel(
        id = id,
        title = "书$id",
        localRoot = "/tmp/$id",
        format = NovelFormat.TXT,
        chapterCount = 3,
        addedAt = at,
    )

    @Before
    fun clear() {
        NovelStore.save(ctx, emptyList())
    }

    @Test
    fun `存取往返保留全部持久化字段`() {
        val n = novel(1, 100).copy(
            author = "某作者",
            description = "简介",
            format = NovelFormat.EPUB,
            coverPath = "/tmp/cover.jpg",
            sizeBytes = 12345,
            lastReadChapter = 2,
            lastReadParaIndex = 500,
            lastReadPage = 7,
        )
        NovelStore.save(ctx, listOf(n))
        assertEquals(listOf(n), NovelStore.load(ctx))
    }

    /**
     * `lost` / `importing` 这类**瞬态**字段不能落盘：它们是「按当下磁盘状态实时推导」的结论，
     * 存下来就会变成过期缓存（文件后来又出现了、导入早就结束了，标记还挂着）。
     */
    @Test
    fun `瞬态字段不持久化`() {
        val n = novel(1, 100).copy(
            lost = true,
            importing = true,
            importPhase = ImportPhase.COPYING,
            importPercent = 42,
        )
        NovelStore.save(ctx, listOf(n))
        val loaded = NovelStore.load(ctx).single()
        assertFalse("lost 必须重置", loaded.lost)
        assertFalse("importing 必须重置", loaded.importing)
        assertEquals(null, loaded.importPhase)
        assertEquals(-1, loaded.importPercent)
    }

    @Test
    fun `update 按 id 替换且不动别的条目`() {
        NovelStore.save(ctx, listOf(novel(1, 100), novel(2, 200)))
        NovelStore.update(ctx, novel(1, 100).copy(title = "改名了"))
        val list = NovelStore.load(ctx)
        assertEquals("改名了", list.first { it.id == 1L }.title)
        assertEquals("书2", list.first { it.id == 2L }.title)
    }

    @Test
    fun `update 不存在的 id 是空操作`() {
        NovelStore.save(ctx, listOf(novel(1, 100)))
        NovelStore.update(ctx, novel(9, 900))
        assertEquals(listOf(1L), NovelStore.load(ctx).map { it.id })
    }

    @Test
    fun `remove 只删指定 id`() {
        NovelStore.save(ctx, listOf(novel(1, 100), novel(2, 200)))
        NovelStore.remove(ctx, 1)
        assertEquals(listOf(2L), NovelStore.load(ctx).map { it.id })
    }

    @Test
    fun `没有本地路径的条目读不回来`() {
        NovelStore.save(ctx, listOf(novel(1, 100), novel(2, 200).copy(localRoot = "")))
        assertEquals(listOf(1L), NovelStore.load(ctx).map { it.id })
    }

    @Test
    fun `坏 JSON 不会崩只是读回空表`() {
        ctx.getSharedPreferences("novel_shelf", Context.MODE_PRIVATE)
            .edit().putString("imported_novel_list", "{ not json").apply()
        assertEquals(emptyList<ImportedNovel>(), NovelStore.load(ctx))
    }

    @Test
    fun `未知格式名回退成 TXT 而不是抛异常`() {
        ctx.getSharedPreferences("novel_shelf", Context.MODE_PRIVATE)
            .edit().putString(
                "imported_novel_list",
                """[{"id":1,"title":"x","localRoot":"/tmp/1","format":"MOBI","addedAt":5,"chapterCount":1}]""",
            ).apply()
        assertEquals(NovelFormat.TXT, NovelStore.load(ctx).single().format)
    }

    /**
     * 并发 add 不丢数据。
     *
     * ⚠️ 这条是 `@Synchronized` 的守卫：`add` 是「load → 改 → save」三步，去掉同步后
     * 两个线程会读到同一份旧列表、各自加一条、后写的把先写的整条覆盖掉 → 书架少书。
     */
    @Test
    fun `并发 add 不丢数据`() {
        val threads = 8
        val perThread = 20
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)
        repeat(threads) { t ->
            pool.execute {
                try {
                    repeat(perThread) { i ->
                        val id = (t * perThread + i).toLong()
                        NovelStore.add(ctx, novel(id, id))
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue("并发写入超时", latch.await(60, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(threads * perThread, NovelStore.load(ctx).size)
    }

    @Test
    fun `translationKey 就是 addedAt`() {
        assertEquals("100", novel(1, 100).translationKey)
    }
}
