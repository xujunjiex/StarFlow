package com.moe.starflow.mangaimport.reader

import com.moe.starflow.mangaimport.data.MangaChapter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 底部进度条的坐标域（用户口径 2026-10：多章节漫画的进度条要按**当前章节**移动跳转，
 * 而不是整本）。
 *
 * 这条映射错了只表现为「拖到一半跳到别的章 / 绿条落在别的页上」，没有测试只能靠肉眼发现；
 * 所以坐标换算（页号 ↔ 章内页号、整本页集合 → 章内页集合、章表过期时回退整本）在这里守。
 */
@RunWith(RobolectricTestRunner::class)
class ReaderProgressScopeTest {

    /** 整本 100 页，第 2 章 = 全书 40..69 页（30 页）。 */
    private val ch2 = MangaChapter(number = 2, title = "ch2", startPage = 40, pageCount = 30)

    @Test
    fun multiChapterScopeUsesCurrentChapter() {
        val scope = ReaderProgressScope.of(ch2, currentPage = 45, totalPages = 100)
        assertEquals(40, scope.startPage)
        assertEquals(30, scope.pageCount)
        // 进度条页号 = 章内第几页；拖到章内第 5 页 = 全书第 45 页
        assertEquals(5, scope.localPage(45))
        assertEquals(45, scope.globalPage(5))
    }

    @Test
    fun marksAreScopedToTheCurrentChapter() {
        val scope = ReaderProgressScope.of(ch2, currentPage = 45, totalPages = 100)
        // 全书 {0, 41, 42, 70} → 只有 41/42 在本章，落到章内 1/2；0 与 70 属于别的章，必须丢掉
        assertEquals(setOf(1, 2), scope.localPages(setOf(0, 41, 42, 70)))
        assertEquals(emptySet<Int>(), scope.localPages(emptySet()))
    }

    @Test
    fun singleChapterBookIsTheWholeBook() {
        val only = MangaChapter(number = 1, title = "", startPage = 0, pageCount = 100)
        val scope = ReaderProgressScope.of(only, currentPage = 99, totalPages = 100)
        assertEquals(0, scope.startPage)
        assertEquals(100, scope.pageCount)
        assertEquals(99, scope.localPage(99))
        assertEquals(99, scope.globalPage(99))
    }

    @Test
    fun missingOrStaleChapterFallsBackToWholeBook() {
        // 没有章表（旧书）
        val noChapters = ReaderProgressScope.of(null, currentPage = 12, totalPages = 50)
        assertEquals(0, noChapters.startPage)
        assertEquals(50, noChapters.pageCount)
        assertEquals(12, noChapters.localPage(12))
        // 章表与当前页对不上（外部改动了来源）→ 宁可整本，也不能把拇指指到别的章上
        val stale = ReaderProgressScope.of(ch2, currentPage = 3, totalPages = 100)
        assertEquals(0, stale.startPage)
        assertEquals(100, stale.pageCount)
        assertEquals(3, stale.localPage(3))
    }

    @Test
    fun seekRoundTripsWithinTheChapter() {
        val scope = ReaderProgressScope.of(ch2, currentPage = 40, totalPages = 100)
        // 拖到进度条最右 = 本章最后一页（69），不会滑进下一章
        assertEquals(69, scope.globalPage(scope.pageCount - 1))
        assertEquals(scope.pageCount - 1, scope.localPage(69))
    }
}
