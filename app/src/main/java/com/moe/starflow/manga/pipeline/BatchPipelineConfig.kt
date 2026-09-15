package com.moe.starflow.manga.pipeline

import android.content.Context
import com.moe.starflow.manga.types.DetEngine
import com.moe.starflow.manga.types.OcrEngine
import com.moe.starflow.manga.types.TextDirection
import com.moe.starflow.utils.CustomPreference

/**
 * 分批管线的一次任务入参（任务期间**快照不变**），作为 [IncrementalBatchPipeline] 的构造参数。
 *
 * 字段与旧实现里读取的 `config.*` / `prefs` 一一对应：
 * - [incrementalEnabled] ← 原 `prefs.getBoolean("Incremental_Render", true)`
 * - [isAutoTranslating] ← 原 `autoTranslateEngine.isAutoTranslating`（自动翻译中不弹"未检测到文字"）
 *
 * ⚠️ **已知且有意的行为差异（与搬家前不同）**：
 * 旧实现是**在每个使用点现读** `MangaFloatingService.config`，而该字段会被 `watchedKeys`
 * 监听器热重载（`Ocr_Engine_Group` / `Source_Language` / `Manga_Det_Model` / `Manga_Rec_Model` /
 * `Manga_Keep_Text_Free` / `Manga_Text_Direction` 变化 → `config = loadConfig()`）。
 * 因此旧代码在**一次分批任务中途**（例如第一批翻译/渲染完成、准备跑第二批之前）改设置时，
 * **同一页的两批会用到不同的语言/文字方向** —— 落库的 `bubbleRects` 与译文自相矛盾。
 *
 * 本类改为任务开始时快照，整页严格使用同一套参数。**这是修正了旧有的不一致**，
 * 而非等价改写。仅在"分批运行的数秒内恰好改动上述设置"时可观测，
 * 且新行为严格更合理；因此不再为还原该差异而在管线里引入 `MangaModeConfig` 依赖
 * （那会把该缺陷一并带给阅读器）。
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
