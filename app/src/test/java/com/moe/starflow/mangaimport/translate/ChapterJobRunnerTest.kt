package com.moe.starflow.mangaimport.translate

import com.moe.starflow.translate.batch.ChapterJobRunner
import com.moe.starflow.translate.batch.ChapterJobState

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * 章节批量流水线的守卫（纯 JVM，无 Android 依赖）。
 *
 * 钉死四条用户口径：
 * 1. **OCR 串行**（引擎单例，绝不能并发）
 * 2. **翻译并发** 且 **OCR 与 API 请求重叠**（「第一页 OCR 结束发出请求后立刻开始第二页 OCR」）
 * 3. **按章暂停/取消**：暂停只停取页（队列留着＝「等待」），取消丢队列、在途页跑完
 * 4. **暂停/取消不影响别的章**（多章同时提交时按提交顺序排队）
 */
class ChapterJobRunnerTest {

    /** 等 runner 空下来（带超时兜底防挂死）。 */
    private suspend fun awaitIdle(runner: ChapterJobRunner<Int>, timeoutMs: Long = 8_000) {
        withTimeout(timeoutMs) {
            while (runner.isBusy()) delay(10)
        }
    }

    private fun newScope() = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ===== 1. OCR 串行 + 翻译并发 =====

    @Test
    fun ocr_isSerialized_translateIsConcurrent() = runBlocking {
        val scope = newScope()
        try {
            val ocrNow = AtomicInteger()
            val ocrMax = AtomicInteger()
            val trNow = AtomicInteger()
            val trMax = AtomicInteger()
            val runner = ChapterJobRunner(
                scope = scope,
                concurrency = { 3 },
                ocr = { page ->
                    ocrMax.set(max(ocrMax.get(), ocrNow.incrementAndGet()))
                    delay(15)
                    ocrNow.decrementAndGet()
                    page
                },
                translate = { _, _ ->
                    trMax.set(max(trMax.get(), trNow.incrementAndGet()))
                    delay(40)
                    trNow.decrementAndGet()
                    true
                },
            )
            runner.submit(chapterIndex = 0, pages = (1..9).toList())
            awaitIdle(runner)

            assertEquals("OCR 必须串行（引擎是单例）", 1, ocrMax.get())
            assertTrue("翻译应该并发起来，实测最大并发 ${trMax.get()}", trMax.get() >= 2)
            assertTrue("并发数不得超过设置值 3，实测 ${trMax.get()}", trMax.get() <= 3)
            assertEquals(1, runner.jobs.value.size)
            assertEquals(ChapterJobState.DONE, runner.jobs.value.single().state)
            assertEquals(9, runner.jobs.value.single().done)
        } finally {
            scope.cancel()
        }
    }

    // ===== 2. OCR 与翻译重叠（流水线） =====

    @Test
    fun ocrOfNextPage_startsBeforeTranslateOfPreviousFinishes() = runBlocking {
        val scope = newScope()
        try {
            val pages = 6
            val ocrFinished = AtomicInteger()
            val sawOverlap = AtomicBoolean(false)
            val runner = ChapterJobRunner(
                scope = scope,
                concurrency = { 3 },
                ocr = { page ->
                    delay(25)
                    ocrFinished.incrementAndGet()
                    page
                },
                translate = { _, _ ->
                    // 翻译开始时如果 OCR 还没全做完 → 说明两者重叠了
                    if (ocrFinished.get() < pages) sawOverlap.set(true)
                    delay(30)
                    true
                },
            )
            runner.submit(chapterIndex = 0, pages = (1..pages).toList())
            awaitIdle(runner)
            assertTrue("OCR 必须与翻译请求重叠（否则就是逐页串行）", sawOverlap.get())
        } finally {
            scope.cancel()
        }
    }

    // ===== 3. 等待标签 =====

    @Test
    fun waitingPages_reflectsQueuedPagesOnly() = runBlocking {
        val scope = newScope()
        try {
            val runner = ChapterJobRunner(
                scope = scope,
                concurrency = { 1 },
                ocr = { page -> delay(60); page },
                translate = { _, _ -> delay(60); true },
            )
            runner.submit(chapterIndex = 0, pages = listOf(1, 2, 3, 4))
            // 刚开始：除了在途的那一页，其它都还在「等待」
            delay(30)
            val waiting = runner.waitingPages.value
            assertTrue("应有等待中的页，实际 $waiting", waiting.isNotEmpty())
            assertTrue("已开始翻的页不该标等待，实际 $waiting", waiting.size < 4)
            awaitIdle(runner)
            assertTrue("跑完就没有等待页了", runner.waitingPages.value.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    // ===== 4. 按章暂停：队列留着、别的章继续 =====

    @Test
    fun pause_stopsThatChapterButOthersKeepGoing() = runBlocking {
        val scope = newScope()
        try {
            val translated = Collections.synchronizedList(mutableListOf<Int>())
            val runner = ChapterJobRunner(
                scope = scope,
                concurrency = { 1 },
                ocr = { page -> delay(10); page },
                translate = { page, _ ->
                    translated += page
                    delay(25)
                    true
                },
            )
            runner.submit(chapterIndex = 0, pages = listOf(1, 2, 3, 4))
            runner.submit(chapterIndex = 1, pages = listOf(11, 12))
            delay(40)
            val beforePause = runner.jobOf(0)?.done ?: 0
            runner.pause(0)
            // ⚠️ **在途的那一页照旧跑完**（设计口径：暂停只停"取页"，已经发出去的请求不作废）——
            // 并发 1 时最多再多落地 1 页。真正要钉死的是「之后就再也不前进」。
            delay(250)
            val settledAfterPause = runner.jobOf(0)?.done ?: 0
            delay(250)
            val settledLater = runner.jobOf(0)?.done ?: 0

            assertEquals(ChapterJobState.PAUSED, runner.stateOf(0))
            assertTrue(
                "暂停后最多只该有在途的 1 页落地：before=$beforePause after=$settledAfterPause",
                settledAfterPause <= beforePause + 1
            )
            assertEquals("暂停后不该再前进", settledAfterPause, settledLater)
            assertTrue("暂停的章仍有等待页", runner.waitingPages.value.any { it in 1..4 })
            assertEquals("暂停的那章不该挡住别的章", ChapterJobState.DONE, runner.stateOf(1))

            runner.resume(0)
            awaitIdle(runner)
            assertEquals(ChapterJobState.DONE, runner.stateOf(0))
            assertTrue("恢复后 1..4 都要翻到，实际 $translated", translated.containsAll(listOf(1, 2, 3, 4)))
        } finally {
            scope.cancel()
        }
    }

    // ===== 5. 取消：丢等待、留已完成 =====

    @Test
    fun cancel_dropsWaitingPages_keepsFinishedOnes() = runBlocking {
        val scope = newScope()
        try {
            val finishRecords = Collections.synchronizedList(mutableListOf<Triple<Int, Int, Boolean>>())
            val runner = ChapterJobRunner(
                scope = scope,
                concurrency = { 1 },
                ocr = { page -> delay(10); page },
                translate = { _, _ -> delay(40); true },
                onJobFinished = { chapter, ok, total, cancelled -> finishRecords += Triple(chapter, ok, cancelled) },
            )
            runner.submit(chapterIndex = 0, pages = listOf(1, 2, 3, 4, 5, 6))
            delay(90)
            val doneBefore = runner.jobOf(0)?.done ?: 0
            assertTrue("取消前应该已经翻了一部分", doneBefore >= 1)
            runner.cancel(0)
            awaitIdle(runner)

            assertEquals(ChapterJobState.CANCELLED, runner.stateOf(0))
            assertTrue("取消后不该再有等待页", runner.waitingPages.value.isEmpty())
            assertTrue("已完成的页数不该被取消抹掉", (runner.jobOf(0)?.done ?: 0) >= doneBefore)
            assertEquals(1, finishRecords.size)
            assertEquals(0, finishRecords.single().first)
            assertTrue("取消要如实上报 cancelled=true", finishRecords.single().third)
        } finally {
            scope.cancel()
        }
    }

    // ===== 6. 收尾后可再次提交（通道重建） =====

    @Test
    fun canSubmitAgainAfterPipelineFinished() = runBlocking {
        val scope = newScope()
        try {
            val runner = ChapterJobRunner(
                scope = scope,
                concurrency = { 2 },
                ocr = { page -> delay(5); page },
                translate = { _, _ -> delay(5); true },
            )
            runner.submit(0, listOf(1, 2))
            awaitIdle(runner)
            assertFalse(runner.isBusy())

            // ⚠️ 通道是一次性的：跑完后再提交必须能重新工作（换新通道）
            runner.submit(1, listOf(3, 4))
            awaitIdle(runner)
            assertEquals(ChapterJobState.DONE, runner.stateOf(1))
            assertEquals(2, runner.jobOf(1)?.done)
        } finally {
            scope.cancel()
        }
    }

    // ===== 7. 在途项可查（宿主用它避免把别人在翻的页当残留状态清掉） =====

    @Test
    fun inFlightPages_exposesRunningItems() = runBlocking {
        val scope = newScope()
        try {
            val gate = java.util.concurrent.CountDownLatch(1)
            val runner = ChapterJobRunner(
                scope = scope,
                concurrency = { 1 },
                ocr = { page -> delay(5); page },
                translate = { _, _ ->
                    // 卡住翻译阶段，让在途项稳定停留在翻译中
                    withContext(Dispatchers.IO) { gate.await() }
                    true
                },
            )
            runner.submit(0, listOf(7, 8, 9))
            // 等到真的有一项进了翻译阶段
            withTimeout(4_000) {
                while (runner.inFlightPages().isEmpty()) delay(10)
            }
            val busy = runner.inFlightPages()
            assertTrue("在途项要能被查到，实际 $busy", busy.isNotEmpty())
            assertTrue("在途页号必须在提交的范围内，实际 $busy", busy.all { it in 7..9 })

            gate.countDown()
            awaitIdle(runner)
            assertTrue("收尾后在途必须清空", runner.inFlightPages().isEmpty())
        } finally {
            scope.cancel()
        }
    }

    // ===== 8. 掐掉流水线要给被中止的章派发收尾事件 =====

    @Test
    fun shutdown_reportsAbortedChaptersAsCancelled() = runBlocking {
        val scope = newScope()
        try {
            val finishRecords = Collections.synchronizedList(mutableListOf<Triple<Int, Int, Boolean>>())
            val gate = java.util.concurrent.CountDownLatch(1)
            val runner = ChapterJobRunner(
                scope = scope,
                concurrency = { 1 },
                ocr = { page -> delay(5); page },
                translate = { _, _ ->
                    withContext(Dispatchers.IO) { gate.await() }
                    true
                },
                onJobFinished = { chapter, ok, total, cancelled -> finishRecords += Triple(chapter, ok, cancelled) },
            )
            runner.submit(0, listOf(1, 2, 3))
            runner.submit(1, listOf(4, 5, 6))
            withTimeout(4_000) {
                while (runner.jobs.value.none { it.state == ChapterJobState.RUNNING }) delay(10)
            }
            // ⚠️ 关掉流水线必须给**每个还没收尾的章**报一次 cancelled：否则那一刻标着「翻译中」的
            // 行没人负责退回「未翻译」→ 永久卡在翻译中、面板看着像还在跑
            runner.shutdown()

            assertEquals("两个在跑的章都要被中止上报", 2, finishRecords.size)
            assertTrue("中止上报必须带 cancelled=true", finishRecords.all { it.third })
            assertTrue("收尾后在途必须清空", runner.inFlightPages().isEmpty())
            assertFalse(runner.isBusy())
            gate.countDown()
        } finally {
            scope.cancel()
        }
    }
}
