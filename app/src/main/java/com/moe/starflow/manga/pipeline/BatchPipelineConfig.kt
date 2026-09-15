package com.moe.starflow.manga.pipeline

import android.content.Context
import com.moe.starflow.manga.types.DetEngine
import com.moe.starflow.manga.types.OcrEngine
import com.moe.starflow.manga.types.TextDirection
import com.moe.starflow.utils.CustomPreference

/**
 * 分批管线的一次任务入参（任务期间不变），作为 [IncrementalBatchPipeline] 的构造参数。
 *
 * 字段与旧实现里读取的 `config.*` / `prefs` 一一对应：
 * - [incrementalEnabled] ← 原 `prefs.getBoolean("Incremental_Render", true)`
 * - [isAutoTranslating] ← 原 `autoTranslateEngine.isAutoTranslating`（自动翻译中不弹"未检测到文字"）
 */
data class BatchPipelineConfig(
    val detEngine: DetEngine,
    val ocrEngine: OcrEngine,
    val sourceLang: String,
    val targetLang: String,
    val textDirection: TextDirection,
    val keepTextFree: Boolean,
    /** 原 `Service.prefs`，透传给 `TranslateUtils.translateBubbles`。 */
    val prefs: CustomPreference,
    val incrementalEnabled: Boolean,
    val isAutoTranslating: Boolean,
)
