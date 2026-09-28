package com.moe.starflow.translate.batch

import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 在途项**此刻在哪一段**。
 *
 * ⚠️ 用户口径（2026-09-28）：「进行中的状态只包含两个：**识别中（OCR）**和**翻译中**（发请求 /
 * 本地推理），提示系统和历史记录要分清楚这两个状态」——「同时请求数」只作用于**翻译**这一段，
 * OCR 恒为串行，所以任一时刻最多是 **1 项识别中 + N 项翻译中**（N = 并发设置）。
 * 面板与状态浮层必须按它分开显示，否则用户看到「一次冒出三个一样的卡片」就完全不知道为什么。
 *
 * ⚠️ [QUEUED] 是**必须有的第三态**（2026-09-28 用户追问"为什么第一次启动同时显示 3 个识别中"）：
 * 泵会把后面几页**预取**进流水线（在途表里有它们、但还没进 OCR 工人）——
 * 以前泵一取页就标成"识别中"，于是屏幕上同时出现 3 个「识别中」（1 个真在识别 + 2 个在排队/在通道里）。
 * 预取中的项对用户就是**「等待」**（等同队列里还没取的页），绝不能算「识别中」。
 */
enum class ChapterTaskStage {
    /** 已进流水线（在途表里）但**还没轮到**：在 OCR 通道里排队 / 泵正等着交班 → 界面显示「等待」。 */
    QUEUED,

    /** **真正在 OCR 工人手里**识别 → 界面显示「识别中」。**恒 ≤ 1**（OCR 是单例，必须串行）。 */
    OCR,

    /**
     * 识别结束、**归翻译段**（请求在飞 / 本地推理中 / 等服务端返回；并发 N 时偶尔排队等槽位）
     * → 界面显示「翻译中」。
     *
     * ⚠️ 切换点是**"OCR 一结束"**（不是"工人取到"）：用户口径「只要 ocr 结束立刻就可以交给 api
     * 显示翻译中」——识别完还挂着「识别中」就是记录显示错了。
     */
    TRANSLATE,
}

/**
 * 在途项快照（页号 + 阶段）：面板「识别中 / 翻译中」标签与状态浮层都读它。
 *
 * @param chapterIndex 属于哪一章（多章同时跑时要能分辨）
 * @param page 页号（漫画=全书页号；小说=批号）
 */
data class InFlightTask(
    val chapterIndex: Int,
    val page: Int,
    val stage: ChapterTaskStage,
)

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
        /**
         * 收尾事件是否已派发（**每个 Runtime 至多派发一次**）。
         *
         * ⚠️ 需要它是因为取消会**立刻收尾**（不等在途项），而在途项稍后还会各自结算 ——
         * 没有这个闩，取消后每结算一项就会再派发一次收尾事件（宿主会重复弹提示/重复清状态）。
         */
        var finishedDispatched: Boolean = false,
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
     *
     * [stage] = 这一项此刻在 **识别** 还是 **翻译**（面板/浮层按它分开显示）。只在 `lock` 里改写。
     *
     * [cancelled] = 这一项被 `cancel(本章)` **强制结束**（用户口径：在途页不强等、不入库）。
     */
    private class Task(val id: Int, val chapterIndex: Int, val page: Int) {
        var stage: ChapterTaskStage = ChapterTaskStage.QUEUED

        @Volatile
        var cancelled: Boolean = false
    }
    private class Prepared<T>(val task: Task, val product: T?)

    /** 单项执行结果（[Aborted] = 这一项自己被强制结束，**不是**整条流水线被取消）。 */
    private sealed interface TaskOutcome<out R> {
        data class Done<R>(val value: R) : TaskOutcome<R>
        data object Aborted : TaskOutcome<Nothing>
        data class Failed(val error: Throwable) : TaskOutcome<Nothing>
    }

    private val runtimes = mutableListOf<Runtime>()
    private val lock = Any()

    private val _jobs = MutableStateFlow<List<ChapterJob>>(emptyList())
    val jobs: StateFlow<List<ChapterJob>> = _jobs.asStateFlow()

    /** 排队中（还没开始翻）的页号集合 —— 面板把这些页标成「等待」。 */
    private val _waitingPages = MutableStateFlow<Set<Int>>(emptySet())
    val waitingPages: StateFlow<Set<Int>> = _waitingPages.asStateFlow()

    /**
     * 在途项（页号 + **阶段**）：面板/状态浮层据此区分「识别中」与「翻译中」。
     *
     * ⚠️ 必须是 **flow 而不是纯函数**：阶段变化（OCR → 翻译）不改 `done`，只靠 `jobs` 的话
     * 界面要等到下一页翻完才更新，用户就会看到「明明在调 API，面板还写着识别中」。
     */
    private val _inFlightTasks = MutableStateFlow<List<InFlightTask>>(emptyList())
    val inFlightTasks: StateFlow<List<InFlightTask>> = _inFlightTasks.asStateFlow()

    /** 在途任务（Task.id）→ 任务本身（OCR/翻译中：库里已是「翻译中」，不重复标等待）。 */
    private val inFlight = mutableMapOf<Int, Task>()

    /**
     * 在途任务（Task.id）→ **它自己的协程**（OCR 或翻译那一段）。
     *
     * ⚠️ 用途：`cancel(本章)` 要能**只掐掉这一章的在途项**（用户口径 2026-09-28：
     * 「取消 = 正在识别 / 正在等 API 的那页强制结束、不入库、不等待」），
     * 而 OCR 工人 / 翻译工人是**多章共用**的 —— 不能整条流水线一起取消（会误伤别的章）。
     * 单项协程 + `cancel()` 正好做到「只停这一项、工人继续干别的」。
     */
    private val taskJobs = mutableMapOf<Int, Deferred<*>>()

    /** 任务号自增源（只在 [lock] 里读写）。 */
    private var nextTaskId = 1

    private var pumpJob: Job? = null
    private var ocrJob: Job? = null
    private var workerJobs: List<Job> = emptyList()

    /**
     * ⚠️ **容量必须是 0（会合点）**，不能让 OCR 一路预取：
     * 缓冲给大了（曾写 8）时，泵会在毫秒内把**整章**的页从队列里抽干（1 个在 OCR + 8 个在缓冲），
     * 于是「暂停」时队列已经空了 —— 用户看到的「等待」瞬间消失、暂停也拦不住那些页，
     * 与「暂停只停取页、等待的页留在队列里」的口径直接冲突（`ChapterJobRunnerTest` 抓到的）。
     * 容量 0 → 流水线深度恒为「1 页在 OCR + N 页在翻译」，既保住了「OCR 与请求重叠」的提速，
     * 又把内存（每页一张全尺寸 bitmap）压在 N+1 张以内。
     *
     * ⚠️⚠️ **通道的容量只有一个来源（[newPreparedChannel]）**：收尾后重建通道时曾手写
     * `Channel(8)`，与这里声明的 0 不一致 —— 于是「第一次任务」的一对通道是对的（容量 0），
     * 第二次起 preparedChannel 变成 8，预取深度直接从 N+1 变 N+9：队列被瞬间抽干、
     * 「等待」页数看着对不上、暂停拦不住已预取的页（2026-09-28 复查发现）。
     * 重建通道**必须**调同一个工厂函数。
     */
    private fun newPreparedChannel() = Channel<Prepared<T>>(capacity = 0)

    /** OCR 待办通道（容量 1：泵一次只把下一页交给 OCR 工人）。 */
    private fun newOcrChannel() = Channel<Task>(capacity = 1)

    private var ocrChannel = newOcrChannel()
    private var preparedChannel = newPreparedChannel()

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

    /**
     * 取消：丢掉该章**还没开始翻**的页，并**强制结束在途的那几项**。
     *
     * ⚠️ 用户口径（2026-09-28）：「取消 = 正在识别 / 正在等 API 的那页**强制结束、不入库、不等待**」，
     * 与手动/自动/增量「打开面板 / 退出阅读器」时的强制退出同一套语义。所以这里：
     * ① 清队列（等待的页退回未翻译）；② 标记并 `cancel()` 在途项**各自的协程**
     * （识别中的会被中断、等 API 的不再等待，产物一律丢弃不写库）；
     * ③ **立刻派发收尾事件**（不等在途项 unwind —— 界面必须马上变回确定状态）。
     * ⚠️ 阻塞中的 native OCR / 已发出的 HTTP 请求**无法从中途打断**：我们只是不再等它、也不采用它的结果。
     */
    fun cancel(chapterIndex: Int) {
        val rt: Runtime
        val toCancel: List<Deferred<*>>
        synchronized(lock) {
            rt = runtimes.firstOrNull { it.chapterIndex == chapterIndex } ?: return
            rt.queue.clear()
            rt.state = ChapterJobState.CANCELLED
            val mine = inFlight.values.filter { it.chapterIndex == chapterIndex }
            mine.forEach { it.cancelled = true }
            toCancel = mine.mapNotNull { taskJobs[it.id] }
            publish()
        }
        LogCollector.d(
            TAG,
            "第 $chapterIndex 章 取消：强制结束 ${toCancel.size} 项在途（不入库、不等待）"
        )
        toCancel.forEach { it.cancel() }
        // **立刻收尾**：界面不必等在途项 unwind（用户口径「不等待」）
        dispatchFinished(rt, cancelled = true)
        finishIfIdle()
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

    /** **识别中**（OCR 阶段，真正在识别工人手里）的页号/批号集合。**恒 ≤ 1 页**（OCR 串行）。 */
    fun ocrPages(): Set<Int> = synchronized(lock) {
        inFlight.values.filter { it.stage == ChapterTaskStage.OCR }.map { it.page }.toSet()
    }

    /**
     * **已进流水线但还没轮到识别**（在 OCR 通道排队 / 泵等着交班）的页。
     *
     * ⚠️ 面板必须把它和队列里的页一样显示成「等待」：这些页的 OCR 还没开始，
     * 标成「识别中」就是用户看到的"一启动同时出现 3 个识别中"。
     */
    fun queuedPages(): Set<Int> = synchronized(lock) {
        inFlight.values.filter { it.stage == ChapterTaskStage.QUEUED }.map { it.page }.toSet()
    }

    /** **翻译中**（已发出请求 / 本地推理中）的页号/批号集合。 */
    fun translatingPages(): Set<Int> = synchronized(lock) {
        inFlight.values.filter { it.stage == ChapterTaskStage.TRANSLATE }.map { it.page }.toSet()
    }

    /** 在途项快照（含阶段），供面板/浮层区分「识别中」与「翻译中」。 */
    fun inFlightTasksNow(): List<InFlightTask> = _inFlightTasks.value

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
            inFlight.values.forEach { it.cancelled = true }
            val jobs = taskJobs.values.toList()
            inFlight.clear()
            publish()
            active to jobs
        }
        pumpJob?.cancel(); pumpJob = null
        ocrJob?.cancel(); ocrJob = null
        // 在途项**各自的协程**也要掐（只取消工人不会中断已经在跑的那一项的 await）
        aborted.second.forEach { it.cancel() }
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
        aborted.first.forEach { rt ->
            runCatching {
                val first = synchronized(lock) {
                    if (rt.finishedDispatched) false else {
                        rt.finishedDispatched = true; true
                    }
                }
                if (first) onJobFinished(rt.chapterIndex, rt.ok, rt.total, true)
            }
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
                // ⚠️ **必须走工厂函数**：手写 `Channel(8)` 会让 preparedChannel 的容量从 0 变 8
                // （第一次任务是对的、第二次起开始预取整章），见 [newPreparedChannel] 的注释。
                ocrChannel = newOcrChannel()
                preparedChannel = newPreparedChannel()
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
            synchronized(lock) {
                // 这一项从「队列」进入流水线 —— 但**还不是「识别中」**：它可能先在 OCR 通道里排队，
                // 或者泵正卡在 send 上等 OCR 工人腾出手。用户视角这就是「等待」（见 ChapterTaskStage）
                task.stage = ChapterTaskStage.QUEUED
                inFlight[task.id] = task
            }
            publish()
            LogCollector.d(TAG, "第 ${task.chapterIndex} 章 page=${task.page} 进入流水线（等待识别）")
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

    /**
     * 单项工作跑在自己的协程里（可被 [cancel] 单独掐掉）。
     *
     * ⚠️ 用 `async` 而不是 `launch`：单项里的异常必须在 `await()` 抛回给工人自己处理，
     * `launch` 会把异常丢给 scope 的未捕获处理器（= 整个应用崩）。
     * ⚠️ 取消分两种，必须分清（否则会误伤别的章 / 该停的停不下来）：
     * - **这一项被单独取消**（`cancel(本章)`）：工人还活着 → 返回 [TaskOutcome.Aborted]，工人继续干别的
     * - **整条流水线被取消**（`shutdown()` / scope 取消）：工人自己也已取消 → 原样抛出，让循环退出
     *
     * @param onAbort 这一项被单独取消时，若产物已经出来，交回给调用方回收（漫画是一张全尺寸位图）
     */
    private suspend fun <R> runTask(
        task: Task,
        onAbort: (R) -> Unit = {},
        block: suspend () -> R,
    ): TaskOutcome<R> {
        if (task.cancelled) return TaskOutcome.Aborted
        val job = scope.async { block() }
        synchronized(lock) {
            // 提交与取消的竞态：进锁前刚被取消 → 当场掐掉，一秒都不多跑
            if (task.cancelled) job.cancel() else taskJobs[task.id] = job
        }
        return try {
            TaskOutcome.Done(job.await())
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 工人自己也已被取消 → 这是"整条流水线在停"，让调用方的循环退出
            if (!currentCoroutineContext().isActive) throw e
            // 只是这一项被强制结束：产物可能已经出来了，必须回收（否则整页 bitmap 内存悬着）
            runCatching { onAbort(job.getCompleted()) }
            TaskOutcome.Aborted
        } catch (e: Exception) {
            TaskOutcome.Failed(e)
        } finally {
            synchronized(lock) { taskJobs.remove(task.id) }
        }
    }

    private suspend fun ocrLoop() {
        for (task in ocrChannel) {
            // 取消后再轮到它 → 直接结算，不做无用的识别（native 那一跑要好几秒）
            if (task.cancelled) {
                onPageSettled(task, ok = false)
                continue
            }
            // ⚠️ **现在**才是「识别中」（用户口径：同一时刻只该有 1 个识别中）：
            // 取到任务 ≠ 开始识别之外的任何阶段都算「等待」，见 [ChapterTaskStage.QUEUED]。
            updateStage(task, ChapterTaskStage.OCR)
            LogCollector.d(TAG, "第 ${task.chapterIndex} 章 page=${task.page} → 识别中")
            val outcome = runTask(task, onAbort = { p -> p?.let { discardProduct(task, it) } }) {
                ocr(task.page)
            }
            val product: T? = when (outcome) {
                is TaskOutcome.Done -> outcome.value
                TaskOutcome.Aborted -> {
                    // 这一项被强制结束：结算掉，**工人继续**处理别的章
                    LogCollector.d(TAG, "第 ${task.chapterIndex} 章 page=${task.page} 识别阶段被取消（结果丢弃）")
                    onPageSettled(task, ok = false)
                    continue
                }
                is TaskOutcome.Failed -> {
                    LogCollector.e(TAG, "OCR 阶段异常 page=${task.page}", outcome.error)
                    null
                }
            }
            // 识别结束 → **立刻归翻译段**（用户口径：OCR 一结束就该显示「翻译中」）。
            // product 为 null（识别失败/等锁超时）时不动阶段：这一项马上会按失败结算。
            if (product != null) {
                updateStage(task, ChapterTaskStage.TRANSLATE)
                LogCollector.d(TAG, "第 ${task.chapterIndex} 章 page=${task.page} 识别完成 → 翻译中")
            }
            // 取消竞态：识别跑完的瞬间用户点了取消 → 产物不交出去（翻译阶段不会跑）
            if (task.cancelled) {
                discardProduct(task, product)
                onPageSettled(task, ok = false)
                continue
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

    /**
     * 改在途项的阶段（`lock` 内改 + `publish()` 让面板/浮层立刻看到）。
     *
     * ⚠️ 项已经结算/被丢弃时**不能再改**（`inFlight` 里没有它）——否则会把已经离开流水线的页
     * 又写回阶段表，界面上出现"翻完了还挂着翻译中"。
     */
    private fun updateStage(task: Task, stage: ChapterTaskStage) {
        synchronized(lock) {
            if (inFlight[task.id] !== task) return
            if (task.stage == stage) return
            task.stage = stage
            publish()
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
                if (shouldRun) {
                    // 这一项归 **翻译中**（正常情况下 OCR 结束时已经标过，这里是兜底：
                    // 万一通道实现变了导致阶段没来得及更新，至少工人接手时一定是对的）
                    item.task.stage = ChapterTaskStage.TRANSLATE
                    publish()
                } else {
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
            LogCollector.d(TAG, "第 ${item.task.chapterIndex} 章 page=${item.task.page} → 翻译中")
            var ok = false
            if (item.task.cancelled) {
                // 已在交接前被取消：产物直接丢弃、**不进翻译阶段**（用户口径：不入库、不等待）
                discardProduct(item.task, item.product)
                onPageSettled(item.task, ok = false)
                continue
            }
            if (item.product == null) {
                // ⚠️ **绝不静默**：OCR 没产出（等锁超时/引擎未就绪）时以前什么都不记，
                // 结果"进度在涨、什么都没翻、面板卡片消失、日志空白"（用户 2026-09-28 报的）。
                LogCollector.w(
                    TAG,
                    "第 ${item.task.chapterIndex} 章 page=${item.task.page} 未产出（OCR 阶段未完成/等锁超时）→ 记为未成功",
                )
                onPageSettled(item.task, ok = false)
                continue
            }
            when (val outcome = runTask(item.task) { translate(item.task.page, item.product) }) {
                is TaskOutcome.Done -> ok = outcome.value
                TaskOutcome.Aborted -> {
                    // 这一项被强制结束（取消）：翻译阶段自己已经退回「未翻译」、**不写库**；
                    // **工人继续**处理别的章（整条流水线的取消不在这里，见 [runTask]）
                    LogCollector.d(TAG, "第 ${item.task.chapterIndex} 章 page=${item.task.page} 翻译阶段被取消（不入库）")
                }
                is TaskOutcome.Failed -> LogCollector.e(TAG, "翻译阶段异常 page=${item.task.page}", outcome.error)
            }
            onPageSettled(item.task, ok = ok)
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
            // ⚠️ 取消的章**永远到不了 total**（队列已被丢掉）→ 必须在最后一项结算时也收尾，
            // 否则 `onJobFinished(cancelled=true)` 可能一直不派发（宿主清不了「翻译中」残留状态）。
            val shouldFinish = rt != null &&
                (rt.done >= rt.total || rt.state == ChapterJobState.CANCELLED)
            if (shouldFinish) rt!!.chapterIndex else null
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
        dispatchFinished(rt, cancelled)
        finishIfIdle()
    }

    /**
     * 派发收尾事件（**每个 Runtime 至多一次**）。
     *
     * ⚠️ 取消会**立刻**调它（不等在途项），而在途项稍后结算时还会走 [maybeFinish] ——
     * 没有这个闩，宿主会收到重复的收尾事件（重复弹提示、重复清「翻译中」状态）。
     */
    private fun dispatchFinished(rt: Runtime, cancelled: Boolean) {
        val first = synchronized(lock) {
            if (rt.finishedDispatched) false else {
                rt.finishedDispatched = true
                true
            }
        }
        if (!first) return
        onJobFinished(rt.chapterIndex, rt.ok, rt.total, cancelled)
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
            // ⚠️ 必须**取消**旧工人：只把它们从列表里丢掉的话，它们还挂在**旧通道**的 `hasNext()`
            // 上（通道关了以后会自己退出，但那是"迟早"）—— 下次提交会换一对新通道，谁读哪一对
            // 就不再是一眼能看清的事。这里取消掉，语义变成"这一轮流水线的人全部下岗"。
            workerJobs.forEach { it.cancel() }
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
        // 在途项的阶段（识别中 / 翻译中）：**每次 publish 都重发**，阶段变化才有人看得见
        _inFlightTasks.value = inFlight.values.map {
            InFlightTask(it.chapterIndex, it.page, it.stage)
        }
    }
}
