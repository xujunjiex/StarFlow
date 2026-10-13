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
        localRoot = com.moe.starflow.library.LibrarySessions.current(ctx).local("novel_import/$id").path,
        format = NovelFormat.TXT,
        chapterCount = 3,
        bookUuid = com.moe.starflow.library.LibrarySessions.current(ctx).uuid("novel", id),
        libraryLocationId = com.moe.starflow.library.LibrarySessions.current(ctx).locationId,
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
            coverPath = com.moe.starflow.library.LibrarySessions.current(ctx).local(".starflow/books/${novel(1, 100).bookUuid}/cover/cover.jpg").path,
            sizeBytes = 12345,
            lastReadChapter = 2,
            lastReadParaIndex = 500,
            lastReadPage = 7,
            lastReadAt = 1_700_000_000_000,
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
        NovelStore.save(ctx, listOf(novel(1, 100)))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { NovelStore.add(ctx, novel(2, 200).copy(localRoot = "")) }
        assertEquals(listOf(1L), NovelStore.load(ctx).map { it.id })
    }

    @Test
    fun `坏 JSON 不会崩只是读回空表`() {
        ctx.getSharedPreferences("novel_shelf", Context.MODE_PRIVATE)
            .edit().putString("imported_novel_list", "{ not json").apply()
        org.junit.Assert.assertThrows(java.io.IOException::class.java) { NovelStore.loadLegacy(ctx) }
    }

    @Test
    fun `未知格式名回退成 TXT 而不是抛异常`() {
        ctx.getSharedPreferences("novel_shelf", Context.MODE_PRIVATE)
            .edit().putString(
                "imported_novel_list",
                """[{"id":1,"title":"x","localRoot":"/tmp/1","format":"MOBI","addedAt":5,"chapterCount":1}]""",
            ).apply()
        assertEquals(NovelFormat.TXT, NovelStore.loadLegacy(ctx).single().format)
    }

    @Test
    fun `旧书架条目没有最近阅读时间时回退为零`() {
        ctx.getSharedPreferences("novel_shelf", Context.MODE_PRIVATE)
            .edit().putString(
                "imported_novel_list",
                """[{"id":1,"title":"x","localRoot":"/tmp/1","format":"TXT","addedAt":5,"chapterCount":1}]""",
            ).apply()

        assertEquals(0, NovelStore.loadLegacy(ctx).single().lastReadAt)
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
        val n = novel(1, 100)
        assertEquals("${n.libraryLocationId}:novel:${n.bookUuid}", n.translationKey)
    }

    /**
     * **回归**：可空字段往返之后不能变成字符串 `"null"`。
     *
     * 写侧是 `put(k, v ?: JSONObject.NULL)`，而 Android 的 `optString` 走 `JSON.toString(obj)`
     * → `String.valueOf(JSONObject.NULL)` → 拿到的是**字面量 "null"**（四字符，`ifBlank` 判不出）。
     * 后果是必现的：退出阅读器就会 `update` → save，此后 `coverPath` 变成 `File("null")`
     * 找不到 —— **已导入的书封面永久消失**，作者栏显示成「null」。
     */
    @Test
    fun `可空字段往返后仍是 null 而不是字符串 null`() {
        NovelStore.save(ctx, listOf(novel(1, 100).copy(author = null, coverPath = null)))

        val got = NovelStore.load(ctx).single()

        assertEquals(null, got.author)
        assertEquals("封面路径不能变成 File(\"null\")", null, got.coverPath)
    }

    /** 非空值往返不能丢（别为了修 null 把正常值也吞掉）。 */
    @Test
    fun `非空的可空字段往返后不丢`() {
        NovelStore.save(ctx, listOf(novel(1, 100).copy(author = "某作者", coverPath = com.moe.starflow.library.LibrarySessions.current(ctx).local(".starflow/books/${novel(1, 100).bookUuid}/cover/1.jpg").path)))

        val got = NovelStore.load(ctx).single()

        assertEquals("某作者", got.author)
        assertEquals(com.moe.starflow.library.LibrarySessions.current(ctx).local(".starflow/books/${got.bookUuid}/cover/1.jpg").path, got.coverPath)
    }

    /**
     * 整份清单解析不了时，原始串必须另存一份 —— 否则这次 save 会把用户仅剩的清单原地抹掉。
     * （`load()` 返回空表 → 书架空 → 下一次 add/update 的 save 覆写。）
     */
    @Test
    fun `清单整体损坏时先备份原始串再覆盖`() {
        ctx.getSharedPreferences("novel_shelf", Context.MODE_PRIVATE)
            .edit().putString("imported_novel_list", "{坏掉的 json").commit()

        org.junit.Assert.assertThrows(java.io.IOException::class.java) { NovelStore.loadLegacy(ctx) }
        assertEquals("{坏掉的 json", ctx.getSharedPreferences("novel_shelf", Context.MODE_PRIVATE)
            .getString("imported_novel_list", null))
    }
}
