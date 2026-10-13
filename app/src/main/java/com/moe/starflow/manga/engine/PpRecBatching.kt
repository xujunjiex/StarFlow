package com.moe.starflow.manga.engine

import ai.onnxruntime.OrtException
import com.moe.starflow.manga.types.RecResult

/** An actual OCR allocation failure; callers must not restart the entire OCR route. */
internal class OcrMemoryException(
    val diagnostic: String,
    cause: Throwable? = null,
    message: String = "OCR memory allocation failed. The error has been recorded in the log.",
) : Exception(message, cause) {
    var logged = false
}

/** Group equal-width inputs without imposing an application memory or image-size budget. */
internal object PpRecBatching {
    const val HEIGHT = 48

    data class BatchOutput(val seqLen: Int, val numClasses: Int, val results: List<RecResult>)

    fun normalizedWidth(width: Int, height: Int): Int {
        require(width > 0 && height > 0)
        val natural = (width.toLong() * HEIGHT + height - 1) / height
        return Math.toIntExact(((natural + 31) / 32) * 32).coerceAtLeast(32)
    }

    fun inputBytes(width: Int, batch: Int): Long =
        Math.multiplyExact(Math.multiplyExact(width.toLong(), batch.toLong()), 3L * HEIGHT * 4)

    // ORT reports allocator failures as OrtException, not Java OutOfMemoryError.
    // Do not classify unrelated inference/model errors as memory failures.
    fun isAllocationFailure(message: String?): Boolean {
        val text = message.orEmpty().lowercase()
        return listOf("out of memory", "not enough memory", "failed to allocate memory",
            "cannot allocate memory", "memory allocation failed", "std::bad_alloc", "bad allocation")
            .any { it in text }
    }

    internal inline fun <T> guard(stage: String = "recognition", block: () -> T): T = guard({ stage }, block)

    internal inline fun <T> guard(stage: () -> String, block: () -> T): T = try {
        block()
    } catch (e: OutOfMemoryError) {
        throw OcrMemoryException("${stage()}: memory allocation failed", e)
    } catch (e: OrtException) {
        if (!isAllocationFailure(e.message)) throw e
        throw OcrMemoryException("${stage()}: ORT memory allocation failed: ${e.message}", e)
    }

    /** Retry smaller batches only after a real allocator failure; always preserve input order. */
    fun recognize(
        widths: List<Int>,
        maxBatch: Int,
        onAllocationFailure: (OcrMemoryException, Int, Int) -> Unit = { _, _, _ -> },
        runBatch: (indices: List<Int>, width: Int) -> BatchOutput,
    ): List<RecResult> {
        if (widths.isEmpty()) return emptyList()
        require(maxBatch > 0 && widths.all { it > 0 })
        val restored = arrayOfNulls<RecResult>(widths.size)
        for ((width, indices) in widths.indices.groupBy { widths[it] }.toSortedMap()) {
            var cursor = 0
            var groupMaxBatch = maxBatch
            while (cursor < indices.size) {
                var count = minOf(groupMaxBatch, indices.size - cursor)
                while (true) {
                    val batchIndices = indices.subList(cursor, cursor + count)
                    try {
                        val output = guard("recognition width=$width batch=$count") { runBatch(batchIndices, width) }
                        require(output.results.size == count && output.seqLen > 0 && output.numClasses > 0)
                        batchIndices.forEachIndexed { i, index -> restored[index] = output.results[i] }
                        cursor += count
                        break
                    } catch (e: OcrMemoryException) {
                        onAllocationFailure(e, width, count)
                        if (count == 1) throw e
                        count = (count / 2).coerceAtLeast(1)
                        groupMaxBatch = count
                    }
                }
            }
        }
        return restored.map { requireNotNull(it) }
    }
}
