package com.moe.starflow.mangaimport.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.concurrent.thread

/**
 * 清单「读-改-写」并发守卫。
 *
 * 背景：导入改成**一个文件/文件夹一个并发任务**（`ImportManager`）之后，多个任务可能同毫秒收尾。
 * `add` 是 `load → 改 → save` 三步、不是原子的：没有同步时两个任务各自读到不含对方的旧列表，
 * 后写的把先写的整条吞掉 —— **书架少一部、占位卡片凭空消失，而且没有任何报错**。
 *
 * 这个用例不靠"运气"：8 线程 × 20 次 add 的循环里必然出现交错，去掉 `@Synchronized` 会红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedMangaStoreConcurrencyTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        ImportedMangaStore.save(context, emptyList())
    }

    private fun manga(id: Long) = ImportedManga(
        id = id,
        title = "漫画$id",
        localRoot = "/data/manga_import/$id",
        isArchive = false,
        coverPath = null,
        pageCount = 3,
        addedAt = id
    )

    @Test
    fun concurrentAdd_neverLosesEntries() {
        val threads = 8
        val perThread = 20

        val workers = (0 until threads).map { t ->
            thread {
                for (i in 0 until perThread) {
                    val id = (t * perThread + i).toLong() + 1
                    ImportedMangaStore.add(context, manga(id))
                }
            }
        }
        workers.forEach { it.join() }

        val loaded = ImportedMangaStore.load(context)
        assertEquals(
            "并发导入的条目一条都不能丢（丢失 = 用户看到书架少书）",
            threads * perThread,
            loaded.size
        )
        assertEquals(
            "id 不能重复",
            threads * perThread,
            loaded.map { it.id }.toSet().size
        )
    }

    /** 并发 add 与 remove 混跑：remove 的条目不能被"复活"，未 remove 的条目不能丢。 */
    @Test
    fun concurrentAddAndRemove_keepsConsistentSet() {
        // 先放 30 条，随后一半线程删偶数、一半线程加新条目
        (1L..30L).forEach { ImportedMangaStore.add(context, manga(it)) }

        val removers = (2L..30L step 2).map { id ->
            thread { ImportedMangaStore.remove(context, id) }
        }
        val adders = (100L..109L).map { id ->
            thread { ImportedMangaStore.add(context, manga(id)) }
        }
        (removers + adders).forEach { it.join() }

        val ids = ImportedMangaStore.load(context).map { it.id }.toSet()
        val expected = (1L..30L).filter { it % 2 == 1L }.toSet() + (100L..109L).toSet()
        assertEquals("删掉的不能复活、新加的不能丢", expected, ids)
    }
}
