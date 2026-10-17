package com.moe.starflow.manga.engine

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** ML Kit cannot abort a Task. Each request owns its input until that Task completes. */
object MlKitRecognition {
    suspend fun recognize(bitmap: Bitmap, language: String): Text = coroutineScope {
        OcrExecution.checkCancellation()
        val stopped = OcrExecution.cancellationSignal()
        withTimeout(120_000L) {
            val owned = try { bitmap.copy(Bitmap.Config.ARGB_8888, false) }
                catch (e: OutOfMemoryError) { throw OcrMemoryException("ML Kit input allocation failed", e) }
            checkNotNull(owned) { "ML Kit input copy failed" }
            val recognizer = try { when (language) {
                "zh", "zh-CN", "zh-TW" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            } } catch (error: Throwable) {
                owned.recycle()
                throw error
            }
            var watcher: Job? = null
            try {
                suspendCancellableCoroutine { cont ->
                    watcher = launch {
                        while (cont.isActive) {
                            if (stopped()) cont.cancel()
                            delay(50)
                        }
                    }
                    try {
                        recognizer.process(InputImage.fromBitmap(owned, 0))
                            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                            .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
                            .addOnCompleteListener { try { recognizer.close() } finally { owned.recycle() } }
                    } catch (e: Exception) {
                        recognizer.close()
                        owned.recycle()
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            } finally { watcher?.cancel() }
        }
    }
}
