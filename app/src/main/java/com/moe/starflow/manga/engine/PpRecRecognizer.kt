package com.moe.starflow.manga.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.moe.starflow.R
import com.moe.starflow.manga.types.RecResult
import com.moe.starflow.utils.LogCollector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.WeakHashMap
import kotlin.math.ceil

/** PP recognition using reusable, caller-owned ORT output buffers instead of Java tensor copies. */
internal object PpRecRecognizer {
    internal data class OutputShape(val name: String, val seqLen: Int, val classes: Int)
    // Shape depends on the model session and normalized width. Never retain released sessions.
    private val shapes = WeakHashMap<OrtSession, MutableMap<Int, OutputShape>>()

    internal inline fun <T> withMemoryGuard(context: Context, tag: String, stage: String = "OCR", block: () -> T): T = try {
        PpRecBatching.guard(stage) { block() }
    } catch (e: OcrMemoryException) {
        if (!e.logged) logAllocationFailure(tag, e)
        val message = context.getString(R.string.error_ocr_memory)
        if (e.message == message) throw e
        throw OcrMemoryException(e.diagnostic, e, message).also { it.logged = true }
    }

    fun logAllocationFailure(tag: String, error: OcrMemoryException) {
        val runtime = Runtime.getRuntime()
        LogCollector.e(tag, "${error.diagnostic}; heapMax=${runtime.maxMemory()} " +
            "heapAllocated=${runtime.totalMemory()} heapFree=${runtime.freeMemory()}", error)
        error.logged = true
    }

    fun recognize(
        env: OrtEnvironment,
        session: OrtSession,
        images: List<Bitmap>,
        dict: List<String>,
        maxBatch: Int,
        tag: String,
    ): List<RecResult> {
        val widths = images.map { PpRecBatching.normalizedWidth(it.width, it.height) }
        var reusableInput: FloatBuffer? = null
        var reusableOutput: FloatBuffer? = null
        return PpRecBatching.recognize(widths, maxBatch,
            onAllocationFailure = { error, _, _ ->
                // Called for recovered retries too, so the original allocator error is not lost.
                reusableInput = null
                reusableOutput = null
                logAllocationFailure(tag, error)
            },
        ) { indices, width ->
            var stage = "input allocation bytes=${PpRecBatching.inputBytes(width, indices.size)}"
            PpRecBatching.guard({ "$stage width=$width batch=${indices.size}" }) {
                val bytes = PpRecBatching.inputBytes(width, indices.size)
                val elements = Math.toIntExact(bytes / 4)
                val buffer = reusableInput?.takeIf { it.capacity() >= elements } ?: directFloats(elements)
                    .also { reusableInput = it }
                buffer.clear()
                buffer.limit(elements)
                stage = "input preprocessing"
                val pixels = IntArray(Math.multiplyExact(width, PpRecBatching.HEIGHT))
                indices.forEach { fillInput(images[it], width, buffer, pixels) }
                buffer.flip()
                stage = "input tensor creation"
                OnnxTensor.createTensor(env, buffer, longArrayOf(indices.size.toLong(), 3, 48, width.toLong())).use { input ->
                    stage = "output shape discovery"
                    val shape = synchronized(shapes) { shapes[session]?.get(width) } ?: run {
                        // Discover dynamic dimensions once, then close the probe before allocating
                        // the pinned output. Never call getFloatBuffer on the probe (it copies logits).
                        val probeBuffer = buffer.duplicate().apply { limit(Math.toIntExact(PpRecBatching.inputBytes(width, 1) / 4)) }
                        OnnxTensor.createTensor(env, probeBuffer, longArrayOf(1, 3, 48, width.toLong())).use { probe ->
                            session.run(mapOf("x" to probe)).use { result ->
                                session.outputNames.asSequence().mapNotNull { name ->
                                    (result.get(name).orElse(null) as? OnnxTensor)?.let { output ->
                                        val dims = output.info.shape
                                        require(dims.size == 3 && dims[0] == 1L) {
                                            "Invalid PP output shape: ${dims.contentToString()}"
                                        }
                                        OutputShape(name, Math.toIntExact(dims[1]), Math.toIntExact(dims[2]))
                                            .also { require(it.seqLen > 0 && it.classes > 0) }
                                    }
                                }.firstOrNull() ?: error("PP recognition returned no tensor")
                            }
                        }.also { synchronized(shapes) { shapes.getOrPut(session) { mutableMapOf() }[width] = it } }
                    }
                    val outputElements = Math.toIntExact(Math.multiplyExact(
                        indices.size.toLong(), Math.multiplyExact(shape.seqLen.toLong(), shape.classes.toLong())))
                    stage = "output allocation shape=[${indices.size},${shape.seqLen},${shape.classes}] bytes=${outputElements.toLong() * 4}"
                    val predictions = reusableOutput?.takeIf { it.capacity() >= outputElements } ?: directFloats(outputElements)
                        .also { reusableOutput = it }
                    predictions.clear()
                    predictions.limit(outputElements)
                    OnnxTensor.createTensor(env, predictions,
                        longArrayOf(indices.size.toLong(), shape.seqLen.toLong(), shape.classes.toLong())).use { output ->
                        stage = "recognition inference shape=[${indices.size},${shape.seqLen},${shape.classes}] outputBytes=${outputElements.toLong() * 4}"
                        session.run(mapOf("x" to input), mapOf(shape.name to output)).use {
                            // ORT writes directly into predictions. Result does not own pinned output;
                            // the surrounding use closes its tensor exactly once, after decoding.
                            stage = "CTC decoding"
                            PpRecBatching.BatchOutput(shape.seqLen, shape.classes, indices.indices.map {
                                PpRecCtcDecoder.decode(predictions, it, shape.seqLen, shape.classes, dict)
                            })
                        }
                    }
                }
            }
        }
    }

    private fun directFloats(elements: Int): FloatBuffer = ByteBuffer
        .allocateDirect(Math.toIntExact(elements.toLong() * 4)).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun fillInput(bitmap: Bitmap, paddedWidth: Int, buffer: FloatBuffer, pixels: IntArray) {
        val width = ceil(48.0 * bitmap.width / bitmap.height).toInt().coerceIn(1, paddedWidth)
        val resized = Bitmap.createScaledBitmap(bitmap, width, 48, true)
        try {
            resized.getPixels(pixels, 0, width, 0, 0, width, 48)
            for (channel in 0 until 3) {
                val shift = 16 - channel * 8
                for (y in 0 until 48) {
                    for (x in 0 until paddedWidth) {
                        val value = if (x < width) {
                            val pixel = ((pixels[y * width + x] shr shift) and 255) / 255f
                            (pixel - 0.5f) / 0.5f
                        } else 0f
                        buffer.put(value)
                    }
                }
            }
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    }
}
