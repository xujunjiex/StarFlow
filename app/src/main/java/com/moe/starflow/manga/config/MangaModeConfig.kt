package com.moe.starflow.manga.config
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import com.moe.starflow.manga.types.DetEngine
import com.moe.starflow.manga.types.OcrEngine
import com.moe.starflow.manga.types.TextAlign
import com.moe.starflow.manga.types.TextDirection
import com.moe.starflow.manga.types.VerticalFlow

/** 竖排书写流向（仅 RL / LR）。配置值只允许这两种，HORIZONTAL 由检测另行判断。 */
val MangaModeConfig.verticalFlow: VerticalFlow
    get() = VerticalFlow.fromPref(if (textDirection == TextDirection.VERTICAL_LR) "1" else "0")

data class MangaModeConfig(
    val enabled: Boolean = false,
    val textDirection: TextDirection = TextDirection.VERTICAL_RL,
    val smartBackground: Boolean = true,
    val autoDetectBubble: Boolean = true,
    val fontSize: Float = 16f,
    val autoFontSize: Boolean = true,
    val sourceLang: String = "ja",
    val targetLang: String = "zh",
    val textColor: Int = android.graphics.Color.BLACK,
    val bgColor: Int = android.graphics.Color.argb(200, 255, 255, 255),
    val ocrEngine: OcrEngine = OcrEngine.PPOcrV6,  // OCR 引擎（默认 PP-OCRv6）
    val detEngine: DetEngine = DetEngine.PP_OCR_V6,  // 检测引擎（默认 PP-OCRv6）
    val keepTextFree: Boolean = false,  // RT-DETR-V2: 是否保留 text_free 区域（自由文字/旁白/音效）
    val horizontalAlign: TextAlign = TextAlign.CENTER,  // 横排译文对齐（竖排不受影响）
    val trackingRatio: Float = 0f,                      // 用户字间距（×字号）
    val leadingRatio: Float = 0f                        // 用户行间距（×字号）
)
