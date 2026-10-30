package com.moe.starflow.novel.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.preference.PreferenceManager
import com.moe.starflow.R
import com.moe.starflow.data.TranslationCacheManager
import com.moe.starflow.manga.OcrLock
import com.moe.starflow.manga.engine.OcrExecution
import kotlinx.coroutines.CancellationException
import com.moe.starflow.manga.TranslateUtils
import com.moe.starflow.manga.TranslationCancelledException
import com.moe.starflow.manga.config.MangaModeConfig
import com.moe.starflow.manga.config.RtTextDirection
import com.moe.starflow.manga.engine.OcrMemoryException
import com.moe.starflow.manga.engine.DetectionBridge
import com.moe.starflow.manga.types.BubbleRegion
import com.moe.starflow.manga.types.TextDirection
import com.moe.starflow.manga.types.TranslatedBubble
import com.moe.starflow.mangaimport.translate.TranslationEngineInit
import com.moe.starflow.translate.ImageTextBlock
import com.moe.starflow.translate.ImageTranslationLog
import com.moe.starflow.translate.ImageTranslatorFactory
import com.moe.starflow.translate.TranslationPicAPI
import com.moe.starflow.translate.TranslationResult
import com.moe.starflow.translate.TranslationTextAPI
import com.moe.starflow.translate.isLocalHeavyEngine
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.OcrEngineManager
import com.moe.starflow.utils.TranslationBusyRegistry
import com.moe.starflow.utils.TranslationExecutionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** 一次图片翻译的结果：[payload] 与 [error] 必有其一（都为空 = 调用方取消了）。 */
class NovelImageOutcome(val payload: NovelImageTranslation?, val error: String?)

/**
 * 源语言是不是**这个 OCR 引擎组不支持**（纯函数，顶层 → 单测直接调）。
 *
 * 比的是**主语言子标签**（`zh-TW` 与 `zh` 视为同一门语言）：引擎组给的是具体代码
 * （`OcrEngineGroup.sourceLangs`，如 `zh-TW`），而用户设的可能是 `zh` 或反过来 ——
 * 精确比较会把"其实能用"的情况判成不支持，反而把用户拦在正确的路上。
 *
 * 组列表为空（取不到设置）时一律**放行**：宁可让 OCR 自己给结果，也不要因为读不到设置就拒绝。
 */
internal fun languageUnsupportedByOcr(sourceLang: String, supported: Set<String>): Boolean {
    if (sourceLang.isBlank() || supported.isEmpty()) return false
    val want = sourceLang.substringBefore('-').lowercase()
    return supported.none { it.substringBefore('-').lowercase() == want }
}

/**
 * **一张插图的「OCR → 翻译」流水线**（阅读器与整章任务共用这一份）。
 *
 * ### 与漫画那边的关系（用户口径「相关逻辑和漫画翻译那边一样」）
 * 逐段对齐漫画阅读器的 OCR 链路（`ReaderTranslationController.ocrPhaseLocked` +
 * `translatePlain`），只是把「漫画页」换成「小说插图」：
 *
 * ```
 * OcrLock.beginOcrDemand() → OcrLock.acquire(currentCoroutineContext()[Job], "novel image OCR")（带等锁 + 5s 心跳）
 *   → TranslationEngineInit.ensureReady(context)   ← 引擎组来自全局设置（默认 PP-OCRv6，免下载）
 *   → DetectionBridge.runOCR(bitmap, srcLang, det, ocr, context, keepTextFree, rtDir)
 *   → DetectionBridge.ocrToBubbleRegions(blocks, 方向)     → 文字块（矩形 + 原文）
 * → **放 OCR 锁**（翻译阶段绝不持它：那把锁的语义是"OCR/超分引擎串行"）
 * → TranslateUtils.translateBubbles(api, bubbles, src, tgt, prefs, isCancelled)
 * ```
 *
 * 三态、渲染、缓存都不在这里（见 `NovelImageOverlay`）：这里只产出**载荷**。
 *
 * ### 两条硬纪律
 * 1. **必须持 `OcrLock`**：PP-OCR / RT-DETR / manga-ocr 都是进程级单例 ONNX，
 *    与截屏翻译 / 漫画阅读器 / 超分共用同一个 session —— 不持锁并发调用就是崩或出垃圾结果。
 *    锁忙时**等**（最多 [LOCK_WAIT_TIMEOUT_MS]），拿到再干；等不到报"引擎忙"。
 * 2. **本地文本引擎（NLLB / LlamaCpp）要串行**：由 `TranslateUtils` 统一取得可取消的批次租约，
 *    工作线程在原生调用结束前保持模型互斥（远端 API 不占）。
 *
 * ⚠️ 位图**不缓存**（与显示用的 `NovelImageStore` 分开）：OCR 要大一点的分辨率，
 * 而它用完就 `recycle()` —— 一章十几张插图全留着就是几十 MB。
 */
class NovelImagePipeline(
    context: Context,
    /** 文本翻译引擎（**延迟取**：本地模型每造一次都要重载，不能在这儿常驻）。 */
    private val api: () -> TranslationTextAPI?,
) {

    private companion object {
        const val TAG = "NovelImage"

        /** OCR 用的最大边长（原图更大就采样）：识别质量与内存的折中。 */
        const val OCR_MAX_SIDE = 1600

        /** 等 OCR 锁的轮询与超时（与漫画阅读器同量级：它等 60s）。 */
        const val LOCK_POLL_MS = 200L
        /**
     * 等锁的最长时间。
     *
     * ⚠️ 从 60s 收到 **20s**（2026-10-04）：正常情况这把锁几秒内就该到手（单页 OCR 是秒级），
     * 等满 20s 基本只剩"上一张图还在 OCR"这一种可能 —— 再等下去用户只会看到一条很长的
     * "正在识别插图"然后失败。时间短一点，失败得快、重试得快。
     */
    const val LOCK_WAIT_TIMEOUT_MS = 20_000L

        /** 持锁期间的心跳间隔，必须远小于 `OcrLock.STALE_TIMEOUT_MS`(30s)。 */
        const val LOCK_HEARTBEAT_MS = 5_000L
    }

    private val app: Context = context.applicationContext
    private val appPrefs = PreferenceManager.getDefaultSharedPreferences(app)
    private val customPrefs = CustomPreference.getInstance(app)

    private var picTranslator: TranslationPicAPI? = null
    private var picTranslatorKey: String = ""

    /** 释放图片接口（宿主换配置/退出时可选调用；不调用也只会随管线实例一起被回收）。 */
    fun release() {
        picTranslator?.release()
        picTranslator = null
    }

    private fun imageTranslator(): TranslationPicAPI? {
        val key = "${appPrefs.getInt("Pic_API", 0)}:${appPrefs.getInt("Custom_Pic_API", 0)}"
        if (picTranslator == null || key != picTranslatorKey) {
            picTranslator?.release()
            picTranslator = ImageTranslatorFactory.create(app, customPrefs)
            picTranslatorKey = key
        }
        return picTranslator
    }

    /**
     * 翻一张插图。
     *
     * @param file 插图文件（epub 条目已经解出来落盘，见 `NovelChapterRepository.imagesOf`）
     * @param isCancelled 取消检测（阅读器退出 / 用户切走）；返回 true 时尽早收手
     */
    suspend fun translate(
        file: File,
        sourceLang: String,
        targetLang: String,
        isCancelled: () -> Boolean = { false },
    ): NovelImageOutcome = withContext(Dispatchers.IO + OcrExecution.context(isCancelled)) {
        translateOnIo(file, sourceLang, targetLang, isCancelled)
    }

    /**
     * ⚠️ **必须在 IO 线程上跑**（上面那个 `withContext(Dispatchers.IO)` 不是摆设）：
     * 解码插图、ONNX 推理、请求翻译都是阻塞活，而小说侧的调用方可能是
     * `lifecycleScope`（**主线程派发器**）—— 漫画阅读器那边整个控制器都建在 IO scope 上，
     * 所以同样的调用在那里没问题，搬过来就会卡主线程。
     */
    private suspend fun translateOnIo(
        file: File,
        sourceLang: String,
        targetLang: String,
        isCancelled: () -> Boolean,
    ): NovelImageOutcome {
        if (isCancelled()) return NovelImageOutcome(null, null)
        if (!file.isFile) return NovelImageOutcome(null, "插图文件不存在")
        if (TranslationExecutionMode.isImage(app)) {
            return translateImageOnIo(file, sourceLang, targetLang, isCancelled)
        }
        // ⚠️ **先校验语言与 OCR 引擎组是否兼容**：`DetectionBridge.runOCR` 在语言不受支持时
        // **静默返回空列表**（PP-OCRv5 的 ko/ru 没下 rec 模型时就是这样，manga-ocr 只认日文），
        // 直接去 OCR 的话用户看到的是"这张图里没识别到文字"—— 一个完全误导的错误。
        val groupLangs = runCatching {
            com.moe.starflow.utils.OcrEngineManager.getOcrEngineGroup(appPrefs).sourceLangs
        }.getOrNull().orEmpty()
        if (languageUnsupportedByOcr(sourceLang, groupLangs)) {
            return NovelImageOutcome(
                null,
                "当前 OCR 引擎不支持「$sourceLang」（模型管理页里换一组引擎，或把源语言改成支持的语言）",
            )
        }
        val decoded = decodeForOcr(file)
        if (decoded == null) {
            LogCollector.w(TAG, "插图解码失败 image=${file.name} bytes=${file.length()}")
            return NovelImageOutcome(null, "插图读不出来（可能已损坏）")
        }
        val base = decoded.bitmap
        try {
            val bubbles = try {
                ocrWithLock(base, sourceLang)
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                return NovelImageOutcome(null, null)
            } catch (e: Exception) {
                LogCollector.e(TAG, "插图 OCR 失败: ${file.name}", e)
                return NovelImageOutcome(
                    null,
                    if (e is OcrMemoryException) e.message else "${e.javaClass.simpleName}: ${e.message.orEmpty()}".ifBlank { "OCR 失败" },
                )
            }
            if (bubbles.isEmpty()) return NovelImageOutcome(null, "这张图里没识别到文字")
            if (isCancelled()) return NovelImageOutcome(null, null)

            val translated = translateBubbles(bubbles, sourceLang, targetLang, isCancelled)
                ?: return NovelImageOutcome(null, null) // 取消
            if (translated.isEmpty()) return NovelImageOutcome(null, "翻译没有返回结果")

            // ⚠️ **矩形必须换回原图坐标系**：OCR 跑在**采样后**的位图上（[OCR_MAX_SIDE]），
            // 而载荷的坐标要有一个"与解码尺寸无关"的基准 —— 否则渲染时按显示尺寸再解一次码，
            // 矩形就会整体偏移（原图 2000 宽的图：矩形会跑到右边一半之外，译文全画在图外）。
            // 基准统一取 **原图像素**（`NovelImage.width/height` 也是它）。
            val toOriginal = decoded.originalWidth.toFloat() / base.width
            return NovelImageOutcome(
                NovelImageTranslation(
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    ocrEngine = engineLabel(),
                    blocks = toBlocks(translated, toOriginal),
                ),
                null,
            )
        } finally {
            // renderOverlay 会自己 copy 出新的位图；OCR 用的这张用完就可以还给系统
            if (!base.isRecycled) base.recycle()
        }
    }

    /**
     * 图片模式：解码插图 → 云端图片接口。有坐标就生成 blocks；只有文本则用 [NovelImageTranslation.plainText]。
     * 全程不碰 [OcrLock]，也不初始化本地 OCR/检测模型。
     */
    private suspend fun translateImageOnIo(
        file: File,
        sourceLang: String,
        targetLang: String,
        isCancelled: () -> Boolean,
    ): NovelImageOutcome {
        val decoded = decodeForOcr(file)
        if (decoded == null) {
            LogCollector.w(TAG, "插图解码失败 image=${file.name} bytes=${file.length()}")
            return NovelImageOutcome(null, "插图读不出来（可能已损坏）")
        }
        val base = decoded.bitmap
        val ctx = "novel image=${file.name}"
        val api = imageTranslator()
            ?: run {
                base.recycle()
                ImageTranslationLog.configMissing(
                    ImageTranslatorFactory.providerId(customPrefs), ctx,
                    missing = "factory returned null（见 ImageTranslatorFactory 的同段日志）",
                )
                return NovelImageOutcome(null, app.getString(R.string.image_translate_api_not_configured))
            }
        TranslationBusyRegistry.enter()
        try {
            val result = try {
                suspendCancellableCoroutine<TranslationResult> { cont ->
                    api.getTranslation(base, sourceLang, targetLang, ctx) { outcome ->
                        if (cont.isActive) cont.resume(outcome)
                    }
                    cont.invokeOnCancellation { api.cancelTranslation() }
                }
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive || isCancelled()) {
                    return NovelImageOutcome(null, null)
                }
                throw e
            }

            when (result) {
                is TranslationResult.Success -> {
                    if (isCancelled()) return NovelImageOutcome(null, null)
                    val payload = result.image
                    val text = result.translatedText.ifBlank {
                        payload?.blocks?.joinToString("\n") { it.translatedText }.orEmpty()
                    }
                    val scale = decoded.originalWidth.toFloat() / base.width.coerceAtLeast(1)
                    val blocks = payload?.blocks.orEmpty().mapNotNull { it.toNovelBlock(scale) }
                    if (blocks.isEmpty() && text.isBlank()) {
                        return NovelImageOutcome(null, app.getString(R.string.image_translate_empty))
                    }
                    return NovelImageOutcome(
                        NovelImageTranslation(
                            sourceLang = sourceLang,
                            targetLang = targetLang,
                            ocrEngine = ImageTranslatorFactory.label(app, customPrefs),
                            blocks = blocks,
                            plainText = if (blocks.isEmpty()) text else null,
                        ),
                        null,
                    )
                }
                is TranslationResult.Error -> {
                    // 失败详情已由 ImageTranslate 的 ✗ 行落盘（ctx 里带文件名），这里不重复刷屏
                    return NovelImageOutcome(
                        null,
                        result.error.message ?: app.getString(R.string.image_translate_empty),
                    )
                }
            }
        } finally {
            TranslationBusyRegistry.exit()
            if (!base.isRecycled) base.recycle()
        }
    }

    private fun ImageTextBlock.toNovelBlock(scale: Float): NovelImageTextBlock? {
        val l = (left * scale).toInt()
        val t = (top * scale).toInt()
        val r = (right * scale).toInt()
        val b = (bottom * scale).toInt()
        if (r <= l || b <= t) return null
        val translated = translatedText.ifBlank { sourceText }
        if (translated.isBlank()) return null
        return NovelImageTextBlock(
            left = l,
            top = t,
            right = r,
            bottom = b,
            original = sourceText,
            translated = translated,
            vertical = vertical,
            fontSize = (fontSize * scale).coerceAtLeast(1f),
        )
    }

    // ===== OCR =====

    /**
     * OCR：**持 `OcrLock` 走漫画同一条链路**（引擎组来自全局设置，默认 PP-OCRv6 免下载）。
     *
     * 返回的文字块已经按阅读顺序排好、且带矩形 —— 与漫画的气泡级结果同一份类型
     * （[BubbleRegion]），所以后面的翻译/渲染都能直接复用漫画的实现。
     */
    private suspend fun ocrWithLock(bitmap: Bitmap, sourceLang: String): List<BubbleRegion> {
        OcrLock.beginOcrDemand()
        var token = 0L
        var heartbeat: Job? = null
        try {
            token = acquireLockWithWait()
            if (token == 0L) {
                // ⚠️ 这条**只该在真的排队时**出现（同一时刻只有一个 OCR 引擎）——外面再持一把
                // 就变成自己等自己（`OcrLock` 不可重入），所以队列侧含图批一律不持外锁
                // （见 `NovelTranslationQueue.runInFlight` 的注释）。
                throw IllegalStateException("OCR 引擎忙（前面还有插图在识别），等一下重试")
            }
            heartbeat = startHeartbeat(token)
            val (det, ocr) = TranslationEngineInit.ensureReady(app)
            OcrLock.heartbeat(token)
            val rtDir = RtTextDirection.load(appPrefs)
            val cfg = TranslationCacheManager(app).getOverlayConfig(appPrefs)
            val blocks = DetectionBridge.runOCR(
                bitmap = bitmap,
                language = sourceLang,
                detEngine = det.value,
                ocrEngine = ocr.value,
                context = app,
                keepTextFree = appPrefs.getBoolean(MangaModeConfig.KEY_KEEP_TEXT_FREE, true),
                rtDirection = rtDir,
            )
            OcrLock.heartbeat(token)
            val bubbles = DetectionBridge.ocrToBubbleRegions(
                blocks,
                RtTextDirection.resolve(det, rtDir, cfg.textDirection)
            )
            LogCollector.d(TAG, "插图 OCR 完成：${blocks.size} 块 / ${bubbles.size} 气泡（${det.name}/${ocr.name}）")
            return bubbles
        } finally {
            heartbeat?.cancel()
            if (token != 0L) OcrLock.release(token)
            OcrLock.endOcrDemand()
        }
    }

    // ===== 翻译 =====

    /**
     * 逐气泡翻译（**复用漫画的 `TranslateUtils.translateBubbles`**：编号批量 + prefill +
     * 空译文回退原文 + 取消看门狗都在里面）。
     *
     * 返回 null = 用户取消（不是失败）。
     */
    private suspend fun translateBubbles(
        bubbles: List<BubbleRegion>,
        sourceLang: String,
        targetLang: String,
        isCancelled: () -> Boolean,
    ): List<TranslatedBubble>? {
        val engine = api() ?: throw IllegalStateException("没有配置文本翻译引擎")
        // 本地引擎（NLLB / LlamaCpp）全局串行：与漫画 translatePhase 同一把锁。
        // 判据收敛在 `isLocalHeavyEngine()`（唯一来源），别自己 instanceof 两个类。
        try {
            return TranslateUtils.translateBubbles(
                translator = engine,
                bubbles = bubbles,
                sourceLang = sourceLang,
                targetLang = targetLang,
                prefs = customPrefs,
                isCancelled = isCancelled,
            )
        } catch (e: TranslationCancelledException) {
            return null
        }
    }

    // ===== 收尾 =====

    /**
     * 气泡 → 载荷里的文字块。
     *
     * @param scaleToOriginal OCR 位图 → **原图像素**的倍率（原图被采样过就 > 1）：
     *   矩形与字号都要乘上它，载荷的坐标系才是"原图"（与 `NovelImage.width/height` 同一个基准）。
     */
    private fun toBlocks(bubbles: List<TranslatedBubble>, scaleToOriginal: Float): List<NovelImageTextBlock> =
        bubbles.mapNotNull { b ->
            val text = b.translatedText.ifBlank { b.originalText }
            if (b.originalText.isBlank() && text.isBlank()) return@mapNotNull null
            NovelImageTextBlock(
                left = (b.rect.left * scaleToOriginal).toInt(),
                top = (b.rect.top * scaleToOriginal).toInt(),
                right = (b.rect.right * scaleToOriginal).toInt(),
                bottom = (b.rect.bottom * scaleToOriginal).toInt(),
                original = b.originalText,
                translated = b.translatedText,
                vertical = b.direction == TextDirection.VERTICAL_RL ||
                    b.direction == TextDirection.VERTICAL_LR,
                fontSize = b.fontSize * scaleToOriginal,
            )
        }

    /** OCR 引擎组标签（写进载荷，排查"这张图当初是谁认的字"）。 */
    private fun engineLabel(): String = runCatching {
        com.moe.starflow.utils.OcrEngineManager.getOcrEngineGroup(appPrefs).key
    }.getOrDefault("")

    // ===== 位图 / 锁 =====

    /** 采样解码的结果：位图本身 + **原图**像素尺寸（载荷坐标系的基准）。 */
    private class Decoded(val bitmap: Bitmap, val originalWidth: Int, val originalHeight: Int)

    /** 按 [OCR_MAX_SIDE] 采样解码（原图 4000 宽按 1/4 采样，内存与耗时都降一个量级）。 */
    private fun decodeForOcr(file: File): Decoded? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
                },
            )?.let { Decoded(it, bounds.outWidth, bounds.outHeight) }
        }
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: Exception) {
        LogCollector.w(TAG, "插图解码失败: ${file.name}", e)
        null
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= OCR_MAX_SIDE || height / (sample * 2) >= OCR_MAX_SIDE) {
            sample *= 2
        }
        return sample
    }

    /** 轮询等 `OcrLock`（它没有 await）；超时返回 0。 */
    private suspend fun acquireLockWithWait(): Long {
        var waited = 0L
        while (true) {
            OcrExecution.checkCancellation()
            OcrLock.acquire(currentCoroutineContext()[Job], "novel image OCR").let { if (it != 0L) return it }
            if (waited >= LOCK_WAIT_TIMEOUT_MS) return 0L
            delay(LOCK_POLL_MS)
            waited += LOCK_POLL_MS
        }
    }

    /** 心跳由共享锁托管，取消收尾期间保留，释放令牌或持有者完成后停止。 */
    private suspend fun startHeartbeat(token: Long): Job = OcrLock.startHeartbeat(token, LOCK_HEARTBEAT_MS)
}
