/*
 * Copyright (C) 2024 murangogo
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with this library; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */

package com.moe.starflow.manga.engine
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.state.*
import com.moe.starflow.manga.render.*
import com.moe.starflow.manga.merge.*

import com.moe.starflow.manga.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import android.graphics.Bitmap
import android.util.Log
import com.moe.starflow.manga.types.TextDirection
import com.moe.starflow.utils.LogCollector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


object OCRTextRecognizer {
    private const val TAG = "OCRTextRecognizer"
    private val recognizers = mutableMapOf<String, TextRecognizer>()

    private fun getOrCreateRecognizer(language: String): TextRecognizer {
        return recognizers.getOrPut(language) {
            when (language) {
                "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                "en" -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
        }
    }

    // 使用suspend函数使其成为协程
    suspend fun getPicText(language: String, bitmap: Bitmap): String =
        withContext(Dispatchers.Default) {
            suspendCancellableCoroutine { continuation ->
                val recognizer = getOrCreateRecognizer(language)
                try {
                    val image = InputImage.fromBitmap(bitmap, 0)
                    recognizer.process(image)
                        .addOnSuccessListener { visionText ->
                            val resultText = mergeText(visionText, language)
                            LogCollector.d(TAG, "getPicText: bitmap=${bitmap.width}x${bitmap.height}")
                            continuation.resume(resultText)
                        }
                        .addOnFailureListener { e ->
                            e.printStackTrace()
                            continuation.resumeWithException(e)
                        }
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        }

    /**
     * 「竖排读取方向」重载：**按几何自行判断阅读顺序**，不依赖 ML Kit 的块序。
     *
     * ⚠️ ML Kit 与 PP-OCR 的区别（用户指出的关键差异）：
     * - **PP-OCR 是行列级识别器**：每个 `box` 就是一行/一列，组的阅读方向由几何直接可判
     * - **ML Kit 输出的是它自己版式分析后的 block/line**，块序由它内部决定，
     *   我们的设置**插不进去** —— 必须自己拿 `boundingBox` 重排
     *
     * 排序分两级（对齐 [com.moe.starflow.manga.merge.MangaSpatialGrouping.sortByReadingOrder]）：
     * 1. **组间**：优先竖排的 block —— 竖排漫画的正文列先读，横排标题后读
     * 2. **组内行序**：竖排组的列按 [verticalDirection] 取 x 主键（RL 右→左 / LR 左→右），
     *    同列上→下；横排组恒上→下、行内左→右（不受设置影响）
     */
    suspend fun getPicText(
        language: String,
        bitmap: Bitmap,
        verticalDirection: TextDirection
    ): String = withContext(Dispatchers.Default) {
        suspendCancellableCoroutine { continuation ->
            val recognizer = getOrCreateRecognizer(language)
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        val resultText = mergeText(visionText, language, verticalDirection)
                        LogCollector.d(
                            TAG,
                            "getPicText(方向重排): bitmap=${bitmap.width}x${bitmap.height}, dir=$verticalDirection"
                        )
                        continuation.resume(resultText)
                    }
                    .addOnFailureListener { e ->
                        e.printStackTrace()
                        continuation.resumeWithException(e)
                    }
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    }

    /** 行在哪个 block 里的组内下标 → 用于组内重排后按序取文本。 */
    private data class LineRef(val blockIdx: Int, val lineIdx: Int, val box: android.graphics.Rect)

    private fun sep(language: String) = if (language == "en") " " else ""

    /**
     * 原版拼接：完全沿用 ML Kit 自己的块序/行序（**保持不变**，供不开方向适配的调用方使用）。
     */
    private fun mergeText(visionText: Text, language: String): String {
        if (visionText.textBlocks.isEmpty()) return ""
        return visionText.textBlocks.mapNotNull { block ->
            if (block.lines.isEmpty()) null
            else block.lines.mapNotNull { line ->
                line.text.trim().takeIf { it.isNotEmpty() }
            }.joinToString(separator = sep(language)).takeIf { it.isNotEmpty() }
        }.joinToString(separator = sep(language))
    }

    /**
     * 方向适配版拼接：**按几何自行决定阅读顺序**（见 [getPicText] 重载的说明）。
     *
     * 无有效 boundingBox 的行按原相对顺序兜底，不会被丢弃。
     */
    private fun mergeText(
        visionText: Text,
        language: String,
        verticalDirection: TextDirection
    ): String {
        val blocks = visionText.textBlocks
        if (blocks.isEmpty()) return ""

        // 摊平成「行 + 它属于哪个 block」，保留原下标用于兜底
        val refs = mutableListOf<LineRef>()
        for ((bi, block) in blocks.withIndex()) {
            for ((li, line) in block.lines.withIndex()) {
                val box = line.boundingBox
                if (box != null) refs.add(LineRef(bi, li, box))
            }
        }
        val valid = refs.filter { it.box.width() > 0 && it.box.height() > 0 }
        if (valid.isEmpty()) return mergeText(visionText, language)  // 无几何 → 退回原版

        val isRl = verticalDirection != TextDirection.VERTICAL_LR
        val sorted = valid.sortedWith { a, b ->
            val ra = a.box
            val rb = b.box
            val va = ra.height() > ra.width()
            val vb = rb.height() > rb.width()
            when {
                // ① 组间：竖排的 block 排在横排之前（竖排正文先读，横排标题后读）
                va != vb -> if (va) -1 else 1
                // ② 竖排组内：按列取 x 主键（RL 右→左 / LR 左→右），同列上→下
                va -> {
                    val xa = if (isRl) -ra.right else ra.left
                    val xb = if (isRl) -rb.right else rb.left
                    if (xa != xb) xa.compareTo(xb) else ra.top.compareTo(rb.top)
                }
                // ③ 横排组内：恒上→下、行内左→右（不受设置影响，避免把横排句子倒过来）
                else -> if (ra.top != rb.top) ra.top.compareTo(rb.top)
                else ra.left.compareTo(rb.left)
            }
        }

        // 按上面算出的阅读顺序取文本
        return sorted
            .mapNotNull { ref ->
                blocks.getOrNull(ref.blockIdx)?.lines?.getOrNull(ref.lineIdx)
                    ?.text?.trim()?.takeIf { it.isNotEmpty() }
            }
            .joinToString(separator = sep(language))
    }

    // 添加清理方法
    fun cleanup() {
        recognizers.values.forEach { it.close() }
        recognizers.clear()
    }
}