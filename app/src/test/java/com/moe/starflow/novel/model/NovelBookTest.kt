package com.moe.starflow.novel.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * `NovelBook` 手写了 `equals`/`hashCode`（data class 带 `ByteArray` 时默认按引用比较）。
 * 这里锁住 [NovelBook.coverBytes] 的内容语义 —— 删掉手写 override 本测试即红。
 */
class NovelBookTest {

    private fun meta(i: Int) = NovelChapterMeta(index = i, title = "第 $i 章", locator = "ch$i.html")

    private val chapters = listOf(meta(0), meta(1))

    private fun book(
        title: String = "书",
        author: String? = "作者",
        chapterList: List<NovelChapterMeta> = chapters,
        cover: ByteArray? = null,
    ) = NovelBook(title = title, author = author, chapters = chapterList, coverBytes = cover)

    @Test
    fun `内容全等且 coverBytes 非空 相等`() {
        val a = book(cover = byteArrayOf(1, 2, 3))
        val b = book(cover = byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `内容相同但 coverBytes 不同 不相等`() {
        val a = book(cover = byteArrayOf(1, 2, 3))
        val b = book(cover = byteArrayOf(4, 5, 6))
        assertNotEquals(a, b)
    }

    @Test
    fun `两本书都没有封面 相等`() {
        val a = book(cover = null)
        val b = book(cover = null)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `一个是 null 一个是空数组 不相等`() {
        val a = book(cover = null)
        val b = book(cover = ByteArray(0))
        assertNotEquals(a, b)
    }

    @Test
    fun `书名 作者 章节不同 都不相等`() {
        val base = book(title = "书", author = "作者")
        assertNotEquals(base, book(title = "另一本", author = "作者"))
        assertNotEquals(base, book(title = "书", author = "另一人"))
        assertNotEquals(base, book(title = "书", author = "作者", chapterList = listOf(meta(0))))
    }
}
