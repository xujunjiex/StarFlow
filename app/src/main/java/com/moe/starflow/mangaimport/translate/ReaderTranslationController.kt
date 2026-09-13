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
import com.moe.starflow.manga.render.OverlayRenderer
import com.moe.starflow.manga.types.BubbleRegion
import com.moe.starflow.manga.types.TextBlockInfo
import com.moe.starflow.manga.types.TranslatedBubble
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.translate.TranslationTextAPI
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import translationapi.TranslatorFactory
import java.util.concurrent.ConcurrentHashMap

/** 翻译阶段（供状态浮层显示进度：检测/翻译/成功/失败）。 */
enum class ReaderTranslatePhase { DETECTING, TRANSLATING, SUCCESS, FAILED }

/**
 * 阅读器翻译编排器：每页记录（Room）+ 渲染缓存（LRU）+ 三态（译文/原文/原图）。
 * 阶段一只实现手动翻译；translateMode 固定 0（自动/增量置灰，后续阶段接入）。
 */
class ReaderTranslationController(
    private val context: Context,
    private val manga: ImportedManga,
    private val scope: CoroutineScope,
) {

    val version = MutableStateFlow(0L)
    val translateMode = MutableStateFlow(0) // 0 手动 / 1 自动 / 2 增量（阶段一恒为 0）

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

    /** 各页当前三态（内存态；翻页默认：成功页=译文，其余=原图）。并发安全（IO 页图提供者 + 主线程切换都会读写）。 */
    private val currentVisualByPage = ConcurrentHashMap<Int, TranslationCacheManager.OverlayMode>()

    companion object {
        private const val TAG = "ReaderTranslate"
        private const val RENDER_CACHE_KB = 100 * 1024 // 100MB 预算（原图同量级，够 3 态来回切）
        private fun renderKey(pageIndex: Int, mode: TranslationCacheManager.OverlayMode) =
            "page:$pageIndex:${mode.name}"
    }

    // ========== 记录读取 ==========

    /** 打开阅读器时载入全部记录。 */
    suspend fun load() {
        rows.value = dao.forManga(manga.id, mangaKey).associateBy { it.pageIndex }
        version.value += 1
    }

    fun stateOf(pageIndex: Int): Int =
        rows.value[pageIndex]?.state ?: ImportedPageTranslation.STATE_IDLE

    fun failMessageOf(pageIndex: Int): String? = rows.value[pageIndex]?.failMessage

    fun recordOf(pageIndex: Int): ImportedPageTranslation? = rows.value[pageIndex]

    /** 全部记录（pageIndex 升序），供面板。 */
    fun records(): List<ImportedPageTranslation> = rows.value.values.sortedBy { it.pageIndex }

    // ========== 手动翻译 ==========

    /**
     * 翻译/重翻某一页。全程在 [OcrLock] 互斥下（与截屏翻译、历史重翻共用同一把锁）。
     * 成功：写 SUCCESS 记录 + 预热译文图缓存；失败：写 FAILED + failCode/failMessage。
     * [onPhase] 上报阶段（检测/翻译/成功/失败）供状态浮层显示，与截屏翻译路线的状态提示一致。
     */
    suspend fun translatePage(
        pageIndex: Int,
        loadFull: suspend (Int) -> Bitmap?,
        onToast: (String) -> Unit = {},
        onVisual: () -> Unit = {},
        onPhase: (ReaderTranslatePhase, String?) -> Unit = { _, _ -> },
    ) {
        if (!OcrLock.tryAcquire()) {
            onToast(context.getString(R.string.reader_translate_busy))
            return
        }
        try {
            upsert(pageIndex, ImportedPageTranslation.STATE_TRANSLATING)
            onPhase(ReaderTranslatePhase.DETECTING, null)

            val (det, ocr) = try {
                TranslationEngineInit.ensureReady(context)
            } catch (e: Exception) {
                LogCollector.e(TAG, "engine init failed page=$pageIndex", e)
                val msg = context.getString(R.string.reader_translate_model_missing, e.message.orEmpty())
                fail(pageIndex, "OCR_MODEL_MISSING", msg)
                onPhase(ReaderTranslatePhase.FAILED, msg)
                return
            }

            val translator: TranslationTextAPI? =
                TranslatorFactory.create(context, customPrefs, TranslatorFactory.Mode.MANGA)
            if (translator == null) {
                val msg = context.getString(R.string.reader_translate_api_not_configured)
                fail(pageIndex, "TRANSLATION_API_NOT_CONFIGURED", msg)
                onPhase(ReaderTranslatePhase.FAILED, msg)
                return
            }

            val bitmap = loadFull(pageIndex)
            if (bitmap == null) {
                val msg = context.getString(R.string.reader_translate_load_failed)
                fail(pageIndex, "PROCESS_EXCEPTION", msg)
                onPhase(ReaderTranslatePhase.FAILED, msg)
                return
            }

            val srcLang = customPrefs.getString("Source_Language", "ja")
            val tgtLang = customPrefs.getString("Target_Language", "zh")
            val overlayConfig = cacheManager.getOverlayConfig(appPrefs)

            // 检测 + OCR + 气泡合并
            val blocks: List<TextBlockInfo> =
                DetectionBridge.runOCR(bitmap, srcLang, det.value, ocr.value, context)
            val bubbleRegions: List<BubbleRegion> =
                DetectionBridge.ocrToBubbleRegions(blocks, overlayConfig.textDirection)
            if (bubbleRegions.isEmpty()) {
                val msg = context.getString(R.string.reader_translate_ocr_empty)
                fail(pageIndex, "OCR_EMPTY", msg)
                onPhase(ReaderTranslatePhase.FAILED, msg)
                return
            }

            // 翻译
            onPhase(ReaderTranslatePhase.TRANSLATING, null)
            val translated: List<TranslatedBubble> = TranslateUtils.translateBubbles(
                translator, bubbleRegions, srcLang, tgtLang, customPrefs,
                isCancelled = { false },
            )
            if (translated.isEmpty()) {
                val msg = context.getString(R.string.reader_translate_empty)
                fail(pageIndex, "TRANSLATE_EMPTY", msg)
                onPhase(ReaderTranslatePhase.FAILED, msg)
                return
            }

            // 预热译文图缓存 + 默认切到译文态
            renderInto(pageIndex, translated, TranslationCacheManager.OverlayMode.TRANSLATED, bitmap, overlayConfig)

            // 写库 SUCCESS
            val row = ImportedPageTranslation(
                mangaId = manga.id, pageIndex = pageIndex,
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
            rows.value = rows.value + (pageIndex to row)
            dao.upsert(row)
            version.value += 1
            onVisual()
            onPhase(ReaderTranslatePhase.SUCCESS, null)
            LogCollector.d(TAG, "translated page=$pageIndex bubbles=${translated.size}")
        } catch (e: TranslationCancelledException) {
            // 用户主动停止：保持 TRANSLATING，不判失败
            LogCollector.d(TAG, "translate cancelled page=$pageIndex")
        } catch (e: Exception) {
            LogCollector.e(TAG, "translate page=$pageIndex failed", e)
            val msg = e.message ?: "Unknown error"
            fail(pageIndex, "PROCESS_EXCEPTION", msg)
            onPhase(ReaderTranslatePhase.FAILED, msg)
        } finally {
            OcrLock.release()
        }
    }

    /** 供适配器同步取图（IO 线程安全）：该页当前三态对应的渲染图缓存，无则 null（显示原图）。 */
    fun cachedDisplayBitmap(pageIndex: Int): Bitmap? {
        if (stateOf(pageIndex) != ImportedPageTranslation.STATE_SUCCESS) return null
        val mode = currentVisual(pageIndex)
        if (mode == TranslationCacheManager.OverlayMode.PLAIN) return null
        return renderLru.get(renderKey(pageIndex, mode))
    }

    // ========== 三态切换 / 渲染 ==========

    /** 循环切换：PLAIN → ORIGINAL → TRANSLATED → PLAIN。无成功记录页忽略。 */
    fun cycleVisual(pageIndex: Int, onVisual: () -> Unit) {
        if (stateOf(pageIndex) != ImportedPageTranslation.STATE_SUCCESS) return
        val order = listOf(
            TranslationCacheManager.OverlayMode.PLAIN,
            TranslationCacheManager.OverlayMode.ORIGINAL,
            TranslationCacheManager.OverlayMode.TRANSLATED,
        )
        val cur = order.indexOf(currentVisual(pageIndex)).let { if (it < 0) 0 else it }
        val next = order[(cur + 1) % order.size]
        currentVisualByPage[pageIndex] = next
        version.value += 1
        onVisual()
    }

    /** 当前页显示态（成功页默认译文，其余默认原图）。 */
    fun currentVisual(pageIndex: Int): TranslationCacheManager.OverlayMode =
        currentVisualByPage[pageIndex]
            ?: (if (stateOf(pageIndex) == ImportedPageTranslation.STATE_SUCCESS)
                TranslationCacheManager.OverlayMode.TRANSLATED
            else TranslationCacheManager.OverlayMode.PLAIN)

    /** 取某页某态的图。PLAIN=原图（loadFull）；译文/原文=缓存命中或实时渲染。 */
    suspend fun visualBitmap(
        pageIndex: Int,
        mode: TranslationCacheManager.OverlayMode,
        loadFull: suspend (Int) -> Bitmap?,
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
    )

    /** 翻译成功后预热译文图缓存并切到译文态。 */
    private fun renderInto(
        pageIndex: Int,
        bubbles: List<TranslatedBubble>,
        mode: TranslationCacheManager.OverlayMode,
        original: Bitmap,
        cfg: TranslationCacheManager.OverlayConfig,
    ) {
        renderLru.put(renderKey(pageIndex, mode), renderBubbles(original, bubbles, mode, cfg))
        currentVisualByPage[pageIndex] = mode
    }

    // ========== 私有：写记录 ==========

    private suspend fun upsert(pageIndex: Int, state: Int) {
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