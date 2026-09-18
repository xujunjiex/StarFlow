package com.moe.starflow.mangaimport.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
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
import com.moe.starflow.manga.types.TextDirection
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import translationapi.TranslatorFactory
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

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
    /** 无事可做（空闲时双击 / Webtoon 模式禁用）。 */
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

        /**
         * Webtoon 译图缓存。比翻页模式小得多：Webtoon 只预热**当前位置上下几页**
         * （见 [prewarmWebtoon]），同时live的译图本就少。
         */
        private const val WEBTOON_CACHE_KB = 64 * 1024

        /** Webtoon 预热半径：当前页 ± [WEBTOON_PREWARM_RADIUS] 页。 */
        const val WEBTOON_PREWARM_RADIUS = 2

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

    /**
     * 漫画身份指纹。
     *
     * ⚠️ **只用 `addedAt`，绝不要把 `title` 拼进来**：书架有「重命名」功能
     * （`ImportMangaFragment.renameSelected` → `manga.copy(title = name)`），
     * 一旦标题变化，含 title 的指纹就全部失配 → 整本书的译文读不出来（数据还在库里，
     * 但显示为未翻译，且 `purgeOrphanTranslations` 也清不到它们，会永久堆积）。
     * `addedAt` 每次导入唯一，本身已足够区分。
     */
    private val mangaKey: String = manga.addedAt.toString()

    private val renderLru = object : LruCache<String, Bitmap>(RENDER_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap) =
            value.allocationByteCount.coerceAtLeast(value.rowBytes * value.height) / 1024
    }

    /** 各页当前三态（内存态）。并发安全（IO 页图提供者 + 主线程切换都会读写）。 */
    private val currentVisualByPage = ConcurrentHashMap<Int, TranslationCacheManager.OverlayMode>()

    // ========== 宿主注入（避免控制器反向依赖 Activity / ReaderPageSource） ==========

    private var loadFull: (Int) -> Bitmap? = { null }
    private var loadWebtoon: ((Int) -> Bitmap?)? = null
    private var originalWidthOf: ((Int) -> Int)? = null
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

    /**
     * Webtoon 页图来源：采样解码器 + 原图宽查询。
     *
     * ⚠️ 不能用 [loadFull]：Webtoon 页可能是 1080×12000 的超长条，全解析一页就 50MB+，
     * 而 Webtoon 显示宽度只有屏宽 —— 必须按屏宽采样解码，再把气泡坐标等比缩回去。
     */
    fun bindWebtoonSource(loadWebtoon: (Int) -> Bitmap?, originalWidth: (Int) -> Int) {
        this.loadWebtoon = loadWebtoon
        this.originalWidthOf = originalWidth
    }

    // ========== 队列引擎 ==========

    private var queueJob: Job? = null

    /** 用户取消标志：翻译与队列都读它。 */
    private val cancelFlag = AtomicBoolean(false)

    /** 翻译面板是否打开：打开时暂停队列（用户在调设置，不该后台继续翻），关闭时恢复。 */
    private var panelOpen = false

    /** 已切到后台（onStop）：暂停队列但**保留模式**，回前台自动恢复。 */
    private var backgroundPaused = false

    /**
     * 切到后台：暂停队列与在途翻译，**保留翻译模式**。
     *
     * ⚠️ 必须做：队列挂在 `lifecycleScope` 上，`onStop` 并不会取消它 ——
     * 按 Home / 息屏后 OCR + 翻译 + HyMT2 推理会整段在后台跑，
     * 而且常驻状态芯片（`TYPE_APPLICATION_OVERLAY` 系统窗口）会一直盖在别的应用上。
     */
    fun pauseForBackground() {
        if (backgroundPaused) return
        backgroundPaused = true
        cancelEverything()
    }

    /** 回到前台：恢复队列（模式不变）。 */
    fun resumeFromBackground() {
        if (!backgroundPaused) return
        backgroundPaused = false
        restartQueue()
    }

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
     * - 打开：**暂停并回退到手动模式**（用户要调设置，后台不该继续烧）
     * - 关闭：恢复队列（模式已是手动则不动）—— 即"翻译要等退出面板后才开始"
     *
     * ⚠️ 本方法**不负责提示**：「回退到手动」的提示由宿主按回退前的模式判断
     * （见阅读器 `onPanelOpened`）。放在这里的话，判据会是"原本有没有任务在跑"，
     * 而最需要提示的那种情况（面板打开前本来就是手动、队列空闲）恰好不满足。
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

    /**
     * 停止一切在途翻译并回退到手动模式。
     *
     * ⚠️ **曾经**这里靠 `wasActive`（原本有没有在跑）决定要不要回调 `onPaused` 弹提示，结果
     * 最需要提示的那种情况——面板打开前本来就是手动、队列空闲——恰好**不**触发提示。
     * 现在统一由宿主按「回退前的模式」自己判断（见阅读器 `onPanelOpened`），
     * 判据少了一层间接，也不会再出现"该提示却没提示"。
     */
    private fun pauseToManual() {
        cancelEverything()
        if (translateMode.value != MODE_MANUAL) {
            translateMode.value = MODE_MANUAL
            version.value += 1
        }
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
        // 面板打开 / 已切后台时都不启动
        if (panelOpen || backgroundPaused) return

        queueJob = scope.launch(Dispatchers.IO) {
            LogCollector.d(TAG, "queue start mode=${translateMode.value}")
            // 窗口跑完（而非被取消/切模式）→ 收官时提示"队列已耗尽，翻页后继续"
            var drained = false
            // 引擎被别的翻译占用时只提示一次，避免每轮都刷同一条
            var waitingNotified = false
            try {
                while (isActive) {
                    // 防抖：翻页期间反复重算也没关系，停留够久才开始翻
                    delay(debounceMs.value.toLong().coerceAtLeast(QUEUE_IDLE_TICK_MS))
                    if (translateMode.value == MODE_MANUAL) break

                    // ⚠️ 必须先查锁：runTranslate 拿不到锁会直接 return（本页没翻），
                    // 而循环下一轮又会重选到同一个仍是 IDLE 的页 → **每 500ms 空转一次，
                    // 永远翻不动、也永远走不到"队列耗尽"**。这里原地等待并提示，锁一释放就继续。
                    if (OcrLock.isRunning) {
                        if (!waitingNotified) {
                            waitingNotified = true
                            LogCollector.d(TAG, "queue: OcrLock busy, waiting")
                            onPhase(
                                ReaderTranslatePhase.TRANSLATING,
                                context.getString(R.string.reader_translate_waiting_other)
                            )
                        }
                        continue
                    }
                    waitingNotified = false

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
                        // 一律退回 IDLE：这是队列唯一会挑的状态（见 isTranslatable），
                        // 留着 SUCCESS 会让「点了重翻又取消」的页再也排不进队列。
                        // 旧译文不丢显示 —— 载荷还在行里，由 cachedDisplayBitmap 的 IDLE 分支渲染
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
        try {
            migrateLegacyMangaKey()
        } catch (e: Exception) {
            LogCollector.e(TAG, "migrateLegacyMangaKey failed", e)
        }
        rows.value = dao.forManga(manga.id, mangaKey).associateBy { it.pageIndex }
        version.value += 1
    }

    /**
     * 一次性迁移旧指纹：`title|addedAt` → `addedAt`（见 [mangaKey] 的说明）。
     *
     * 不做的话，升级后老用户的译文会全部「消失」（行还在，但按新指纹查不到）。
     * 以 `|addedAt` 后缀匹配旧行，因此**用户改过名也能救回来**（旧行里存的是改名前的标题）。
     */
    private suspend fun migrateLegacyMangaKey() {
        val legacySuffix = "|${manga.addedAt}"
        val keys = dao.mangaKeysFor(manga.id)
        for (k in keys) {
            if (k.isNullOrEmpty() || k == mangaKey) continue
            if (k.endsWith(legacySuffix)) {
                dao.rewriteMangaKey(manga.id, k, mangaKey)
                LogCollector.d(TAG, "migrated legacy mangaKey '$k' → '$mangaKey'")
            }
        }
    }

    fun stateOf(pageIndex: Int): Int =
        rows.value[pageIndex]?.state ?: ImportedPageTranslation.STATE_IDLE

    fun failMessageOf(pageIndex: Int): String? = rows.value[pageIndex]?.failMessage

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

        // 引擎组与语言先解析出来：逐气泡日志的「来源/引擎」字段要它们，失败记录也要它们兜底
        val (det, ocr) = try {
            TranslationEngineInit.ensureReady(context)
        } catch (e: Exception) {
            LogCollector.e(TAG, "engine init failed page=$page", e)
            val msg = context.getString(R.string.reader_translate_model_missing, e.message.orEmpty())
            withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { fail(page, "OCR_MODEL_MISSING", msg) }
            }
            phase(ReaderTranslatePhase.FAILED, msg)
            OcrLock.release()
            return
        }
        runDet = det
        runOcr = ocr

        try {
            upsertState(page, ImportedPageTranslation.STATE_TRANSLATING)
            // 清掉上一轮可能残留的半成品：否则重翻时 cachedDisplayBitmap 会先把旧半成品显示出来
            renderLru.remove(partialKey(page))
            // 本次翻译的统计从零起（管线是每次 runTranslate 新建的，这里跟着重置）
            cacheCandidates = 0
            cacheHits = 0

            phase(ReaderTranslatePhase.DETECTING, null)

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
            // 逐气泡日志（位置 / 原文 / 译文 / 来源）——阅读器排查时唯一能看清"这一页到底翻了什么"
            // 的地方，与截屏翻译的 `RT-DETR-V2(MangaOcr) [i]: rect=..., text='...'` 对齐
            LogCollector.d(
                TAG,
                "translate page=$page 开始: 引擎=$det/$ocr, ${srcLang}->${tgtLang}, " +
                    "分批=$batchingOn, 位图=${bitmap.width}x${bitmap.height}, mode=${translateMode.value}"
            )

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
            // 逐气泡明细（rect / 原文 / 译文 / 来源）：阅读器排查时唯一能看清"这页到底翻了什么"的地方
            logBubbles(page, translated)
            // 半成品不论是否上屏都要清：用户翻走后整页渲染不执行，那个 PARTIAL 会永久占着
            // 100MB 渲染缓存（只能靠 LRU 淘汰），且同页重翻时会先闪出旧半成品
            renderLru.remove(partialKey(page))

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
            rows.update { it + (page to row) }
            dao.upsert(row)
            version.value += 1
            onVisual()
            phase(ReaderTranslatePhase.SUCCESS, cacheNotice(page, translated.size))
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

        // 管线每次 run 新建，缓存统计按「两条路线相加」取回
        cacheCandidates += pipeline.cacheStats.candidates
        cacheHits += pipeline.cacheStats.hits

        when (outcome) {
            null -> {
                // 管线抛异常（非取消）：用通用失败文案。**不能**用 reader_translate_ocr_empty ——
                // 那条文案属于下面的 OCR_EMPTY 分支，而这里记的是 PROCESS_EXCEPTION，
                // 文案与 failCode 不一致会让翻译面板把它归到「异常」却显示"未识别到文字"。
                val msg = context.getString(R.string.reader_translate_failed)
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
        // ⚠️ 这条路径**不查文本缓存**（缓存只在分批管线的 translateWithCache 里）。
        // 打出来是为了不让「同页重翻有时调 API 有时不调」变成谜：走哪条路决定了有没有缓存。
        LogCollector.d(
            TAG,
            "translatePlain: OCR ${blocks.size} 个文字块 -> ${bubbleRegions.size} 个气泡" +
                "（此路径不经文本缓存，全部走 API）"
        )
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
            onPartialBubbles = { partial -> if (streamingOn) showPartial(page, partial, bitmap) },
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
                // translating_do_not_tap 只在第一批翻译前发（translateFirstThenSecondBatch 里），
                // 用 batchIndex 而非字面量 1，避免管线将来改发射时机后这里静默出错
                R.string.translating_do_not_tap -> ReaderTranslatePhase.TRANSLATING to
                    context.getString(R.string.reader_translate_batch_translate, batchIndex)
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

        override fun onPartialRender(bubbles: List<TranslatedBubble>) =
            showPartial(page, bubbles, pageBitmap)

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

    /** 本次 [runTranslate] 的文本缓存候选/命中数（两条路线相加）。见 [reportCacheOutcome]。 */
    private var cacheCandidates = 0
    private var cacheHits = 0

    /** 本次 [runTranslate] 的识别引擎，用于逐气泡日志里的「来源」字段。 */
    private var runDet: DetEngine = DetEngine.PP_OCR_V6
    private var runOcr: OcrEngine = OcrEngine.PPOcrV6

    // ========== 渲染 ==========

    /** 上一版整页译图（取消重翻 / 重翻在途时的显示回退）。无则 null。 */
    private fun lastFullRender(pageIndex: Int): Bitmap? {
        val mode = currentVisual(pageIndex)
        if (mode == TranslationCacheManager.OverlayMode.PLAIN) return null
        return renderLru.get(renderKey(pageIndex, mode))
    }

    /** 该页行里是否还留着可渲染的译文载荷（取消/失败后 upsertState 会保留）。 */
    private fun rowHasPayload(pageIndex: Int): Boolean {
        val row = rows.value[pageIndex] ?: return false
        return !row.bubbleRects.isNullOrBlank() || !row.translatedText.isNullOrBlank()
    }

    /** 供适配器同步取图（IO 线程安全）：该页当前应显示的渲染图，无则 null（显示原图）。 */
    fun cachedDisplayBitmap(pageIndex: Int): Bitmap? = when (stateOf(pageIndex)) {
        ImportedPageTranslation.STATE_SUCCESS -> lastFullRender(pageIndex)

        // 翻译中：返回「首批半成品」如果有 —— 否则用户翻走再翻回时看不到已经翻好的那半页。
        // ⚠️ 必须用独立 key（PARTIAL）：不能用 renderKey(TRANSLATED)，
        // 否则会和最终整页结果混在一起，且失败/取消后残留一张永远刷不掉的半成品。
        // 半成品还没出来时回退到上一版整页译图：重翻期间页面不该突然退回原图（翻页也会闪一下）
        ImportedPageTranslation.STATE_TRANSLATING ->
            renderLru.get(partialKey(pageIndex)) ?: lastFullRender(pageIndex)

        // 未翻译但行里还带着上一次成功的载荷（典型：重翻被取消 / 切后台中断）：
        // 继续显示旧译文。⚠️ 状态必须是 IDLE，队列才会重新挑中这一页去翻 —— 退回 SUCCESS 会让
        // 「点了重翻」的页永远排不进队列（用户看到的是"重翻了却没变"）
        ImportedPageTranslation.STATE_IDLE ->
            if (rowHasPayload(pageIndex)) lastFullRender(pageIndex) else null

        else -> null
    }

    /** 在途的半成品渲染（每页只允许一个，见 [showPartial]）。 */
    private var partialJob: Job? = null

    /**
     * 把「首批/流式半成品」渲染上屏 —— 仅当该页正是用户在看的那页。
     *
     * ⚠️ 必须传**已经解码好的** [bitmap]，不能在这里再调 `loadFull`：
     * `ReaderPageSource.loadFull` 对 zip 会**每次重开 ZipFile 并全尺寸解码**（~10-30MB），
     * 而本方法由流式回调**每出一个气泡调用一次** → 20 气泡的页 = 20 次重解码 + 20 次全页渲染。
     *
     * ⚠️ 同时做**合并**：已有渲染在途就直接丢弃本次回调（最终整页结果走 [renderInto]，不会丢）。
     * 否则并发的全页渲染会把内存顶爆（截屏翻译路径复用同一张截图 bitmap，无此问题）。
     */
    private fun showPartial(page: Int, bubbles: List<TranslatedBubble>, bitmap: Bitmap) {
        if (bubbles.isEmpty()) return
        if (page != currentPageProvider()) return
        if (partialJob?.isActive == true) return
        partialJob = scope.launch(Dispatchers.IO) {
            val cfg = cacheManager.getOverlayConfig(appPrefs)
            val out = renderBubbles(bitmap, bubbles, TranslationCacheManager.OverlayMode.TRANSLATED, cfg)
            renderLru.put(partialKey(page), out)
            withContext(Dispatchers.Main) { onVisual() }
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

    /**
     * 导出用：按**原图全分辨率**渲染某页译文（`useOriginalText=false`）。仅成功页可渲染。
     *
     * ⚠️ 不能复用 [visualBitmap]：它会把结果放进 100MB 的 `renderLru`，而导出动辄上百页 →
     * 一路把 LRU 冲干净，把用户正在看的那页译图也挤掉，退回阅读器还得重渲。
     *
     * ⚠️ **必须在 IO 线程调用**（全尺寸解码 + 全页渲染）；调用方负责 `recycle()` 返回值。
     */
    fun renderForExport(pageIndex: Int): Bitmap? {
        val row = rows.value[pageIndex] ?: return null
        if (row.state != ImportedPageTranslation.STATE_SUCCESS) return null
        val config = cacheManager.getOverlayConfig(appPrefs)
        val bubbles = PageTranslationCodec.fromRow(row, config.fontSize, config.bgColor) ?: return null
        val orig = loadFull(pageIndex) ?: return null
        return try {
            renderBubbles(orig, bubbles, TranslationCacheManager.OverlayMode.TRANSLATED, config)
        } finally {
            // renderOverlay 开头就 copy() 出独立副本 → 源图渲染完即可回收（导出逐页进行，别攒内存）
            orig.recycle()
        }
    }

    /**
     * 把气泡从「原图坐标空间」等比缩放到「采样图坐标空间」。
     * Webtoon 按屏宽采样解码后用得到（见 [prewarmWebtoon]）；角度不随缩放变化。
     */
    private fun TranslatedBubble.scaledBy(s: Float): TranslatedBubble = copy(
        rect = Rect(
            (rect.left * s).roundToInt(),
            (rect.top * s).roundToInt(),
            (rect.right * s).roundToInt(),
            (rect.bottom * s).roundToInt(),
        ),
        fontSize = fontSize * s,
        centerX = if (centerX >= 0f) centerX * s else centerX,
        centerY = if (centerY >= 0f) centerY * s else centerY,
    )

    // ========== 日志与缓存提示 ==========

    /**
     * 逐气泡日志：序号 + 矩形 + 竖排标记 + 原文 → 译文 + **来源**。
     *
     * 阅读器以前整条链路没有一条气泡级日志，出问题只能靠猜（哪块漏翻、哪块被合并、哪块命中了缓存
     * 全看不出来）。格式与截屏翻译的 `RT-DETR-V2(MangaOcr) [i]: rect=..., text='...'` 对齐，方便
     * 两边对照。来源按「本次有没有真的调过 API」判定，不用渲染时的 `fromCache` —— 那个标志还被
     * 「缓存命中标记」设置控制，不能当作事实来源。
     */
    private fun logBubbles(page: Int, bubbles: List<TranslatedBubble>) {
        if (bubbles.isEmpty()) return
        LogCollector.d(TAG, "page=$page 气泡明细（共 ${bubbles.size} 个，引擎=$runDet/$runOcr）：")
        bubbles.forEachIndexed { i, b ->
            val origin = originOf(b)
            val vertical = b.direction != TextDirection.HORIZONTAL
            LogCollector.d(
                TAG,
                "  [$i] rect=[${b.rect.left},${b.rect.top},${b.rect.right},${b.rect.bottom}] " +
                    "v=$vertical fs=${b.fontSize.roundToInt()} 来源=$origin\n" +
                    "      src='${b.originalText}'\n" +
                    "      dst='${b.translatedText}'"
            )
        }
    }

    /**
     * 气泡的来源：唯一权威是数据本身带的两个缓存标志（阅读器路径上只有「精确命中」与
     * 「模糊命中」两处会置位），不靠计数推算 —— 推算在分批/流式下容易错位。
     *
     * 模糊命中时**拿不到**当时那条缓存原文（`TranslatedBubble` 不带这个字段），要看它得翻
     * 管线打印的 `Text cache hit (fuzzy): 'A' ~ 'B' → 'C'`。
     */
    private fun originOf(b: TranslatedBubble): String = when {
        b.isInMemoryCache -> "精确缓存"
        b.fromCache -> "模糊缓存"
        else -> "API"
    }

    /**
     * 完成提示里的缓存说明；没有缓存命中时返回 null（走原来的「翻译完成」文案）。
     *
     * 用户要求：提示「12 条里面命中 3 条」这个口径，而不是笼统说一句"有缓存"。
     */
    private fun cacheNotice(page: Int, total: Int): String? {
        if (cacheHits <= 0) return null
        val fromApi = (cacheCandidates - cacheHits).coerceIn(0, total)
        LogCollector.d(
            TAG,
            "page=$page 缓存命中 $cacheHits/$cacheCandidates（译文 $fromApi 条来自 API，共 $total 条）"
        )
        return when {
            fromApi <= 0 -> context.getString(R.string.reader_translate_all_cached, total)
            else -> context.getString(R.string.reader_translate_partial_cached, cacheHits, cacheCandidates, fromApi)
        }
    }

    /** 渲染一行气泡到页图（阅读器渲染的统一出口）。 */
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
        align = cfg.horizontalAlign,
        trackingRatio = cfg.trackingRatio,
        leadingRatio = cfg.leadingRatio,
        mergeOverlap = cfg.mergeOverlap,
        density = context.resources.displayMetrics.density
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

    // ========== Webtoon 原图/译文切换 ==========

    /**
     * Webtoon 的整屏显示态：false = 原图，true = 译文（**默认译文**）。
     *
     * Webtoon 是连续滚动，"当前页"语义不唯一，因此**不支持单页翻译**，也没有单页三态；
     * 只有一个全局开关，且**只对状态为 SUCCESS 的页**生效（未翻译页永远显示原图）。
     * 该开关由阅读模式分段器上的「连续滑动」按钮两态控制（原图图标 ↔ 带「译」角标图标）。
     */
    private val _webtoonTranslated = MutableStateFlow(true)
    val webtoonTranslated: StateFlow<Boolean> get() = _webtoonTranslated

    /** Webtoon 译图缓存（key = pageIndex）。只放当前位置附近的几页，见 [prewarmWebtoon]。 */
    private val webtoonLru = object : LruCache<Int, Bitmap>(WEBTOON_CACHE_KB) {
        override fun sizeOf(key: Int, value: Bitmap) =
            value.allocationByteCount.coerceAtLeast(value.rowBytes * value.height) / 1024
    }

    private var webtoonPrewarmJob: Job? = null

    /**
     * 切换 Webtoon 显示态（原图 ↔ 译文）。值未变时直接返回，不做任何事。
     *
     * ⚠️ **不 evict 缓存**：这个 LRU 里只有译图（原图由适配器直接采样解码，从不进缓存），
     * 切到原图时 [webtoonCachedBitmap] 会按 flag 短路返回 null —— 缓存留着不碍事，
     * 切回译文还能秒回，不必重渲。曾经在这里 evictAll，导致每次切换都要重渲附近几页，
     * 切换期间新旧图交替闪跳。
     *
     * ⚠️ 也不在这里调 [onVisual]：重绑由调用方（Activity）一次性全量触发，见 `onWebtoonTranslated`。
     */
    fun setWebtoonTranslated(value: Boolean) {
        if (_webtoonTranslated.value == value) return
        _webtoonTranslated.value = value
        // 停掉在途预热：它可能还在渲染一批已经滚过去的页，切态后没必要继续烧 CPU。
        // 已渲染好的那些留在缓存里（切回译文直接可用）。
        webtoonPrewarmJob?.cancel()
        webtoonPrewarmJob = null
        version.value += 1
    }

    /**
     * Webtoon 适配器同步取图（IO 线程安全）：该页**已渲染好**的译图，无则 null → 适配器回落原图。
     * 只读缓存，不做渲染（渲染由 [prewarmWebtoon] 在后台限范围预热）。
     */
    fun webtoonCachedBitmap(pageIndex: Int): Bitmap? {
        if (!_webtoonTranslated.value) return null
        if (stateOf(pageIndex) != ImportedPageTranslation.STATE_SUCCESS) return null
        return webtoonLru.get(pageIndex)
    }

    /**
     * 预热 Webtoon 当前位置**上下各 [radius] 页**的译图。
     *
     * ⚠️ 不能一次渲染全部：一本 Webtoon 可能上百页，每页译图 ~7-30MB。
     * 只渲染已翻译(SUCCESS)且在半径内的页；未翻译页不需要任何渲染（直接显示原图）。
     * 每次调用会取消上一次预热，避免快速滚动时堆积。
     */
    fun prewarmWebtoon(center: Int, radius: Int = WEBTOON_PREWARM_RADIUS) {
        webtoonPrewarmJob?.cancel()
        if (!_webtoonTranslated.value) return
        val total = pageCount()
        if (total <= 0) return

        val from = (center - radius).coerceAtLeast(0)
        val to = (center + radius).coerceAtMost(total - 1)
        val pending = (from..to).filter { p ->
            stateOf(p) == ImportedPageTranslation.STATE_SUCCESS && webtoonLru.get(p) == null
        }
        if (pending.isEmpty()) return

        webtoonPrewarmJob = scope.launch(Dispatchers.IO) {
            val cfg = cacheManager.getOverlayConfig(appPrefs)
            val loader = loadWebtoon ?: return@launch
            val widthOf = originalWidthOf ?: { 0 }
            for (p in pending) {
                if (!isActive) break
                val row = rows.value[p] ?: continue
                val bubbles = PageTranslationCodec.fromRow(row, cfg.fontSize, cfg.bgColor) ?: continue
                // 按屏宽采样解码（防超长页 OOM），再把「原图空间」的气泡坐标等比缩到采样图
                val src = loader(p) ?: continue
                val fullW = widthOf(p)
                val scale = if (fullW > 0) src.width.toFloat() / fullW else 1f
                val scaled = if (scale != 1f && scale > 0f) bubbles.map { it.scaledBy(scale) } else bubbles
                // Webtoon 只有「原图」与「译文」两态：原图不经渲染（适配器直出采样图），
                // 所以这里恒按译文渲染
                val out = renderBubbles(src, scaled, TranslationCacheManager.OverlayMode.TRANSLATED, cfg)
                webtoonLru.put(p, out)
                withContext(Dispatchers.Main) { onVisual() }
            }
        }
    }

    /** 缓存与模式作废（切出 Webtoon / 重新翻译后调用）。 */
    fun clearWebtoonCache() {
        webtoonPrewarmJob?.cancel()
        webtoonPrewarmJob = null
        webtoonLru.evictAll()
    }

    // ========== UI 回调（由 Activity 注入） ==========

    /** 页面显示需要刷新（三态切换 / 翻译完成）。 */
    var onVisual: () -> Unit = {}

    /** 翻译阶段变化（检测中/翻译中/完成/失败/队列耗尽）。 */
    var onPhase: (ReaderTranslatePhase, String?) -> Unit = { _, _ -> }

    // ========== 私有：写记录 ==========

    private suspend fun upsertState(pageIndex: Int, state: Int) {
        val old = rows.value[pageIndex]
        val row = ImportedPageTranslation(
            mangaId = manga.id, pageIndex = pageIndex, state = state,
            sourceText = old?.sourceText, translatedText = old?.translatedText,
            bubbleRects = old?.bubbleRects, failCode = old?.failCode,
            failMessage = old?.failMessage, updatedAtMs = System.currentTimeMillis(),
            mangaKey = mangaKey,
            // ⚠️ 这三列也必须带上：行主键是 (mangaId, pageIndex) 且 REPLACE 写入，
            // 漏掉就等于「任何一次状态流转（翻译中 / 退回未翻译）都清空翻译器与语言元数据」，
            // 详情面板那行会变空且不可恢复（fail() 的注释同样依赖这一点）
            translatorName = old?.translatorName, sourceLang = old?.sourceLang, targetLang = old?.targetLang,
        )
        // ⚠️ 用 update{} 而非 `rows.value = rows.value + x`：
        // 后者是「读-改-写」，与取消清理协程并发时会丢更新。
        rows.update { it + (pageIndex to row) }
        dao.upsert(row)
        version.value += 1
    }

    private suspend fun fail(pageIndex: Int, code: String, message: String) {
        val old = rows.value[pageIndex]
        val row = ImportedPageTranslation(
            mangaId = manga.id, pageIndex = pageIndex, state = ImportedPageTranslation.STATE_FAILED,
            // ⚠️ 必须保留上一次成功的译文载荷：行主键是 (mangaId, pageIndex) 且用 REPLACE 写入，
            // 若不带上这些字段，**重翻一次失败就会把已有译文整行抹掉**（用户之前花过 API 额度的结果
            // 不可恢复）。upsertState 一直是有意保留的，fail 必须与之一致。
            sourceText = old?.sourceText, translatedText = old?.translatedText,
            bubbleRects = old?.bubbleRects,
            failCode = code, failMessage = message, updatedAtMs = System.currentTimeMillis(),
            mangaKey = mangaKey,
            translatorName = old?.translatorName, sourceLang = old?.sourceLang, targetLang = old?.targetLang,
        )
        // ⚠️ 必须 upsert 局部变量 row，不能写 `dao.upsert(rows.value.getValue(pageIndex))`：
        // 并发写入下 rows.value 可能已被别的协程换成不含本页的新 map → NoSuchElementException 崩溃。
        rows.update { it + (pageIndex to row) }
        dao.upsert(row)
        version.value += 1
    }
}
