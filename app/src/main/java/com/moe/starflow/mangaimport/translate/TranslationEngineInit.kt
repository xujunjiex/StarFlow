package com.moe.starflow.mangaimport.translate

import android.content.Context
import androidx.preference.PreferenceManager
import com.moe.starflow.manga.engine.ComicBubbleDetector
import com.moe.starflow.manga.engine.MangaOcrModelFiles
import com.moe.starflow.manga.engine.MangaOcrRecognizer
import com.moe.starflow.manga.engine.PPOcrV5Engine
import com.moe.starflow.manga.engine.PPOcrV6Engine
import com.moe.starflow.manga.types.DetEngine
import com.moe.starflow.manga.types.OcrEngine
import com.moe.starflow.utils.OcrEngineManager

/**
 * 阅读器翻译引擎初始化：按当前 OCR 引擎组（Ocr_Engine_Group）就绪漫画检测/识别引擎。
 * 模型缺失/初始化失败时抛异常，由 [ReaderTranslationController] 捕获转失败码。
 */
object TranslationEngineInit {

    /** 返回 (det, ocr)，供 [com.moe.starflow.manga.DetectionBridge.runOCR] 路由。 */
    suspend fun ensureReady(context: Context): Pair<DetEngine, OcrEngine> {
        val group = OcrEngineManager.getOcrEngineGroup(
            PreferenceManager.getDefaultSharedPreferences(context)
        )
        val det = group.mangaDet
        val ocr = group.mangaOcr

        when (det) {
            DetEngine.PP_OCR_V5 -> PPOcrV5Engine.initialize(context)
            DetEngine.PP_OCR_V6 -> PPOcrV6Engine.initialize(context)
            DetEngine.RT_DETR_V2 -> ComicBubbleDetector.initialize(context)
            DetEngine.MLKIT -> {} // 无需初始化
        }
        when (ocr) {
            OcrEngine.PPOcrV5 -> PPOcrV5Engine.initialize(context)
            OcrEngine.PPOcrV6 -> PPOcrV6Engine.initialize(context)
            OcrEngine.MangaOcr -> {
                if (!MangaOcrModelFiles.isModelDownloaded(context)) {
                    throw IllegalStateException("manga-ocr 模型未下载，请先在模型管理页下载")
                }
                MangaOcrRecognizer.initialize(context, useAssets = false)
            }
            OcrEngine.MLKit -> {} // 无需初始化
        }
        return det to ocr
    }
}