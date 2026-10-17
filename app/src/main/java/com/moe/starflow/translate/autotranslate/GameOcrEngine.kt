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
import com.moe.starflow.manga.engine.OCRTextRecognizer
import com.moe.starflow.manga.engine.PPOcrV5Engine
import com.moe.starflow.manga.engine.PPOcrV6Engine
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 游戏翻译 OCR 引擎封装。
 * 封装 ML Kit、PP-OCRv5、PP-OCRv6 三种游戏/视频引擎。
 * RT-MANGA 组在此模式下由 OcrEngineGroup 映射到纯 ML Kit。
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
     *   ML Kit 使用其自身阅读顺序；PP-OCRv5/v6 按检测框几何排序。
     * @return 识别出的文字
     */
    suspend fun recognize(
        bitmap: Bitmap,
        verticalDirection: TextDirection = TextDirection.VERTICAL_RL
    ): String = withContext(Dispatchers.IO + OcrExecution.context()) {
        OcrLock.beginOcrDemand()
        var token = 0L
        var heartbeat: kotlinx.coroutines.Job? = null
        try {
            val started = System.nanoTime()
            while (token == 0L) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                OcrExecution.checkCancellation()
                token = OcrLock.acquire(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job], "game/video OCR")
                if (token == 0L) {
                    check((System.nanoTime() - started) / 1_000_000 < 60_000) { "OCR engine busy: ${OcrLock.describeOwner()}" }
                    kotlinx.coroutines.delay(50)
                }
            }
            heartbeat = OcrLock.startHeartbeat(token)
            recognizeLocked(bitmap, verticalDirection)
        } finally {
            heartbeat?.cancel()
            if (token != 0L) OcrLock.release(token)
            OcrLock.endOcrDemand()
        }
    }

    private suspend fun recognizeLocked(bitmap: Bitmap, verticalDirection: TextDirection): String {
        val engine = com.moe.starflow.utils.OcrEngineManager.getGameVideoOcrEngineGroup(
            prefs.getSharedPreferences()
        ).gameEngine
        val language = prefs.getString("Source_Language", "ja")

        return when (engine) {
            1 -> recognizeWithPPOcrV5(bitmap, language, verticalDirection)
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

}
