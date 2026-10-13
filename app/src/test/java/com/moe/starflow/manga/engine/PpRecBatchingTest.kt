package com.moe.starflow.manga.engine

import ai.onnxruntime.OrtException
import com.moe.starflow.manga.types.RecResult
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PpRecBatchingTest {
    private fun output(indices: List<Int>, seq: Int = 209, classes: Int = 18385) =
        PpRecBatching.BatchOutput(seq, classes, indices.map { RecResult("line-$it", it / 100f) })

    @Test fun normalizationPadsInsteadOfSquashingLongText() {
        assertEquals(1696, PpRecBatching.normalizedWidth(1672, 48))
        assertEquals(32, PpRecBatching.normalizedWidth(1, 200))
        assertEquals(96, PpRecBatching.normalizedWidth(100, 60))
    }

    @Test fun largeOutputIsAcceptedWithoutAnApplicationBudget() {
        val calls = mutableListOf<Int>()
        val results = PpRecBatching.recognize(List(21) { 1696 }, 16) { ids, _ ->
            calls += ids.size
            // A [16,209,18385] output is ~234 MiB. Shape alone must not reject it.
            output(ids)
        }
        assertEquals(listOf(16, 5), calls)
        assertEquals((0..20).map { "line-$it" }, results.map { it.text })
    }

    @Test fun longSingleLineIsNotRejectedByItsOutputShape() {
        val results = PpRecBatching.recognize(listOf(4096), 16) { ids, _ -> output(ids, 1024) }
        assertEquals(listOf("line-0"), results.map { it.text })
    }

    @Test fun configuredBatchSizeIsUsedUntilAnActualFailure() {
        val calls = mutableListOf<Int>()
        PpRecBatching.recognize(List(12) { 320 }, 6) { ids, _ ->
            calls += ids.size
            output(ids, 40, 18710)
        }
        assertEquals(listOf(6, 6), calls)
    }

    @Test fun widthSortingRestoresTextsAndScoresToOriginalIndices() {
        val widths = listOf(320, 64, 320, 128, 64)
        val calls = mutableListOf<Pair<Int, List<Int>>>()
        val results = PpRecBatching.recognize(widths, 16) { ids, width ->
            calls += width to ids.toList()
            assertTrue(ids.all { widths[it] == width })
            output(ids, 10, 100)
        }
        assertEquals(64, calls.first().first)
        assertEquals(widths.indices.map { "line-$it" }, results.map { it.text })
        assertEquals(widths.indices.map { it / 100f }, results.map { it.score })
    }

    @Test fun allocationFailureIsReportedBeforeRetryAndOnlyFailedInputsAreRetried() {
        val calls = mutableListOf<List<Int>>()
        val failures = mutableListOf<Triple<OcrMemoryException, Int, Int>>()
        val original = OutOfMemoryError("simulated allocation failure")
        val results = PpRecBatching.recognize(List(8) { 320 }, 6,
            onAllocationFailure = { error, width, count -> failures += Triple(error, width, count) },
        ) { ids, _ ->
            calls += ids.toList()
            if (ids.size > 2) throw original
            output(ids, 10, 100)
        }
        assertEquals(8, results.size)
        assertEquals(listOf(6, 3, 1, 1, 1, 1, 1, 1, 1, 1), calls.map { it.size })
        assertEquals(listOf(6, 3), failures.map { it.third })
        assertTrue(failures.all { it.second == 320 && it.first.cause === original })
        assertEquals((0..7).map { "line-$it" }, results.map { it.text })
    }

    @Test fun singleAllocationFailureIsReportedAndReturnedAsARecoverableException() {
        val original = OutOfMemoryError("single")
        var attempts = 0
        val failures = mutableListOf<OcrMemoryException>()
        val error = assertThrows(OcrMemoryException::class.java) {
            PpRecBatching.recognize(listOf(320), 6,
                onAllocationFailure = { e, _, _ -> failures += e },
            ) { _, _ -> attempts++; throw original }
        }
        assertEquals(1, attempts)
        assertEquals(listOf(error), failures)
        assertSame(original, error.cause)
    }

    @Test fun nativeAllocatorFailureIsRecoverableButOtherOrtErrorsPropagate() {
        val allocation = OrtException("BFCArena: Failed to allocate memory for requested buffer of size 245917760")
        val memory = assertThrows(OcrMemoryException::class.java) {
            PpRecBatching.guard("inference") { throw allocation }
        }
        assertSame(allocation, memory.cause)
        assertTrue(memory.diagnostic.contains("245917760"))
        val badModel = OrtException("Invalid input dimensions")
        assertSame(badModel, assertThrows(OrtException::class.java) {
            PpRecBatching.guard { throw badModel }
        })
    }

    @Test fun cancellationPropagatesWithoutRetryOrMemoryError() {
        val cancelled = CancellationException("stop")
        var calls = 0
        var loggedFailures = 0
        val actual = assertThrows(CancellationException::class.java) {
            PpRecBatching.recognize(listOf(320, 320), 6,
                onAllocationFailure = { _, _, _ -> loggedFailures++ },
            ) { _, _ -> calls++; throw cancelled }
        }
        assertSame(cancelled, actual)
        assertEquals(1, calls)
        assertEquals(0, loggedFailures)
    }

    @Test fun dimensionsUseTheRuntimeIntegerRangeWithoutAnImageSizeThreshold() {
        assertEquals(4096, PpRecBatching.normalizedWidth(4096, 48))
        assertEquals(4096L * 16 * 3 * 48 * 4, PpRecBatching.inputBytes(4096, 16))
        assertThrows(ArithmeticException::class.java) {
            PpRecBatching.normalizedWidth(Int.MAX_VALUE, 1)
        }
    }
}
