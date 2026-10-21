package com.moe.starflow.manga.engine

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PpRegionSizeFilterTest {
    private val enabled = PpRegionSizeFilter.Config(enabled = true)

    private fun box(size: Double, width: Double = 1000.0, angle: Double = 0.0, scale: Double = 1.0): FloatArray {
        val points = listOf(0.0 to 0.0, width to 0.0, width to size, 0.0 to size)
        return points.flatMap { (x, y) ->
            listOf(((x * cos(angle) - y * sin(angle)) * scale).toFloat(),
                ((x * sin(angle) + y * cos(angle)) * scale).toFloat())
        }.toFloatArray()
    }

    @Test
    fun wholePageRejectsOnlyExtremeSizesAndRetainsOriginalIndices() {
        val boxes = listOf(8.0, 28.0, 29.0, 30.0, 30.0, 31.0, 32.0, 120.0).map { box(it) }
        val before = boxes.map { it.copyOf() }
        val result = PpRegionSizeFilter.evaluate(boxes, enabled)
        assertEquals((1..6).toList(), result.keptIndices)
        assertEquals(listOf(0, 7), result.discarded.map { it.index })
        assertEquals(listOf(PpRegionSizeFilter.Reason.TOO_SMALL, PpRegionSizeFilter.Reason.TOO_LARGE),
            result.discarded.map { it.reason })
        assertEquals(15.0, result.bounds!!.lower, 0.001)
        assertEquals(60.0, result.bounds!!.upper, 0.001)
        boxes.indices.forEach { assertArrayEquals(before[it], boxes[it], 0f) }
    }

    @Test
    fun defaultAndNonMangaCallsKeepEverything() {
        val boxes = listOf(box(1.0), box(30.0), floatArrayOf(Float.NaN))
        for (config in listOf(null, PpRegionSizeFilter.Config())) {
            val result = PpRegionSizeFilter.evaluate(boxes, config)
            assertEquals(boxes.indices.toList(), result.keptIndices)
            assertTrue(result.discarded.isEmpty())
            assertNull(result.bounds)
        }
    }

    @Test
    fun fewSamplesDoNotGuessWhichTextSizeIsWrong() {
        val result = PpRegionSizeFilter.evaluate(listOf(1.0, 30.0, 30.0, 600.0).map { box(it) }, enabled)
        assertEquals(listOf(0, 1, 2, 3), result.keptIndices)
        assertTrue(result.discarded.isEmpty())
        assertNull(result.bounds)
        assertTrue(PpRegionSizeFilter.evaluate(emptyList(), enabled).keptIndices.isEmpty())
    }

    @Test
    fun separateTolerancesWorkEvenWhenMadIsZero() {
        val boxes = listOf(15.0, 30.0, 30.0, 30.0, 30.0, 30.0, 60.0).map { box(it) }
        assertTrue(PpRegionSizeFilter.evaluate(boxes, enabled).discarded.isEmpty())
        val smallOnly = PpRegionSizeFilter.evaluate(boxes, enabled.copy(smallTolerance = 1.0, largeTolerance = 8.0))
        val largeOnly = PpRegionSizeFilter.evaluate(boxes, enabled.copy(smallTolerance = 8.0, largeTolerance = 1.0))
        assertEquals(listOf(0), smallOnly.discarded.map { it.index })
        assertEquals(listOf(6), largeOnly.discarded.map { it.index })
    }

    @Test
    fun uniformResizeRotationAndInputOrderDoNotChangeClassification() {
        val sizes = listOf(8.0, 28.0, 29.0, 30.0, 30.0, 31.0, 32.0, 120.0)
        val plain = PpRegionSizeFilter.evaluate(sizes.map { box(it) }, enabled)
        val transformed = PpRegionSizeFilter.evaluate(sizes.map { box(it, angle = 0.6, scale = 3.0) }, enabled)
        val reversed = PpRegionSizeFilter.evaluate(sizes.reversed().map { box(it) }, enabled)
        assertEquals(plain.keptIndices, transformed.keptIndices)
        assertEquals(plain.bounds!!.lower * 3, transformed.bounds!!.lower, 0.001)
        assertEquals(plain.keptIndices.map { sizes[it] }.sorted(), reversed.keptIndices.map { sizes.reversed()[it] }.sorted())
    }

    @Test
    fun longSentencesAndVerticalColumnsAreNotLargerText() {
        val boxes = listOf(60.0, 100.0, 300.0, 800.0, 2000.0, 4000.0).mapIndexed { i, width ->
            box(30.0, width, angle = if (i % 2 == 0) Math.PI / 2 else 0.0)
        }
        assertTrue(PpRegionSizeFilter.evaluate(boxes, enabled).discarded.isEmpty())
    }

    @Test
    fun legitimateMixedSizesExpandTheStatisticalRange() {
        val boxes = List(5) { box(30.0) } + List(5) { box(90.0) }
        assertTrue(PpRegionSizeFilter.evaluate(boxes, enabled).discarded.isEmpty())
    }

    @Test
    fun invalidGeometryIsExcludedFromStatisticsWithoutCorruptingOtherCandidates() {
        val boxes = List(5) { box(30.0) } + listOf(floatArrayOf(1f), box(0.0), box(30.0).apply { this[0] = Float.NaN })
        val result = PpRegionSizeFilter.evaluate(boxes, enabled)
        assertEquals((0..4).toList(), result.keptIndices)
        assertEquals(listOf(5, 6, 7), result.discarded.map { it.index })
        assertTrue(result.discarded.all { it.reason == PpRegionSizeFilter.Reason.INVALID_GEOMETRY })
        assertEquals(30.0, result.bounds!!.median, 0.001)
    }
}
