package com.moe.starflow.mangaimport.reader

import com.moe.starflow.mangaimport.data.MangaChapter

/**
 * 底部进度条的**坐标域**（章内页号）。
 *
 * 多章节漫画的进度条只表达「当前章读到哪 / 拖到章内哪一页」，与顶部胶囊的
 * 「章内第几页 / 本章共几页」是同一个坐标系（小说阅读器早就是这个取舍：全书进度由目录弹窗承载）。
 * 整本口径下几百页挤在一条约 900px 的线上，一像素代表好几页，拖拽根本落不准。
 *
 * 章表与当前页对不上时（旧书、章表过期）退回整本 —— 宁可按整条线走，也不能把拇指指到别的章上。
 */
internal data class ReaderProgressScope(val startPage: Int, val pageCount: Int) {

    /** 全书页号 → 进度条页号。 */
    fun localPage(page: Int): Int = page - startPage

    /** 进度条页号 → 全书页号（拖拽/点击寻页用）。 */
    fun globalPage(local: Int): Int = startPage + local

    /** 全书的「已翻译 / 已超分」页集合 → 章内集合（章外的页丢掉）。 */
    fun localPages(pages: Set<Int>): Set<Int> {
        if (pages.isEmpty()) return emptySet()
        val end = startPage + pageCount
        return pages.asSequence()
            .filter { it in startPage until end }
            .map { it - startPage }
            .toSet()
    }

    companion object {
        /**
         * 当前页所在的章 → 进度条坐标域；[chapter] 为 null 或不含当前页时退回整本口径。
         *
         * @param totalPages 整本页数（章表不可用时的兜底页数）
         */
        fun of(chapter: MangaChapter?, currentPage: Int, totalPages: Int): ReaderProgressScope {
            val whole = ReaderProgressScope(0, totalPages.coerceAtLeast(1))
            if (chapter == null || chapter.pageCount <= 0 || !chapter.containsPage(currentPage)) return whole
            return ReaderProgressScope(chapter.startPage, chapter.pageCount)
        }
    }
}
