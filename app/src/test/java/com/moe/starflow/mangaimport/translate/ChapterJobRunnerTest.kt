package com.moe.starflow.mangaimport.translate

import com.moe.starflow.translate.batch.ChapterJobRunner
import com.moe.starflow.translate.batch.ChapterJobState
import com.moe.starflow.translate.batch.ChapterTaskStage

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

    // ===== 9. 每一页都必须结算（防"静默跳过"复活） =====

    /**
     * 用户报的「进度从 0 涨到 10，但什么都没翻、卡片翻完就消失」就是**页被静默结算**：
     * 进度在涨（done+1），可这一页既没翻、也没记失败、日志还是空的。
     *
     * 这条钉死：翻译阶段返回 false（= 这一页没翻出来）时，**每一页仍然要走到结算**
     * （done 必须走满 total、ok 如实为 0），且不能在途表里留下残项。
     */
    @Test
    fun everyPageSettles_evenWhenTranslateFailsImmediately() = runBlocking {
        val scope = newScope()
        try {
            val finished = Collections.synchronizedList(mutableListOf<Triple<Int, Int, Int>>())
            val runner = ChapterJobRunner(
                scope = scope,
                concurrency = { 3 },
                ocr = { page -> delay(5); page },
                // 全部"翻失败"（等价于每页都在翻译阶段直接返回 false）
                translate = { _, _ -> false },
                onJobFinished = { chapter, ok, total, _ -> finished += Triple(chapter, ok, total) },
            )
            runner.submit(0, (1..6).toList())
            awaitIdle(runner)

            val job = runner.jobOf(0)!!
            assertEquals("失败的页也必须结算，否则任务永远收不了尾", 6, job.done)
            assertEquals(ChapterJobState.DONE, job.state)
            assertEquals("收尾要如实上报成功 0 页", Triple(0, 0, 6), finished.single())
            assertTrue("收尾后在途必须清空", runner.inFlightPages().isEmpty())
            assertTrue("收尾后阶段表也要清空", runner.inFlightTasks.value.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    // ===== 10. 阶段（识别中 / 翻译中）可查 =====

    /**
     * 用户口径：「进行中的状态只包含两个：**识别中（OCR）**和**翻译中**，提示系统和历史记录
     * 要分清楚这两个状态」——两个阶段必须能从 runner 查出来，且切换阶段时立刻更新。
     */
    @Test
    fun stage_isOcrWhileRecognizing_andTranslateWhileRequesting() = runBlocking {
        val scope = newScope()
        try {
            val ocrGate = java.util.concurrent.CountDownLatch(1)
            val trGate = java.util.concurrent.CountDownLatch(1)
            val runner = ChapterJobRunner(
                scope = scope,
                concurrency = { 1 },
                ocr = { page ->
                    withContext(Dispatchers.IO) { ocrGate.await() }
                    page
                },
                translate = { _, _ ->
                    withContext(Dispatchers.IO) { trGate.await() }
                    true
                },
            )
            runner.submit(0, listOf(5))
            withTimeout(4_000) { while (runner.ocrPages().isEmpty()) delay(10) }
            assertTrue("识别阶段必须报「识别中」，实际 ${runner.ocrPages()}", runner.ocrPages().contains(5))
            assertTrue("还没进翻译阶段，不该有「翻译中」", runner.translatingPages().isEmpty())
            assertEquals(ChapterTaskStage.OCR, runner.inFlightTasks.value.single().stage)

            ocrGate.countDown()
            withTimeout(4_000) { while (runner.translatingPages().isEmpty()) delay(10) }
            assertTrue(
                "翻译阶段必须报「翻译中」，实际 ${runner.translatingPages()}",
                runner.translatingPages().contains(5)
            )
            assertTrue("进入翻译后就不该再算「识别中」", runner.ocrPages().isEmpty())
            assertEquals(ChapterTaskStage.TRANSLATE, runner.inFlightTasks.value.single().stage)

            trGate.countDown()
            awaitIdle(runner)
            assertTrue("收尾后阶段表必须清空", runner.inFlightTasks.value.isEmpty())
        } finally {
            scope.cancel()        }
    }

    // ===== 10b. 「识别中」同一时刻只能有 1 页（预取中的页算「等待」） =====

    /**
     * ⚠️ 回归守卫（2026-09-28 用户追问「为什么第一次启动会同时显示 3 个识别中？OCR 不是串行吗」）：
     * 泵会把后面几页**预取**进流水线（在途表里有它们、但还排在 OCR 通道里 / 泵正等着交班）。
     * 曾经泵一取页就标「识别中」→ 屏幕上同时 3 个「识别中」，与"OCR 串行"直接矛盾。
     *
     * 现在：真正在识别工人手里的才是 `OCR`（恒 ≤1），预取中的是 `QUEUED`（界面显示「等待」）。
     */
    @Test
    fun onlyOnePageIsInOcrStage_whilePipelinePrefetches() = runBlocking {
        val scope = newScope()
        try {
            val ocrGate = java.util.concurrent.CountDownLatch(1)
            val runner = ChapterJobRunner(
                scope = scope,
                concurrency = { 1 },
                ocr = { page ->
                    withContext(Dispatchers.IO) { ocrGate.await() }
                    page
                },
                translate = { _, _ -> true },
            )
            runner.submit(0, (1..6).toList())
            withTimeout(4_000) { while (runner.ocrPages().isEmpty()) delay(10) }
            delay(200)   // 让泵把能预取的都预取掉（通道容量 0 → 深度很小，但预取项一定存在）

            assertEquals(
                "「识别中」恒 ≤1 页（OCR 是单例、必须串行），实际 ${runner.ocrPages()}",
                1, runner.ocrPages().size
            )
            assertTrue(
                "预取中的页必须报 QUEUED（界面显示「等待」），实际 ${runner.inFlightTasks.value.map { it.page to it.stage }}",
                runner.queuedPages().isNotEmpty()
            )
            assertTrue("预取中的页绝不能算「识别中」", runner.queuedPages().none { it in runner.ocrPages() })
            assertTrue("没有工人接手，此时不该有「翻译中」", runner.translatingPages().isEmpty())
            assertTrue(
                "在途页总数 = 1 识别中 + 预取等待（+ 至多 1 页在泵手上）",
                runner.inFlightPages().size <= 3
            )

            ocrGate.countDown()
            awaitIdle(runner)
        } finally {
            scope.cancel()
        }
    }

    // ===== 10c. 取消 = 在途页强制结束、不等待、只收尾一次 =====

    /**
     * 用户口径（2026-09-28）：「取消 = 正在识别 / 正在等 API 的那页**强制结束、不入库、不等待**，
     * 与手动/自动/增量打开面板或退出阅读器时一样」。
     *
     * 这条钉死三件事：
     * ① 取消**立刻**派发收尾事件（不等在途项 unwind —— 界面必须马上变回确定状态）；
     * ② 在途项被**单独掐掉**（API 一直不返回也拦得住），且不计成功；
     * ③ 在途项稍后结算时**不会再派发一次**收尾（宿主会被重复通知）。
     */
    @Test
    fun cancel_forceAbortsInFlight_immediatelyAndFinishesOnce() = runBlocking {
        val scope = newScope()
        try {
            val gate = java.util.concurrent.CountDownLatch(1)
            val finishes = Collections.synchronizedList(mutableListOf<Triple<Int, Int, Boolean>>())
            val runner = ChapterJobRunner(
                scope = scope,
                concurrency = { 1 },
                ocr = { page -> delay(5); page },
                translate = { _, _ ->
                    // 模拟"正在等 API 返回"：阻塞在非协程感知的等待上（和真实 HTTP 一样，
                    // 取消**不能**从中途打断它，但可以不再等它、不采用它的结果）
                    withContext(Dispatchers.IO) { gate.await() }
                    true
                },
                onJobFinished = { ch, ok, total, cancelled -> finishes += Triple(ch, ok, cancelled) },
            )
            runner.submit(0, (1..4).toList())
            withTimeout(4_000) { while (runner.translatingPages().isEmpty()) delay(10) }

            val t0 = System.currentTimeMillis()
            runner.cancel(0)
            assertTrue("取消必须立刻收尾（用户口径：不等待）", finishes.isNotEmpty())
            assertTrue("取消要如实上报 cancelled=true", finishes.single().third)
            assertEquals(ChapterJobState.CANCELLED, runner.stateOf(0))
            assertTrue(
                "收尾不该等在途项：耗时 ${System.currentTimeMillis() - t0}ms",
                System.currentTimeMillis() - t0 < 1_500
            )

            // 放掉被卡的翻译（现实里 = HTTP 请求返回）→ 被掐掉的那一项自行结算
            gate.countDown()
            awaitIdle(runner)
            assertEquals("收尾事件只该派发一次（在途项稍后结算不得再派发）", 1, finishes.size)
            assertEquals("被强制结束的页不算成功", 0, finishes.single().second)
            assertTrue("收尾后在途必须清空", runner.inFlightPages().isEmpty())
        } finally {
            scope.cancel()
        }
    }

    // ===== 11. 预取深度：通道重建后仍是「1 项识别 + N 项翻译」 =====

    /**
     * ⚠️ 回归守卫（2026-09-28 复查发现的真 bug）：通道是**一次性**的，收尾时关闭、下次提交重建 ——
     * 而重建那处曾手写 `Channel(8)`，与字段声明的 `capacity = 0` 不一致。
     * 于是**第一次任务**深度是对的（1+N），**第二次起** preparedChannel 变成 8：
     * 泵在毫秒内把整章从队列抽干 → 面板「等待」页数对不上、暂停/取消拦不住已预取的页。
     *
     * 判据用「队列还剩多少」而不是精确深度：容量 0 时最多 1 项在识别 + 1 项在翻译（+ 通道里排队），
     * 容量 8 时 8 页短任务会被一次抽干（waitingPages 直接空）。
     */
    @Test
    fun prefetchDepth_staysTiny_afterChannelRebuild() = runBlocking {
        val scope = newScope()
        try {
            val blocking = AtomicBoolean(false)
            val gate = java.util.concurrent.CountDownLatch(1)
            val runner = ChapterJobRunner(
                scope = scope,
                concurrency = { 1 },
                ocr = { page -> delay(5); page },
                translate = { _, _ ->
                    if (blocking.get()) withContext(Dispatchers.IO) { gate.await() }
                    true
                },
            )
            // 第一次任务：正常跑完 → 通道被关闭（channelsClosed = true）
            runner.submit(0, listOf(100, 101))
            awaitIdle(runner)

            // 第二次任务：翻译阶段卡住，看泵能把多少页预取出去
            blocking.set(true)
            runner.submit(1, (1..10).toList())
            withTimeout(4_000) { while (runner.translatingPages().isEmpty()) delay(10) }
            delay(200)   // 给泵足够时间把"能预取的"全预取掉

            val depth = runner.inFlightPages().size
            val waiting = runner.waitingPages.value.size
            assertTrue(
                "预取深度失控（preparedChannel 容量不是 0？）：在途 $depth 页、等待 $waiting 页",
                depth <= 5
            )
            assertTrue("队列被抽干 → 「等待」页数不对、暂停/取消拦不住已预取的页", waiting >= 5)
            // ⚠️ 并发 1 时「翻译中」最多 2 页：1 页在工人手里 + **至多 1 页刚识别完、正在交班**
            // （OCR 一结束就归翻译段，见 ChapterTaskStage.TRANSLATE；通道容量 0 ⇒ 至多多出这 1 页）
            assertTrue(
                "并发 1 时「翻译中」最多 2 页（1 在工人手里 + 1 刚识别完待交班），实际 ${runner.translatingPages()}",
                runner.translatingPages().size <= 2
            )

            gate.countDown()
            awaitIdle(runner)
        } finally {
            scope.cancel()
        }
    }
}
