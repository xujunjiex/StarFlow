package com.moe.starflow.mangaimport.translate

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.preference.PreferenceManager
import com.moe.starflow.R
import com.moe.starflow.data.ImportedPageTranslation
import com.moe.starflow.data.TranslationCacheManager
import com.moe.starflow.data.TranslationHistoryDatabase
import com.moe.starflow.manga.OcrLock
import com.moe.starflow.manga.TranslationCancelledException
import com.moe.starflow.manga.TranslateUtils
import com.moe.starflow.manga.engine.DetectionBridge
import com.moe.starflow.manga.pipeline.BatchOutcome
import com.moe.starflow.manga.pipeline.BatchPipelineConfig
import com.moe.starflow.manga.pipeline.BatchPipelineHost
import com.moe.starflow.manga.pipeline.IncrementalBatchPipeline
import com.moe.starflow.manga.render.OverlayRenderer
import com.moe.starflow.manga.state.RegionCacheManager
import com.moe.starflow.manga.types.DetEngine
import com.moe.starflow.manga.types.OcrEngine
import com.moe.starflow.manga.types.TextBlockInfo
import com.moe.starflow.manga.types.TranslatedBubble
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.translate.TranslationTextAPI
import com.moe.starflow.translate.widget.BallStateManager
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import translationapi.TranslatorFactory
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 翻译阶段（供状态浮层显示进度：检测/翻译/成功/失败/队列耗尽）。 */
enum class ReaderTranslatePhase {
    DETECTING,
    TRANSLATING,
    SUCCESS,
    FAILED,
    /** 增量队列跑完（窗口内无可翻页）—— 提示用户"翻页后继续"，随后自动消失。 */
    QUEUE_DRAINED,
}

/** 翻译按钮点击的结果，由 Activity 负责呈现（提示/浮层）。 */
sealed interface TranslateClick {
    /** 单击：**只提示，不打断**（取消必须双击）。[text] 已含页码等信息。 */
    data class Hint(val text: String) : TranslateClick
    /** 双击：已强制取消并回退到手动模式。 */
    data object CancelledToManual : TranslateClick
    /** 手动模式下开始翻译当前页（已在控制器内启动）。 */
    data object StartedManual : TranslateClick
    /** OCR 引擎被占用（截屏翻译正在翻 / 上一次取消的任务还没退出 native）→ 提示用户稍后。 */
    data object Busy : TranslateClick
    /** 无事可做（空闲时双击 / Webtoon、双页模式禁用）。 */
    data object Ignored : TranslateClick
}

/**
 * 阅读器翻译编排器：每页记录（Room）+ 渲染缓存（LRU）+ 三态 + **三种翻译模式**。
 *
 * 三种模式（[MODE_MANUAL] / [MODE_AUTO] / [MODE_AHEAD]）共用同一个**串行队列引擎**，只有窗口大小不同：
 * - 自动 = 窗口 1 页（只翻当前页）
 * - 增量 = 窗口 N 页（当前页 + 往后 N 页）
 *
 * 队列每轮都**重新读取当前页**并重算窗口，因此翻页不需要重启队列；正在翻译的那页不会被重复挑中，
 * 也不会被打断（翻完当前页才进下一轮）。
 */
class ReaderTranslationController(
    private val context: Context,
    private val manga: ImportedManga,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "ReaderTranslate"
        private const val RENDER_CACHE_KB = 100 * 1024 // 100MB 预算（原图同量级，够 3 态来回切）

        const val MODE_MANUAL = 0
        const val MODE_AUTO = 1
        const val MODE_AHEAD = 2

        /** 队列每轮之间的等待下限，避免窗口内无活时死循环空转。 */
        private const val QUEUE_IDLE_TICK_MS = 200L

        private fun renderKey(pageIndex: Int, mode: TranslationCacheManager.OverlayMode) =
            "page:$pageIndex:${mode.name}"

        /** 首批半成品的独立缓存 key（见 [cachedDisplayBitmap]）。 */
        private fun partialKey(pageIndex: Int) = "page:$pageIndex:PARTIAL"
    }

    val version = MutableStateFlow(0L)
    val translateMode = MutableStateFlow(MODE_MANUAL)

    /** 翻译启动前的停留防抖（翻页/窗口重算都走它）。面板可调。 */
    val debounceMs = MutableStateFlow(500)

    /** 增量模式向后翻多少页（1..10）。面板可调。 */
    val aheadPages = MutableStateFlow(5)

    /** 队列正在翻译的页（-1 = 空闲）。供状态浮层显示"正在翻译第 N 页"。 */
    val queuePage = MutableStateFlow(-1)

    private val db = TranslationHistoryDatabase.getInstance(context)
    private val dao = db.importedPageTranslationDao()
    private val cacheManager = TranslationCacheManager(context)
    private val rows = MutableStateFlow<Map<Int, ImportedPageTranslation>>(emptyMap())

    private val appPrefs get() = PreferenceManager.getDefaultSharedPreferences(context)
    private val customPrefs get() = CustomPreference.getInstance(context)

    /** 漫画身份指纹（title|addedAt）：重导复用 id 时旧记录指纹不匹配 → 忽略，杜绝串数据。 */
    private val mangaKey: String = "${manga.title}|${manga.addedAt}"

    private val renderLru = object : LruCache<String, Bitmap>(RENDER_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap) =
            value.allocationByteCount.coerceAtLeast(value.rowBytes * value.height) / 1024
    }

    /** 各页当前三态（内存态）。并发安全（IO 页图提供者 + 主线程切换都会读写）。 */
    private val currentVisualByPage = ConcurrentHashMap<Int, TranslationCacheManager.OverlayMode>()

    // ========== 宿主注入（避免控制器反向依赖 Activity / ReaderPageSource） ==========

    private var loadFull: (Int) -> Bitmap? = { null }
    private var currentPageProvider: () -> Int = { 0 }
    private var pageCount: () -> Int = { 0 }

    /** 页图 / 当前页 / 总页数 由 Activity 注入。 */
    fun bind(
        loadFull: (Int) -> Bitmap?,
        currentPage: () -> Int,
        pageCount: () -> Int,
    ) {
        this.loadFull = loadFull
        this.currentPageProvider = currentPage
        this.pageCount = pageCount
    }

    // ========== 队列引擎 ==========

    private var queueJob: Job? = null

    /** 用户取消标志：翻译与队列都读它。 */
    private val cancelFlag = AtomicBoolean(false)

    /** 翻译面板是否打开：打开时暂停队列（用户在调设置，不该后台继续翻），关闭时恢复。 */
    private var panelOpen = false

    /** 是否有翻译在途（队列在跑或手动翻译中）。 */
    val isBusy: Boolean get() = queueJob?.isActive == true || manualJob?.isActive == true
    private var manualJob: Job? = null

    /** 切换模式。离开手动模式会启动队列；回到手动模式会停止队列。 */
    fun setMode(mode: Int) {
        if (translateMode.value == mode) return
        // 切走手动模式时，把在途的手动翻译停掉，避免与队列抢 OcrLock
        if (mode != MODE_MANUAL) {
            manualJob?.cancel()
            manualJob = null
        }
        translateMode.value = mode
        restartQueue()
        version.value += 1
    }

    /**
     * 翻译面板开合。
     * - 打开：**暂停并回退到手动模式**（用户要调设置，后台不该继续烧），并回调 [onPaused] 弹提示
     * - 关闭：恢复队列（模式已是手动则不动）—— 即"翻译要等退出面板后才开始"
     */
    fun setPanelOpen(open: Boolean) {
        if (panelOpen == open) return
        panelOpen = open
        if (open) {
            pauseToManual()
        } else {
            restartQueue()
        }
    }

    /** 退出阅读器时调用：停止翻译并回退手动（不弹提示，由调用方决定）。 */
    fun shutdown() {
        cancelEverything()
        translateMode.value = MODE_MANUAL
        version.value += 1
    }

    /** 停止一切在途翻译并回退到手动模式；原本有在跑则回调 [onPaused]。 */
    private fun pauseToManual() {
        val wasActive = translateMode.value != MODE_MANUAL ||
            queueJob?.isActive == true || manualJob?.isActive == true
        cancelEverything()
        if (translateMode.value != MODE_MANUAL) {
            translateMode.value = MODE_MANUAL
            version.value += 1
        }
        if (wasActive) onPaused()
    }

    /**
     * 翻页落定后调用。
     *
     * 两件事：
     * 1. **若队列已跑完（窗口没活了）而模式仍是自动/增量，则重新启动** ——
     *    窗口是跟着当前页滑动的，翻页后就有了新工作，不重启的话用户翻到新页会一直不翻。
     * 2. 面板打开期间不启动（等退出面板）。
     */
    fun onCurrentPageChanged() {
        if (translateMode.value != MODE_MANUAL && queueJob?.isActive != true) {
            restartQueue()
        }
    }

    private fun restartQueue() {
        queueJob?.cancel()
        queueJob = null
        queuePage.value = -1
        if (translateMode.value == MODE_MANUAL) return
        // 面板打开时不启动：翻译要等用户退出面板后才开始
        if (panelOpen) return

        queueJob = scope.launch(Dispatchers.IO) {
            LogCollector.d(TAG, "queue start mode=${translateMode.value}")
            // 窗口跑完（而非被取消/切模式）→ 收官时提示"队列已耗尽，翻页后继续"
            var drained = false
            try {
                while (isActive) {
                    // 防抖：翻页期间反复重算也没关系，停留够久才开始翻
                    delay(debounceMs.value.toLong().coerceAtLeast(QUEUE_IDLE_TICK_MS))
                    if (translateMode.value == MODE_MANUAL) break

                    val total = pageCount()
                    if (total <= 0) break
                    val cur = currentPageProvider().coerceIn(0, total - 1)
                    val window = if (translateMode.value == MODE_AUTO) 1 else aheadPages.value
                    val end = (cur + window).coerceAtMost(total)

                    val target = (cur until end).firstOrNull { p -> isTranslatable(p) }
                    if (target == null) {
                        LogCollector.d(TAG, "queue drained: 窗口 [$cur, $end) 无待翻页")
                        drained = true
                        break
                    }
                    queuePage.value = target
                    try {
                        runTranslate(target, fromQueue = true)
                    } finally {
                        queuePage.value = -1
                    }
                }
            } finally {
                queuePage.value = -1
                LogCollector.d(TAG, "queue end")
                if (drained) onPhase(ReaderTranslatePhase.QUEUE_DRAINED, null)
            }
        }
    }

    /**
     * 该页是否需要翻译。
     * **只有「未翻译(IDLE)」才翻**：`SUCCESS` 显然跳过；`FAILED` 也跳过 ——
     * 否则内容性失败（如空白页 OCR 为空）会被窗口反复重挑，**无限重试**。
     */
    private fun isTranslatable(page: Int): Boolean =
        stateOf(page) == ImportedPageTranslation.STATE_IDLE

    // ========== 翻译按钮 ==========

    /**
     * 翻译按钮点击（三模式统一）：
     * - **单击** → 只弹提示，**绝不打断**（提示"正在翻译 Pxx 页，双击暂停翻译"）
     * - **双击** → 强制取消当前翻译并回退到手动模式
     * - 手动模式空闲时单击 → 开始翻译当前页
     *
     * [isDouble] 由 Activity 按双击时间窗判定后传入。
     */
    fun onTranslateButtonClick(isDouble: Boolean): TranslateClick {
        val mode = translateMode.value
        val busy = mode != MODE_MANUAL || manualJob?.isActive == true

        if (busy) {
            if (!isDouble) return TranslateClick.Hint(busyHintText(mode))
            // 双击：强制取消 + 回退手动
            cancelEverything()
            translateMode.value = MODE_MANUAL
            version.value += 1
            return TranslateClick.CancelledToManual
        }

        // 手动模式且空闲
        if (isDouble) return TranslateClick.Ignored
        // 引擎被占用（截屏翻译在翻 / 上一次取消的任务仍卡在 native OCR 中，PP-OCR 要 1~3s 才退出）：
        // 直接反馈，否则这一击被静默吞掉、按钮看起来像坏了。
        if (OcrLock.isRunning) return TranslateClick.Busy
        val page = currentPageProvider()
        manualJob = scope.launch(Dispatchers.IO) { runTranslate(page, fromQueue = false) }
        return TranslateClick.StartedManual
    }

    /**
     * 单击提示文案。判据是**有没有正在翻的页**，不是"队列在不在跑"：
     * - `queuePage >= 0`：正在翻某一页 → 报状态 + 页码
     * - `queuePage < 0`（**队列耗尽 / 防抖等待 / 稳定性检测中**）：其实没在翻 →
     *   提示"请双击退出…后重试"。若这里仍报"正在翻译中"，用户会看到一条永远不动的假进度。
     */
    private fun busyHintText(mode: Int): String {
        val p = queuePage.value
        return when {
            p >= 0 && mode == MODE_AUTO ->
                context.getString(R.string.reader_translate_hint_auto_page, p + 1)
            p >= 0 ->
                context.getString(R.string.reader_translate_hint_ahead_page, p + 1)
            mode == MODE_AUTO ->
                context.getString(R.string.reader_translate_hint_auto_idle)
            else ->
                context.getString(R.string.reader_translate_hint_ahead_idle)
        }
    }

    /** 取消在途翻译与队列，并把「翻译中」的记录退回「未翻译」。 */
    private fun cancelEverything() {
        cancelFlag.set(true)
        queueJob?.cancel()
        queueJob = null
        manualJob?.cancel()
        manualJob = null
        queuePage.value = -1
        // ⚠️ 这里**不**调 OcrLock.release()：在途的 runTranslate 会在 finally 里释放。
        // 若此处提前释放，新翻译可能在旧协程仍处于 native 调用中时抢到锁 → 引擎单例并发崩溃。
        scope.launch(Dispatchers.IO) {
            // 取消 = 不存库、不算失败：把开始翻译时预写的 TRANSLATING 退回 IDLE，
            // 否则该页会永久卡在「翻译中」而再也翻不了。
            rows.value.filterValues { it.state == ImportedPageTranslation.STATE_TRANSLATING }
                .keys.forEach { page ->
                    try {
                        upsertState(page, ImportedPageTranslation.STATE_IDLE)
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "取消后重置状态失败 page=$page", e)
                    }
                }
        }
    }

    // ========== 记录读取 ==========

    /** 打开阅读器时载入全部记录，并清理上次异常退出遗留的「翻译中」。 */
    suspend fun load() {
        // 先清残留：上次退出/崩溃时被掐死的翻译会永久停在 TRANSLATING（见 DAO 注释）
        try {
            dao.resetTranslating(manga.id, mangaKey)
        } catch (e: Exception) {
            LogCollector.e(TAG, "resetTranslating failed", e)
        }
        rows.value = dao.forManga(manga.id, mangaKey).associateBy { it.pageIndex }
        version.value += 1
    }

    fun stateOf(pageIndex: Int): Int =
        rows.value[pageIndex]?.state ?: ImportedPageTranslation.STATE_IDLE

    fun failMessageOf(pageIndex: Int): String? = rows.value[pageIndex]?.failMessage

    fun recordOf(pageIndex: Int): ImportedPageTranslation? = rows.value[pageIndex]

    /** 全部记录（pageIndex 升序），供面板。 */
    fun records(): List<ImportedPageTranslation> = rows.value.values.sortedBy { it.pageIndex }

    /** 已成功翻译的页码集合，供进度条绿色区间。 */
    fun translatedPages(): Set<Int> =
        rows.value.filterValues { it.state == ImportedPageTranslation.STATE_SUCCESS }.keys

    // ========== 单页翻译（手动 / 队列共用） ==========

    /**
     * 翻译一页。全程在 [OcrLock] 互斥下（与截屏翻译共用同一把锁）。
     *
     * [fromQueue] = true 时表示这是后台队列页：**只有该页恰好是用户正在看的那页才渲染上屏** ——
     * 后台预翻的页面渲染出来没人看，纯烧 CPU 和 100MB 渲染缓存。
     */
    private suspend fun runTranslate(page: Int, fromQueue: Boolean) {
        if (!OcrLock.tryAcquire()) {
            LogCollector.d(TAG, "runTranslate: OcrLock 被占用，跳过 page=$page")
            return
        }
        cancelFlag.set(false)

        // ⚠️ 必须在 try 之外定义：catch 分支也要用它上报失败阶段
        val shouldRender = { page == currentPageProvider() }
        val phase: (ReaderTranslatePhase, String?) -> Unit = { p, msg ->
            // 队列页的**检测/翻译阶段照常上报**（用户要求顶部状态栏实时跟随当前页数），
            // 但成功/失败不弹 —— 连续翻 10 页会弹 10 次"翻译完成"，太吵。
            if (fromQueue && (p == ReaderTranslatePhase.SUCCESS || p == ReaderTranslatePhase.FAILED)) {
                if (shouldRender()) onPhase(p, msg)
            } else {
                onPhase(p, msg)
            }
        }

        try {
            upsertState(page, ImportedPageTranslation.STATE_TRANSLATING)
            // 清掉上一轮可能残留的半成品：否则重翻时 cachedDisplayBitmap 会先把旧半成品显示出来
            renderLru.remove(partialKey(page))

            phase(ReaderTranslatePhase.DETECTING, null)

            val (det, ocr) = try {
                TranslationEngineInit.ensureReady(context)
            } catch (e: Exception) {
                LogCollector.e(TAG, "engine init failed page=$page", e)
                val msg = context.getString(R.string.reader_translate_model_missing, e.message.orEmpty())
                fail(page, "OCR_MODEL_MISSING", msg)
                phase(ReaderTranslatePhase.FAILED, msg)
                return
            }

            val translator: TranslationTextAPI? =
                TranslatorFactory.create(context, customPrefs, TranslatorFactory.Mode.MANGA)
            if (translator == null) {
                val msg = context.getString(R.string.reader_translate_api_not_configured)
                fail(page, "TRANSLATION_API_NOT_CONFIGURED", msg)
                phase(ReaderTranslatePhase.FAILED, msg)
                return
            }

            val bitmap = loadFull(page)
            if (bitmap == null) {
                val msg = context.getString(R.string.reader_translate_load_failed)
                fail(page, "PROCESS_EXCEPTION", msg)
                phase(ReaderTranslatePhase.FAILED, msg)
                return
            }

            val srcLang = customPrefs.getString("Source_Language", "ja")
            val tgtLang = customPrefs.getString("Target_Language", "zh")
            val overlayConfig = cacheManager.getOverlayConfig(appPrefs)

            // 增量模式禁用分批与流式：翻的是用户没在看的页面，"先出一部分"没有观众
            val batchingOn = translateMode.value != MODE_AHEAD

            val translated: List<TranslatedBubble> =
                translateWithPipelineOn(
                    enabled = batchingOn,
                    bitmap = bitmap, det = det, ocr = ocr,
                    srcLang = srcLang, tgtLang = tgtLang,
                    translator = translator,
                    phase = phase,
                    page = page,
                ) ?: return   // 已写失败记录

            if (translated.isEmpty()) {
                val msg = context.getString(R.string.reader_translate_empty)
                fail(page, "TRANSLATE_EMPTY", msg)
                phase(ReaderTranslatePhase.FAILED, msg)
                return
            }

            // 渲染：仅"正在看的那页"才预热译文图
            if (shouldRender()) {
                renderInto(page, translated, TranslationCacheManager.OverlayMode.TRANSLATED, bitmap, overlayConfig)
            }

            val row = ImportedPageTranslation(
                mangaId = manga.id, pageIndex = page,
                state = ImportedPageTranslation.STATE_SUCCESS,
                sourceText = PageTranslationCodec.sourceText(translated),
                translatedText = PageTranslationCodec.translatedText(translated),
                bubbleRects = PageTranslationCodec.bubbleRects(translated),
                failCode = null, failMessage = null,
                updatedAtMs = System.currentTimeMillis(),
                mangaKey = mangaKey,
                translatorName = TranslateUtils.buildTranslatorDisplayName(translator, det, ocr, appPrefs),
                sourceLang = srcLang,
                targetLang = tgtLang,
            )
            rows.value = rows.value + (page to row)
            dao.upsert(row)
            version.value += 1
            onVisual()
            phase(ReaderTranslatePhase.SUCCESS, null)
            LogCollector.d(TAG, "translated page=$page bubbles=${translated.size} fromQueue=$fromQueue")
        } catch (e: TranslationCancelledException) {
            // 用户主动停止：**不能**保持 TRANSLATING（会永久卡死该页），退回未翻译
            LogCollector.d(TAG, "translate cancelled page=$page")
            withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { upsertState(page, ImportedPageTranslation.STATE_IDLE) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程被取消（退出阅读器 / 强制取消）：必须在 NonCancellable 里把状态退回 IDLE。
            // ⚠️ 普通 catch 里调挂起的 dao.upsert 会立刻再抛 CancellationException 而写不进库，
            // 记录就永久停在 TRANSLATING（进阅读器时的 resetTranslating 能兜底，但不该依赖它）。
            LogCollector.d(TAG, "translate coroutine cancelled page=$page")
            withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { upsertState(page, ImportedPageTranslation.STATE_IDLE) }
            }
            throw e
        } catch (e: Exception) {
            LogCollector.e(TAG, "translate page=$page failed", e)
            val msg = e.message ?: "Unknown error"
            fail(page, "PROCESS_EXCEPTION", msg)
            phase(ReaderTranslatePhase.FAILED, msg)
        } finally {
            OcrLock.release()
        }
    }

    /**
     * 走分批管线（可在半途上屏）或普通一次性路径。
     * 返回 null 表示失败记录已写好，调用方直接结束。
     */
    private suspend fun translateWithPipelineOn(
        enabled: Boolean,
        bitmap: Bitmap,
        det: DetEngine,
        ocr: OcrEngine,
        srcLang: String,
        tgtLang: String,
        translator: TranslationTextAPI,
        phase: (ReaderTranslatePhase, String?) -> Unit,
        page: Int,
    ): List<TranslatedBubble>? {
        val cfg = BatchPipelineConfig(
            detEngine = det,
            ocrEngine = ocr,
            sourceLang = srcLang,
            targetLang = tgtLang,
            textDirection = cacheManager.getOverlayConfig(appPrefs).textDirection,
            keepTextFree = appPrefs.getBoolean("Manga_Keep_Text_Free", true),
            prefs = customPrefs,
            // 分批开关：增量模式强制关，其余跟随用户设置
            incrementalEnabled = enabled && appPrefs.getBoolean("Incremental_Render", true),
            isAutoTranslating = false,
        )
        val host = ReaderBatchHost(bitmap, translator, phase, page)
        val pipeline = IncrementalBatchPipeline(host, scope, cfg)

        val outcome = try {
            pipeline.run(bitmap)
        } catch (e: TranslationCancelledException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消（切模式 / 退出阅读器）：必须重抛，不能当"处理异常"写成 FAILED ——
            // 那会先污染一次内存态（UI 闪一下失败），再由外层 catch 改回 IDLE，日志也会误导。
            throw e
        } catch (e: Exception) {
            LogCollector.e(TAG, "pipeline failed page=$page", e)
            null
        }

        when (outcome) {
            null -> {
                val msg = context.getString(R.string.reader_translate_ocr_empty)
                fail(page, "PROCESS_EXCEPTION", msg)
                phase(ReaderTranslatePhase.FAILED, msg)
                return null
            }
            is BatchOutcome.Handled -> return outcome.translated
            // 分批路径处理了但没结果（未检测到文字）：翻失败，落 OCR_EMPTY
            is BatchOutcome.HandledEmpty -> {
                val msg = context.getString(R.string.reader_translate_ocr_empty)
                fail(page, "OCR_EMPTY", msg)
                phase(ReaderTranslatePhase.FAILED, msg)
                return null
            }
            is BatchOutcome.NotApplicable -> {
                // 普通路径（增量模式 / 引擎组合不支持分批 / 气泡太少）
                return translatePlain(bitmap, det, ocr, srcLang, tgtLang, translator, phase, page)
            }
        }
    }

    /** 不分批的一次性路径（原阶段一实现）。增量模式也走这里，且**不接流式回调**。 */
    private suspend fun translatePlain(
        bitmap: Bitmap,
        det: DetEngine,
        ocr: OcrEngine,
        srcLang: String,
        tgtLang: String,
        translator: TranslationTextAPI,
        phase: (ReaderTranslatePhase, String?) -> Unit,
        page: Int,
    ): List<TranslatedBubble>? {
        val blocks: List<TextBlockInfo> =
            DetectionBridge.runOCR(bitmap, srcLang, det.value, ocr.value, context)
        val overlayConfig = cacheManager.getOverlayConfig(appPrefs)
        val bubbleRegions = DetectionBridge.ocrToBubbleRegions(blocks, overlayConfig.textDirection)
        if (bubbleRegions.isEmpty()) {
            val msg = context.getString(R.string.reader_translate_ocr_empty)
            fail(page, "OCR_EMPTY", msg)
            phase(ReaderTranslatePhase.FAILED, msg)
            return null
        }

        phase(ReaderTranslatePhase.TRANSLATING, null)
        val streamingOn = translateMode.value != MODE_AHEAD &&
            appPrefs.getBoolean("Incremental_Render", true)
        return TranslateUtils.translateBubbles(
            translator, bubbleRegions, srcLang, tgtLang, customPrefs,
            isCancelled = { cancelFlag.get() },
            // 流式局部结果：仅非增量模式 + 开关打开时上屏
            onPartialBubbles = { partial -> if (streamingOn) showPartial(page, partial) },
        )
    }

    /** 分批管线的宿主钩子：进度落状态浮层、首批结果落页面（仅当前页）。 */
    private inner class ReaderBatchHost(
        private val pageBitmap: Bitmap,
        override val translator: TranslationTextAPI,
        private val phase: (ReaderTranslatePhase, String?) -> Unit,
        private val page: Int,
    ) : BatchPipelineHost {

        override val context: Context get() = this@ReaderTranslationController.context

        override suspend fun ensureEnginesReady(det: DetEngine, ocr: OcrEngine) {
            TranslationEngineInit.ensureReady(this@ReaderTranslationController.context)
        }

        /** 当前是第几批（1/2）。首批结果上屏后置 2 —— 管线的进度回调只给 resId，不带批次。 */
        private var batchIndex = 1

        override fun onProgress(textRes: Int) {
            val (p, text) = when (textRes) {
                // 与截屏翻译对齐：分批时明确写出「第几批」，用户才知道现在在干什么
                R.string.recognizing_half -> ReaderTranslatePhase.DETECTING to
                    context.getString(R.string.reader_translate_batch_detect, batchIndex)
                R.string.translating_do_not_tap -> ReaderTranslatePhase.TRANSLATING to
                    context.getString(R.string.reader_translate_batch_translate, 1)
                R.string.manga_translating -> ReaderTranslatePhase.TRANSLATING to
                    context.getString(R.string.reader_translate_batch_translate, batchIndex)
                R.string.manga_reading -> ReaderTranslatePhase.TRANSLATING to
                    context.getString(R.string.manga_reading)
                else -> ReaderTranslatePhase.DETECTING to context.getString(textRes)
            }
            phase(p, text)
        }

        override fun onToast(text: String, long: Boolean) = Unit   // 阅读器用状态浮层，不弹 Toast
        override fun onError(text: String) = Unit                  // 错误由 fail() 记录，不弹浮层
        override fun onBallState(state: BallStateManager.State) = Unit  // 阅读器没有悬浮球

        override fun onPartialRender(bubbles: List<TranslatedBubble>) = showPartial(page, bubbles)

        override suspend fun onBatchResult(bubbles: List<TranslatedBubble>) {
            // ⚠️ 必须写 PARTIAL key，不能走 renderInto（后者写 renderKey 且要求 state==SUCCESS）。
            // 此刻本页状态还是 TRANSLATING，而 cachedDisplayBitmap 对 TRANSLATING 只查 PARTIAL ——
            // 写错 key 会让首批结果**根本显示不出来**，分批形同虚设（这就是"分批没生效"的根因）。
            if (bubbles.isEmpty()) return
            if (page != currentPageProvider()) return   // 后台队列页不渲染
            val cfg = cacheManager.getOverlayConfig(appPrefs)
            val out = renderBubbles(pageBitmap, bubbles, TranslationCacheManager.OverlayMode.TRANSLATED, cfg)
            renderLru.put(partialKey(page), out)
            // 首批已出 → 后续进度就是第二批（管线回调不带批次，只能这样推）
            batchIndex = 2
            withContext(Dispatchers.Main) { onVisual() }
        }

        override fun isCancelled(): Boolean = cancelFlag.get()

        override fun contextHistory(): LinkedList<Pair<String, String>> = readerContextHistory

        override fun textCache(): RegionCacheManager = readerTextCache
    }

    /** 阅读器侧上下文历史（分批的批次间上下文用；阅读器按页翻译，正常不跨页复用）。 */
    private val readerContextHistory = LinkedList<Pair<String, String>>()
    private val readerTextCache = RegionCacheManager()

    // ========== 渲染 ==========

    /** 供适配器同步取图（IO 线程安全）：该页当前应显示的渲染图，无则 null（显示原图）。 */
    fun cachedDisplayBitmap(pageIndex: Int): Bitmap? = when (stateOf(pageIndex)) {
        ImportedPageTranslation.STATE_SUCCESS -> {
            val mode = currentVisual(pageIndex)
            if (mode == TranslationCacheManager.OverlayMode.PLAIN) null
            else renderLru.get(renderKey(pageIndex, mode))
        }
        // 翻译中：返回「首批半成品」如果有 —— 否则用户翻走再翻回时看不到已经翻好的那半页。
        // ⚠️ 必须用独立 key（PARTIAL）：不能用 renderKey(TRANSLATED)，
        // 否则会和最终整页结果混在一起，且失败/取消后残留一张永远刷不掉的半成品。
        ImportedPageTranslation.STATE_TRANSLATING -> renderLru.get(partialKey(pageIndex))
        else -> null
    }

    /**
     * 把「首批/流式半成品」渲染上屏 —— 仅当该页正是用户在看的那页。
     *
     * 后台队列预翻的页面渲染出来没人看，纯烧 CPU 和 100MB 渲染缓存，因此直接跳过。
     */
    private fun showPartial(page: Int, bubbles: List<TranslatedBubble>) {
        if (bubbles.isEmpty()) return
        if (page != currentPageProvider()) return
        scope.launch(Dispatchers.IO) {
            val cfg = cacheManager.getOverlayConfig(appPrefs)
            val bmp = loadFull(page) ?: return@launch
            val out = renderBubbles(bmp, bubbles, TranslationCacheManager.OverlayMode.TRANSLATED, cfg)
            renderLru.put(partialKey(page), out)
            kotlinx.coroutines.withContext(Dispatchers.Main) { onVisual() }
        }
    }

    /** 取某页某态的图。PLAIN=原图（loadFull）；译文/原文=缓存命中或实时渲染。 */
    suspend fun visualBitmap(
        pageIndex: Int,
        mode: TranslationCacheManager.OverlayMode,
    ): Bitmap? = when (mode) {
        TranslationCacheManager.OverlayMode.PLAIN -> loadFull(pageIndex)
        else -> {
            val key = renderKey(pageIndex, mode)
            renderLru.get(key) ?: run {
                val row = rows.value[pageIndex] ?: return null
                val config = cacheManager.getOverlayConfig(appPrefs)
                val bubbles = PageTranslationCodec.fromRow(row, config.fontSize, config.bgColor) ?: return null
                val orig = loadFull(pageIndex) ?: return null
                val out = renderBubbles(orig, bubbles, mode, config)
                renderLru.put(key, out)
                out
            }
        }
    }

    private fun renderBubbles(
        original: Bitmap,
        bubbles: List<TranslatedBubble>,
        mode: TranslationCacheManager.OverlayMode,
        cfg: TranslationCacheManager.OverlayConfig,
    ): Bitmap = OverlayRenderer.renderOverlay(
        original = original,
        regions = bubbles,
        fontSize = cfg.fontSize,
        autoFit = cfg.autoFit,
        textColor = cfg.textColor,
        bgColor = cfg.bgColor,
        useOriginalText = mode == TranslationCacheManager.OverlayMode.ORIGINAL,
        verticalDirection = cfg.textDirection,
        // 阅读器译图渲染也要用自定义结果字体（Custom_Result_Font），否则恒为系统字体
        fontTypeface = OverlayRenderer.loadResultTypeface(context, customPrefs),
    )

    /** 渲染并预热译文图缓存，同时切到该态。最终结果写入后**丢弃半成品**，避免残留旧图。 */
    private fun renderInto(
        pageIndex: Int,
        bubbles: List<TranslatedBubble>,
        mode: TranslationCacheManager.OverlayMode,
        original: Bitmap,
        cfg: TranslationCacheManager.OverlayConfig,
    ) {
        renderLru.put(renderKey(pageIndex, mode), renderBubbles(original, bubbles, mode, cfg))
        renderLru.remove(partialKey(pageIndex))
        currentVisualByPage[pageIndex] = mode
    }

    // ========== 三态切换 ==========

    /** 循环切换：PLAIN → ORIGINAL → TRANSLATED → PLAIN。无成功记录页忽略。 */
    fun cycleVisual(pageIndex: Int) {
        if (stateOf(pageIndex) != ImportedPageTranslation.STATE_SUCCESS) return
        val order = listOf(
            TranslationCacheManager.OverlayMode.PLAIN,
            TranslationCacheManager.OverlayMode.ORIGINAL,
            TranslationCacheManager.OverlayMode.TRANSLATED,
        )
        val cur = order.indexOf(currentVisual(pageIndex)).let { if (it < 0) 0 else it }
        currentVisualByPage[pageIndex] = order[(cur + 1) % order.size]
        version.value += 1
        onVisual()
    }

    /** 当前页显示态（成功页默认译文，其余默认原图）。 */
    fun currentVisual(pageIndex: Int): TranslationCacheManager.OverlayMode =
        currentVisualByPage[pageIndex]
            ?: (if (stateOf(pageIndex) == ImportedPageTranslation.STATE_SUCCESS) {
                TranslationCacheManager.OverlayMode.TRANSLATED
            } else {
                TranslationCacheManager.OverlayMode.PLAIN
            })

    // ========== UI 回调（由 Activity 注入） ==========

    /** 页面显示需要刷新（三态切换 / 翻译完成）。 */
    var onVisual: () -> Unit = {}

    /** 翻译阶段变化（检测中/翻译中/完成/失败/队列耗尽）。 */
    var onPhase: (ReaderTranslatePhase, String?) -> Unit = { _, _ -> }

    /** 翻译被暂停并回退到手动（打开面板 / 退出阅读器）→ Activity 弹底部提示。 */
    var onPaused: () -> Unit = {}

    // ========== 私有：写记录 ==========

    private suspend fun upsertState(pageIndex: Int, state: Int) {
        val old = rows.value[pageIndex]
        val row = ImportedPageTranslation(
            mangaId = manga.id, pageIndex = pageIndex, state = state,
            sourceText = old?.sourceText, translatedText = old?.translatedText,
            bubbleRects = old?.bubbleRects, failCode = old?.failCode,
            failMessage = old?.failMessage, updatedAtMs = System.currentTimeMillis(),
            mangaKey = mangaKey,
        )
        rows.value = rows.value + (pageIndex to row)
        dao.upsert(row)
        version.value += 1
    }

    private suspend fun fail(pageIndex: Int, code: String, message: String) {
        rows.value = rows.value + (pageIndex to ImportedPageTranslation(
            mangaId = manga.id, pageIndex = pageIndex, state = ImportedPageTranslation.STATE_FAILED,
            failCode = code, failMessage = message, updatedAtMs = System.currentTimeMillis(),
            mangaKey = mangaKey,
        ))
        dao.upsert(rows.value.getValue(pageIndex))
        version.value += 1
    }
}
