package com.moe.starflow.translate.autotranslate
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.*
import com.moe.starflow.manga.*
import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import com.moe.starflow.translate.screenshot.*

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.LruCache
import com.moe.starflow.data.TranslationCacheManager
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.PixelCompare
import com.moe.starflow.utils.TextSimilarity

/**
 * 游戏自动翻译引擎（像素驱动版）。
 *
 * 核心逻辑：
 * 1. 像素快检（150ms）判断页面是否稳定
 * 2. 像素连续稳定 2 帧后触发 OCR
 * 3. OCR 结果查 LRU 缓存，命中直接显示，未命中调翻译 API
 */
class AutoTranslateEngine(
    private val context: Context,
    private val cacheManager: TranslationCacheManager,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val getSourceLanguage: () -> String,
    private val getTargetLanguage: () -> String,
    /** 竖排读取方向（`Game_Text_Direction`）。每次 OCR 现读，设置改动实时生效。 */
    private val getVerticalDirection: () -> com.moe.starflow.manga.types.TextDirection =
        { com.moe.starflow.manga.types.TextDirection.VERTICAL_RL },
    private val onMessage: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "AutoTranslateEngine"
        private const val STABLE_FRAMES = 2
        private const val LRU_CAPACITY = 20
        private const val DEFAULT_PIXEL_THRESHOLD = 1
    }

    // 像素稳定状态机
    enum class PixelState {
        IDLE,       // 已翻译完成，像素不变则跳过 OCR
        CHANGED,    // 像素和上帧不同
        STABLE_1,   // 稳定第1帧
        STABLE_2    // 稳定第2帧，触发 OCR
    }

    // 状态
    private var isRunning = false
    private var pixelState = PixelState.CHANGED
    private var stableCount = 0

    // OCR 引擎
    private val ocrEngine = GameOcrEngine(context, onMessage)

    // 强制翻译标志（手动点击时跳过像素检查）
    var isManualForceTranslate = false

    // 像素比较
    private var lastPixels: IntArray? = null
    private var lastWidth = 0
    private var lastHeight = 0
    var lastDiffRatio: Float = 0f

    // 内存 LRU 缓存：normalize(sourceText) → translatedText
    private val translationCache = LruCache<String, String>(LRU_CAPACITY)

    // 翻译决策
    sealed class Decision {
        /** 像素正在变化，跳过 */
        data class PixelChanging(val diffRatio: Float) : Decision()
        /** 像素稳定但未到阈值，继续等待 */
        data class PixelStabilizing(val stableCount: Int, val diffRatio: Float) : Decision()
        /** 已翻译，像素不变，跳过 OCR */
        data class Idle(val diffRatio: Float) : Decision()
        /**
         * 触发了 OCR 但**识别结果为空**。
         *
         * ⚠️ 与 [Idle] 分开：`Idle` 是「像素没变所以根本没跑 OCR」，静默是对的；
         * 这里是「跑了 OCR 但没内容」，用户需要反馈。此前两者共用一个分支 →
         * 自动翻译下空页面**完全静默**，用户无从判断是真没内容还是卡住了。
         */
        data class Empty(val diffRatio: Float) : Decision()
        /** 命中 LRU 缓存，直接显示 */
        data class CacheHit(val ocrText: String, val cachedText: String) : Decision()
        /** 需要翻译 */
        data class Translate(val ocrText: String) : Decision()
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        pixelState = PixelState.CHANGED
        stableCount = 0
        isManualForceTranslate = false
        LogCollector.d(TAG, "自动翻译启动（像素驱动）")
    }

    fun stop() {
        isRunning = false
        isManualForceTranslate = false
        lastPixels = null
        lastDiffRatio = 0f
        LogCollector.d(TAG, "自动翻译停止")
    }

    /**
     * 像素快检：仅比较像素，返回当前像素状态。
     * 不执行 OCR，由 FloatingBallService 根据返回值决定是否触发 OCR。
     */
    fun checkPixel(bitmap: Bitmap): Decision {
        if (!isRunning) return Decision.PixelChanging(0f)

        val w = bitmap.width
        val h = bitmap.height
        val currPixels = IntArray(w * h)
        bitmap.getPixels(currPixels, 0, w, 0, 0, w, h)

        // 读取用户设置的像素阈值
        val prefs = CustomPreference.getInstance(context)
        val thresholdPct = prefs.getInt("Game_Pixel_Similar_Threshold", DEFAULT_PIXEL_THRESHOLD)
        val diffThreshold = thresholdPct / 100f

        val prevPixels = lastPixels
        if (prevPixels != null && lastWidth == w && lastHeight == h) {
            val result = PixelCompare.comparePixels(prevPixels, currPixels, w, h, diffThreshold = diffThreshold)
            lastDiffRatio = result.diffRatio
            lastPixels = currPixels

            if (result.isSimilar) {
                // 像素没变
                if (pixelState == PixelState.IDLE) {
                    // 已翻译过，像素仍不变，跳过 OCR
                    LogCollector.d(TAG, "【IDLE】像素未变 diff=${"%.6f".format(result.diffRatio)}, 跳过OCR")
                    return Decision.Idle(result.diffRatio)
                }
                stableCount++
                pixelState = if (stableCount >= STABLE_FRAMES) PixelState.STABLE_2 else PixelState.STABLE_1
                LogCollector.d(TAG, "【像素稳定】count=$stableCount diff=${"%.6f".format(result.diffRatio)}")
                return Decision.PixelStabilizing(stableCount, result.diffRatio)
            } else {
                // 像素变了
                stableCount = 0
                pixelState = PixelState.CHANGED
                LogCollector.d(TAG, "【像素变化】diff=${"%.6f".format(result.diffRatio)}")
                // 关闭稳定性检测时，像素变化立即触发 OCR
                val stabilityCheck = prefs.getBoolean("pixel_stability_check", false)
                if (!stabilityCheck) {
                    LogCollector.d(TAG, "【跳过稳定检测·立即OCR】")
                    return Decision.PixelStabilizing(STABLE_FRAMES, result.diffRatio)
                }
                return Decision.PixelChanging(result.diffRatio)
            }
        } else {
            lastPixels = currPixels
            lastWidth = w
            lastHeight = h
            stableCount = 0
            pixelState = PixelState.CHANGED
            LogCollector.d(TAG, "【首次截图·保存像素】")
            return Decision.PixelChanging(0f)
        }
    }

    /**
     * OCR + 缓存查询。像素稳定后由 FloatingBallService 调用。
     */
    suspend fun ocrAndTranslate(bitmap: Bitmap): Decision {
        if (!isRunning) return Decision.PixelChanging(0f)

        LogCollector.d(TAG, "【触发OCR】正在识别...")
        val ocrText = ocrEngine.recognize(bitmap, getVerticalDirection())
        val normalizedText = TextSimilarity.normalize(ocrText)

        if (normalizedText.isBlank()) {
            LogCollector.d(TAG, "【跳过】OCR 结果为空，进入IDLE等待页面变化")
            markIdle()
            return Decision.Empty(0f)
        }

        if (isManualForceTranslate) {
            isManualForceTranslate = false
            LogCollector.d(TAG, "【手动强制翻译】${normalizedText.take(20)}...")
            return Decision.Translate(normalizedText)
        }

        // 查 LRU 缓存
        val cached = translationCache.get(normalizedText)
        if (cached != null) {
            LogCollector.d(TAG, "【LRU缓存命中】${normalizedText.take(20)}...")
            return Decision.CacheHit(normalizedText, cached)
        }

        LogCollector.d(TAG, "【需翻译】${normalizedText.take(20)}...")
        return Decision.Translate(normalizedText)
    }

    fun onTranslationSuccess(sourceText: String, translatedText: String) {
        val normalized = TextSimilarity.normalize(sourceText)
        translationCache.put(normalized, translatedText)
        LogCollector.d(TAG, "翻译成功，写入LRU缓存: ${sourceText.take(20)}...")
    }

    fun forceTranslate(ocrText: String): Decision.Translate {
        isManualForceTranslate = false
        return Decision.Translate(TextSimilarity.normalize(ocrText))
    }

    /**
     * 进入 IDLE 状态：翻译完成后调用，像素不变则跳过 OCR。
     */
    fun markIdle() {
        pixelState = PixelState.IDLE
        stableCount = 0
        LogCollector.d(TAG, "【进入IDLE】等待像素变化")
    }

    fun isFloatingViewOverlappingCrop(floatViewRect: Rect, cropRect: Rect): Boolean {
        return Rect.intersects(floatViewRect, cropRect)
    }

    fun onLanguageChanged() {
        translationCache.evictAll()
        LogCollector.d(TAG, "语言变化，清空 LRU 缓存")
    }

    fun onOcrEngineChanged() {
        translationCache.evictAll()
        LogCollector.d(TAG, "OCR 引擎变化，清空 LRU 缓存")
    }

    fun onCropRegionChanged() {
        translationCache.evictAll()
        lastPixels = null
        LogCollector.d(TAG, "裁剪区域变化，清空 LRU 缓存")
    }

    fun onApiConfigChanged() {
        translationCache.evictAll()
        LogCollector.d(TAG, "API 配置变化，清空 LRU 缓存")
    }
}
