package com.moe.starflow.manga.engine
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.state.*
import com.moe.starflow.manga.render.*
import com.moe.starflow.manga.merge.*
import com.moe.starflow.manga.*

import com.moe.starflow.manga.config.*
import android.graphics.Bitmap
import android.util.Log
import com.moe.starflow.manga.types.TextBlockInfo
import com.moe.starflow.utils.LogCollector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OCRBridge {

    private const val TAG = "OCRBridge"

    suspend fun recognizeWithLocation(language: String, bitmap: Bitmap): List<TextBlockInfo> {
        val result = MlKitRecognition.recognize(bitmap, language)
        return result.textBlocks.map { block ->
            TextBlockInfo(block.text, block.boundingBox, block.cornerPoints)
        }
    }

    suspend fun recognizeText(language: String, bitmap: Bitmap): String {
        LogCollector.d(TAG, "recognizeText: bitmap=${bitmap.width}x${bitmap.height}")
        return OCRTextRecognizer.getPicText(language, bitmap)
    }

}
