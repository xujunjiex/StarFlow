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
import com.moe.starflow.manga.engine.MangaOcrBridge
import com.moe.starflow.manga.engine.OCRTextRecognizer
import com.moe.starflow.manga.engine.MangaOcrModelFiles
import com.moe.starflow.manga.engine.PPOcrV5Engine
import com.moe.starflow.manga.engine.PPOcrV6Engine
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 游戏翻译 OCR 引擎封装。
 * 封装 MLKit(0)、PP-OCRv5(1)、manga-ocr(2)、PP-OCRv6(3) 四种引擎，
 * 统一调用接口，支持引擎切换和自动降级。
 */
class GameOcrEngine(
    private val context: Context,
    private val onMessage: ((String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "GameOcrEngine"
    }

    private val prefs = CustomPreference.getInstance(context)

    /**
     * 执行 OCR 识别。
     *
     * @param bitmap 待识别的图片
     * @param verticalDirection 竖排读取方向（`Game_Text_Direction`）。
     *   **只对 PP-OCRv5/v6 生效** —— 它们原本只输出栅格扫描序（无任何排序），
     *   横屏时同一段竖排文字在帧里转了 90°、顺序会整段翻过来。这里统一补一次
     *   阅读顺序排序（横排恒左→右，不受设置影响），让「识别顺序」不再随屏幕方向变。
     *
     *   ⚠️ **ML Kit / manga-ocr 不接，且不要接**：ML Kit 的 block 是它自己版式分析的
     *   产物（官方文档：*"a contiguous set of text lines, such as a paragraph or a
     *   **column**"*）—— 一个竖排整列常常就是**一个 block**，块内读序封在它手里、
     *   我们插不进去。曾试图按 `line.boundingBox` 重排，表现为「设置时灵时不灵」，
     *   比不支持更难解释，已删除（见 `OCRTextRecognizer` 的说明）。manga-ocr 借 ML Kit
     *   定位，同理不接。
     * @return 识别出的文字
     */
    suspend fun recognize(
        bitmap: Bitmap,
        verticalDirection: TextDirection = TextDirection.VERTICAL_RL
    ): String {
        val engine = com.moe.starflow.utils.OcrEngineManager.getOcrEngineGroup(prefs.getSharedPreferences()).gameEngine
        val language = prefs.getString("Source_Language", "ja")

        return when (engine) {
            1 -> recognizeWithPPOcrV5(bitmap, language, verticalDirection)
            2 -> recognizeWithMangaOcr(bitmap, language)
            3 -> recognizeWithPPOcrV6(bitmap, language, verticalDirection)
            else -> recognizeWithMLKit(bitmap, language)
        }
    }

    /** ML Kit 路径：**不接方向设置** —— 理由见 [recognize] 的说明。 */
    private suspend fun recognizeWithMLKit(bitmap: Bitmap, language: String): String {
        return OCRTextRecognizer.getPicText(language, bitmap)
    }

    private suspend fun recognizeWithPPOcrV5(
        bitmap: Bitmap,
        language: String,
        verticalDirection: TextDirection
    ): String {
        initPPOcrV5IfNeeded()
        val (recLang, hint) = PPOcrV5Engine.resolveRecLang(context, language)
        if (hint != null) {
            LogCollector.w(TAG, "PP-OCRv5: $hint")
        }
        return if (recLang != null) {
            val result = withContext(Dispatchers.IO) {
                PPOcrV5Engine.runOCR(context, bitmap, recLang, useDet = true)
            }
            result.toReadOrderText(verticalDirection)
        } else {
            LogCollector.w(TAG, "PP-OCRv5 不支持语言: $language, 回退 ML Kit")
            recognizeWithMLKit(bitmap, language)
        }
    }

    private suspend fun recognizeWithMangaOcr(bitmap: Bitmap, language: String): String {
        initMangaOcrIfNeeded()
        return if (MangaOcrBridge.isAvailable()) {
            val textBlocks = MangaOcrBridge.recognizeWithLocation(bitmap, language)
            textBlocks.joinToString("\n") { it.text }
        } else {
            LogCollector.w(TAG, "manga-ocr 未初始化, 回退 ML Kit")
            recognizeWithMLKit(bitmap, language)
        }
    }

    private fun initPPOcrV5IfNeeded() {
        LogCollector.d(TAG, "initPPOcrV5IfNeeded: isInitialized=${PPOcrV5Engine.isInitialized}")
        if (PPOcrV5Engine.isInitialized) return
        synchronized(PPOcrV5Engine) {
            if (!PPOcrV5Engine.isInitialized) {
                LogCollector.d(TAG, "initPPOcrV5IfNeeded: 开始初始化 PP-OCRv5")
                onMessage?.invoke("PP-OCRv5 识别器初始化中...")
                PPOcrV5Engine.initialize(context)
                LogCollector.d(TAG, "initPPOcrV5IfNeeded: PP-OCRv5 初始化完成")
                onMessage?.invoke("PP-OCRv5 识别器初始化成功")
            }
        }
    }

    private suspend fun recognizeWithPPOcrV6(
        bitmap: Bitmap,
        language: String,
        verticalDirection: TextDirection
    ): String {
        initPPOcrV6IfNeeded()
        val result = withContext(Dispatchers.IO) {
            PPOcrV6Engine.runOCR(context, bitmap, useDet = true)
        }
        return result.toReadOrderText(verticalDirection)
    }

    /**
     * `OcrResult` → 按**阅读顺序**拼接的文本。
     *
     * ⚠️ 不能再用 `texts.joinToString("")`：PP 引擎的 `texts` 是 det 候选序
     * （历史实现里是栅格扫描序），**完全没有排序** —— 横屏时帧整体转了 90°，
     * 同一段竖排文字从「左右并排的列」变成「上下堆叠的行」，
     * 按坐标取出的顺序就整段翻过来（用户实测：同一段文本横竖屏识别顺序不同）。
     *
     * 这里按框的几何重排一次：竖排按 [verticalDirection] 取列序、同列上→下；
     * 横排恒上→下、行内左→右（不受设置影响，避免把横排句子倒过来）。
     * 无框结果（`useDet=false` 的全图识别）原样返回。
     */
    private fun OcrResult.toReadOrderText(verticalDirection: TextDirection): String {
        val boxes = this.boxes
        if (boxes.isEmpty()) return texts.joinToString("")
        val ordered = PPOcrDetGeometry.sortDetCandidates(
            boxes, scores, verticalDirection == TextDirection.VERTICAL_LR
        )
        val reordered = ordered.reorder(texts)
        if (PPOcrDetGeometry.enableDebugLogging) {
            // 最终送进翻译的拼接顺序（按气泡/列逐条列出，便于与 det 逐框日志对照）
            LogCollector.d(
                TAG,
                "toReadOrderText: dir=$verticalDirection, n=${reordered.size}, " +
                    "顺序=${reordered.mapIndexed { i, t -> "$i:'${t.take(8)}'" }}"
            )
        }
        return reordered.joinToString("")
    }

    private fun initPPOcrV6IfNeeded() {
        LogCollector.d(TAG, "initPPOcrV6IfNeeded: isInitialized=${PPOcrV6Engine.isInitialized}")
        if (PPOcrV6Engine.isInitialized) return
        synchronized(PPOcrV6Engine) {
            if (!PPOcrV6Engine.isInitialized) {
                LogCollector.d(TAG, "initPPOcrV6IfNeeded: 开始初始化 PP-OCRv6")
                onMessage?.invoke("PP-OCRv6 识别器初始化中...")
                PPOcrV6Engine.initialize(context)
                LogCollector.d(TAG, "initPPOcrV6IfNeeded: PP-OCRv6 初始化完成")
                onMessage?.invoke("PP-OCRv6 识别器初始化成功")
            }
        }
    }

    private suspend fun initMangaOcrIfNeeded() {
        LogCollector.d(TAG, "initMangaOcrIfNeeded: isAvailable=${MangaOcrBridge.isAvailable()}")
        if (MangaOcrBridge.isAvailable()) return
        try {
            if (MangaOcrModelFiles.isModelDownloaded(context)) {
                LogCollector.d(TAG, "initMangaOcrIfNeeded: 开始初始化 manga-ocr")
                onMessage?.invoke("manga-ocr 识别器初始化中...")
                MangaOcrBridge.initializeDownloaded(context)
                LogCollector.d(TAG, "initMangaOcrIfNeeded: manga-ocr 初始化完成")
                onMessage?.invoke("manga-ocr 识别器初始化成功")
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "manga-ocr 识别器初始化失败", e)
            onMessage?.invoke("manga-ocr 识别器初始化失败: ${e.message}")
        }
    }
}
