package com.moe.starflow.translate.batch

import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 一个章节翻译任务（**纯内存态**：任务本身随进程消失，不写库）。
 *
 * @param done 已处理完（含失败/命中缓存）的页数
 * @param total 本次任务要翻的页数
 */
data class ChapterJob(
    val chapterIndex: Int,
    val total: Int,
    val done: Int,
    val state: ChapterJobState,
    /** 章标题（通知栏/面板显示用，由宿主传入；空串则调用方自己兜底）。 */
    val label: String = "",
    /** 该章第一页（点通知回到这一章时直接跳过去）。 */
    val startPage: Int = 0,
) {
    /** 还排在队里没开始翻的页数（面板上的「等待」）。 */
    val waiting: Int get() = (total - done).coerceAtLeast(0)

    val isActive: Boolean
        get() = state == ChapterJobState.RUNNING || state == ChapterJobState.PAUSED ||
            state == ChapterJobState.QUEUED
}

/**
 * 章节批量翻译的**流水线调度器**：OCR 串行、翻译并发、按章暂停/取消。
 *
 * 解决的问题（用户口径）：
 * - 「第一个 OCR 结束发出请求后，立刻开始第二页的 OCR」→ **OCR 与 API 请求重叠**：
 *   一个 OCR 工人（[ocrLoop]）+ N 个翻译工人（[translateLoop]），中间用 `preparedChannel` 衔接。
 *   ⚠️ OCR 本身**必须串行**（PP-OCR/manga-ocr 是单例，见 `OcrLock`），所以只开一个 OCR 工人；
 *   并发的只有翻译请求（远端 API 可以并发；本地引擎由调用方把并发数压到 1）。
 * - 「可以同时启动多个章节」→ 每章一个 [Runtime]（各自队列），调度器**按提交顺序**取页；
 *   某一章暂停时它的队列原样留着（＝面板上的「等待」），调度器直接跳到下一章继续。
 * - 「暂停等待的页面不会清除 / 取消等待的页全部删除、保留已下载的」→ 暂停只停取页、
 *   取消只丢队列；**在途的那一页照旧跑完**（已经付过 API 费用，丢掉纯属浪费）。
 *
 * 本类刻意不依赖 Android（只依赖协程），阶段行为由构造参数注入 → 可纯 JVM 单测
 * （`ChapterJobRunnerTest`）。
 *
 * @param ocr OCR 阶段（串行调用）。返回 null 表示这一页在 OCR 阶段就失败了（调用方已写库）
 * @param translate 翻译阶段（并发调用，最多 [concurrency] 个同时在跑）。返回 true = 这一页真的翻出来了
 * @param concurrency 翻译阶段的并发数（每次拉起工人时求值一次，便于跟随设置/引擎变化）
 */
class ChapterJobRunner<T>(
    private val scope: CoroutineScope,
    private val concurrency: () -> Int,
    private val ocr: suspend (page: Int) -> T?,
    private val translate: suspend (page: Int, product: T) -> Boolean,
    /** 一章收尾（含取消）时回调：ok = 成功页数。 */
    private val onJobFinished: (chapterIndex: Int, ok: Int, total: Int, cancelled: Boolean) -> Unit = { _, _, _, _ -> },
    /**
     * **已准备好但没被翻译**的项被丢弃/退回队列时回调（暂停退回、取消丢弃、任务已收尾）。
     *
     * ⚠️ 必须给：漫画的 `product` 里带着**一张全尺寸页图**，真正开翻时由翻译阶段自己 `recycle`；
     * 而被退回/丢弃的这些**根本不会进翻译阶段** → 没人回收（GC 最终会收，但大图堆在 native 堆上
     * 就是一段可观的峰值内存）。宿主应当在这里 `recycle()` 自己那份资源。
     */
    private val discard: ((page: Int, product: T) -> Unit)? = null,
) {

    private companion object {
        const val TAG = "ChapterJobRunner"

        /** 调度器空转时的轮询间隔（等暂停章恢复 / 等在途页收尾）。 */
        const val IDLE_POLL_MS = 120L
    }

    private class Runtime(
        val chapterIndex: Int,
        val queue: ArrayDeque<Int>,
        var state: ChapterJobState,
        var total: Int,
        var done: Int,
        /** 成功页数（失败的不算）。 */
        var ok: Int,
        var label: String,
        var startPage: Int,
    ) {
        val isActive: Boolean
            get() = state == ChapterJobState.RUNNING || state == ChapterJobState.PAUSED ||
                state == ChapterJobState.QUEUED
    }

    /**
     * 一张待翻的页。
     *
     * ⚠️ [id] 是**内部自增的唯一号**，不是页号：在途表按它做 key，跨章同序号（小说里每章都从 0 开始编号、
     * 漫画多章也可能同页号）才不会互相覆盖。调用方只管给页号/批号，唯一性别交给它们负责。
     */
    private class Task(val id: Int, val chapterIndex: Int, val page: Int)
    private class Prepared<T>(val task: Task, val product: T?)

    private val runtimes = mutableListOf<Runtime>()
    private val lock = Any()

    private val _jobs = MutableStateFlow<List<ChapterJob>>(emptyList())
    val jobs: StateFlow<List<ChapterJob>> = _jobs.asStateFlow()

    /** 排队中（还没开始翻）的页号集合 —— 面板把这些页标成「等待」。 */
    private val _waitingPages = MutableStateFlow<Set<Int>>(emptySet())
    val waitingPages: StateFlow<Set<Int>> = _waitingPages.asStateFlow()

    /** 在途任务（Task.id）→ 任务本身（OCR/翻译中：库里已是「翻译中」，不重复标等待）。 */
    private val inFlight = mutableMapOf<Int, Task>()

    /** 任务号自增源（只在 [lock] 里读写）。 */
    private var nextTaskId = 1

    private var pumpJob: Job? = null
    private var ocrJob: Job? = null
    private var workerJobs: List<Job> = emptyList()

    private var ocrChannel = Channel<Task>(capacity = 1)

    /**
     * OCR 完成、等待翻译工人的页。
     *
     * ⚠️ **容量必须是 0（会合点）**，不能让 OCR 一路预取：
     * 缓冲给大了（曾写 8）时，泵会在毫秒内把**整章**的页从队列里抽干（1 个在 OCR + 8 个在缓冲），
     * 于是「暂停」时队列已经空了 —— 用户看到的「等待」瞬间消失、暂停也拦不住那些页，
     * 与「暂停只停取页、等待的页留在队列里」的口径直接冲突（`ChapterJobRunnerTest` 抓到的）。
     * 容量 0 → 流水线深度恒为「1 页在 OCR + N 页在翻译」，既保住了「OCR 与请求重叠」的提速，
     * 又把内存（每页一张全尺寸 bitmap）压在 N+1 张以内。
     */
    private var preparedChannel = Channel<Prepared<T>>(capacity = 0)

    /** 通道是否已被关掉（关掉就作废，下次提交必须换一对新的）。 */
    private var channelsClosed = false

    // ===== 对外 API =====

    /**
     * 提交（或替换）某一章的任务。[pages] 是要翻的页（调用方已按状态筛过）。
     * 同一章重复提交 = 重新排队（旧队列丢掉）。
     */
    fun submit(chapterIndex: Int, pages: List<Int>, label: String = "", startPage: Int = 0) {
        if (pages.isEmpty()) return
        synchronized(lock) {
            runtimes.removeAll { it.chapterIndex == chapterIndex }
            runtimes += Runtime(
                chapterIndex = chapterIndex,
                queue = ArrayDeque(pages),
                state = ChapterJobState.RUNNING,
                total = pages.size,
                done = 0,
                ok = 0,
                label = label,
                startPage = startPage,
            )
            publish()
        }
        ensureStarted()
    }

    fun pause(chapterIndex: Int) = setState(chapterIndex, ChapterJobState.PAUSED)

    fun resume(chapterIndex: Int) {
        setState(chapterIndex, ChapterJobState.RUNNING)
        ensureStarted()
    }

    /** 取消：丢掉该章**还没开始翻**的页；在途页照旧跑完。 */
    fun cancel(chapterIndex: Int) {
        synchronized(lock) {
            val rt = runtimes.firstOrNull { it.chapterIndex == chapterIndex } ?: return
            rt.queue.clear()
            rt.state = ChapterJobState.CANCELLED
            publish()
        }
        maybeFinish(chapterIndex)
    }

    fun stateOf(chapterIndex: Int): ChapterJobState? =
        synchronized(lock) { runtimes.firstOrNull { it.chapterIndex == chapterIndex }?.state }

    fun jobOf(chapterIndex: Int): ChapterJob? =
        _jobs.value.firstOrNull { it.chapterIndex == chapterIndex }

    /**
     * 正在 OCR/翻译中的项（页号/批号）。宿主用它把「在途项」排除在各种「清残留状态」之外 ——
     * 别把别人正在翻的那一页打回「未翻译」（面板会闪，取消时还可能留下错乱的行）。
     */
    fun inFlightPages(): Set<Int> = synchronized(lock) { inFlight.values.map { it.page }.toSet() }

    fun isBusy(): Boolean = synchronized(lock) {
        inFlight.isNotEmpty() || runtimes.any { it.isActive && (it.queue.isNotEmpty() || it.done < it.total) }
    }

    /** 关掉这条流水线（换书 / 退出阅读器且不保留任务时调用）。 */
    fun shutdown() {
        // ⚠️ 被这一下掐掉的章必须**派发收尾事件**（cancelled=true）：否则那一刻标着「翻译中」的行
        // 没人负责退回「未翻译」→ 永久卡在翻译中、面板看着像还在跑（用户口径：取消要能停干净）
        val aborted = synchronized(lock) {
            val active = runtimes.filter { it.isActive }
            runtimes.forEach { it.queue.clear(); it.state = ChapterJobState.CANCELLED }
            inFlight.clear()
            publish()
            active
        }
        pumpJob?.cancel(); pumpJob = null
        ocrJob?.cancel(); ocrJob = null
        workerJobs.forEach { it.cancel() }
        workerJobs = emptyList()
        ocrChannel.close()
        preparedChannel.close()
        // ⚠️ 通道关掉后，**还躺在里面**的已备好项不会再被任何工人取走 → 在这里把它们带的资源还回去
        // （漫画每项一张全尺寸页图；不还就是几张大图堆在 native 堆上等 GC）
        while (true) {
            val item = preparedChannel.tryReceive().getOrNull() ?: break
            discardProduct(item.task, item.product)
        }
        // 收尾事件放在最后发：宿主收到时通道/工人已经停干净，可以安全地清「翻译中」状态
        aborted.forEach { rt ->
            runCatching { onJobFinished(rt.chapterIndex, rt.ok, rt.total, true) }
        }
    }

    // ===== 内部 =====

    private fun setState(chapterIndex: Int, state: ChapterJobState) {
        synchronized(lock) {
            val rt = runtimes.firstOrNull { it.chapterIndex == chapterIndex } ?: return
            if (rt.state == ChapterJobState.CANCELLED || rt.state == ChapterJobState.DONE) return
            rt.state = state
            publish()
        }
    }

    /**
     * 拉起流水线。⚠️ 通道是**一次性**的（关掉就不能再用），收尾时会被关闭，
     * 所以再次提交必须换一对新通道 —— 不换的话 `send` 直接抛 ClosedSendChannelException。
     */
    private fun ensureStarted() {
        synchronized(lock) {
            if (channelsClosed) {
                ocrChannel = Channel(1)
                preparedChannel = Channel(8)
                channelsClosed = false
            }
            if (pumpJob?.isActive != true) pumpJob = scope.launch { pumpLoop() }
            if (ocrJob?.isActive != true) ocrJob = scope.launch { ocrLoop() }
            val want = concurrency().coerceAtLeast(1)
            if (workerJobs.size == want && workerJobs.all { it.isActive }) return
            // ⚠️ **有在途项时不要重建工人**：`cancel()` 会让它正在处理的 `preparedChannel` 元素
            // **丢掉**（通道不会重投），那一页就永远不会 `onPageSettled` → 任务的 done 到不了 total
            // → 任务永久停在"进行中"、`isBusy()` 永远为 true（用户看到的"暂停后不显示完成"）。
            // 并发数变了就等这一轮流水线排空后再生效，代价只是"改设置不会立刻作用于当前这一章"。
            if (inFlight.isNotEmpty()) {
                LogCollector.d(TAG, "有 ${inFlight.size} 页在途，并发数变更推迟生效（want=$want）")
                return
            }
            workerJobs.forEach { it.cancel() }
            workerJobs = (0 until want).map { scope.launch { translateLoop() } }
            LogCollector.d(TAG, "翻译并发工人数 = $want")
        }
    }

    /** 调度：按提交顺序取「下一张该翻的页」；全都暂停/没活了就等一会儿再看。 */
    private suspend fun pumpLoop() {
        while (scope.isActive) {
            val task = nextTask()
            if (task == null) {
                if (allSettled()) break
                delay(IDLE_POLL_MS)
                continue
            }
            synchronized(lock) { inFlight[task.id] = task }
            publish()
            ocrChannel.send(task)
        }
        finishIfIdle()
    }

    private fun nextTask(): Task? = synchronized(lock) {
        val rt = runtimes.firstOrNull { it.state == ChapterJobState.RUNNING && it.queue.isNotEmpty() }
            ?: return null
        Task(nextTaskId++, rt.chapterIndex, rt.queue.removeFirst())
    }

    /** 没有任何可调度/在途的页了。 */
    private fun allSettled(): Boolean = synchronized(lock) {
        inFlight.isEmpty() && runtimes.none { it.state == ChapterJobState.RUNNING && it.queue.isNotEmpty() }
    }

    private suspend fun ocrLoop() {
        for (task in ocrChannel) {
            val product = try {
                ocr(task.page)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // ⚠️ 取消也要**结算这一项**：不结算的话它会永远留在 `inFlight`，
                // `maybeFinish` 因为「本章还有在途项」永不收尾 → 章卡片上的「暂停/取消」
                // 一直挂着、任务永远显示进行中（用户报的"已经翻完了还显示暂停和取消"）。
                onPageSettled(task, ok = false)
                throw e
            } catch (e: Exception) {
                LogCollector.e(TAG, "OCR 阶段异常 page=${task.page}", e)
                null
            }
            try {
                preparedChannel.send(Prepared(task, product))
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 同上：交接过程中被取消也要结算，否则这一项就丢在表里了
                discardProduct(task, product)
                onPageSettled(task, ok = false)
                throw e
            }
        }
    }

    /** 把"已备好但不会进翻译阶段"的项所带的资源还回去（见构造参数 [discard]）。 */
    private fun discardProduct(task: Task, product: T?) {
        product?.let { p -> runCatching { discard?.invoke(task.page, p) } }
    }

    private suspend fun translateLoop() {
        for (item in preparedChannel) {
            // ⚠️ 取到之后**再确认一次这一章还要不要翻**：OCR 与发送请求之间用户完全可能点了暂停/取消，
            // 而这一页是「预取」进来的（已不在队列里）。
            // - 暂停 → **退回队列**（重新成为「等待」），不计 done、不丢失
            // - 取消 → 直接丢（用户口径：取消就把还没开始翻的丢掉），不计 done
            // 不这么做的话，这两页会照旧翻完 —— 用户看到的「暂停/取消没反应」正是这么来的。
            val runnable = synchronized(lock) {
                val rt = runtimes.firstOrNull { it.chapterIndex == item.task.chapterIndex }
                val shouldRun = rt != null && rt.state == ChapterJobState.RUNNING
                if (!shouldRun) {
                    inFlight.remove(item.task.id)
                    if (rt != null && rt.state == ChapterJobState.PAUSED) rt.queue.addFirst(item.task.page)
                    publish()
                }
                shouldRun
            }
            if (!runnable) {
                // 这一项不会进翻译阶段 → 它带的资源（漫画是一张全尺寸页图）必须在这里还回去
                discardProduct(item.task, item.product)
                maybeFinish(item.task.chapterIndex)
                continue
            }
            var ok = false
            try {
                if (item.product != null) ok = translate(item.task.page, item.product)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                LogCollector.e(TAG, "翻译阶段异常 page=${item.task.page}", e)
            } finally {
                onPageSettled(item.task, ok = ok)
            }
        }
    }

    private fun onPageSettled(task: Task, ok: Boolean) {
        val finished = synchronized(lock) {
            inFlight.remove(task.id)
            val rt = runtimes.firstOrNull { it.chapterIndex == task.chapterIndex }
            if (rt != null) {
                rt.done += 1
                if (ok) rt.ok += 1
            }
            publish()
            if (rt != null && rt.done >= rt.total) rt.chapterIndex else null
        }
        if (finished != null) maybeFinish(finished)
    }

    /** 该章队列空了、且这一章已无在途页 → 收尾。 */
    private fun maybeFinish(chapterIndex: Int) {
        val rt = synchronized(lock) { runtimes.firstOrNull { it.chapterIndex == chapterIndex } } ?: return
        val myInFlight = synchronized(lock) { inFlight.values.count { it.chapterIndex == chapterIndex } }
        if (rt.queue.isNotEmpty() || myInFlight > 0) return
        val cancelled = rt.state == ChapterJobState.CANCELLED
        if (!cancelled && rt.done < rt.total) {
            // ⚠️ **安全网**：队列空、无在途，却还有页没结算 —— 只可能是某一项在流水线里被丢了
            // （通道关闭/取消竞态）。以前这里直接 `return`，于是任务**永远停在 RUNNING**、
            // 章卡片上的「暂停/取消」一直挂着 —— 用户报的「已经翻完了还显示暂停和取消」就是这个。
            // 宁可当成"提前收尾"（ok 如实报成功数）也不能让它挂着：用户看到的必须是确定状态。
            LogCollector.w(
                TAG,
                "章 $chapterIndex 队列已空但仍有未结算页（done=${rt.done}/${rt.total}）→ 按收尾处理"
            )
        }
        synchronized(lock) {
            if (rt.state != ChapterJobState.CANCELLED) rt.state = ChapterJobState.DONE
            publish()
        }
        onJobFinished(chapterIndex, rt.ok, rt.total, cancelled)
        finishIfIdle()
    }

    /** 全部任务收尾 → 关通道、清工人（下次提交会重新拉起并换新通道）。 */
    private fun finishIfIdle() {
        val idle = synchronized(lock) {
            inFlight.isEmpty() && runtimes.none { it.isActive && (it.queue.isNotEmpty() || it.done < it.total) }
        }
        if (!idle) return
        synchronized(lock) {
            ocrChannel.close()
            preparedChannel.close()
            channelsClosed = true
            pumpJob = null
            ocrJob = null
            workerJobs = emptyList()
        }
        LogCollector.d(TAG, "章节批量翻译流水线已收尾")
    }

    private fun publish() {
        _jobs.value = runtimes.map {
            ChapterJob(
                chapterIndex = it.chapterIndex,
                total = it.total,
                done = it.done,
                state = it.state,
                label = it.label,
                startPage = it.startPage,
            )
        }
        _waitingPages.value = runtimes
            .filter { it.state == ChapterJobState.RUNNING || it.state == ChapterJobState.PAUSED || it.state == ChapterJobState.QUEUED }
            .flatMap { it.queue }
            .toSet()
    }
}
