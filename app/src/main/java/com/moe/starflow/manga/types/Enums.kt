package com.moe.starflow.manga.types
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

/**
 * OCR 引擎类型
 */
enum class OcrEngine(val value: Int) {
    MLKit(0),      // 系统 OCR（默认，无需下载）
    MangaOcr(1),   // manga-ocr（下载版，从 HuggingFace 下载）
    PPOcrV5(4),    // PP-OCRv5（内置，多语言）
    PPOcrV6(5);    // PP-OCRv6（内置，多语言）

    companion object {
        fun fromValue(value: Int) = entries.firstOrNull { it.value == value } ?: MLKit
    }
}

/**
 * 文字排版轴向。注意与 [VerticalFlow] 的区别：本枚举描述「往哪个方向排」，
 * [VerticalFlow] 描述「竖排时列从哪边起」——两者概念不同，历史代码曾都叫「方向」而混淆。
 */
enum class TextDirection {
    VERTICAL_RL,   // 从上到下，列从右到左（传统日漫）
    VERTICAL_LR,   // 从上到下，列从左到右
    HORIZONTAL     // 从左到右，从上到下（标准）
}

/** 横排译文的行对齐方式（用户可配，仅作用于 HORIZONTAL，竖排不受影响）。 */
enum class TextAlign { LEFT, CENTER, RIGHT }

/**
 * 竖排书写流向（用户配置项 `Manga_Text_Direction`）。
 * 与 [TextDirection] 区分：这里只可能 RL/LR，不可能是横排，
 * 所以配置层不该复用带 HORIZONTAL 的 [TextDirection]（曾因此把「排版轴向」和「书写流向」混为一谈）。
 */
enum class VerticalFlow {
    RL,   // 列从右到左（传统日漫，默认）
    LR;   // 列从左到右

    /** 转成对应的排版轴向（仅竖排两值，不含 HORIZONTAL）。 */
    fun toTextDirection(): TextDirection =
        if (this == LR) TextDirection.VERTICAL_LR else TextDirection.VERTICAL_RL

    companion object {
        /** 从持久化值解析（"1" = LR，其余 = RL），与既有 prefs 值保持兼容。 */
        fun fromPref(value: String?): VerticalFlow = if (value == "1") LR else RL
    }
}

/**
 * 文字检测引擎。
 * MLKIT: ML Kit 检测+识别一体化
 */
enum class DetEngine(val value: Int) {
    MLKIT(0),
    RT_DETR_V2(3),
    PP_OCR_V5(4),
    PP_OCR_V6(5);

    companion object {
        fun fromValue(value: Int) = entries.firstOrNull { it.value == value } ?: MLKIT
    }
}
