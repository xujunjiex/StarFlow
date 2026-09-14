package com.moe.starflow.manga.state
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.*
import com.moe.starflow.manga.render.*
import com.moe.starflow.manga.merge.*

import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import android.content.Context
import com.moe.starflow.R
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 漫画 OCR 引擎初始化/释放管理器（从 MangaFloatingService 阶段 3 提取）。
 *
 * 各 OCR 引擎均为静态单例（PPOcrV5Engine/PPOcrV6Engine/ComicBubbleDetector/MangaOcrRecognizer），
 * 本类包装它们的 init/release + IO 调度 + 错误提示。UI 副作用（Toast/状态浮层/悬浮球状态）经回调注入。
 */
class MangaEngineManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onShowToast: (String) -> Unit,
    private val loadConfig: () -> MangaModeConfig,
    private val onMangaOcrError: (String) -> Unit,            // statusOverlay.showError + ballState Error
    private val onMangaOcrDownloadRequired: (String) -> Unit  // statusOverlay.showImmediate + ballState Idle
) {
    private val TAG = "MangaEngineManager"

    fun releaseMangaOcr() {
        try {
            LogCollector.d(TAG, "releaseMangaOcr: 释放 manga-ocr 资源")
            MangaOcrRecognizer.release()
        } catch (e: Exception) {
            LogCollector.e(TAG, "releaseMangaOcr: 释放失败", e)
        }
    }

    /** OCR 引擎初始化角色标签（det/rec/det+rec）走本地化资源，避免英文模式下显示「检测器/识别器」。 */
    private fun roleLabel(role: String): String = context.getString(when (role) {
        "识别器" -> R.string.role_rec
        "检测器" -> R.string.role_det
        else -> R.string.role_det_rec
    })

    fun initRTDetrV2() {
        scope.launch {
            try {
                initRTDetrV2IfNeeded()
                onShowToast(context.getString(R.string.engine_init_success, "RT-DETR-V2", context.getString(R.string.role_det)))
            } catch (e: Exception) {
                LogCollector.e(TAG, "RT-DETR-V2 检测器初始化失败", e)
                onShowToast(context.getString(R.string.engine_init_failed, "RT-DETR-V2", context.getString(R.string.role_det), e.message ?: context.getString(R.string.error_unknown)))
            }
        }
    }

    suspend fun initRTDetrV2IfNeeded() {
        if (ComicBubbleDetector.isInitialized) return
        try {
            LogCollector.d(TAG, "initRTDetrV2IfNeeded: 开始初始化 RT-DETR-V2")
            withContext(Dispatchers.IO) {
                ComicBubbleDetector.initialize(context)
            }
            LogCollector.d(TAG, "initRTDetrV2IfNeeded: RT-DETR-V2 初始化完成")
        } catch (e: Exception) {
            LogCollector.e(TAG, "initRTDetrV2IfNeeded: 初始化失败", e)
            withContext(Dispatchers.Main) {
                onShowToast(context.getString(R.string.engine_init_failed, "RT-DETR-V2", context.getString(R.string.role_det), e.message ?: ""))
            }
            throw e
        }
    }

    fun releaseRTDetrV2() {
        try {
            LogCollector.d(TAG, "releaseRTDetrV2: 释放 RT-DETR-V2 资源")
            ComicBubbleDetector.release()
        } catch (e: Exception) {
            LogCollector.e(TAG, "releaseRTDetrV2: 释放失败", e)
        }
    }

    /**
     * 初始化 PP-OCRv5
     * @param role 角色："检测器" 或 "识别器"
     */
    fun initPPOcrV5(role: String = "检测器") {
        scope.launch {
            try {
                initPPOcrV5IfNeeded()
                onShowToast(context.getString(R.string.engine_init_success, "PP-OCRv5", roleLabel(role)))
            } catch (e: Exception) {
                LogCollector.e(TAG, "PP-OCRv5${role}初始化失败", e)
                onShowToast(context.getString(R.string.engine_init_failed, "PP-OCRv5", roleLabel(role), e.message ?: context.getString(R.string.error_unknown)))
            }
        }
    }

    suspend fun initPPOcrV5IfNeeded() {
        if (PPOcrV5Engine.isInitialized) return
        try {
            LogCollector.d(TAG, "initPPOcrV5IfNeeded: 开始初始化 PP-OCRv5")
            withContext(Dispatchers.IO) {
                PPOcrV5Engine.initialize(context)
            }
            LogCollector.d(TAG, "initPPOcrV5IfNeeded: PP-OCRv5 初始化完成")
        } catch (e: Exception) {
            LogCollector.e(TAG, "initPPOcrV5IfNeeded: 初始化失败", e)
            throw e
        }
    }

    fun releasePPOcrV5() {
        try {
            LogCollector.d(TAG, "releasePPOcrV5: 释放 PP-OCRv5 资源")
            PPOcrV5Engine.release()
        } catch (e: Exception) {
            LogCollector.e(TAG, "releasePPOcrV5: 释放失败", e)
        }
    }

    /**
     * 初始化 PP-OCRv6
     * @param role 角色："检测器" 或 "识别器"
     */
    fun initPPOcrV6(role: String = "检测器") {
        scope.launch {
            try {
                initPPOcrV6IfNeeded()
                onShowToast(context.getString(R.string.engine_init_success, "PP-OCRv6", roleLabel(role)))
            } catch (e: Exception) {
                LogCollector.e(TAG, "PP-OCRv6${role}初始化失败", e)
                onShowToast(context.getString(R.string.engine_init_failed, "PP-OCRv6", roleLabel(role), e.message ?: context.getString(R.string.error_unknown)))
            }
        }
    }

    suspend fun initPPOcrV6IfNeeded() {
        if (PPOcrV6Engine.isInitialized) return
        try {
            LogCollector.d(TAG, "initPPOcrV6IfNeeded: 开始初始化 PP-OCRv6")
            withContext(Dispatchers.IO) {
                PPOcrV6Engine.initialize(context)
            }
            LogCollector.d(TAG, "initPPOcrV6IfNeeded: PP-OCRv6 初始化完成")
        } catch (e: Exception) {
            LogCollector.e(TAG, "initPPOcrV6IfNeeded: 初始化失败", e)
            throw e
        }
    }

    fun releasePPOcrV6() {
        try {
            LogCollector.d(TAG, "releasePPOcrV6: 释放 PP-OCRv6 资源")
            PPOcrV6Engine.release()
        } catch (e: Exception) {
            LogCollector.e(TAG, "releasePPOcrV6: 释放失败", e)
        }
    }

    /**
     * 确保 manga-ocr 已初始化。
     * 优先使用已下载的模型（通过 MangaOcrModelFiles 管理），
     * 如果没有下载的模型则提示用户去下载。
     */
    suspend fun ensureMangaOcrInitialized() {
        val currentConfig = loadConfig()

        when (currentConfig.ocrEngine) {
            OcrEngine.MangaOcr -> {
                if (MangaOcrModelFiles.isModelDownloaded(context)) {
                    try {
                        // 如果已初始化，直接返回
                        if (MangaOcrRecognizer.isInitialized) {
                            LogCollector.d(TAG, "ensureMangaOcrInitialized: manga-ocr 已初始化")
                            return
                        }
                        LogCollector.d(TAG, "ensureMangaOcrInitialized: 加载 manga-ocr 模型")
                        withContext(Dispatchers.IO) {
                            MangaOcrBridge.initializeDownloaded(context)
                        }
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "manga-ocr 识别器初始化失败", e)
                        withContext(Dispatchers.Main) {
                            onMangaOcrError("manga-ocr 识别器初始化失败：${e.message ?: "未知错误"}")
                        }
                        return
                    }
                } else {
                    // 未下载，提示用户去下载
                    LogCollector.d(TAG, "ensureMangaOcrInitialized: manga-ocr 未下载，提示用户")
                    withContext(Dispatchers.Main) {
                        onMangaOcrDownloadRequired(context.getString(R.string.manga_ocr_download_required))
                    }
                    return
                }
            }
            else -> {
                // MLKit 不需要 manga-ocr
                return
            }
        }
        LogCollector.d(TAG, "ensureMangaOcrInitialized: manga-ocr 初始化完成")
        withContext(Dispatchers.Main) {
            onShowToast(context.getString(R.string.engine_init_success, "manga-ocr", context.getString(R.string.role_rec)))
        }
    }
}
