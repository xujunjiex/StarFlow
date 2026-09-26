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

/**
 * 翻译窗口计算（纯函数，可单测）。
 *
 * 窗口以**章**为单位而不是页：章的边界是内容自带的，页的划分随字号与译文变化 ——
 * 按页算窗口会在用户改字号后指向别处。
 */
object NovelQueueWindow {

    /** 窗口只对「自动 / 增量」有意义；手动不进队列（[NovelTranslateMode.MANUAL] 返回空窗口）。 */
    fun forMode(mode: NovelTranslateMode, current: Int, ahead: Int, chapterCount: Int): List<Int> {
        if (current !in 0 until chapterCount) return emptyList()
        return when (mode) {
            NovelTranslateMode.AUTO -> listOf(current)
            NovelTranslateMode.AHEAD -> {
                val n = ahead.coerceAtLeast(1)
                (current until (current + n)).filter { it in 0 until chapterCount }
            }
            else -> emptyList()
        }
    }

    /**
     * 挑下一个待翻的章。
     *
     * ⚠️ **失败章也要跳过**。只跳「已成功」的话，内容性失败（空章、模型返回空）会被每一轮
     * 重新挑中 → 每轮重试同一章、永远翻不动，而且因为「有章正在翻」不成立，用户看不到任何
     * 进度提示，只觉得队列卡死了。
     */
    fun nextPending(window: List<Int>, translated: Set<Int>, failed: Set<Int>): Int? =
        window.firstOrNull { it !in translated && it !in failed }
}

/** 队列阶段。 */
enum class NovelQueuePhase {
    /** 未启用或面板打开中。 */
    IDLE,
    /** 抢翻译锁中（漫画/悬浮窗翻译在跑时会出现）。 */
    WAITING_LOCK,
    /** 正在翻某一章。 */
    TRANSLATING,
    /** 窗口内全部翻过或全部失败，等用户切章。 */
    DRAINED,
}

data class NovelQueueState(
    val phase: NovelQueuePhase = NovelQueuePhase.IDLE,
    val chapterIndex: Int = -1,
    val translatedChapters: Int = 0,
    val windowSize: Int = 0,
)

/**
 * 自动翻译串行队列。
 *
 * 语义与漫画阅读器的翻译队列对齐（那套已踩平坑）：每轮 debounce → 重读当前章 → 取窗口内
 * 第一个待翻章 → 串行翻；**切章不重启队列**（窗口每轮自己重算），正在翻的章不被打断。
 *
 * ⚠️ **抢 `OcrLock` 必须轮询等待，不能拿不到就 return**。`OcrLock.tryAcquire()` 是非阻塞的
 * （忙就返回 false，没有 await）。直接 return 会让本轮什么都不做，而下一轮又会选到同一个
 * 仍未翻的章 → 每 debounce 周期空转一次、永远翻不动，且「有章正在翻」这个判据不成立，
 * 用户连进度提示都看不到。
 */
class NovelTranslationQueue(
    private val scope: CoroutineScope,
    private val translator: NovelChapterTranslator,
    /** 取某章的段落（由仓库提供，带缓存）。 */
    private val paragraphsOf: suspend (ImportedNovel, Int) -> List<NovelParagraph>,
    private val sourceLang: () -> String,
    private val targetLang: () -> String,
    private val translatorName: () -> String,
    private val batchSize: () -> Int,
) {

    private companion object {
        const val TAG = "NovelTranslationQueue"

        /** `OcrLock` 没有 await，只能轮询。 */
        const val LOCK_POLL_MS = 200L
        const val LOCK_WAIT_TIMEOUT_MS = 30_000L

        /** 全部翻完后的空转间隔（等用户切章）。 */
        const val DRAINED_POLL_MS = 800L
    }

    private val _state = MutableStateFlow(NovelQueueState())
    val state: StateFlow<NovelQueueState> = _state.asStateFlow()

    private var job: Job? = null
    private var panelOpen = false

    /** 本次会话内已判定失败的章：进程内记住，避免每轮重挑同一个空章。 */
    private var sessionFailed = mutableSetOf<Int>()

    /**
     * 启动（或按新参数重启）队列。
     *
     * @param currentChapter 每轮**重新求值**（lambda 而非值）：切章不重启队列，窗口自动跟上。
     */
    fun start(
        book: ImportedNovel,
        mode: NovelTranslateMode,
        aheadCount: Int,
        debounceMs: Int,
        currentChapter: () -> Int,
        onChapterTranslated: suspend (Int) -> Unit,
    ) {
        job?.cancel()
        sessionFailed = mutableSetOf()
        if (mode == NovelTranslateMode.MANUAL) {
            _state.value = NovelQueueState()
            return
        }
        job = scope.launch {
            while (isActive) {
                if (panelOpen) {
                    _state.value = NovelQueueState()
                    delay(LOCK_POLL_MS)
                    continue
                }
                delay(debounceMs.toLong())

                val current = currentChapter()
                val window = NovelQueueWindow.forMode(mode, current, aheadCount, book.chapterCount)
                if (window.isEmpty()) {
                    _state.value = NovelQueueState()
                    continue
                }

                val stats = translator.chapterStats(book)
                val done = stats.filterValues { it.total > 0 && it.success >= it.total }.keys
                val failed = sessionFailed + stats.filterValues { it.total > 0 && it.success == 0 }.keys
                val pending = NovelQueueWindow.nextPending(window, done, failed)
                if (pending == null) {
                    _state.value = NovelQueueState(NovelQueuePhase.DRAINED, current, done.size, window.size)
                    delay(DRAINED_POLL_MS)
                    continue
                }

                _state.value = NovelQueueState(NovelQueuePhase.WAITING_LOCK, pending, done.size, window.size)
                if (!acquireLockWithWait()) {
                    // 超时（别的翻译任务一直占着）→ 记账跳过，避免同一章无限重试刷屏
                    LogCollector.w(TAG, "等待翻译锁超时，本轮跳过 ch=$pending")
                    sessionFailed += pending
                    continue
                }
                try {
                    _state.value = NovelQueueState(NovelQueuePhase.TRANSLATING, pending, done.size, window.size)
                    val paragraphs = paragraphsOf(book, pending)
                    var got = 0
                    translator.translateChapter(
                        book = book,
                        chapterIndex = pending,
                        paragraphs = paragraphs,
                        sourceLang = sourceLang(),
                        targetLang = targetLang(),
                        translatorName = translatorName(),
                        batchSize = batchSize(),
                    ).collect { progress -> got = progress.translations.size }
                    if (got == 0) sessionFailed += pending
                    else onChapterTranslated(pending)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LogCollector.e(TAG, "第 $pending 章翻译失败", e)
                    sessionFailed += pending
                } finally {
                    OcrLock.release()
                }
            }
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
     * 翻译面板开合：**打开即暂停**。
     *
     * 用户打开面板多半是要改设置，此时继续按旧设置翻下去既浪费额度也可能翻错；
     * 关闭面板后由调用方按新参数重启队列。
     */
    fun setPanelOpen(open: Boolean) {
        panelOpen = open
    }
}
