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
     * ⚠️ **本重载曾经存在，已删除**（2026-09-18 用户明确要求）。
     *
     * 它按 `line.boundingBox` 自行重排 ML Kit 的读序，试图让 `Manga/Game_Text_Direction`
     * 对 Kit 生效。**结论是不成立**：ML Kit 的 `block` 是它自己版式分析的产物
     * （官方文档：*"a contiguous set of text lines, such as a paragraph or a **column**"*），
     * 一个竖排整列常常就是**一个 block**，块内行序封在它手里 —— 我们只能重排**块之间**，
     * 块内部无从干预。表现为「设置时灵时不灵」，比不支持更难解释。
     *
     * **方向设置只适配 PP-OCRv5/v6**（行列级识别器，每个 box = 一行/一列，几何直接可判）。
     * ML Kit / RT-DETR + manga-ocr 一律不接，**勿再加回来**。
     */

    private fun sep(language: String) = if (language == "en") " " else ""

    /**
     * 拼接：完全沿用 ML Kit 自己的块序/行序 —— **刻意不做任何重排**。
     * 见上方说明：Kit 的读序是它版式分析的一部分，重排只会破坏它。
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

    // 添加清理方法
    fun cleanup() {
        recognizers.values.forEach { it.close() }
        recognizers.clear()
    }
}