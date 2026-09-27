package com.moe.starflow.novel.translate

import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.model.NovelFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **错峰并发**的批调度守卫（纯 JVM，虚拟时间）。
 *
 * 用户口径：「最多 N 个批同时在飞，**依次错开 500ms** 启动一个；第 1 个结束后**立刻**补第 N+1 个，
 * 始终保持 N 个在飞直到没有后续批」。这里就是那条口径的回归：
 * - 前 N 个是**错峰**铺开的（不是同一瞬间全发出去）
 * - 占满之后**不再多发**（要等有槽位）
 * - 有批一结束就**立刻**补位（不等满一个错峰周期）
 * - 并发 == 1（本地引擎）→ 全程最多 1 个在飞（天然串行）
 * - 并发下**失败的批同样不会被重复挑中**（[NovelTranslationQueue.failedAnchors] 语义未变）
 */
class NovelTranslationConcurrencyTest {

    private fun book() = ImportedNovel(
        id = 1, title = "t", localRoot = "/tmp", format = NovelFormat.TXT, chapterCount = 3, addedAt = 1,
    )

    private fun queue(
        scope: CoroutineScope,
        translator: NovelBatchTranslator,
        translated: MutableSet<Int>,
        page: () -> List<Int>,
        chapterSize: Int,
        batchSize: Int,
        concurrency: Int,
    ) = NovelTranslationQueue(
        scope = scope,
        translator = translator,
        paragraphsOf = { _, _ ->
            (0 until chapterSize).map { NovelParagraph(it, NovelParagraphType.TEXT, "p$it") }
        },
        sourceLang = { "en" },
        targetLang = { "zh" },
        translatorName = { "fake" },
        batchSize = { batchSize },
        debounceMs = { 0 },
        currentPageParaIndexes = page,
        chapterParaIndexes = { _, _ -> (0 until chapterSize).toList() },
        translatedIndexes = { _, _ -> translated.toSet() },
        concurrency = { concurrency },
    )

    /** 记录「第几批在什么时刻启动」「同时在飞的最大个数」。 */
    private class Probe(private val holdMs: Long) : NovelBatchTranslator {
        val starts = mutableListOf<Pair<Long, Int>>()
        var now: () -> Long = { 0 }
        var maxInflight = 0
        var inflight = 0
        var failAll = false

        override suspend fun translateBatch(
            book: ImportedNovel,
            chapterIndex: Int,
            paragraphs: List<NovelParagraph>,
            paraIndexes: List<Int>,
            sourceLang: String,
            targetLang: String,
            translatorName: String,
        ): NovelBatchResult {
            starts += now() to paraIndexes.first()
            inflight++
            maxInflight = maxOf(maxInflight, inflight)
            try {
                if (holdMs > 0) delay(holdMs)
                if (failAll) return NovelBatchResult(emptyMap(), "fake 失败")
                return NovelBatchResult(paraIndexes.associateWith { "译$it" })
            } finally {
                inflight--
            }
        }
    }

    @Test
    fun `并发按 500ms 错峰铺开且占满后立刻补位`() = runTest {
        val probe = Probe(holdMs = 3_000).apply { now = { testScheduler.currentTime } }
        val translated = mutableSetOf<Int>()
        val q = queue(
            backgroundScope, probe, translated,
            page = { (0..9).toList() }, chapterSize = 10, batchSize = 1, concurrency = 3,
        )

        q.start(book(), NovelTranslateMode.AUTO, currentChapter = { 0 }) { _, r ->
            translated += r.translations.keys
        }

        // ① 首批立刻发车（首批不等错峰）
        advanceTimeBy(50)
        assertEquals("首批立刻发车", listOf(0L to 0), probe.starts)

        // ② 第 2、3 个按 500ms 错峰铺开（不是同一瞬间全发出去）
        advanceTimeBy(1_100)
        assertEquals(listOf(0, 1, 2), probe.starts.map { it.second })
        assertEquals("错峰间隔 500ms", listOf(500L, 500L), probe.starts.zipWithNext { a, b -> b.first - a.first })

        // ③ 占满之后不再多发（等到有槽位为止）—— 走到 2950ms（第一个批 3000ms 才结束）
        advanceTimeBy(1_800)
        assertEquals("N 个在飞时不许再发第 N+1 个", 3, probe.starts.size)
        assertEquals("同时在飞的最大个数 = 并发数", 3, probe.maxInflight)

        // ④ 第 1 个结束（t=3000）→ **立刻**补第 4 个（最多一个槽位轮询周期 60ms 的延迟）
        advanceTimeBy(200)
        assertEquals("有批结束就补位", 4, probe.starts.size)
        assertEquals("补的是第 4 个批", 3, probe.starts[3].second)
        val gapSinceFirstDone = probe.starts[3].first - 3_000
        assertTrue(
            "补位必须紧跟第一个结束（实测 ${probe.starts[3].first}ms，第一个 3000ms 结束）",
            gapSinceFirstDone in 0..120,
        )

        q.stop()
    }

    /** 并发 == 1（本地引擎 `novelConcurrency()` 返回 1）→ 全程最多 1 个在飞 = 天然串行。 */
    @Test
    fun `本地引擎并发为 1 时天然串行`() = runTest {
        val probe = Probe(holdMs = 200).apply { now = { testScheduler.currentTime } }
        val translated = mutableSetOf<Int>()
        val q = queue(
            backgroundScope, probe, translated,
            page = { (0..5).toList() }, chapterSize = 6, batchSize = 1, concurrency = 1,
        )

        q.start(book(), NovelTranslateMode.AUTO, currentChapter = { 0 }) { _, r ->
            translated += r.translations.keys
        }
        advanceTimeBy(30_000)

        assertEquals("页内 6 段都要翻到（队列不能被并发改动卡住）", 6, probe.starts.size)
        assertEquals("本地引擎全程只有一个批在飞", 1, probe.maxInflight)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), probe.starts.map { it.second })
        q.stop()
    }

    /** 并发下失败的批同样**不会**被重复挑中（`failedAnchors` 记账语义没变）。 */
    @Test
    fun `并发下失败的批不会被重复挑中`() = runTest {
        val probe = Probe(holdMs = 0).apply { now = { testScheduler.currentTime } }
        probe.failAll = true
        val translated = mutableSetOf<Int>()
        val q = queue(
            backgroundScope, probe, translated,
            page = { (0..2).toList() }, chapterSize = 3, batchSize = 1, concurrency = 3,
        )

        q.start(book(), NovelTranslateMode.AUTO, currentChapter = { 0 }) { _, r ->
            translated += r.translations.keys
        }
        advanceTimeBy(5_000)
        assertEquals("三段各失败一次", 3, probe.starts.size)

        advanceTimeBy(60_000)
        assertEquals("失败后不该继续重挑同一批", 3, probe.starts.size)
        q.stop()
    }

    /**
     * **真并发**（多线程 + 真正的挂起）下：账本不会 `ConcurrentModificationException`、
     * 批计数不丢、同一段不会被两个工人同时翻。
     *
     * ⚠️ 这条不能用虚拟时间：要的就是"工人收尾（改账本）"与"调度迭代账本"撞在一起。
     * 触发条件很实际 —— queue 的 scope 是应用级 `Dispatchers.IO/Default`，而 `translateBatch`
     * 内部还会 `withContext(IO)`。CME 抛在 `launch` 里没人接就是**崩进程**。
     */
    @Test
    fun `多线程收尾不会 CME 且批计数守恒`() {
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() +
                kotlinx.coroutines.Dispatchers.Default +
                kotlinx.coroutines.CoroutineExceptionHandler { _, e -> errors += e },
        )
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val translated = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val paragraphs = (0 until 60).map { NovelParagraph(it, NovelParagraphType.TEXT, "p$it") }

        val t = object : NovelBatchTranslator {
            override suspend fun translateBatch(
                book: ImportedNovel,
                chapterIndex: Int,
                paragraphs: List<NovelParagraph>,
                paraIndexes: List<Int>,
                sourceLang: String,
                targetLang: String,
                translatorName: String,
            ): NovelBatchResult {
                calls.incrementAndGet()
                // 挂起 + 切线程：完成回调真的落在别的线程上（这才是并发账本被撞的场景）
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Thread.sleep(2)
                }
                return NovelBatchResult(paraIndexes.associateWith { "译$it" })
            }
        }

        val q = NovelTranslationQueue(
            scope = scope,
            translator = t,
            paragraphsOf = { _, _ -> paragraphs },
            sourceLang = { "en" },
            targetLang = { "zh" },
            translatorName = { "fake" },
            batchSize = { 2 },
            debounceMs = { 0 },
            currentPageParaIndexes = { paragraphs.map { it.index } },
            chapterParaIndexes = { _, _ -> paragraphs.map { it.index } },
            translatedIndexes = { _, _ -> translated.toSet() },
            concurrency = { 4 },
        )
        try {
            q.start(book(), NovelTranslateMode.AUTO, currentChapter = { 0 }) { _, r ->
                translated += r.translations.keys
            }
            val deadline = System.currentTimeMillis() + 20_000
            while (translated.size < 60 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            Thread.sleep(200)   // 留一点时间让在途/收尾的账本操作撞一撞

            assertEquals("60 段都要翻到（批计数不能丢）", 60, translated.size)
            assertEquals("每段只发一次请求：60 段 / 每批 2 段 = 30 次（并发去重要靠在飞账本）", 30, calls.get())
            assertTrue("不能有异常（CME 会在这里现形）: $errors", errors.isEmpty())
        } finally {
            q.stop()
            scope.cancel()
        }
    }
}
