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
import com.moe.starflow.manga.config.MangaModeConfig
import com.moe.starflow.manga.config.RtTextDirection
import com.moe.starflow.manga.config.TranslationTextRules
import com.moe.starflow.manga.engine.DetectionBridge
import com.moe.starflow.manga.pipeline.BatchOutcome
import com.moe.starflow.manga.pipeline.BatchPipelineConfig
import com.moe.starflow.manga.pipeline.BatchPipelineHost
import com.moe.starflow.manga.pipeline.IncrementalBatchPipeline
import com.moe.starflow.manga.render.OverlayRenderer
import com.moe.starflow.manga.state.RegionCacheManager
import com.moe.starflow.manga.types.BubbleRegion
import com.moe.starflow.manga.types.CroppedBubble
import com.moe.starflow.manga.types.DetEngine
import com.moe.starflow.manga.types.OcrEngine
import com.moe.starflow.manga.types.TextBlockInfo
import com.moe.starflow.manga.types.TextDirection
import com.moe.starflow.manga.types.TranslatedBubble
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.mangaimport.reader.ReaderPageSource
import com.moe.starflow.translate.TranslationTextAPI
import com.moe.starflow.translate.isLocalHeavyEngine
import com.moe.starflow.sr.SrBaseKind
import com.moe.starflow.sr.SrDisplayBase
import com.moe.starflow.sr.SrFailReason
import com.moe.starflow.sr.SrModelManager
import com.moe.starflow.sr.SrOutcome
import com.moe.starflow.sr.SrProcessor
import com.moe.starflow.sr.SrSettings
import com.moe.starflow.sr.SrStore
import com.moe.starflow.sr.SuperResolutionEngines
import com.moe.starflow.sr.anime4k.Anime4kMode
import com.moe.starflow.translate.batch.ChapterJob
import com.moe.starflow.translate.batch.ChapterJobRunner
import com.moe.starflow.translate.batch.ChapterJobState
import com.moe.starflow.translate.batch.InFlightTask
import com.moe.starflow.translate.widget.BallStateManager
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.TranslationConcurrency
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
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
/** 阅读器上屏译图的超采样倍率 */
private const val READER_RENDER_SCALE = 2f

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

        /**
         * 超分/增强**显示底图**缓存预算。
         *
         * 2x 一页 ≈ 1848×2644 ARGB ≈ 19MB → 48MB 只装得下 2~3 页。
         * 刻意不放大：底图只在"渲染那一瞬间 + 二态切换回来"用到，渲染产物在 `renderLru` 里，
         * 底图被淘汰了重读一次 webp 只要几十毫秒。
         */
        private const val SR_BASE_CACHE_KB = 48 * 1024

        /** Webtoon 预热半径：当前页 ± [WEBTOON_PREWARM_RADIUS] 页。 */
        const val WEBTOON_PREWARM_RADIUS = 2

        const val MODE_MANUAL = 0
        const val MODE_AUTO = 1
        const val MODE_AHEAD = 2

        /** 队列每轮之间的等待下限，避免窗口内无活时死循环空转。 */
        private const val QUEUE_IDLE_TICK_MS = 200L

        /** 渲染缓存 key 的页前缀（作废某页全部底图变体时按它前缀匹配）。 */
        private fun pagePrefix(pageIndex: Int) = "page:$pageIndex:"
        /**
         * 等 OCR 引擎锁的最长时间 / 轮询间隔。
         *
         * ⚠️ **超时必须记失败 + 记日志**，不能静默跳过：静默跳过会让「进度在涨、什么都没翻，
         * 面板上正在翻译的卡片直接消失」且毫无线索（2026-09-28 用户报的三个症状同一个根因）。
         */
        private const val OCR_LOCK_WAIT_TIMEOUT_MS = 60_000L
        private const val OCR_LOCK_POLL_MS = 50L

        private fun renderKey(pageIndex: Int, mode: TranslationCacheManager.OverlayMode) =
            "page:$pageIndex:${mode.name}"

        /**
         * 渲染缓存 key。
         *
         * ⚠️ **必须带底图签名** [baseSig]：同一页在「原图底图 / 超分底图 / Anime4K 底图」下
         * 渲出来的位图是不同的，key 不带它就会把旧底图的译图当成新底图的结果返回
         * （表现为：超分做完了、译文还是在糊的原图上）。
         */
        private fun renderKey(
            pageIndex: Int,
            mode: TranslationCacheManager.OverlayMode,
            baseSig: String,
        ) = "page:$pageIndex:${mode.name}:b$baseSig"

        /** 首批半成品的独立缓存 key（见 [cachedDisplayBitmap]）。同样带底图签名。 */
        private fun partialKey(pageIndex: Int, baseSig: String) = "page:$pageIndex:PARTIAL:b$baseSig"

        /** Webtoon 译图 key。同样带底图签名（底图换了旧译图必须失效）。 */
        private fun webtoonKey(pageIndex: Int, baseSig: String) = "$pageIndex:b$baseSig"
    }

    /**
     * 超分底图的一个缓存项：位图 + 它相对**渲染坐标空间**的倍率 + 产生它的设置签名。
     *
     * `sig` 是必须的：换了模型 / 换了 Anime4K 档 / 关了开关，缓存里那份底图就不再对应当前设置，
     * 只按页码取会把旧底图（甚至别的模型的产物）当新的用。
     */
    private data class SrBase(val bitmap: Bitmap, val scale: Float, val sig: String)

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
     * 「译文替换表」的原始存储串（指纹，**不解析 JSON**）：它一变就说明规则被改过。
     * 见 [refreshIfRulesChanged] —— 替换表在**渲染时**套用，改了就必须作废已渲染的译图。
     */
    private fun rulesRaw(): String =
        appPrefs.getString(TranslationTextRules.KEY_REPLACEMENTS, "").orEmpty()

    private var appliedRulesRaw: String = rulesRaw()

    /**
     * 渲染代次：替换表一变就 +1。
     *
     * ⚠️ 光 `evictAll()` 不够：在途的 `prewarmWebtoon` / `showPartial` 渲染完仍会把**旧规则**的位图
     * 写回缓存（它们的 `isActive` 检查在循环开头，渲染返回后就不再检查），而预热又按
     * `webtoonLru.get(p) == null` 挑页 → 这些页会被永久跳过，规则永远不生效。
     * 所以每个渲染任务开工时记下代次，写缓存前比对，代次变了就丢弃结果。
     */
    private var renderGeneration = 0L

    /**
     * 替换表变过 → 作废所有已渲染译图（分页 `renderLru` + Webtoon `webtoonLru`），并让在途渲染作废。
     *
     * ⚠️ 为什么需要它：替换表是**渲染时**套用的（译文只存文本，overlay 后期才画），
     * 所以「改完规则不用重翻」；但已渲染好的位图还在缓存里，不作废就永远看不到新规则。
     * 调用方（阅读器 `onStart`）拿到 true 后触发重渲染，译文从数据库行重建 —— **不调翻译 API**。
     */
    fun refreshIfRulesChanged(): Boolean {
        val now = rulesRaw()
        if (now == appliedRulesRaw) return false
        appliedRulesRaw = now
        renderGeneration++
        // 在途任务先取消：它们的产物已按旧规则渲染，写回缓存就是脏数据
        webtoonPrewarmJob?.cancel()
        webtoonPrewarmJob = null
        partialJob?.cancel()
        partialJob = null
        renderLru.evictAll()
        srBaseLru.evictAll()
        clearWebtoonCache()
        LogCollector.d(TAG, "译文替换表已变更 → 作废已渲染译图（代次 $renderGeneration），等待重渲染")
        return true
    }

    /**
     * 作废已渲染译图（**渲染期参数**变了就用它：字号 / 自动字号 / 颜色 / 字距行距 / 合并重叠…）。
     *
     * ⚠️ 这些参数**不在 `renderLru` 的 key 里**（key 只有 `page:idx:MODE`），所以改完不作废，
     * 屏幕上永远还是旧字号 —— 面板里的字号滑块必须调它。
     * 与 [refreshIfRulesChanged] 同一套动作（代次 +1、取消在途、清两级缓存），只是不做规则比较。
     */
    fun invalidateRenders() {
        renderGeneration++
        webtoonPrewarmJob?.cancel()
        webtoonPrewarmJob = null
        partialJob?.cancel()
        partialJob = null
        renderLru.evictAll()
        // 底图签名里含 Anime4K 档位 → 档位变了缓存里那份底图就不再对应当前设置
        srBaseLru.evictAll()
        clearWebtoonCache()
        LogCollector.d(TAG, "渲染参数已变更 → 作废已渲染译图（代次 $renderGeneration）")
    }

    /**
     * 漫画身份指纹。
     *
     * ⚠️ **只用 `addedAt`，绝不要把 `title` 拼进来**：书架有「重命名」功能
     * （`ImportMangaFragment.renameSelected` → `manga.copy(title = name)`），
     * 一旦标题变化，含 title 的指纹就全部失配 → 整本书的译文读不出来（数据还在库里，
     * 但显示为未翻译，且 `purgeOrphanTranslations` 也清不到它们，会永久堆积）。
     * `addedAt` 每次导入唯一，本身已足够区分。
     *
     * 推导收敛在 [ImportedManga.translationKey]（书架删除前的「有没有译文」提示用同一个值）。
     */
    private val mangaKey: String = manga.translationKey

    private val renderLru = object : LruCache<String, Bitmap>(RENDER_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap) =
            value.allocationByteCount.coerceAtLeast(value.rowBytes * value.height) / 1024
    }

    /** 各页当前三态（内存态）。并发安全（IO 页图提供者 + 主线程切换都会读写）。 */
    private val currentVisualByPage = ConcurrentHashMap<Int, TranslationCacheManager.OverlayMode>()

    // ========== 超分 / 增强「显示底图」（v2，2026-10） ==========

    /**
     * 超分/Anime4K **显示底图**缓存（key = 页号）。
     *
     * ⚠️ **只装底图、不装渲染产物**：底图是"翻译之前那一层"，与 overlay（译文/原文/纯原图）
     * 正交。渲染产物按 `renderKey(page, mode, baseSig)` 存在 [renderLru] 里。
     */
    private val srBaseLru = object : LruCache<Int, SrBase>(SR_BASE_CACHE_KB) {
        override fun sizeOf(key: Int, value: SrBase) = value.bitmap
            .let { it.allocationByteCount.coerceAtLeast(it.rowBytes * it.height) } / 1024
    }

    /**
     * 每页的「显示超分底图还是原图」二态（**用户口径：默认显示超分**）。
     *
     * 缺省 = true（超分底图）。用户点右下角超分按钮切到原图时写 false。
     * ⚠️ 这是**纯显示态**，与"有没有超分文件"无关：切到原图**不删文件**（用户口径）。
     */
    private val srVisualByPage = ConcurrentHashMap<Int, Boolean>()

    /**
     * 该页已落盘的超分结果**是哪个模型超的**（seed 自 `SrStore.modelOf`，超分完成后立即写入）。
     *
     * 只在 IO 侧（[srBaseFor] / [warmSrBase] / 超分完成）维护，UI 侧 [srActionOf] 纯内存读 ——
     * `refreshTranslationChrome()` 在主线程被高频调用，不能在里面读磁盘。
     */
    private val srModelByPage = ConcurrentHashMap<Int, String>()

    /** 该页是否正显示超分底图（缺省 true）。 */
    fun isSrVisualOn(pageIndex: Int): Boolean = srVisualByPage[pageIndex] ?: true

    /**
     * 该页当前的**底图签名** —— 渲染缓存 key 的一部分，也是"底图变没变"的唯一判据。
     *
     * 判据与 [SuperResolutionEngines.resolveSteps] 完全同源（超分模型优先、不可用时才 Anime4K）：
     * ```
     * "o"                    原图（超分关 或 用户把该页切回原图 或 该页还没有超分文件）
     * "s:<模型名>"            已落盘的超分模型底图
     * "a:<Anime4K 档位 id>"   Anime4K 同分辨率增强底图
     * ```
     *
     * ⚠️ 会做一次 `File.isFile/length`（`SrStore.exists`）—— 每页几微秒，可接受；
     * **绝不能在这里读 webp 或跑推理**（本函数会被适配器绑定路径调用）。
     */
    private fun baseSig(pageIndex: Int): String {
        val prefs = appPrefs
        val kind = SrDisplayBase.resolveBaseKind(
            srVisualOn = isSrVisualOn(pageIndex),
            srEnabled = SrSettings.isEnabledForReader(prefs),
            srModelUsable = SuperResolutionEngines.isSrModelUsable(context, prefs),
            anime4kEnabled = Anime4kMode.isEnabled(prefs),
        )
        return when (kind) {
            SrBaseKind.ORIGINAL -> SrDisplayBase.baseSignature(kind, null, null, false)
            SrBaseKind.ANIME4K -> SrDisplayBase.baseSignature(
                kind = kind,
                srModelName = null,
                anime4kModeId = Anime4kMode.fromPrefs(prefs).id,
                storedSrFile = false,
            )
            SrBaseKind.SR_MODEL -> {
                // ⚠️ 模型名取自**标记文件**（这份超分图到底是哪个模型超的），不是"当前选中的模型"：
                //    换模型但没重超时文件内容没变、渲染缓存仍有效；重超完成后标记换成新模型 →
                //    签名变 → 旧渲染自动作废（不依赖任何手工 remove）。
                val stored = SrStore.storedModelOrNull(context, manga.id, pageIndex)
                SrDisplayBase.baseSignature(
                    kind = kind,
                    srModelName = stored,
                    anime4kModeId = null,
                    storedSrFile = stored != null,
                )
            }
        }
    }

    /**
     * 取该页的显示底图（**suspend，可能读磁盘**）。
     *
     * - 超分模型可用且该页已有落盘结果 → 读 webp，`scale = 底图宽 / [spaceWidth]`
     * - 否则若 Anime4K 开着 → 就地跑 Anime4K（1x）
     * - 都没有 → null（调用方用原图，`baseScale = 1f`）
     *
     * @param space [spaceWidth] 所属的位图（Anime4K 要拿它做输入；超分分支只用宽度）
     * @param spaceWidth **气泡坐标所在空间**的宽。分页渲染时 = 原图宽；
     *   它决定 `baseScale`，进而决定 `OverlayRenderer` 反算出的坐标空间。
     * @return (底图, baseScale)，底图归缓存所有，**调用方不得 recycle**
     */
    private suspend fun srBaseFor(pageIndex: Int, space: Bitmap, spaceWidth: Int): SrBase? {
        if (pageIndex < 0 || spaceWidth <= 0) return null

        // ── ① 先回答「**这一页到底有没有超分结果**」——它与"现在显示哪张底图"是**两件事** ──
        // ⚠️ 真机反馈：「点击切换回原图，整个超分的组件都没有了」。
        //    根因就在这里：以前把「用户切回原图」和「结果不存在」都当成 `sig == "o"`，
        //    一并 `srModelByPage.remove(...)` → `hasSrResult` 变 false → 右下角整个超分组消失，
        //    于是**再也切不回超分图**（按钮没了）。
        //    现在：结果的存在性只看**文件**（IO 一次 stat+读标记），显示态由 `isSrVisualOn` 单独管。
        val storedModel = withContext(Dispatchers.IO) { SrStore.storedModelOrNull(context, manga.id, pageIndex) }
        if (storedModel == null) {
            srModelByPage.remove(pageIndex)
            srBaseLru.remove(pageIndex)
        } else {
            srModelByPage[pageIndex] = storedModel
        }

        // ── ② 显示态：用户把这一页切回原图 → 不取底图（但上面那句仍记着"结果还在"）──
        if (!isSrVisualOn(pageIndex)) return null

        val sig = baseSig(pageIndex)
        if (sig == "o") {
            srBaseLru.remove(pageIndex)
            return null
        }
        srBaseLru.get(pageIndex)?.let { if (it.sig == sig) return it }

        if (sig.startsWith("s:")) {
            val stored = withContext(Dispatchers.IO) { SrStore.load(context, manga.id, pageIndex) }
            // ⚠️ 记的是**标记文件里的模型**（不是 sig 里那个）：换模型后 `srActionOf` 要能看出
            //    "这一页是别的模型超的 → 该重新超分"，拿 sig 当答案永远等于"已经是当前模型"。
            if (stored == null) {
                // 文件刚被系统/用户清掉（`SrStore` 以"文件存在"为真值）→ 本次回落原图
                LogCollector.d(TAG, "超分底图读取失败，回落原图 page=$pageIndex")
                return null
            }
            val scale = stored.width.toFloat() / spaceWidth
            if (scale <= 1.001f) {
                // 尺寸不对（截断/损坏）→ 宁可不放大，也不要把译文画错位
                LogCollector.w(TAG, "超分底图尺寸异常 ${stored.width}x${stored.height} page=$pageIndex → 回落原图")
                stored.recycle()
                return null
            }
            return SrBase(stored, scale, sig).also { srBaseLru.put(pageIndex, it) }
        }

        // Anime4K：同分辨率增强，输出尺寸 == 输入尺寸 → baseScale 恒 1
        // ⚠️ 失败**只记日志、不弹提示**：这是被动显示增强（用户没点任何东西），
        //    在翻页过程中弹提示纯属打扰；真要报也是等用户主动点超分时那条路径去报。
        val enhanced = withContext(Dispatchers.IO) {
            val r = SuperResolutionEngines.enhanceWithAnime4k(context, appPrefs, space)
            if (!r.ok) LogCollector.d(TAG, "Anime4K 增强未产出 page=$pageIndex: ${r.reason} ${r.detail ?: ""}")
            r.bitmap
        } ?: return null
        return SrBase(enhanced, 1f, sig).also { srBaseLru.put(pageIndex, it) }
    }

    /**
     * 只预热底图、不渲染 overlay（未翻译页 / 纯原图态走它）。
     *
     * ⚠️ 自己 `loadFull` 就自己 `recycle`（`ReaderPageSource.loadFull` 是纯解码、每次返回新位图）。
     *
     * ⚠️ **必须是 suspend 且由调用方 await**（原来的 `scope.launch` = 发射后不管）：
     * 宿主 `applyPageVisual` 的模式是「先预热、再 `notifyItemChanged` 让适配器按 [cachedDisplayBitmap] 取图」。
     * 预热还没落地就 notify → 适配器取不到底图 → 回落**源图**；而预热完成后**没有任何东西再触发重绑**，
     * 于是屏幕一直停在源图上。表现就是真机反馈的那条：
     * 「当前显示**纯原图**态时切换底图没作用」（译文/原文态走 `visualBitmap` 是同步 await 的，所以正常）。
     */
    suspend fun warmSrBase(pageIndex: Int) {
        val sig = baseSig(pageIndex)
        srBaseLru.get(pageIndex)?.let { if (it.sig == sig) return }
        if (sig == "o") return
        withContext(Dispatchers.IO) {
            val src = loadFull(pageIndex) ?: return@withContext
            try {
                srBaseFor(pageIndex, src, src.width)
            } finally {
                if (!src.isRecycled) src.recycle()
            }
        }
    }

    /**
     * 给调色面板「处理后」预览格准备的**超分底图**（缩放到与 `targetWidth` 同宽）。
     *
     * 用户口径（2026-10）：「在调色面板启用超分或者切换超分模型，面板的预览应该要实时更新
     * 超分或者其他调整之后的结果」。
     *
     * ⚠️ **必须在 IO 线程调用**：预览格是主线程渲染的，读盘 + 缩放都不能在面板里做
     * （那正是"一开调色面板就卡死"的老毛病）。
     * @return null = 这一页没有超分结果 / 用户把这一页切回原图（预览用原图即可）
     */
    suspend fun srPreviewFor(pageIndex: Int, targetWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (targetWidth <= 0 || !isSrVisualOn(pageIndex)) return@withContext null
        if (SrStore.storedModelOrNull(context, manga.id, pageIndex) == null) return@withContext null
        // 已经预热过就直接缩它（省一次解码）
        val cached = srBaseLru.get(pageIndex)?.bitmap
        val full = cached ?: SrStore.load(context, manga.id, pageIndex) ?: return@withContext null
        try {
            if (full.width <= targetWidth) return@withContext full
            val h = (full.height.toLong() * targetWidth / full.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(full, targetWidth, h, true)
        } finally {
            // 只有"本次现解码的"才回收（缓存里那份归缓存）
            if (cached == null && !full.isRecycled) full.recycle()
        }
    }

    /** 底图变了（超分完成 / 二态切换 / 换模型）：作废该页渲染缓存并刷新上屏。 */
    private suspend fun onBaseChanged(pageIndex: Int) {
        srBaseLru.remove(pageIndex)
        evictPageRenders(pageIndex)
        clearWebtoonCache()
        withContext(Dispatchers.Main) {
            if (uiAttached && pageIndex == currentPageProvider()) onVisual()
        }
    }

    /** 删掉某页**所有底图变体**的渲染产物（key 里带 sig，只能按前缀清）。 */
    private fun evictPageRenders(pageIndex: Int) {
        val prefix = pagePrefix(pageIndex)
        renderLru.snapshot().keys.filter { it.startsWith(prefix) }.forEach { renderLru.remove(it) }
    }

    // ========== 右下角超分按钮（每页三重语义） ==========

    /** 该页超分按钮此刻该做什么（UI 只读它决定图标/高亮/可点性）。**纯内存，无 IO**。 */
    enum class SrAction {
        /** 超分功能关闭 → 整个按钮不显示（用户口径） */
        HIDDEN,

        /** 正在超分 → 置灰 */
        BUSY,

        /** 未超分（或该页是别的模型超的）→ 点击 = 用当前模型超分（覆盖） */
        ENHANCE,

        /** 已超分、正显示超分底图 → 点击 = 切回原图（文件保留） */
        SHOW_ORIGINAL,

        /** 已超分、正显示原图 → 点击 = 切回超分底图 */
        SHOW_SR,
    }

    fun srActionOf(pageIndex: Int): SrAction {
        if (!SrSettings.isEnabledForReader(appPrefs)) return SrAction.HIDDEN
        if (SrProcessor.isRunning(manga.id, pageIndex)) return SrAction.BUSY
        val active = SrModelManager.getActiveKey(appPrefs)?.name
        if (active == null || srModelByPage[pageIndex] != active) return SrAction.ENHANCE
        return if (isSrVisualOn(pageIndex)) SrAction.SHOW_ORIGINAL else SrAction.SHOW_SR
    }

    /**
     * 该页是否**已有超分结果**（有 → 右下角出现「二态切换」与「删除超分」两个按钮，
     * 且「超分」按钮变成「重新超分」，与翻译侧 [stateOf]==SUCCESS 的写法完全对称）。
     *
     * ⚠️ 纯内存读：`srModelByPage` 在**每次上屏**（[srBaseFor] / [warmSrBase] 的 IO 路径）里 seed，
     * 所以正常流程下它是准的；这里不能读磁盘（本函数在 chrome 刷新里被高频调用）。
     */
    fun hasSrResult(pageIndex: Int): Boolean = srModelByPage.containsKey(pageIndex)

    /** 该页当前显示的是不是超分底图（二态按钮用它决定图标/高亮）。 */
    fun srBaseOn(pageIndex: Int): Boolean = isSrVisualOn(pageIndex)

    /**
     * 二态切换：原图 ⇄ 超分底图（**只改显示，不动文件**）。
     * 没有超分结果时什么都不做（按钮那时也不该出现）。
     */
    suspend fun toggleSrBase(pageIndex: Int): SrAction {
        if (!hasSrResult(pageIndex)) return srActionOf(pageIndex)
        val nowOn = isSrVisualOn(pageIndex)
        srVisualByPage[pageIndex] = !nowOn
        onBaseChanged(pageIndex)
        return srActionOf(pageIndex)
    }

    /**
     * 删除本页超分结果（图片 + 标记），并把显示切回原图 —— 与「清除本页译文」对称。
     *
     * 用户口径（2026-10）：「超分之后…同样可以删除超分结果」。
     */
    suspend fun deleteSrResult(pageIndex: Int): Boolean {
        val deleted = withContext(Dispatchers.IO) { SrStore.deletePage(context, manga.id, pageIndex) }
        srBaseLru.remove(pageIndex)
        srModelByPage.remove(pageIndex)
        // 没有超分结果了 → 自然回落原图（留着 true 也没用，但没有结果时 baseSig 本来就是 o）
        srVisualByPage.remove(pageIndex)
        evictPageRenders(pageIndex)
        clearWebtoonCache()
        withContext(Dispatchers.Main) { if (uiAttached) onVisual() }
        LogCollector.d(TAG, "删除超分结果 page=$pageIndex ok=$deleted")
        return deleted
    }

    /**
     * 点击右下角超分按钮的结果。
     *
     * ⚠️ 刻意不是一个 `SrAction`：超分**失败时要带出具体原因**（用户口径：不许写模糊提示），
     * 而 `SrAction` 只描述"按钮该长什么样"。
     */
    sealed interface SrClickResult {
        /** 动作成功：切了显示态，或超分产出并已切到超分底图 */
        data class Done(val action: SrAction) : SrClickResult

        /** 超分没成功 —— [message] 已经是**可直接展示的完整原因**（本地化 + 带参数） */
        data class Failed(val message: String) : SrClickResult
    }

    /**
     * **「超分 / 重新超分」按钮**：无条件跑一次超分（有结果就覆盖）。
     *
     * 与「二态切换」拆开是用户口径（2026-10）：「超分之后变成重新超分，然后显示两态切换按钮，
     * 和翻译的逻辑差不多」—— 翻译侧就是「翻译/重翻」一个按钮 + 「三态」另一个按钮，
     * 两者职责不混在一起（以前一个按钮既跑超分又切换，用户根本看不出点下去会发生什么）。
     */
    suspend fun onSrButtonClicked(pageIndex: Int): SrClickResult {
        val action = srActionOf(pageIndex)
        when (action) {
            SrAction.HIDDEN -> return SrClickResult.Failed(context.getString(R.string.sr_fail_disabled))
            SrAction.BUSY -> return SrClickResult.Failed(context.getString(R.string.sr_fail_busy))
            else -> {
                val outcome = enhancePage(pageIndex)
                if (!outcome.ok) return SrClickResult.Failed(outcome.message(context))
            }
        }
        onBaseChanged(pageIndex)
        return SrClickResult.Done(srActionOf(pageIndex))
    }

    /**
     * 用当前模型对某页**原图**跑一次超分并落盘。
     *
     * ⚠️ **输入永远是 `loadFull` 出来的原图** —— 绝不能拿渲染后的译图去超分
     * （那会把画好的字当像素放大）。译文是**数据**，超分底图之后重新渲染即可。
     *
     * @return 失败时 [SrOutcome.reason] 说明**具体**是哪里不行（没模型 / 文件不在 / 加载失败 / 图太大 / 推理失败…）
     */
    private suspend fun enhancePage(pageIndex: Int): SrOutcome {
        if (!SrSettings.isEnabledForReader(appPrefs)) return SrOutcome.fail(SrFailReason.DISABLED)
        val src = withContext(Dispatchers.IO) { loadFull(pageIndex) }
            ?: return SrOutcome.fail(SrFailReason.PAGE_LOAD_FAILED, context.getString(R.string.sr_fail_page_load))
        val outcome = try {
            SrProcessor.enhanceAndStore(context, manga.id, pageIndex, src)
        } finally {
            // 原图是本次现解码的（`loadFull` 纯解码）→ 用完必须回收
            if (!src.isRecycled) src.recycle()
        }
        if (outcome.ok) {
            // 超分完成后默认显示在超分底图上（用户口径）
            srVisualByPage[pageIndex] = true
            srModelByPage[pageIndex] = SrModelManager.getActiveKey(appPrefs)?.name.orEmpty()
        }
        return outcome
    }

    /**
     * 「翻译时自动超分」的**启动判断 + 提交**（两个调用点共用，只是时机不同）。
     *
     * @return 已提交的超分任务；null = 本次不超分（开关关着 / 不是当前页 / 已经是当前模型超的）
     *
     * ## 两个调用点（用户口径 2026-10）
     * ```
     * 点翻译 → ① 原图 OCR（持 OcrLock）→ ② 【这里】启动超分 ∥ 翻译请求 → ③ 渲染
     * ```
     * - **`onOcrDone`**：非分批路线的**首选**落点 —— 就在 `OcrLock` 刚放掉的瞬间，
     *   于是「超分 ∥ API 请求」成立（用户要的并行）。
     * - **[maybeStartSrAfterTranslate]**：**兜底** —— 分批路线（识别与两批翻译交错在管线里，
     *   拿不到 OCR 结束点）与各种中途返回的路线，只能在收尾后启动（退化成串行）。
     *   两者**互斥**：前者拿到任务就不会再走后者（`runTranslate` 里用 `srStarted` 记着）。
     *
     * ## 两条硬约束（都是用户口径）
     * 1. **必须排在 OCR 之后**：超分与 OCR 共用 `OcrLock`，且「不要超分和 OCR 同时启动」
     *    （都是本地重计算，抢核只会两边都慢）。上面两个落点都满足。
     * 2. **只对"当前页"**：自动/增量模式会预翻后面 5~10 页，每页都顺手超分 = 每翻一页多跑
     *    5~10 次推理，队列会爬不动。整章批量翻译不受此限（用户显式点了「翻译本章」）。
     */
    private fun startAutoSr(page: Int): Job? {
        if (!SrSettings.isAutoEnabledForReader(appPrefs)) return null
        if (page != currentPageProvider()) return null
        // 该页已经是"当前模型超的" → 没什么可做（换过模型 / 手动点过都会走到这）
        if (srModelByPage[page] == SrModelManager.getActiveKey(appPrefs)?.name) return null
        return scope.launch(Dispatchers.IO) {
            val outcome = enhancePage(page)
            if (outcome.ok) {
                LogCollector.d(TAG, "翻译时自动超分完成 page=$page")
                onBaseChanged(page)
            } else {
                // ⚠️ 自动超分失败**必须告诉用户原因**（只记日志 = 用户看到"开了超分却没效果"，
                //    只能自己猜是没选模型还是图太大）；呈现方式由宿主决定（状态浮层/Toast）。
                val msg = outcome.message(context)
                LogCollector.d(TAG, "翻译时自动超分未产出 page=$page: $msg")
                withContext(Dispatchers.Main) { onSrNotice(msg, true) }
            }
        }
    }

    /**
     * **兜底**启动点：翻译收尾（锁已释放）后启动。
     *
     * 只有"没能在 OCR 结束点启动"的路线会走到这里（分批路线 / OCR 为空 / 模型缺失等中途返回）。
     */
    private fun maybeStartSrAfterTranslate(page: Int) {
        startAutoSr(page)
    }

    // ========== 宿主注入（避免控制器反向依赖 Activity / ReaderPageSource） ==========

    // ⚠️ 默认就走**自持数据源**（而不是 { null }）：后台任务/通知栏进来时 Activity 没 bind，
    // 用 { null } 的话每一页都会"原图加载失败"（静默 → 全页标失败 → 队列跳过 → 看着像没反应）
    // ⚠️ 这几个 lambda 都是**主线程写（bind/unbindUi）、应用级后台任务读**，
    // 必须 @Volatile：否则（JMM 无 happens-before）unbindUi() 之后后台仍可能看到旧 lambda
    // → 继续调用已销毁 Activity 的页图加载器/浮层回调（正是这段注释声称要避免的泄漏）。
    @Volatile private var loadFull: (Int) -> Bitmap? = { ownSource.loadFull(it) }
    @Volatile private var loadWebtoon: ((Int) -> Bitmap?)? = null
    @Volatile private var originalWidthOf: ((Int) -> Int)? = null
    @Volatile private var currentPageProvider: () -> Int = { -1 }
    @Volatile private var pageCount: () -> Int = { 0 }

    /**
     * 阅读器 UI 是否还挂着。
     *
     * 章节批量任务是**应用级后台任务**：阅读器关掉之后它照跑（前台服务显示进度），
     * 但那时不该再渲染上屏、也不该往状态浮层推消息 —— 渲染产物没人看，纯烧 CPU 和渲染缓存。
     */
    @Volatile
    var uiAttached: Boolean = false
        private set

    /**
     * 是否**曾经**被阅读器挂上过。
     *
     * ⚠️ 用途：`ReaderTranslationHub` 的收集协程订阅 `chapterJobs` 时会**立刻收到一次当前值**，
     * 而那一刻 Activity 还在 `bind()` 之前（`uiAttached` 仍是 false）→ 会把**刚创建**的控制器
     * 当场 `releaseIfIdle` 回收掉，Activity 手上留下一个已经 `shutdownAll()` 的实例，
     * 下次再进阅读器又新建一个（日志里能看到"创建→回收→再创建"）。
     * 所以回收必须等它至少被挂过一次。
     */
    @Volatile
    var everAttached: Boolean = false
        private set

    /**
     * 是否已被 [shutdownAll] 关掉（hub 回收后就**不能再 bind**）。
     *
     * ⚠️ 竞态：`controllerFor()` 与 Activity 的 `bind()` 之间，hub 的收集协程可能判定
     * 「无 UI 且无任务」把它回收（那一刻 `uiAttached` 还是 false）→ Activity 手上留一个已关实例，
     * 之后「翻译本章」全都不动。阅读器 bind 后自检这个标志并换一个新实例即可闭合窗口。
     */
    @Volatile
    var closed: Boolean = false
        private set

    /**
     * 后台任务自己读页图用的数据源。
     *
     * ⚠️ 必须有它：以前页图靠 Activity `bind(loadFull = { source.loadFull(it) })` 注入，
     * 阅读器一关这个 lambda 就指向已销毁的 Activity（既拿不到图、又把 Activity 泄漏住）。
     * 后台任务改成用这条**自持**数据源后，OCR 阶段与 UI 完全解耦。
     */
    private val ownSource: ReaderPageSource by lazy { ReaderPageSource(manga.isArchive, manga.localRoot) }

    /** 页图 / 当前页 / 总页数 由 Activity 注入。 */
    fun bind(
        loadFull: (Int) -> Bitmap?,
        currentPage: () -> Int,
        pageCount: () -> Int,
    ) {
        this.loadFull = loadFull
        this.currentPageProvider = currentPage
        this.pageCount = pageCount
        this.uiAttached = true
        this.everAttached = true
    }

    /**
     * 阅读器关闭：解除 UI 绑定，**保留后台章节任务**。
     *
     * ⚠️ 必须把 [loadFull] 换回自持数据源、把 UI 回调清空：不然后台任务会继续调用
     * 已销毁 Activity 的页图加载器与状态浮层（泄漏 + 往桌面贴芯片）。
     */
    fun unbindUi() {
        uiAttached = false
        loadFull = { ownSource.loadFull(it) }
        loadWebtoon = null
        originalWidthOf = null
        currentPageProvider = { -1 }
        pageCount = { ownSource.size }
        onVisual = {}
        onPhase = { _, _ -> }
        onSrNotice = { _, _ -> }
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

    /** 队列引擎是否在跑（用于状态浮层/按钮文案）。 */
    private var queueJob: Job? = null

    /**
     * 阅读器前台路径（手动 / 自动 / 增量）在途任务的取消信号集合。
     *
     * ⚠️ **章节批量任务刻意不用它**，各自持一个"永远不会被 [cancelAll] 触碰"的标志 ——
     * 共用一份会出两个方向的错，详见 [ReaderCancelSignals] 的类注释（其中一个是
     * "翻译本章一页都翻不出来"这种静默重故障）。
     */
    private val readerCancel = ReaderCancelSignals()

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

    /**
     * 阅读器关闭时调用：停掉自动/增量队列与在途手动翻译、回退手动、解除 UI 绑定
     * —— 但**保留章节批量任务**（那是应用级后台任务，用户明确要求它继续跑）。
     *
     * 不弹提示，提示由调用方决定（见阅读器 `onDestroy`）。
     */
    fun onReaderClosed() {
        cancelEverything()
        translateMode.value = MODE_MANUAL
        version.value += 1
        unbindUi()
    }

    /** 彻底停掉（连章节批量任务一起）—— 换书 / 控制器被回收时用。 */
    fun shutdownAll() {
        closed = true
        cancelEverything()
        chapterRunner.shutdown()
        translateMode.value = MODE_MANUAL
        version.value += 1
        unbindUi()
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

                    // ⚠️ 必须先查锁：OCR 拿不到锁这一页就白跑，而循环下一轮又会重选到同一个仍是
                    // IDLE 的页 → **每 500ms 空转一次，永远翻不动、也永远走不到"队列耗尽"**。
                    // 这里原地等待并提示，锁一释放就继续。
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

                    if (translateMode.value == MODE_AUTO) {
                        // **自动**：窗口恒 1 页，仍走单页路径 —— 它要保留**页内分批 + 流式**
                        // （边翻边出：用户正看着这一页，先出一部分才有意义）。
                        // 并发数在这里没有意义（同时只有 1 页）。
                        val target = cur.takeIf { isTranslatable(it) }
                        if (target == null) {
                            LogCollector.d(TAG, "queue drained: 当前页 P${cur + 1} 无需翻译")
                            drained = true
                            break
                        }
                        queuePage.value = target
                        try {
                            runTranslate(target, fromQueue = true)
                        } finally {
                            queuePage.value = -1
                        }
                        continue
                    }

                    // **增量**：一个窗口一次 —— OCR 逐页串行 + 翻译请求并发 `同时请求数`。
                    // 用户口径（2026-09-28）：「最大请求数对增量翻译也应该生效」。
                    val end = (cur + aheadPages.value).coerceAtMost(total)
                    val targets = (cur until end).filter { p -> isTranslatable(p) }
                    if (targets.isEmpty()) {
                        LogCollector.d(TAG, "queue drained: 窗口 [$cur, $end) 无待翻页")
                        drained = true
                        break
                    }
                    val dispatched = runWindow(targets)
                    if (dispatched == 0) {
                        // 整窗口都在 OCR 阶段失败（失败已各自落库）→ 别原地空转
                        LogCollector.d(TAG, "queue drained: 窗口 [$cur, $end) 全部未产出")
                        drained = true
                        break
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
    private fun isTranslatable(page: Int): Boolean {
        // ⚠️ 还要排除**章节任务已经拿走的页**：它们在库里的状态可能仍是 IDLE（识别中/预取中不写库），
        // 只看库状态会让增量窗口与整章任务翻同一页 → OCR 两遍 + API 请求两份（白烧额度）。
        if (page in chapterRunner.runningOwnedPages()) return false
        return stateOf(page) == ImportedPageTranslation.STATE_IDLE
    }

    /**
     * **增量模式的一个窗口**：OCR 逐页串行 + 翻译请求**并发 N**（`manga_concurrent_requests`）。
     *
     * 用户口径（2026-09-28）：「最大请求数对增量翻译也应该生效」——语义与整章任务**完全一致**：
     * - OCR 仍逐页串行（引擎是单例、持 `OcrLock`）→ 界面恒为「1 页识别中」；
     * - 识别完**立刻**把这一页交给翻译段（并发 N 个请求在飞，多的排队）；
     * - 取消 = 协程取消：在途页**强制结束、不入库**（`translatePhase` 的取消分支退回未翻译），
     *   与「打开面板 / 退出阅读器」时的强制退出同一套；
     * - 只有"用户正在看的那页"渲染上屏（[translatePhase] 内部判定）。
     *
     * ⚠️ **先占槽位再 OCR**：把流水线深度压在「1 页识别 + N 页翻译」，
     * 内存（每页一张全尺寸 page bitmap）不随窗口大小（最多 10 页）膨胀。
     *
     * @return 本窗口真的进入翻译阶段的页数（0 = 窗口内全都没产出 → 队列耗尽）
     */
    private suspend fun runWindow(targets: List<Int>): Int {
        val slots = kotlinx.coroutines.sync.Semaphore(
            TranslationConcurrency.mangaConcurrency(context, appPrefs).coerceAtLeast(1)
        )
        var dispatched = 0
        try {
            coroutineScope {
                for (page in targets) {
                    if (!isActive || translateMode.value != MODE_AHEAD) break
                    if (!isTranslatable(page)) continue
                    slots.acquire()
                    if (!isActive) {
                        slots.release()
                        break
                    }
                    queuePage.value = page
                    reportQueuePhase(page, ReaderTranslatePhase.DETECTING, null)
                    val prep = ocrPhase(page)
                    if (prep == null) {
                        // OCR 阶段自己已经写了失败记录 + 日志（等锁超时/加载失败/空页）
                        slots.release()
                        continue
                    }
                    reportQueuePhase(page, ReaderTranslatePhase.TRANSLATING, null)
                    dispatched++
                    launch {
                        try {
                            if (translatePhase(page, prep, label = "增量")) {
                                reportQueuePhase(page, ReaderTranslatePhase.SUCCESS, null)
                            }
                        } finally {
                            slots.release()
                        }
                    }
                }
            }
        } finally {
            queuePage.value = -1
        }
        LogCollector.d(TAG, "增量窗口 [$targets] 已提交 $dispatched 页（并发 ${TranslationConcurrency.mangaConcurrency(context, appPrefs)}）")
        return dispatched
    }

    /**
     * 队列（自动/增量）的阶段性提示。
     *
     * ⚠️ 与旧 `runTranslate` 的口径一致：**检测/翻译阶段照常上报**（顶部状态栏要跟着当前页走），
     * 成功/失败**只有当前页才报**（连续翻 10 页会弹 10 次"翻译完成"，太吵）。
     */
    private fun reportQueuePhase(page: Int, phase: ReaderTranslatePhase, message: String?) {
        if (phase == ReaderTranslatePhase.SUCCESS || phase == ReaderTranslatePhase.FAILED) {
            if (page == currentPageProvider()) onPhase(phase, message)
        } else {
            onPhase(phase, message)
        }
    }

    // ========== 章节批量翻译（应用级后台任务 · 章卡片上的「翻译本章 / 暂停 / 取消」） ==========

    /**
     * 章节批量翻译的流水线：OCR 串行 + 翻译并发 + 按章暂停/取消（见 [ChapterJobRunner]）。
     *
     * ⚠️ **这是应用级后台任务**（用户口径）：控制器由 `ReaderTranslationHub` 持有、跑在应用级 scope 上，
     * 阅读器关掉也继续翻，进度在前台服务的通知栏里（`TranslationJobService`）。
     * 所以章节任务**不走** `lifecycleScope`，`cancelEverything()` 也不再取消它。
     */
    private val chapterRunner = ChapterJobRunner(
        scope = scope,
        concurrency = { TranslationConcurrency.mangaConcurrency(context, appPrefs) },
        ocr = { page -> ocrPhase(page) },
        translate = { page, prep -> translatePhase(page, prep) },
        onJobFinished = { chapterIndex, ok, total, cancelled ->
            // 取消时把残留的「翻译中」退回未翻译（正常跑完每页已各自落库）
            if (cancelled) resetTranslatingRowsAsync()
            jobFinishedListeners.forEach { runCatching { it(chapterIndex, ok, total, cancelled) } }
        },
        // ⚠️ 被退回/丢弃的预取页不会进翻译阶段 → 它那张全尺寸页图必须在这里回收
        discard = { _, prep -> prep.bitmap.recycle() },
    )

    /** 每章一个任务（含暂停/取消状态）—— 面板据此切按钮文案与「等待」标签。 */
    val chapterJobs: StateFlow<List<ChapterJob>> = chapterRunner.jobs

    /** 排队中（还没开始翻）的页。 */
    val waitingPages: StateFlow<Set<Int>> = chapterRunner.waitingPages

    /**
     * 一章收尾（含取消）的监听器：章下标 / 成功页数 / 总页数 / 是否取消。
     *
     * ⚠️ 用**监听器列表**而不是单个 `var`：宿主（阅读器）与 `ReaderTranslationHub`（通知栏）
     * 都要收这个事件 —— 单个 var 会被后设的那个覆盖（曾经 `onTranslateChanged` 没有调用方
     * 就是这类"回调只有一个坑位"的坑）。宿主关掉阅读器时要记得 `remove`。
     */
    private val jobFinishedListeners =
        java.util.concurrent.CopyOnWriteArrayList<(Int, Int, Int, Boolean) -> Unit>()

    fun addJobFinishedListener(listener: (chapterIndex: Int, ok: Int, total: Int, cancelled: Boolean) -> Unit) {
        jobFinishedListeners += listener
    }

    fun removeJobFinishedListener(listener: (chapterIndex: Int, ok: Int, total: Int, cancelled: Boolean) -> Unit) {
        jobFinishedListeners -= listener
    }

    /** 这本书的 id / 标题（通知栏与本控制器对外汇总用）。 */
    val mangaId: Long get() = manga.id

    /** 身份指纹（= `manga.addedAt`）：宿主用它判断"同一个 id 是否已经换了一本书"。 */
    fun translationKeyOf(): String = mangaKey
    val mangaTitle: String get() = manga.title

    fun isChapterBatchRunning(): Boolean = chapterRunner.isBusy()

    fun chapterJob(chapterIndex: Int): ChapterJob? = chapterRunner.jobOf(chapterIndex)


    /**
     * **正在被章节任务处理的页**（OCR 中 / 翻译中）。
     *
     * ⚠️ 面板必须能拿到它：在途页的库状态在"OCR 中"这段还是 IDLE（`translatePhase` 开始才写 TRANSLATING），
     * 而面板**不显示 IDLE 行** → 不额外补这些页的话，用户会看到"正在翻译的页卡片直接消失"
     * （2026-09-28 用户报的）。
     */
    fun inFlightPages(): Set<Int> = chapterRunner.inFlightPages()

    /** **识别中**（OCR 阶段）的页 —— 面板与状态浮层要把它和「翻译中」分开显示（用户口径）。恒 ≤1 页。 */
    fun ocrPages(): Set<Int> = chapterRunner.ocrPages()

    /** **翻译中**（请求已发出 / 本地推理中）的页。 */
    fun translatingPages(): Set<Int> = chapterRunner.translatingPages()

    /**
     * 面板/浮层口径的**「等待」**：队列里还没取的页 **+ 已被流水线预取、但还没轮到识别的页**。
     *
     * ⚠️ 两者必须合并显示：预取项（`QUEUED`）的 OCR 还没开始，对用户就是「等待」——
     * 只用 `waitingPages` 的话这些页会掉进「在途但既非识别中、也非翻译中」的空档，
     * 界面要么不显示它们、要么（更糟）把它们标成「识别中」（用户报的"一启动同时 3 个识别中"）。
     */
    fun panelWaitingPages(): Set<Int> = waitingPages.value + chapterRunner.queuedPages()

    /** 在途项快照（含阶段）：状态浮层按它拼「识别中 P8 · 翻译中 P5, P6」。 */
    val chapterInFlight: StateFlow<List<InFlightTask>> = chapterRunner.inFlightTasks

    /**
     * 提交一章的批量翻译。[pages] 由调用方按状态筛好（默认「未成功」的页）。
     *
     * 会先停掉自动/增量队列与在途手动翻译（它们与本任务抢同一把 `OcrLock`），
     * 但**不会动别的章已经在跑的任务** —— 用户要求可以同时启动多个章节。
     */
    fun startChapterJob(chapterIndex: Int, pages: List<Int>, label: String = "", startPage: Int = 0) {
        val targets = pages.filter { it >= 0 }
        if (targets.isEmpty()) return
        cancelEverything()
        // ⚠️⚠️ **必须把 cancelFlag 复位**（2026-09-28 定位到的致命 bug）：
        // `cancelEverything()` 会把队列取消标志置 true，而这个标志是**手动/增量队列**的取消信号；
        // 章节批量任务以前**没人复位它**（只有 `runTranslate` 开头会复位），于是：
        //   启动 → cancelFlag=true → `translatePhase` → 管线第一句
        //   `if (host.isCancelled()) throw TranslationCancelledException()` 立刻抛出
        //   → 这一页既不调 API、也不记失败、**连日志都没有**（该 catch 分支静默）→ 退回 IDLE。
        // 表现就是用户报的那一串：「进度在涨但阅读器毫无变化、没有译文、没有成功卡片、
        // 进行中的卡片一翻完就消失、取消后什么都没有」——**章节翻译 100% 无效**。
        // 章节任务自己的取消走协程取消（`ChapterJobRunner.cancel/shutdown`），不靠这个标志。
        // ⚠️ 这里**不重置任何取消标志**：分支的取消是每页一个新 flag，
        //    章节任务的取消判据恒为 `{ false }`（由 ChapterJobRunner 用协程取消来停）。
        //    master 侧那行「把队列取消标志复位」随旧字段一起作废 —— 当年正是"读同一个字段"
        //    让 startChapterJob 把整章打成已取消（章节翻译 100% 无效）。
        chapterRunner.submit(chapterIndex, targets, label, startPage)
    }

    fun pauseChapterJob(chapterIndex: Int) {
        chapterRunner.pause(chapterIndex)
    }

    fun resumeChapterJob(chapterIndex: Int) {
        chapterRunner.resume(chapterIndex)
    }

    /** 取消：丢掉该章还没开始翻的页，**已翻好的译文保留**（用户口径）。 */
    fun cancelChapterJob(chapterIndex: Int) {
        chapterRunner.cancel(chapterIndex)
    }

    /** 取消全部章节任务（换书 / 用户明确要停）。 */
    fun cancelAllChapterJobs() {
        chapterRunner.shutdown()
    }

    /** OCR 阶段的产物：页图 + 气泡（含识别文本）。 */
    private class PreparedPage(
        val bitmap: Bitmap,
        val bubbles: List<BubbleRegion>,
        val det: DetEngine,
        val ocr: OcrEngine,
        val srcLang: String,
        val tgtLang: String,
    )

    /**
     * OCR 阶段：检测 + 识别，**持 `OcrLock`（全局串行）**。
     *
     * ⚠️ 与旧实现的关键区别：以前 `runTranslate` **从 OCR 一直持锁到翻译结束**，
     * 所以两页之间不可能重叠。这里锁只覆盖 OCR —— 翻译请求不碰 OCR 引擎单例，
     * 放开之后「第 1 页发出请求」与「第 2 页开始 OCR」才能并行（用户要的提速）。
     */
    /**
     * 等 OCR 引擎锁（带超时）。拿到 true；超时 false。
     *
     * ⚠️ 超时**必须**由调用方如实记账（记失败 + 日志）：静默跳过会让"进度在涨但什么都没翻"
     * 且完全没有线索（见 [ocrPhase] 的注释）。
     */
    private suspend fun acquireOcrLockWithWait(page: Int): Long {
        OcrLock.acquire().let { if (it != 0L) return it }
        var waited = 0L
        while (waited < OCR_LOCK_WAIT_TIMEOUT_MS) {
            delay(OCR_LOCK_POLL_MS)
            waited += OCR_LOCK_POLL_MS
            OcrLock.acquire().let { if (it != 0L) return it }
            if (waited % 5_000L < OCR_LOCK_POLL_MS) {
                LogCollector.d(TAG, "OCR 阶段：等待引擎锁 ${waited}ms（page=$page，持有 ${OcrLock.heldMs()}ms）")
            }
        }
        return 0L
    }
    private suspend fun ocrPhase(page: Int): PreparedPage? {
        // ⚠️ **等锁必须有超时，且超时要如实记账**（2026-09-28 用户报的"进度在涨、什么都没翻、
        // 正在翻译的卡片直接消失"就是这个静默 return null 造成的）：
        // 以前 `while (isRunning) delay()` 之后 `tryAcquire()` 一旦被别人抢先就**静默返回 null** ——
        // 这一页于是既不翻、也不记失败、连日志都没有，而流水线照样把它算作"已结算"（done+1）
        // → 面板上那一行消失、进度在涨、取消后"什么都没留下"。
        val ocrToken = acquireOcrLockWithWait(page)
        if (ocrToken == 0L) {
            val msg = context.getString(R.string.reader_translate_ocr_busy)
            LogCollector.w(TAG, "OCR 阶段：等待引擎锁超时 page=$page（别的翻译一直占着）→ 本页记为失败")
            withContext(kotlinx.coroutines.NonCancellable) { runCatching { fail(page, "PROCESS_EXCEPTION", msg) } }
            return null
        }
        try {
            val (det, ocr) = try {
                // ⚠️ 持锁期间**必须打心跳**：OcrLock 有 30s 无心跳就判定"持有者已死"并强制释放，
                // 而引擎就绪（模型加载）可能很久 —— 误放会让两个线程同时用单例 ONNX 引擎。
                OcrLock.heartbeat(ocrToken)
                TranslationEngineInit.ensureReady(context).also { OcrLock.heartbeat(ocrToken) }
            } catch (e: Exception) {
                LogCollector.e(TAG, "engine init failed page=$page", e)
                val msg = context.getString(R.string.reader_translate_model_missing, e.message.orEmpty())
                withContext(NonCancellable) { runCatching { upsertState(page, ImportedPageTranslation.STATE_IDLE) } }
                // 失败原因落库，面板行里能看到
                withContext(NonCancellable) { runCatching { fail(page, "OCR_MODEL_MISSING", msg) } }
                return null
            }
            val bitmap = loadFull(page)
            if (bitmap == null) {
                val msg = context.getString(R.string.reader_translate_load_failed)
                withContext(NonCancellable) { runCatching { fail(page, "PROCESS_EXCEPTION", msg) } }
                return null
            }
            val srcLang = customPrefs.getString("Source_Language", "ja")
            val tgtLang = customPrefs.getString("Target_Language", "zh")
            val overlayConfig = cacheManager.getOverlayConfig(appPrefs)
            val rtDirection = RtTextDirection.load(appPrefs)
            return try {
                val blocks = DetectionBridge.runOCR(
                    bitmap, srcLang, det.value, ocr.value, context,
                    appPrefs.getBoolean(MangaModeConfig.KEY_KEEP_TEXT_FREE, true),
                    rtDirection,
                )
                val bubbles = DetectionBridge.ocrToBubbleRegions(
                    blocks, RtTextDirection.resolve(det, rtDirection, overlayConfig.textDirection)
                )
                PreparedPage(bitmap, bubbles, det, ocr, srcLang, tgtLang)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // ⚠️ **取消不是失败**：掐流水线/退出时，正在 OCR 的这一页会被取消 ——
                // 若照 `catch (Exception)` 处理就会把它标成「失败」（面板上冒出一堆用户没见过的失败页，
                // 还得手动清）。取消就原样抛出：页留在原状态，任务按"未完成"结算。
                if (!bitmap.isRecycled) bitmap.recycle()
                throw e
            } catch (e: Exception) {
                if (!bitmap.isRecycled) bitmap.recycle()
                LogCollector.e(TAG, "OCR 阶段失败 page=$page", e)
                withContext(NonCancellable) {
                    runCatching { fail(page, "PROCESS_EXCEPTION", e.message ?: "OCR failed") }
                }
                null
            }
        } finally {
            OcrLock.release(ocrToken)
        }
    }

    /**
     * 翻译阶段：文本缓存 → 调翻译 API（**并发，不持 OcrLock**）→（当前页才）渲染 → 写库。
     *
     * ⚠️ **本阶段绝不受手动/队列的 `cancelFlag` 影响**（见 [ReaderBatchHost] 的取消判据）：
     * 章节任务由 `ChapterJobRunner` 用协程取消来停，抢用同一个标志会让**整章翻译直接报废**
     * （2026-09-28 的致命 bug，注释见 [startChapterJob]）。
     *
     * @return true = 这一页真的翻出来了（用于任务面板上的成功计数）
     */
    private suspend fun translatePhase(
        page: Int,
        prep: PreparedPage,
        /** 日志前缀（"章节" / "增量"）——两条路径共用这一段，日志要能分清是谁在翻。 */
        label: String = "章节",
    ): Boolean {
        // ⚠️ 声明在 try 之外：`finally` 里要 `join()` 它再回收 prep.bitmap
        //    （并行超分时那张图还在用）。—— 分支的超分接线，合并时保留
        var srJob: Job? = null
        val startedAt = System.currentTimeMillis()
        // ⚠️ **进入/离开翻译阶段各留一条日志**：这段以前只有"成功"才打日志，
        // 于是"进没进来、是不是卡住了"完全看不出来（排查 2026-09-28 那个 bug 时吃了大亏）。
        LogCollector.d(
            TAG,
            "$label 翻译 page=$page 开始：气泡=${prep.bubbles.size}，引擎=${prep.det}/${prep.ocr}，" +
                "${prep.srcLang}->${prep.tgtLang}"
        )
        var result = "未完成"
        try {
            if (prep.bubbles.isEmpty()) {
                val msg = context.getString(R.string.reader_translate_ocr_empty)
                fail(page, "OCR_EMPTY", msg)
                result = "失败(OCR 空)"
                return false
            }
            upsertState(page, ImportedPageTranslation.STATE_TRANSLATING)
            renderLru.remove(partialKey(page, baseSig(page)))

            val translator: TranslationTextAPI? =
                TranslatorFactory.create(context, customPrefs, TranslatorFactory.Mode.MANGA)
            if (translator == null) {
                fail(page, "TRANSLATION_API_NOT_CONFIGURED", context.getString(R.string.reader_translate_api_not_configured))
                result = "失败(翻译引擎未配置)"
                return false
            }
            // ── 超分（v2）：OCR 已完成 → 此刻才是启动点 ──
            // • 网络 API：srJob 不 join，超分与翻译请求**并行**
            // • 本地引擎（LlamaCpp/NLLB）：都是 CPU 重活，**必须串行** → 立刻 join
            srJob = maybeStartAutoSr(page, prep)
            if (translator.isLocalHeavyEngine()) srJob?.join()
            val overlayConfig = cacheManager.getOverlayConfig(appPrefs)
            val cfg = BatchPipelineConfig(
                detEngine = prep.det,
                ocrEngine = prep.ocr,
                sourceLang = prep.srcLang,
                targetLang = prep.tgtLang,
                textDirection = overlayConfig.textDirection,
                keepTextFree = appPrefs.getBoolean(MangaModeConfig.KEY_KEEP_TEXT_FREE, true),
                prefs = customPrefs,
                // 批量任务**不做页内分批**：页内分批是"先出一部分给正在看的用户"，
                // 而这里追求的是**跨页流水线**（OCR 下一页与翻译本页重叠），两者会互相拖慢
                incrementalEnabled = false,
                isAutoTranslating = false,
                rtTextDirection = RtTextDirection.load(appPrefs),
            )
            // 阶段回调全部静默：连翻几十页时浮层由宿主按任务进度统一显示
            //
            // ⚠️ 取消信号：**每页一个、永不注销的 false 常量**。
            // 章节批量任务的取消走 `ChapterJobRunner` 的按章取消（用户口径：取消只丢还没
            // 开始翻的页，**在途页照旧跑完**）；绝不能让它读阅读器前台那套标志 ——
            // 那正是"点翻译本章前一停队列，整章就被打成已取消"的根因（见 ReaderCancelSignals）。
            val host = ReaderBatchHost(prep.bitmap, translator, { _, _ -> }, page, prep.det, prep.ocr) { false }
            val pipeline = IncrementalBatchPipeline(host, scope, cfg)
            // 上下文：**用户开了上下文功能就带**（用户口径「api 调用属于同一个上下文，前提是开启了
            // 设置的上下文功能」）—— 整章一批批翻下来，前几页的译文要能帮到后面的页面。
            // 预算由 `ContextBudget` 按 token 裁（网络 API 用设置档位、本地模型用模型自己的 ctx），
            // 超预算自动丢最旧的轮；开关关着时 forceContext=false，与原来完全一致。
            val contextEnabled = appPrefs.getBoolean("game_context_enabled", false)
            val translated = pipeline.translateWithCache(prep.bubbles, forceContext = contextEnabled)
            // ⚠️ 这里**不**再累加缓存统计：本章批量路径从不显示「命中 x/y」那条完成提示，
            //    写进实例字段只会污染前台手动翻译那一路的数字（跨页串号）。
            if (translated.isEmpty()) {
                fail(page, "TRANSLATE_EMPTY", context.getString(R.string.reader_translate_empty))
                result = "失败(译文空)"
                return false
            }
            // 只有"用户正在看的那一页"才渲染上屏（后台页只写库）
            if (uiAttached && page == currentPageProvider()) {
                renderInto(
                    page, translated, TranslationCacheManager.OverlayMode.TRANSLATED,
                    prep.bitmap, overlayConfig, prep.det,
                )
            }
            renderLru.remove(partialKey(page, baseSig(page)))
            val row = ImportedPageTranslation(
                mangaId = manga.id, pageIndex = page,
                state = ImportedPageTranslation.STATE_SUCCESS,
                sourceText = PageTranslationCodec.sourceText(translated),
                translatedText = PageTranslationCodec.translatedText(translated),
                bubbleRects = PageTranslationCodec.bubbleRects(translated),
                failCode = null, failMessage = null,
                updatedAtMs = System.currentTimeMillis(),
                mangaKey = mangaKey,
                translatorName = TranslateUtils.buildTranslatorDisplayName(translator, prep.det, prep.ocr, appPrefs),
                sourceLang = prep.srcLang,
                targetLang = prep.tgtLang,
            )
            rows.update { it + (page to row) }
            dao.upsert(row)
            version.value += 1
            onVisual()
            LogCollector.d(TAG, "批量翻译完成 page=$page bubbles=${translated.size}")
            result = "成功(译文 ${translated.size} 条)"
            return true
        } catch (e: TranslationCancelledException) {
            // ⚠️ 这个分支以前**完全静默**（只有它会让整章翻译悄无声息地作废：进度在涨、
            // 没有译文、没有成功卡片、日志空白）。现在必须留下痕迹。
            LogCollector.w(TAG, "$label 翻译 page=$page 被判为「已取消」→ 本页作废（退回未翻译）")
            withContext(NonCancellable) { runCatching { upsertState(page, ImportedPageTranslation.STATE_IDLE) } }
            result = "取消"
            return false
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 任务被整体取消：状态退回未翻译（在 NonCancellable 里写库，否则写不进去）
            LogCollector.d(TAG, "$label 翻译 page=$page 协程被取消（强制退出，不入库）")
            withContext(NonCancellable) { runCatching { upsertState(page, ImportedPageTranslation.STATE_IDLE) } }
            result = "任务取消"
            throw e
        } catch (e: Exception) {
            LogCollector.e(TAG, "批量翻译失败 page=$page", e)
            fail(page, "PROCESS_EXCEPTION", e.message ?: "Unknown error")
            result = "异常(${e.message})"
            return false
        } finally {
            // ⚠️ 超分任务可能还在用 prep.bitmap（它与翻译请求**并行**跑）——
            //    必须先等它结束再回收，否则就是 use-after-recycle（native 崩溃，Java 层抓不到）。
            srJob?.join()
            if (!prep.bitmap.isRecycled) prep.bitmap.recycle()
            LogCollector.d(
                TAG,
                "$label 翻译 page=$page 结束：$result，耗时 ${System.currentTimeMillis() - startedAt}ms"
            )
        }
    }

    /**
     * 「翻译时自动超分」的启动点 —— **在这里是因为 OCR 已经完成了**。
     *
     * ## 时机（用户口径 2026-10，别改）
     * ```
     * 点翻译 → ① 原图 OCR（持 OcrLock）→ ② 本函数被 translatePhase 调用
     *                                        ├─ 超分（本函数）
     *                                        └─ 翻译请求
     * ```
     * - **不与 OCR 同时启动**：`translatePhase` 收到 `prep` 就说明 OCR 已经结束
     * - **可与网络 API 请求并行**：返回的 Job 不 join，让它在后台与请求重叠
     * - **本地引擎必须串行**：本地翻译（LlamaCpp / NLLB）也是 CPU 重活，
     *   同时跑只会互相抢核 —— 这种情况下由调用方 `join`（见 [isLocalHeavyEngine]）
     *
     * ## 输入永远是**原图**
     * `prep.bitmap` 是 `ocrPhase` 里从 `loadFull` 拿到的**原图**（v2 起 `loadFull` 零增强）。
     * ⚠️ **绝不能传渲染后的译图** —— 那会把画好的字当像素放大，坐标就真的无解了。
     *
     * @return 超分任务（未开启/未选中模型 → null）
     */
    private fun maybeStartAutoSr(
        page: Int,
        prep: PreparedPage,
    ): Job? {
        if (!SrSettings.isAutoEnabledForReader(appPrefs)) return null
        if (!SrSettings.isEnabledForReader(appPrefs)) return null
        if (prep.bitmap.isRecycled) return null
        // ⚠️ 这里**不再自己预判**"模型下没下载/选没选"：判断集中在 `SuperResolutionEngines`，
        //    它才能给出**具体原因**。预判成 return null 的话用户什么都看不到。
        return scope.launch(Dispatchers.IO) {
            val outcome = SrProcessor.enhanceAndStore(context, manga.id, page, prep.bitmap)
            if (outcome.ok) {
                // 超分完成后默认显示在超分底图上（用户口径）
                srVisualByPage[page] = true
                srModelByPage[page] = SrModelManager.getActiveKey(appPrefs)?.name.orEmpty()
                // 底图换了 → 该页渲染缓存 + 底图缓存一起作废，并刷新上屏
                onBaseChanged(page)
                LogCollector.d(TAG, "翻译时自动超分完成 page=$page")
            } else {
                // ⚠️ 必须把**具体原因**告诉用户（以前只记日志，用户只看到"开了超分却没效果"）
                val msg = outcome.message(context)
                LogCollector.d(TAG, "翻译时自动超分未产出 page=$page: $msg")
                withContext(Dispatchers.Main) { onSrNotice(msg, true) }
            }
        }
    }

    // ========== 删除翻译数据（面板「详情 → 删除」/「清除本章译文」） ==========

    /**
     * 删除**一页的翻译数据**（整行：原文/译文/气泡坐标/状态）。
     *
     * ⚠️ 三个缓存都要一起作废，否则删完页面上还挂着旧译图：分页 `renderLru`（三态各一份）、
     * 半成品 `PARTIAL`、Webtoon `webtoonLru`，以及内存里的三态记录。
     */
    suspend fun deletePage(pageIndex: Int) = withContext(Dispatchers.IO) {
        try {
            dao.deletePage(manga.id, mangaKey, pageIndex)
        } catch (e: Exception) {
            LogCollector.e(TAG, "删除单页译文失败 page=$pageIndex", e)
            return@withContext
        }
        rows.update { it - pageIndex }
        evictPageCaches(pageIndex)
        version.value += 1
        withContext(Dispatchers.Main) { onVisual() }
    }

    /**
     * 清除一段页号的翻译数据（**清除本章译文**；`from`/`to` 都是全书页号，含两端）。
     * 返回真正删掉的行数。
     */
    suspend fun clearPageRange(from: Int, to: Int): Int = withContext(Dispatchers.IO) {
        if (to < from) return@withContext 0
        val affected = rows.value.filterKeys { it in from..to }
        try {
            dao.deletePageRange(manga.id, mangaKey, from, to)
        } catch (e: Exception) {
            LogCollector.e(TAG, "清除译文失败 range=$from..$to", e)
            return@withContext 0
        }
        rows.update { it.filterKeys { page -> page !in from..to } }
        affected.keys.forEach { evictPageCaches(it) }
        version.value += 1
        withContext(Dispatchers.Main) { onVisual() }
        affected.size
    }

    private fun evictPageCaches(pageIndex: Int) {
        evictPageRenders(pageIndex)
        webtoonLru.snapshot().keys.filter { it.startsWith("$pageIndex:") }.forEach { webtoonLru.remove(it) }
        currentVisualByPage.remove(pageIndex)
        srBaseLru.remove(pageIndex)
        srVisualByPage.remove(pageIndex)
        srModelByPage.remove(pageIndex)
    }

    /** 该段页号里已成功翻译的页数。 */
    fun successCount(from: Int, to: Int): Int =
        rows.value.count { it.key in from..to && it.value.state == ImportedPageTranslation.STATE_SUCCESS }

    /** 该段页号里**未成功**（未翻译 + 失败 + 翻译中）的页数。 */
    fun pendingCount(from: Int, to: Int): Int =
        rows.value.count { it.key in from..to && it.value.state != ImportedPageTranslation.STATE_SUCCESS }

    /** 该段页号里有失败记录的页数。 */
    fun failedCount(from: Int, to: Int): Int =
        rows.value.count { it.key in from..to && it.value.state == ImportedPageTranslation.STATE_FAILED }

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
        val busy = mode != MODE_MANUAL || manualJob?.isActive == true || isChapterBatchRunning()

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
        if (OcrLock.isRunning) {
            // ⚠️ "点了没反应"排查关键：这条说明锁被别的翻译占着（截屏翻译在跑 / 上一次取消的任务
            // 还卡在 native OCR 里）。频繁出现 = 有地方没释放锁，别当成用户没点。
            LogCollector.w(TAG, "翻译按钮：OcrLock 被占用 → 提示忙（page=$currentPageProvider()）")
            // ⚠️ **超分也抢这把锁**（v2 起自动超分在翻译结束、锁释放后立刻启动）——
            //    那种情况下面文案必须说清是谁在占，否则用户看到"翻译引擎被占用"会以为坏了。
            //    （R6.5 把手动路径的锁范围缩到 OCR 之后，这条提示自然就消失了。）
            return if (SrProcessor.isBusy()) {
                TranslateClick.Hint(context.getString(R.string.reader_translate_busy_sr))
            } else {
                TranslateClick.Busy
            }
        }
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
        // 章节批量任务优先：它跑的时候模式恒为手动，落到下面会报「请双击退出增量翻译」，
        // 而用户根本没开增量 —— 提示要说清楚「正在翻哪一章、翻到第几页」
        val running = chapterRunner.jobs.value.filter { it.state == ChapterJobState.RUNNING }
        if (running.isNotEmpty()) {
            val done = running.sumOf { it.done }
            val total = running.sumOf { it.total }
            return context.getString(R.string.reader_translate_chapter_hint, done, total)
        }
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

    /**
     * 取消在途翻译与队列，并把「翻译中」的记录退回「未翻译」。
     *
     * ⚠️ **不碰章节批量任务**：那是应用级后台任务（用户要求关掉阅读器也继续翻），
     * 只有显式 [cancelChapterJob] / [cancelAllChapterJobs] 才停。
     */
    private fun cancelEverything() {
        // 只取消**阅读器前台**在途任务（手动/自动/增量）。章节批量任务有各自的标志，不受影响 ——
        // 以前这里是唯一把"共用取消标志"置 true 的地方，而章节页读的是同一个标志，
        // 于是"启动章节任务前先停队列"这一步会把整章打成"已取消"（见 ReaderCancelSignals）。
        readerCancel.cancelAll()
        queueJob?.cancel()
        queueJob = null
        manualJob?.cancel()
        manualJob = null
        queuePage.value = -1
        // ⚠️ 这里**不**调 OcrLock.release()：在途的 runTranslate 会在 finally 里释放。
        // 若此处提前释放，新翻译可能在旧协程仍处于 native 调用中时抢到锁 → 引擎单例并发崩溃。
        resetTranslatingRowsAsync()
    }

    /**
     * 把当前仍标着「翻译中」的行退回「未翻译」。
     *
     * ⚠️ 待重置的页号必须**在这里（同步）抓下来**，不能在协程里再读 `rows.value`：
     * 那样会读到一个更晚的快照 —— 例如本章批量翻译刚开始、第一页已置 TRANSLATING，
     * 清理协程才执行，就会把**正在翻的那一页**打回未翻译（完成时又变 SUCCESS，
     * 但中途取消就会留下一条状态错乱的行）。
     */
    private fun resetTranslatingRowsAsync() {
        // ⚠️ **排除章节任务在途的那几页**：`cancelEverything()` 在本章批量翻译开始时就会跑一次，
        // 而别的章此刻可能正翻着某一页 —— 不排除就会把人家在途的页打回「未翻译」
        // （面板闪一下、取消时还会留下状态错乱的行）。收尾事件发下来时 runner 已清空在途，
        // 所以"取消自己这一章"要清的那几页照旧会被清掉。
        val busy = chapterRunner.inFlightPages()
        val stale = rows.value
            .filterValues { it.state == ImportedPageTranslation.STATE_TRANSLATING && it.pageIndex !in busy }
            .keys.toList()
        if (stale.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            // 取消 = 不存库、不算失败：把开始翻译时预写的 TRANSLATING 退回 IDLE，
            // 否则该页会永久卡在「翻译中」而再也翻不了。
            stale.forEach { page ->
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
     * 翻译一页（**手动**与**自动**模式共用；增量模式走 [runWindow] 的并发窗口）。
     *
     * 全程在 [OcrLock] 互斥下（与截屏翻译共用同一把锁）。
     *
     * [fromQueue] = true 时表示这是自动模式排队的页：**只有该页恰好是用户正在看的那页才渲染上屏** ——
     * 后台预翻的页面渲染出来没人看，纯烧 CPU 和 100MB 渲染缓存。
     */
    private suspend fun runTranslate(page: Int, fromQueue: Boolean) {
        val ocrToken = OcrLock.acquire()
        if (ocrToken == 0L) {
            LogCollector.d(TAG, "runTranslate: OcrLock 被占用，跳过 page=$page")
            return
        }
        // ⚠️ 分支的取消语义是**每页一个新 flag**（`readerCancel.newFlag()`，配对注销），
        //    没有可重置的队列取消标志字段 —— master 侧那行复位随旧字段一起作废。
        val cancel = readerCancel.newFlag()
        // ⚠️ 两个标志必须声明在 try 之外：
        //   · `lockHeld`：`onOcrDone` 会把锁提前放掉，`finally` 里不能再放第二次
        //     （放两把会让别的任务以为锁空着冲进来，与 native OCR 并发）；
        //   · `srStarted`：判断"超分是否已启动过"，收尾时才决定要不要兜底启动。
        var lockHeld = true
        var srStarted = false
        // ⚠️ 本方法**从 OCR 一直持锁到翻译结束**（自动模式要页内分批+流式），一次最长可达分钟级，
        // 而 OcrLock 的 30s 无心跳自愈会把仍活着的持有者误判成死锁 → 强制释放 → 单例引擎被并发调用。
        // 这里整段持锁期间后台打心跳（5s 一次），把这个误判的窗口堵死。
        val heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                OcrLock.heartbeat(ocrToken)
                delay(OCR_LOCK_POLL_MS * 100)
            }
        }

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
            // ⚠️ 这条提前 return **在下面那个 try/finally 之外**：不在这里停掉心跳，
            // ticker 会以 5s 一次永远活在应用级 scope 上（`OcrLock.heartbeat()` 一直有人打
            // → 30s 自愈永远不触发，漏放锁的路径再也没人兜底）。
            heartbeatJob.cancel()
            OcrLock.release(ocrToken)
            return
        }
        // ⚠️ 这两个只作为**兜底**（渲染历史行时 `visualBitmap` 拿不到本次引擎，见 [lastDet] 注释）；
        // 真正的渲染路径一律把 det/ocr **当参数**往下传，不再读实例字段。
        lastDet = det
        lastOcr = ocr

        try {
            upsertState(page, ImportedPageTranslation.STATE_TRANSLATING)
            // 清掉上一轮可能残留的半成品：否则重翻时 cachedDisplayBitmap 会先把旧半成品显示出来
            renderLru.remove(partialKey(page, baseSig(page)))

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
            // 的地方，与截屏翻译的 `RT-DETR-V2(MangaOcr) [i]: rect=..., text=...` 对齐
            LogCollector.d(
                TAG,
                "translate page=$page 开始: 引擎=$det/$ocr, ${srcLang}->${tgtLang}, " +
                    "分批=$batchingOn, 位图=${bitmap.width}x${bitmap.height}, mode=${translateMode.value}"
            )

            // ⚠️ OCR 结束回调：**只在"OCR 与翻译分得开"的两条路线会触发**（见 translatePlain）。
            //    分批路线拿不到 OCR 结束点，锁整段持有 —— `onOcrDone` 一次都不会跑，
            //    于是下面的 finally 兜底在收尾后启动超分（退化成串行，与 R6 行为一致）。
            val outcome: TranslateOutcome =
                translateWithPipelineOn(
                    enabled = batchingOn,
                    bitmap = bitmap, det = det, ocr = ocr,
                    srcLang = srcLang, tgtLang = tgtLang,
                    translator = translator,
                    phase = phase,
                    page = page,
                    cancelled = { cancel.get() },
                    onOcrDone = ocrDone@{ t ->
                        // ⚠️ **只有手动路径（`!fromQueue`）提前放锁**，两条理由：
                        //
                        // ① **队列路径本来就没有跨页重叠**（拆锁前逐页串行，拆锁后也不该引入）：
                        //    自动/增量模式会连续翻很多页，一旦放锁就变成「上一页在发请求、这一页在
                        //    OCR」—— 而两页共享的 AI 上下文历史 `readerContextHistory` 是
                        //    **LinkedList（非线程安全）**，分批路线（`forceContext=true`）会
                        //    `size`/`addLast`/`trimInPlace`/回滚并发踩踏。那是既存隐患
                        //    （章节路径 `incrementalEnabled=false` 走不到分批流程，所以现在没暴露），
                        //    **不该借着这次拆锁把它扩大到阅读器队列**。
                        // ② 手动路径同一时刻只有一个任务（`manualJob` 串行 + 队列只在非手动模式跑），
                        //    提前放锁**不引入任何跨页并发** —— 用户口径「点了翻译之后超分与翻译请求
                        //    并行」说的正是这一条路径。
                        if (fromQueue) return@ocrDone   // 队列：什么都不做，交给 finally 兜底

                        // ① 放锁：往下只剩翻译请求，不碰 OCR 引擎单例
                        if (lockHeld) {
                                // ⚠️ 必须带 token：无参 release 会把**别人的**锁放掉
                                //（30s 自愈强释后旧持有者 finally 再放一次 → 第三个调用方闯进单例引擎）
                                OcrLock.release(ocrToken)
                                lockHeld = false
                            }
                        // ② 启动超分（可与下面的翻译请求并行）
                        val job = startAutoSr(page)
                        srStarted = job != null
                        // ③ 本地引擎必须串行：LlamaCpp/NLLB 翻译本身也是 CPU 重活，
                        //    与超分同时跑只会互相抢核（用户口径「本地不行」）。
                        //    ⚠️ join 的是**本次调用自己的** Job，不是实例字段 ——
                        //    实例字段会被别的页覆盖（那样 join 的就成了别人的任务）。
                        //    ⚠️ 这一步**必须**在放锁之后：否则超分在等锁、我们在等超分 = 死锁。
                        if (job != null && t.isLocalHeavyEngine()) job.join()
                    },
                ) ?: return   // 已写失败记录
            val translated = outcome.bubbles

            if (translated.isEmpty()) {
                val msg = context.getString(R.string.reader_translate_empty)
                fail(page, "TRANSLATE_EMPTY", msg)
                phase(ReaderTranslatePhase.FAILED, msg)
                return
            }

            // 渲染：仅"正在看的那页"才预热译文图
            if (shouldRender()) {
                renderInto(page, translated, TranslationCacheManager.OverlayMode.TRANSLATED, bitmap, overlayConfig, det)
            }
            // 逐气泡明细（rect / 原文 / 译文 / 来源）：阅读器排查时唯一能看清"这页到底翻了什么"的地方
            logBubbles(page, translated, det, ocr)
            // 半成品不论是否上屏都要清：用户翻走后整页渲染不执行，那个 PARTIAL 会永久占着
            // 100MB 渲染缓存（只能靠 LRU 淘汰），且同页重翻时会先闪出旧半成品
            renderLru.remove(partialKey(page, baseSig(page)))

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
            phase(ReaderTranslatePhase.SUCCESS, cacheNotice(page, translated.size, outcome.cache))
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
            // ⚠️ 心跳必须停：这条 finally 是唯一兜底点，漏停会让 ticker 以 5s 一次**永远**活在
            // 应用级 scope 上（heartbeat 一直有人打 → 30s 自愈永不触发 → 漏放锁的路径再没人兜底）。
            heartbeatJob.cancel()
            // ⚠️ **注销与登记必须配对**：不注销的话 `readerCancel` 集合会一直涨
            //    （每页一个永不回收的 AtomicBoolean，一本 200 页的书翻一遍就多 200 个）。
            readerCancel.retire(cancel)
            // ⚠️ **条件释放**：`onOcrDone` 可能已经把锁提前放掉了（非分批路线），
            //    无条件 release 会"放两把" → 别的任务以为锁空着就冲进来，与 native OCR 并发。
            //    带 token 释放：无参 release 会把**别人的**锁放掉（30s 自愈强释后旧持有者再放一次）。
            if (lockHeld) OcrLock.release(ocrToken)
            // 「翻译时自动超分」的**兜底**落点：只在刚才那条路线没能提前启动超分时才跑
            // （分批路线拆不开锁；或者 OCR 为空/模型缺失等中途返回）。
            // 此刻锁已确定不在手上 —— 超分自己也抢同一把锁，放在锁内只会白等。
            // ⚠️ **取消过的这一页不再超分**：用户刚掐掉的任务不该在收尾后又去跑一遍本地重计算。
            if (!srStarted && !cancel.get()) maybeStartSrAfterTranslate(page)
        }
    }

    /**
     * 走分批管线（可在半途上屏）或普通一次性路径。
     *
     * @return null = 失败记录已写好，调用方直接结束
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
        /** 本次调用自己的取消信号（**按页**，见 [ReaderCancelSignals]）。 */
        cancelled: () -> Boolean,
        /**
         * **OCR 阶段结束**的回调 —— 只有"OCR 与翻译能分开"的两条路线会触发（见 [translatePlain]）。
         *
         * 分批路线（[BatchOutcome.Handled]）把识别与两批翻译交错在管线里，拿不到"OCR 结束点"，
         * 所以**不回调**：那条路线整段持 `OcrLock`，超分只能等它收尾。
         */
        onOcrDone: suspend (TranslationTextAPI) -> Unit = {},
    ): TranslateOutcome? {
        val cfg = BatchPipelineConfig(
            detEngine = det,
            ocrEngine = ocr,
            sourceLang = srcLang,
            targetLang = tgtLang,
            textDirection = cacheManager.getOverlayConfig(appPrefs).textDirection,
            keepTextFree = appPrefs.getBoolean(MangaModeConfig.KEY_KEEP_TEXT_FREE, true),
            prefs = customPrefs,
            // 分批开关：增量模式强制关，其余跟随用户设置
            incrementalEnabled = enabled && appPrefs.getBoolean("Incremental_Render", true),
            isAutoTranslating = false,
            // RT-DETR + manga-ocr 的渲染方向（两态，默认竖排右→左；该路径不判横竖）
            rtTextDirection = RtTextDirection.load(appPrefs),
        )
        val host = ReaderBatchHost(bitmap, translator, phase, page, det, ocr, cancelled)
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

        // 管线每次 run 新建，缓存统计按「两条路线相加」取回。
        // ⚠️ **取成局部量**：以前 += 到实例字段上，两页（或一页普通翻译与一章批量翻译）
        //    并发时数字会互串 —— 提示里说的"命中了 3 条"根本不是这一页的。
        val stats = CacheOutcome(pipeline.cacheStats.candidates, pipeline.cacheStats.hits)

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
            // 分批路线（Handled）：识别与两批翻译在管线里交错，**OCR 结束点拿不到**
            // → 不回调 [onOcrDone]，锁整段持有（超分只能等这次翻译收尾）。
            is BatchOutcome.Handled -> return TranslateOutcome(outcome.translated, stats)
            // 分批路径处理了但没结果（未检测到文字）：翻失败，落 OCR_EMPTY
            is BatchOutcome.HandledEmpty -> {
                val msg = context.getString(R.string.reader_translate_ocr_empty)
                fail(page, "OCR_EMPTY", msg)
                phase(ReaderTranslatePhase.FAILED, msg)
                return null
            }
            is BatchOutcome.DetectedNotBatched -> {
                // 分批**已经跑完检测**，只是气泡太少不值得分批：复用它的裁剪结果，只补跑识别，
                // 省掉普通路径的第二次整页 RT 检测（气泡少的页面恒定走这条，开销省一半）
                // translatePlain 返回 null = 失败记录已写好（OCR 为空等），调用方直接结束
                val list = translatePlain(
                    bitmap, det, ocr, srcLang, tgtLang, translator, phase, page,
                    cfg.keepTextFree, outcome.bubbles, cfg.rtTextDirection, cancelled, onOcrDone
                ) ?: return null
                return TranslateOutcome(list, stats)
            }
            is BatchOutcome.NotApplicable -> {
                // 普通路径（增量模式 / 引擎组合不支持分批 / 中途出错回退）。
                // ⚠️ keepTextFree 必须**显式**带过去：这条路径会重新检测一次，漏传就会丢掉自由文字，
                // 而同一次翻译的分批路径是保留的 —— 表现为「同一页有时有旁白、有时没有」
                val list = translatePlain(
                    bitmap, det, ocr, srcLang, tgtLang, translator, phase, page, cfg.keepTextFree, null,
                    cfg.rtTextDirection, cancelled, onOcrDone
                ) ?: return null
                return TranslateOutcome(list, stats)
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
        /** RT-DETR-V2 是否保留自由文字。由分批配置带过来，两条路径必须一致。 */
        keepTextFree: Boolean,
        /**
         * 非 null = 复用分批路径**已经跑完**的检测（只补跑识别，不再整页检测）。
         * 成功识别时 `recognizeCroppedBubbles` 会自己回收这些裁剪图；失败则本函数兜底回收。
         */
        preDetected: List<CroppedBubble>?,
        /**
         * RT-DETR + manga-ocr 的渲染方向（`RtTextDirection`）。由分批配置带过来，两条路径必须一致 ——
         * RT 路径不判横竖，方向完全由它决定。
         */
        rtTextDirection: TextDirection = TextDirection.VERTICAL_RL,
        /** 本次调用自己的取消信号（见 [ReaderCancelSignals]）。 */
        cancelled: () -> Boolean = { false },
        /**
         * **OCR 阶段结束**（检测 + 识别都做完）的回调。
         *
         * ⚠️ **调用点必须在"识别完成、翻译请求发出之前"**，这是 v2 超分能与 API 请求并行的唯一落点：
         * ```
         * 持 OcrLock：检测 + 识别  →  ← 这里放锁
         * 不持锁：  超分 ∥ 翻译请求 → 渲染
         * ```
         * 放在更后面（比如翻译完）就等于回到"超分只能等翻译"，用户要的并行没了。
         */
        onOcrDone: suspend (TranslationTextAPI) -> Unit = {},
    ): List<TranslatedBubble>? {
        val blocks: List<TextBlockInfo> = if (preDetected != null) {
            LogCollector.d(TAG, "translatePlain: 复用分批检测的 ${preDetected.size} 个气泡，只跑识别")
            try {
                DetectionBridge.recognizeCroppedBubbles(preDetected, srcLang, rtTextDirection)
            } catch (e: Exception) {
                // 成功路径由 recognizeCroppedBubbles 内部回收；抛异常时可能一张都没回收 → 这里兜底
                preDetected.forEach { if (!it.croppedBitmap.isRecycled) it.croppedBitmap.recycle() }
                throw e
            }
        } else {
            DetectionBridge.runOCR(bitmap, srcLang, det.value, ocr.value, context, keepTextFree, rtTextDirection)
        }
        val overlayConfig = cacheManager.getOverlayConfig(appPrefs)
        // RT-DETR 用 RT 自己的渲染方向（不看竖排方向设置）；PP/ML Kit 用竖排方向设置
        val bubbleRegions = DetectionBridge.ocrToBubbleRegions(
            blocks, RtTextDirection.resolve(det, rtTextDirection, overlayConfig.textDirection)
        )
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

        // ⚠️ **放锁点**：检测 + 识别都做完了，往下只剩翻译请求（不碰 OCR 引擎单例）。
        //    回调里会 `OcrLock.release()` 并启动超分 —— 于是"超分 ∥ 翻译请求"才成立；
        //    本地引擎（LlamaCpp/NLLB）由回调自己 `join`，退化成串行（用户口径：本地不行）。
        onOcrDone(translator)

        phase(ReaderTranslatePhase.TRANSLATING, null)
        val streamingOn = translateMode.value != MODE_AHEAD &&
            appPrefs.getBoolean("Incremental_Render", true)
        return TranslateUtils.translateBubbles(
            translator, bubbleRegions, srcLang, tgtLang, customPrefs,
            isCancelled = cancelled,
            // 流式局部结果：仅非增量模式 + 开关打开时上屏
            onPartialBubbles = { partial -> if (streamingOn) showPartial(page, partial, bitmap, det) },
        )
    }

    /**
     * 分批管线的宿主钩子：进度落状态浮层、首批结果落页面（仅当前页）。
     *
     * ⚠️ `det`/`ocr`/`cancelled` **都由构造点显式传进来**（不再读控制器的实例字段）：
     * 实例字段在并发下会被别的页覆盖 —— 表现是「同一页有时竖排有时横排」、
     * 「取消 A 页连带取消 B 页」这类**不崩不报错的静默错误**。
     */
    private inner class ReaderBatchHost(
        private val pageBitmap: Bitmap,
        override val translator: TranslationTextAPI,
        private val phase: (ReaderTranslatePhase, String?) -> Unit,
        private val page: Int,
        /** 产出这一页的识别引擎（渲染方向要用它，见 [renderBubbles] 的 `det` 参数）。 */
        private val det: DetEngine,
        private val ocr: OcrEngine,
        private val cancelled: () -> Boolean,
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

        override fun onPartialRender(bubbles: List<TranslatedBubble>) {
            // 阅读器已关（后台任务）或这不是用户正在看的那一页 → 不渲染半成品：
            // 渲染一张全页图要几十 MB 渲染缓存，后台任务没人看，纯烧内存
            if (!uiAttached || page != currentPageProvider()) return
            showPartial(page, bubbles, pageBitmap, det)
        }

        override suspend fun onBatchResult(bubbles: List<TranslatedBubble>) {
            // ⚠️ 必须写 PARTIAL key，不能走 renderInto（后者写 renderKey 且要求 state==SUCCESS）。
            // 此刻本页状态还是 TRANSLATING，而 cachedDisplayBitmap 对 TRANSLATING 只查 PARTIAL ——
            // 写错 key 会让首批结果**根本显示不出来**，分批形同虚设（这就是"分批没生效"的根因）。
            if (bubbles.isEmpty()) return
            if (page != currentPageProvider()) return   // 后台队列页不渲染
            val cfg = cacheManager.getOverlayConfig(appPrefs)
            val out = renderPage(page, pageBitmap, bubbles, TranslationCacheManager.OverlayMode.TRANSLATED, cfg, det)
            renderLru.put(partialKey(page, baseSig(page)), out)
            // 首批已出 → 后续进度就是第二批（管线回调不带批次，只能这样推）
            batchIndex = 2
            withContext(Dispatchers.Main) { onVisual() }
        }

        override fun isCancelled(): Boolean = cancelled()

        override fun contextHistory(): LinkedList<Pair<String, String>> = readerContextHistory

        override fun textCache(): RegionCacheManager = readerTextCache
    }

    /** 阅读器侧上下文历史（分批的批次间上下文用；阅读器按页翻译，正常不跨页复用）。 */
    private val readerContextHistory = LinkedList<Pair<String, String>>()
    private val readerTextCache = RegionCacheManager()

    /**
     * 一次翻译（管线路线 + 普通路线）的产物：气泡 + 缓存统计。
     * 调用方拿到 null 表示"失败记录已写好"，直接结束。
     */
    private class TranslateOutcome(val bubbles: List<TranslatedBubble>, val cache: CacheOutcome)

    /**
     * 最近一次真正跑过的识别引擎 —— **只作兜底**。
     *
     * ⚠️ `visualBitmap`（渲染历史行：进阅读器看上一轮的译文、三态切回原文）手上没有"这一页
     * 是哪个引擎跑的"，只能拿最近一次的值。**渲染路径本身一律把 det/ocr 当参数传**
     * （见 `renderPage`/`ReaderBatchHost`），否则并发下同一页会"有时竖排有时横排"。
     */
    @Volatile private var lastDet: DetEngine = DetEngine.PP_OCR_V6
    @Volatile private var lastOcr: OcrEngine = OcrEngine.PPOcrV6

    // ========== 渲染 ==========

    /** 上一版整页译图（取消重翻 / 重翻在途时的显示回退）。无则 null。 */
    private fun lastFullRender(pageIndex: Int): Bitmap? {
        val mode = currentVisual(pageIndex)
        // 纯原图态**没有 overlay**，不进 renderLru —— 返回 null 让调用方回落到超分/增强底图
        // （`cachedDisplayBitmap` 里"没有译图就看底图"那一步）
        if (mode == TranslationCacheManager.OverlayMode.PLAIN) return null
        return renderLru.get(renderKey(pageIndex, mode, baseSig(pageIndex)))
    }

    /** 该页行里是否还留着可渲染的译文载荷（取消/失败后 upsertState 会保留）。 */
    private fun rowHasPayload(pageIndex: Int): Boolean {
        val row = rows.value[pageIndex] ?: return false
        return !row.bubbleRects.isNullOrBlank() || !row.translatedText.isNullOrBlank()
    }

    /**
     * 供适配器同步取图（IO 线程安全）：该页当前应显示的图，无则 null（适配器回退源图）。
     *
     * 优先级：**译图 > 超分/增强底图 > 源图**。
     * ⚠️ 本函数在 `onBindViewHolder` 路径上，**只读内存缓存**：底图的磁盘读取全部发生在
     * [srBaseFor] / [warmSrBase]（IO 侧），这里只 `LruCache.get`。
     */
    fun cachedDisplayBitmap(pageIndex: Int): Bitmap? {
        val rendered = when (stateOf(pageIndex)) {
            ImportedPageTranslation.STATE_SUCCESS -> lastFullRender(pageIndex)

            // 翻译中：返回「首批半成品」如果有 —— 否则用户翻走再翻回时看不到已经翻好的那半页。
            // ⚠️ 必须用独立 key（PARTIAL）：不能用 renderKey(TRANSLATED)，
            // 否则会和最终整页结果混在一起，且失败/取消后残留一张永远刷不掉的半成品。
            // 半成品还没出来时回退到上一版整页译图：重翻期间页面不该突然退回原图（翻页也会闪一下）
            ImportedPageTranslation.STATE_TRANSLATING ->
                renderLru.get(partialKey(pageIndex, baseSig(pageIndex))) ?: lastFullRender(pageIndex)

            // 未翻译但行里还带着上一次成功的载荷（典型：重翻被取消 / 切后台中断）：
            // 继续显示旧译文。⚠️ 状态必须是 IDLE，队列才会重新挑中这一页去翻 —— 退回 SUCCESS 会让
            // 「点了重翻」的页永远排不进队列（用户看到的是"重翻了却没变"）
            ImportedPageTranslation.STATE_IDLE ->
                if (rowHasPayload(pageIndex)) lastFullRender(pageIndex) else null

            else -> null
        }
        if (rendered != null) return rendered
        // 没有译图（未翻译 / 纯原图态）→ 有超分/增强底图就用它，否则 null（适配器显示源图）
        return srBaseLru.get(pageIndex)?.bitmap
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
    private fun showPartial(page: Int, bubbles: List<TranslatedBubble>, bitmap: Bitmap, det: DetEngine) {
        if (bubbles.isEmpty()) return
        if (page != currentPageProvider()) return
        if (partialJob?.isActive == true) return
        partialJob = scope.launch(Dispatchers.IO) {
            val gen = renderGeneration
            val cfg = cacheManager.getOverlayConfig(appPrefs)
            val out = renderPage(page, bitmap, bubbles, TranslationCacheManager.OverlayMode.TRANSLATED, cfg, det)
            // 代次变了 = 期间替换表被改过，这张半成品是旧规则的 → 丢弃（最终整页结果会覆盖）
            if (gen != renderGeneration) {
                LogCollector.d(TAG, "showPartial: 替换表已变，丢弃旧规则的半成品 page=$page")
                return@launch
            }
            renderLru.put(partialKey(page, baseSig(page)), out)
            withContext(Dispatchers.Main) { onVisual() }
        }
    }

    /**
     * 取某页某态的图。纯原图态（PLAIN）返回**底图本身**（超分开着就是超分底图，否则原图）；
     * 译文/原文态返回缓存命中或实时渲染。
     *
     * ⚠️ PLAIN 分支返回值可能是 `srBaseLru` 里那份底图（**不归调用方**）——调用方只读不改；
     * 这与原来"返回 `loadFull` 结果"的约定一致（那份也由数据源自持/现解码，非调用方所有）。
     */
    suspend fun visualBitmap(
        pageIndex: Int,
        mode: TranslationCacheManager.OverlayMode,
    ): Bitmap? {
        if (mode == TranslationCacheManager.OverlayMode.PLAIN) {
            val orig = loadFull(pageIndex) ?: return null
            val base = srBaseFor(pageIndex, orig, orig.width)
            if (base != null) {
                // 底图不是 orig（超分/Anime4K 产物）→ 本次现解码的 orig 要回收
                if (base.bitmap !== orig && !orig.isRecycled) orig.recycle()
                return base.bitmap
            }
            return orig
        }
        val key = renderKey(pageIndex, mode, baseSig(pageIndex))
        renderLru.get(key)?.let { return it }
        val row = rows.value[pageIndex] ?: return null
        val config = cacheManager.getOverlayConfig(appPrefs)
        val bubbles = PageTranslationCodec.fromRow(row, config.fontSize, config.bgColor) ?: return null
        val orig = loadFull(pageIndex) ?: return null
        val out = try {
            renderPage(pageIndex, orig, bubbles, mode, config)
        } finally {
            if (!orig.isRecycled) orig.recycle()
        }
        renderLru.put(key, out)
        return out
    }

    /**
     * 导出用：按**原图全分辨率**渲染某页译文（`useOriginalText=false`）。仅成功页可渲染。
     *
     * ⚠️ 不能复用 [visualBitmap]：它会把结果放进 100MB 的 `renderLru`，而导出动辄上百页 →
     * 一路把 LRU 冲干净，把用户正在看的那页译图也挤掉，退回阅读器还得重渲。
     *
     * ⚠️ **刻意不用超分底图**（`base = null`）：导出包是给别人/别的设备看的，
     * 不该因为"这台机器开过超分"就换一套分辨率；而且 2x 底图会让导出体积翻几倍。
     *
     * ⚠️ **必须在 IO 线程调用**（全尺寸解码 + 全页渲染）；调用方负责 `recycle()` 返回值。
     *
     * ⚠️ 渲染倍率固定 **1f**（导出不上屏、不吃屏幕分辨率）：按 2f 栅格化会把导出图放大 4 倍像素
     * （内存/时间白花，见 manga/CLAUDE.md 的「低分辨率漫画译文模糊」一节里"导出/查看器一律传 1f"）。
     */
    fun renderForExport(pageIndex: Int): Bitmap? {
        val row = rows.value[pageIndex] ?: return null
        if (row.state != ImportedPageTranslation.STATE_SUCCESS) return null
        val config = cacheManager.getOverlayConfig(appPrefs)
        val bubbles = PageTranslationCodec.fromRow(row, config.fontSize, config.bgColor) ?: return null
        val orig = loadFull(pageIndex) ?: return null
        return try {
            renderBubbles(
                original = orig, bubbles = bubbles,
                mode = TranslationCacheManager.OverlayMode.TRANSLATED, cfg = config, base = null,
                det = lastDet,
            )
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
    private fun logBubbles(page: Int, bubbles: List<TranslatedBubble>, det: DetEngine, ocr: OcrEngine) {
        if (bubbles.isEmpty()) return
        LogCollector.d(TAG, "page=$page 气泡明细（共 ${bubbles.size} 个，引擎=$det/$ocr）：")
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
    internal fun cacheNotice(page: Int, total: Int, cache: CacheOutcome): String? {
        if (cache.hits <= 0) return null
        val fromApi = (cache.candidates - cache.hits).coerceIn(0, total)
        LogCollector.d(
            TAG,
            "page=$page 缓存命中 ${cache.hits}/${cache.candidates}（译文 $fromApi 条来自 API，共 $total 条）"
        )
        return when {
            fromApi <= 0 -> context.getString(R.string.reader_translate_all_cached, total)
            else -> context.getString(R.string.reader_translate_partial_cached, cache.hits, cache.candidates, fromApi)
        }
    }

    /**
     * 渲染一行气泡到页图（阅读器渲染的统一出口）。**纯函数式、非 suspend** ——
     * 底图由调用方先经 [srBaseFor] 解析好传进来（那是唯一要读磁盘的一步）。
     *
     * @param base 显示底图；null = 用 [original] 本身（原图，baseScale = 1）
     * @param det 产出这页气泡的**识别引擎**（决定竖排方向是取 RT 设置还是竖排方向设置）。
     *   ⚠️ **必须是参数**，不能在函数里读控制器字段：并发下会被别的页改写，
     *   表现就是那类最难查的「同一页有时竖排有时横排」。
     *
     * ⚠️ **气泡坐标永远是"原图空间"**（v2 不做 OCR 前超分），超分底图只是**更密的像素**：
     * `base.scale = 底图宽 / 坐标空间宽` 交给 `OverlayRenderer` 反算坐标空间，这里不手动乘任何坐标。
     */
    private fun renderBubbles(
        original: Bitmap,
        bubbles: List<TranslatedBubble>,
        mode: TranslationCacheManager.OverlayMode,
        cfg: TranslationCacheManager.OverlayConfig,
        base: SrBase?,
        det: DetEngine,
    ): Bitmap = OverlayRenderer.renderOverlay(
        original = base?.bitmap ?: original,
        regions = bubbles,
        fontSize = cfg.fontSize,
        autoFit = cfg.autoFit,
        textColor = cfg.textColor,
        bgColor = cfg.bgColor,
        useOriginalText = mode == TranslationCacheManager.OverlayMode.ORIGINAL,
        // 渲染时的竖排方向覆盖：RT-DETR + manga-ocr 用「RT-DETR 渲染方向」（不看竖排方向设置，
        // 日文竖排恒右→左）；PP/ML Kit 用竖排方向设置
        verticalDirection = RtTextDirection.resolve(
            det, RtTextDirection.load(appPrefs), cfg.textDirection
        ),
        // 阅读器译图渲染也要用自定义结果字体（Custom_Result_Font），否则恒为系统字体
        fontTypeface = OverlayRenderer.loadResultTypeface(context, customPrefs),
        align = cfg.horizontalAlign,
        trackingRatio = cfg.trackingRatio,
        leadingRatio = cfg.leadingRatio,
        mergeOverlap = cfg.mergeOverlap,
        // 用户译文替换表（渲染时套用 → 改完规则返回阅读器即生效，见 refreshIfRulesChanged）
        replacementRules = cfg.replacementRules,
        density = context.resources.displayMetrics.density,
        // 超采样渲染（2026-10）：阅读器上屏的译图按 2 倍栅格化，让**文字**在低分辨率页上也清晰。
        // 底图不变（该多糊还多糊，那是图源决定的）；只有文字从"插值放大的像素"变成"按最终分辨率栅格化"。
        // ⚠️ 只在这一处传 >1：导出/查看器/历史都传默认 1f（它们不吃屏幕分辨率，且结果进 BitmapLruCache）。
        renderScale = READER_RENDER_SCALE,
        // 超分底图的倍率（v2）：底图 2x、renderScale 也是 2 → 恰好 1:1 落上去，零重采样。
        // ⚠️ 输出尺寸恒为 `原图宽 × renderScale`，**与底图倍率无关** → renderLru 的内存占用不变。
        baseScale = base?.scale ?: 1f,
    )

    /** 解析该页底图后再渲染（分页路径的统一入口：底图 + 渲染一起做完）。 */
    private suspend fun renderPage(
        pageIndex: Int,
        original: Bitmap,
        bubbles: List<TranslatedBubble>,
        mode: TranslationCacheManager.OverlayMode,
        cfg: TranslationCacheManager.OverlayConfig,
        /** 该页的识别引擎；缺省取"最近一次跑过的"（渲染历史行时手上没有，见 [lastDet]）。 */
        det: DetEngine = lastDet,
    ): Bitmap = renderBubbles(
        original = original, bubbles = bubbles, mode = mode, cfg = cfg,
        base = srBaseFor(pageIndex, original, original.width),
        det = det,
    )

    /**
     * 渲染并预热译文图缓存，同时切到该态。最终结果写入后**丢弃半成品**，避免残留旧图。
     *
     * @param det 产出这页气泡的识别引擎（必须由调用方传，见 [renderBubbles] 的 `det` 说明）
     */
    private suspend fun renderInto(
        pageIndex: Int,
        bubbles: List<TranslatedBubble>,
        mode: TranslationCacheManager.OverlayMode,
        original: Bitmap,
        cfg: TranslationCacheManager.OverlayConfig,
        det: DetEngine = lastDet,
    ) {
        val sig = baseSig(pageIndex)
        renderLru.put(renderKey(pageIndex, mode, sig), renderPage(pageIndex, original, bubbles, mode, cfg, det))
        renderLru.remove(partialKey(pageIndex, sig))
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

    /** Webtoon 译图缓存（key = [webtoonKey]，**含底图签名**）。只放当前位置附近的几页。 */
    private val webtoonLru = object : LruCache<String, Bitmap>(WEBTOON_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap) =
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
        return webtoonLru.get(webtoonKey(pageIndex, baseSig(pageIndex)))
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
            stateOf(p) == ImportedPageTranslation.STATE_SUCCESS &&
                webtoonLru.get(webtoonKey(p, baseSig(p))) == null
        }
        if (pending.isEmpty()) return

        webtoonPrewarmJob = scope.launch(Dispatchers.IO) {
            val gen = renderGeneration
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
                // 所以这里恒按译文渲染。
                //
                // ⚠️ **不用超分底图**：Webtoon 页是**按屏宽采样解码**的（超长条防 OOM），
                //    而超分产物是"原图精确 2x"，拿它当底图要重算一套采样倍率，
                //    且 Webtoon 本来就禁用单页翻译、右下角也没有超分按钮（用户口径）。
                val out = renderBubbles(
                    original = src, bubbles = scaled,
                    mode = TranslationCacheManager.OverlayMode.TRANSLATED, cfg = cfg, base = null,
                    det = lastDet,
                )
                // 代次变了 = 渲染期间替换表被改过 → 这张是旧规则的，写进去会被预热的
                // 「webtoonLru.get(p) == null」过滤永久跳过（规则再也生效不了）
                if (gen != renderGeneration) {
                    LogCollector.d(TAG, "prewarmWebtoon: 替换表已变，丢弃旧规则的译图 page=$p")
                    break
                }
                webtoonLru.put(webtoonKey(p, baseSig(p)), out)
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

    /** 页面显示需要刷新（三态切换 / 翻译完成）。主线程写、应用级任务读 → @Volatile。 */
    @Volatile var onVisual: () -> Unit = {}

    /** 翻译阶段变化（检测中/翻译中/完成/失败/队列耗尽）。同上。 */
    @Volatile var onPhase: (ReaderTranslatePhase, String?) -> Unit = { _, _ -> }

    /**
     * **超分提示**（失败原因 / 进度 / 完成）：宿主负责呈现。
     *
     * ⚠️ 用户口径：「超分的提示信息应该也用 app 系统提示，不要用手机底部 Toast」——
     * 阅读器宿主把它接到 `TranslationStatusOverlay`（app 内的状态浮层），
     * 拿不到悬浮窗权限 / 浮层被关时才退回 Toast。
     * ⚠️ 控制器**不直接碰 UI**（与 `onPhase`/`onVisual` 同一约定），否则后台章节任务
     * 会往已销毁的 Activity 上贴东西。
     *
     * @param isError true = 失败原因（浮层用红色、可点复制，方便用户把原文发出来）
     */
    var onSrNotice: (text: String, isError: Boolean) -> Unit = { _, _ -> }

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
        // ⚠️ **每次失败都记日志**：以前 fail() 只写库不记日志 —— 页被标失败后队列会**跳过**它
        // （队列只翻 IDLE），表现就是"自动/增量都没反应、日志里什么都没有"（cbz 那个问题）。
        LogCollector.w(TAG, "页失败 page=$pageIndex code=$code msg=$message")
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
