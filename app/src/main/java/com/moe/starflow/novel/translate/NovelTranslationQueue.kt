package com.moe.starflow.novel.translate

import com.moe.starflow.manga.OcrLock
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 队列阶段。 */
enum class NovelQueuePhase {
    /** 未启用 / 面板打开中 / 手动模式闲着。 */
    IDLE,
    /** 抢翻译锁中（漫画/悬浮窗翻译在跑时会出现）。 */
    WAITING_LOCK,
    /** 正在翻某一批。 */
    TRANSLATING,
    /** 当前页翻完、配额用尽或章末 —— 停下等用户翻页/切模式。 */
    DRAINED,
}

data class NovelQueueState(
    val phase: NovelQueuePhase = NovelQueuePhase.IDLE,
    val chapterIndex: Int = -1,
    /** 正在翻的这一批（段号）；空 = 没在翻。 */
    val batchParaIndexes: List<Int> = emptyList(),
    /** 本次启动已经翻完多少批。 */
    val batchesDone: Int = 0,
    /** 增量还剩多少批配额；手动/自动为 null。 */
    val remaining: Int? = null,
)

/**
 * 按**批**推进的翻译队列。
 *
 * ### 三种模式（用户口径，别再改错）
 * - **手动**：不进队列。按钮点一次 = 从**当前页**第一段没翻的段起翻**一批**（见 [translateOneBatch]）
 * - **自动**：盯着**当前页**，只要本页还有没翻的段就一批批翻下去；本页翻完 → `DRAINED`，
 *   等用户翻到下一页再自动接上
 * - **增量**：在自动的基础上**不受当前页限制**，连续向后翻，直到**配额用尽**或**章末**
 *
 * ⚠️ 增量是「向后 N **批**」，不是「向后 N 章」—— 这条曾经做错过一次，
 * 语义由 `NovelBatchPlannerTest` 与 `NovelTranslationQueueTest` 两处钉死。
 *
 * ⚠️ 三条纪律别改回去（都是踩过的坑）：
 * 1. **`OcrLock` 必须轮询等**，拿不到就 return 会让本轮什么都不做、下一轮又选到同一批 → 空转
 * 2. **失败的批要记进 [failedAnchors] 跳过**，否则每轮重挑同一批 → 无限重试、额度烧光
 * 3. **面板打开时状态置空**（`NovelQueueState()`），否则用户看到一条永远不动的假进度
 */
class NovelTranslationQueue(
    private val scope: CoroutineScope,
    private val translator: NovelBatchTranslator,
    /** 取某章的段落（由仓库提供，带缓存）。 */
    private val paragraphsOf: suspend (ImportedNovel, Int) -> List<NovelParagraph>,
    private val sourceLang: () -> String,
    private val targetLang: () -> String,
    private val translatorName: () -> String,
    private val batchSize: () -> Int,
    private val debounceMs: () -> Int,
    /** 当前页显示的段（按顺序）—— 手动/自动的锚点。 */
    private val currentPageParaIndexes: () -> List<Int>,
    /** 整章的段（按顺序）—— 增量在页内翻完后从这里向后找。 */
    private val chapterParaIndexes: suspend (ImportedNovel, Int) -> List<Int>,
    /** 某章已有译文的段。 */
    private val translatedIndexes: suspend (ImportedNovel, Int) -> Set<Int>,
) {

    private companion object {
        const val TAG = "NovelTranslationQueue"

        /** `OcrLock` 没有 await，只能轮询。 */
        const val LOCK_POLL_MS = 200L
        const val LOCK_WAIT_TIMEOUT_MS = 30_000L

        /** 停下之后的空转间隔（等用户翻页/切模式）。 */
        const val DRAINED_POLL_MS = 800L
    }

    private val _state = MutableStateFlow(NovelQueueState())
    val state: StateFlow<NovelQueueState> = _state.asStateFlow()

    private var job: Job? = null
    private var panelOpen = false

    /**
     * 本次会话里**翻过但没拿到译文**的批（用锚点段号记）。
     *
     * 和已翻译的段一起喂给 [NovelBatchPlanner]，让规划器直接跳过 ——
     * 不记的话每轮都会重挑同一批：无限重试、额度烧光，而且「有批在翻」不成立，
     * 用户连进度提示都看不到（只觉得队列卡死）。
     */
    private val failedAnchors = mutableSetOf<Int>()

    /**
     * 启动（或按新参数重启）队列。[NovelTranslateMode.MANUAL] 时只把状态清空 ——
     * 手动不进循环，由按钮直接调 [translateOneBatch]。
     *
     * @param quota 增量模式的批配额；手动/自动传什么都行
     * @param currentChapter 每轮**重新求值**（lambda 而非值）：切章不重启队列
     * @param onBatchSettled **一批结束时**回调（成功给译文表，失败给空表）。
     *   ⚠️ 失败**也必须调**：宿主靠它刷新统计与失败明细 —— 只报成功的话，
     *   面板里的「失败」状态永远不出现（用户报的「失败的状态不会实时同步」）。
     */
    fun start(
        book: ImportedNovel,
        mode: NovelTranslateMode,
        quota: NovelQuota = NovelQuota.of(NovelQuota.DEFAULT),
        currentChapter: () -> Int,
        onBatchSettled: suspend (Int, NovelBatchResult) -> Unit,
    ) {
        job?.cancel()
        failedAnchors.clear()
        if (mode == NovelTranslateMode.MANUAL) {
            _state.value = NovelQueueState()
            return
        }
        job = scope.launch {
            var remaining = quota
            var batchesDone = 0
            while (isActive) {
                if (panelOpen) {
                    // 面板打开：状态置空 + 不动。用户在看面板时翻下去既浪费额度也可能翻错
                    _state.value = NovelQueueState()
                    delay(LOCK_POLL_MS)
                    continue
                }
                delay(debounceMs().toLong())

                val chapter = currentChapter()
                val chapterParas = chapterParaIndexes(book, chapter)
                val done = translatedIndexes(book, chapter) + failedAnchors
                val anchor = NovelBatchPlanner.anchorForMode(
                    mode = mode,
                    pageParaIndexes = currentPageParaIndexes(),
                    chapterParaIndexes = chapterParas,
                    translated = done,
                )
                if (anchor == null) {
                    // 自动：当前页翻完（等翻页）；增量：本章翻完（不越到下一章）
                    _state.value = drainState(chapter, batchesDone, mode, remaining)
                    delay(DRAINED_POLL_MS)
                    continue
                }
                if (mode == NovelTranslateMode.AHEAD && remaining.exhausted) {
                    _state.value = drainState(chapter, batchesDone, mode, remaining)
                    delay(DRAINED_POLL_MS)
                    continue
                }
                val batch = NovelBatchPlanner.nextBatch(chapterParas, anchor, done, batchSize())
                if (batch.isEmpty()) {
                    _state.value = drainState(chapter, batchesDone, mode, remaining)
                    delay(DRAINED_POLL_MS)
                    continue
                }

                _state.value = NovelQueueState(
                    NovelQueuePhase.WAITING_LOCK, chapter, batch, batchesDone, remaining.remaining,
                )
                if (!acquireLockWithWait()) {
                    LogCollector.w(TAG, "等待翻译锁超时，本轮跳过 ch=$chapter anchor=$anchor")
                    failedAnchors += anchor
                    continue
                }
                try {
                    _state.value = NovelQueueState(
                        NovelQueuePhase.TRANSLATING, chapter, batch, batchesDone, remaining.remaining,
                    )
                    val result = translator.translateBatch(
                        book = book,
                        chapterIndex = chapter,
                        paragraphs = paragraphsOf(book, chapter),
                        paraIndexes = batch,
                        sourceLang = sourceLang(),
                        targetLang = targetLang(),
                        translatorName = translatorName(),
                    )
                    if (result.isEmpty) {
                        // 内容性失败（空响应 / 解析不出编号）：记账跳过，别死循环重试
                        LogCollector.w(TAG, "第 $chapter 章一批（${batch.size} 段）失败：${result.error}")
                        failedAnchors += anchor
                        // 也要通知宿主：面板要立刻显示这一批失败了（带原始原因）
                        onBatchSettled(chapter, result)
                        continue
                    }
                    batchesDone += 1
                    if (mode == NovelTranslateMode.AHEAD) remaining = remaining.consume()
                    onBatchSettled(chapter, result)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LogCollector.e(TAG, "第 $chapter 章翻译失败", e)
                    failedAnchors += anchor
                    onBatchSettled(
                        chapter,
                        NovelBatchResult(emptyMap(), "${e.javaClass.simpleName}: ${e.message.orEmpty()}"),
                    )
                } finally {
                    OcrLock.release()
                }
            }
        }
    }

    private fun drainState(
        chapter: Int,
        batchesDone: Int,
        mode: NovelTranslateMode,
        remaining: NovelQuota,
    ) = NovelQueueState(
        phase = NovelQueuePhase.DRAINED,
        chapterIndex = chapter,
        batchesDone = batchesDone,
        remaining = if (mode == NovelTranslateMode.AHEAD) remaining.remaining else null,
    )

    /**
     * **手动**：从当前页第一段没翻的段起，翻一批就返回。
     *
     * 与队列共用同一套锚点/成批规则（[NovelBatchPlanner]）—— 两条路各写一套迟早会不一致。
     */
    suspend fun translateOneBatch(book: ImportedNovel, chapterIndex: Int): NovelBatchResult {
        val chapterParas = chapterParaIndexes(book, chapterIndex)
        val done = translatedIndexes(book, chapterIndex)
        val anchor = NovelBatchPlanner.anchorOnPage(currentPageParaIndexes(), done)
            ?: chapterParas.firstOrNull { it !in done && it !in failedAnchors }
            ?: return NovelBatchResult(emptyMap(), "没有待翻译的段落了")
        val batch = NovelBatchPlanner.nextBatch(chapterParas, anchor, done, batchSize())
        if (batch.isEmpty()) return NovelBatchResult(emptyMap(), "没有待翻译的段落了")
        return runBatch(book, chapterIndex, batch)
    }

    /**
     * 翻**指定的这几段**（选择模式：长按多选后翻译 / 重翻）。
     *
     * ⚠️ 与 [translateOneBatch] 的关键区别：**不经过规划器**。选中的段原样送出 ——
     * 规划器会把「已有译文」的段当成已完成而跳过，那样**重翻就永远翻不动**。
     *
     * ⚠️ 一次调用 = 一次请求。按「每批段数」拆批由调用方做（只有它知道面板怎么设置的），
     * 这样「选 20 段、每批 3 段」就是 7 次请求、7 次上屏。
     */
    suspend fun translateExact(
        book: ImportedNovel,
        chapterIndex: Int,
        paraIndexes: List<Int>,
    ): NovelBatchResult {
        if (paraIndexes.isEmpty()) return NovelBatchResult(emptyMap())
        return runBatch(book, chapterIndex, paraIndexes)
    }

    /** 一批（已确定段号）的实际请求：抢锁 → 翻 → 落库（在 [NovelBatchTranslator] 里）。 */
    private suspend fun runBatch(
        book: ImportedNovel,
        chapterIndex: Int,
        batch: List<Int>,
    ): NovelBatchResult {
        _state.value = NovelQueueState(NovelQueuePhase.WAITING_LOCK, chapterIndex, batch)
        if (!acquireLockWithWait()) return NovelBatchResult(emptyMap(), "翻译引擎被占用（别的翻译正在跑）")
        try {
            _state.value = NovelQueueState(NovelQueuePhase.TRANSLATING, chapterIndex, batch)
            return translator.translateBatch(
                book = book,
                chapterIndex = chapterIndex,
                paragraphs = paragraphsOf(book, chapterIndex),
                paraIndexes = batch,
                sourceLang = sourceLang(),
                targetLang = targetLang(),
                translatorName = translatorName(),
            )
        } finally {
            OcrLock.release()
            _state.value = NovelQueueState()
        }
    }

    /** 轮询等锁：拿到 true；超时 false。 */
    private suspend fun acquireLockWithWait(): Boolean {
        var waited = 0L
        while (!OcrLock.tryAcquire()) {
            if (waited >= LOCK_WAIT_TIMEOUT_MS) return false
            delay(LOCK_POLL_MS)
            waited += LOCK_POLL_MS
        }
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = NovelQueueState()
    }

    /**
     * 翻译面板开合：**打开即暂停**（与漫画 `pauseToManual` 同义，「回退手动」由宿主做）。
     *
     * 用户打开面板多半是要改设置，此时继续按旧设置翻下去既浪费额度也可能翻错；
     * 关闭面板后由调用方按新参数重启队列。
     */
    fun setPanelOpen(open: Boolean) {
        panelOpen = open
    }
}
