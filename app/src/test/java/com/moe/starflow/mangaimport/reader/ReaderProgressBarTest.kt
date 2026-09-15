package com.moe.starflow.mangaimport.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 进度条绿条区间计算（纯函数）。
 *
 * 这些都是"错了只表现为绿条画歪"的手写几何分支 —— 没有测试时只能靠肉眼发现。
 */
@RunWith(RobolectricTestRunner::class)
class ReaderProgressBarTest {

    @Test
    fun emptySetYieldsNoRuns() {
        assertTrue(ReaderProgressBar.computeTranslatedRuns(emptySet(), 10).isEmpty())
    }

    @Test
    fun singlePageYieldsSingleRun() {
        assertEquals(listOf(3..3), ReaderProgressBar.computeTranslatedRuns(setOf(3), 10))
    }

    @Test
    fun consecutivePagesCollapseIntoOneRun() {
        assertEquals(listOf(1..4), ReaderProgressBar.computeTranslatedRuns(setOf(1, 2, 3, 4), 10))
    }

    @Test
    fun disjointRunsAreSeparated() {
        // 跳翻的典型形状：两段互不相邻的已翻译区。用 20 页的书，避免索引越界被过滤
        assertEquals(
            listOf(1..3, 7..7, 9..10),
            ReaderProgressBar.computeTranslatedRuns(setOf(1, 2, 3, 7, 9, 10), 20)
        )
    }

    @Test
    fun unorderedInputIsSorted() {
        assertEquals(listOf(0..2), ReaderProgressBar.computeTranslatedRuns(setOf(2, 0, 1), 10))
    }

    @Test
    fun outOfRangePagesAreFiltered() {
        // 99 越界（pageCount=10）不得画出界
        assertEquals(listOf(1..3), ReaderProgressBar.computeTranslatedRuns(setOf(1, 2, 3, 99), 10))
        assertTrue(ReaderProgressBar.computeTranslatedRuns(setOf(99), 10).isEmpty())
    }

    @Test
    fun zeroPageCountIsSafe() {
        assertTrue(ReaderProgressBar.computeTranslatedRuns(setOf(0), 0).isEmpty())
    }

    @Test
    fun singlePageBookIsSafe() {
        assertEquals(listOf(0..0), ReaderProgressBar.computeTranslatedRuns(setOf(0), 1))
    }
}
