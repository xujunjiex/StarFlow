package com.moe.moetranslator.manga

enum class TextDirection {
    VERTICAL_RL,   // 从上到下，列从右到左（传统日漫）
    VERTICAL_LR,   // 从上到下，列从左到右
    HORIZONTAL     // 从左到右，从上到下（标准）
}

<<<<<<< HEAD
=======
/**
 * 文字检测引擎。
 * MLKIT: ML Kit 检测+识别一体化
 * DBNET: DBNet 字符级检测 + BoxMerger 合并
 * CTD: ComicTextDetector 文字行级检测（无需合并）
 */
enum class DetEngine(val value: Int) {
    MLKIT(0),
    DBNET(1),
    CTD(2);

    companion object {
        fun fromValue(value: Int) = entries.firstOrNull { it.value == value } ?: MLKIT
    }
}

/**
 * OCR 识别引擎。
 */
enum class OcrEngine {
    MLKIT,
    MANGA_OCR,
    CTC_48PX
}

>>>>>>> 6bd5fb9 (feat(ctd): CTD + manga-ocr 独立流程，按 Y 分组合并框再识别)
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
    val bgColor: Int = android.graphics.Color.argb(200, 255, 255, 255)
)
