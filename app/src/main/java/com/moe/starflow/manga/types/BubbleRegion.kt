package com.moe.starflow.manga.types
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import android.graphics.Rect

/**
 * 气泡级检测结果（BubbleDetector 输出）。
 * 从 BubbleDetector.kt 提取，被 MangaFloatingService/DetectionBridge/TranslateUtils/MangaSpatialGrouping 共享。
 */
data class BubbleRegion(
    val rect: Rect,
    val texts: List<String>,
    /** Estimated glyph size from OCR text geometry; must be supplied by each producer. */
    val fontSize: Float,
    val direction: TextDirection = TextDirection.VERTICAL_RL,
    val angle: Float = 0f,
    val centerX: Float = -1f,
    val centerY: Float = -1f
)

/**
 * RT-DETR-V2 检测器输出（ComicBubbleDetector）。
 * 从 ComicBubbleDetector.kt 提取，被 DetectionBridge/RTDetrV2DebugResult 共享。
 */
data class DetectedBubble(
    val rect: Rect,
    val classId: Int,    // 1=text_bubble, 2=text_free (0 已过滤)
    val confidence: Float
)
