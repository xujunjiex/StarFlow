package com.moe.starflow.mangaimport.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 章节切分 / 页序 / 标签的守卫。
 *
 * 这几条都是「导入侧与阅读侧必须同源」的硬要求：切分器同时决定 [MangaChapterSplitter.Result.keys]
 * （权威页序）与章区间，两边算不一样 → 点第 3 章跳到别人的页、翻译记录挂错章。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MangaChapterSplitterTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    // ===== 单章（没有子文件夹） =====

    @Test
    fun noSubFolder_isSingleChapter() {
        val r = MangaChapterSplitter.split(listOf("page2.jpg", "page10.jpg", "page1.jpg"))
        assertEquals(listOf("page1.jpg", "page2.jpg", "page10.jpg"), r.keys)   // 自然排序
        assertEquals(1, r.chapters.size)
        val c = r.chapters.single()
        assertEquals(1, c.number)
        assertEquals("", c.title)
        assertEquals(0, c.startPage)
        assertEquals(3, c.pageCount)
    }

    // ===== 子文件夹分章 =====

    @Test
    fun subFolders_becomeChaptersInNaturalOrder() {
        val r = MangaChapterSplitter.split(
            listOf("ch2/001.jpg", "ch1/002.jpg", "ch1/001.jpg", "ch10/001.jpg")
        )
        assertEquals(listOf("ch1", "ch2", "ch10"), r.chapters.map { it.title })
        assertEquals(listOf(1, 2, 3), r.chapters.map { it.number })
        assertEquals(listOf(0, 2, 3), r.chapters.map { it.startPage })
        assertEquals(listOf(2, 1, 1), r.chapters.map { it.pageCount })
        // 页序按章分组，章内自然排序
        assertEquals(
            listOf("ch1/001.jpg", "ch1/002.jpg", "ch2/001.jpg", "ch10/001.jpg"),
            r.keys
        )
    }

    // ===== 最外层散图 = 第0章，且排在所有章之前 =====

    @Test
    fun rootLooseImages_becomeChapterZeroAndComeFirst() {
        // ⚠️ 这里同时锁死「页序必须重排」：按完整路径自然排序时 "z.jpg" 会排到 "ch1/…" 后面，
        // 章区间就不再连续（第0章会裂成两段）
        val r = MangaChapterSplitter.split(listOf("ch1/001.jpg", "z.jpg", "a.jpg"))
        assertEquals(listOf(0, 1), r.chapters.map { it.number })
        assertEquals(listOf("", "ch1"), r.chapters.map { it.title })
        assertEquals(0, r.chapters[0].startPage)
        assertEquals(2, r.chapters[0].pageCount)
        assertEquals(listOf("a.jpg", "z.jpg", "ch1/001.jpg"), r.keys)
    }

    // ===== 公共最外层目录要剥掉（zip 常多包一层书名目录） =====

    @Test
    fun commonWrapperDirectory_isStripped() {
        val r = MangaChapterSplitter.split(
            listOf("BookName/ch1/001.jpg", "BookName/ch2/001.jpg")
        )
        assertEquals(listOf("ch1", "ch2"), r.chapters.map { it.title })
        assertEquals(2, r.chapters.size)
    }

    @Test
    fun singleWrapperLeavingNoDirs_isOneUntitledChapter() {
        // ch1/a.jpg + ch1/b.jpg：剥掉唯一的外层后没有目录了 → 单章（标题为空 → 显示「第1章」）
        val r = MangaChapterSplitter.split(listOf("ch1/a.jpg", "ch1/b.jpg"))
        assertEquals(1, r.chapters.size)
        assertEquals("", r.chapters.single().title)
        assertEquals(1, r.chapters.single().number)
    }

    /** 剥掉外层后按**第一层子目录**分章（用户选定的口径）。 */
    @Test
    fun nestedDirs_splitByFirstLevelAfterWrapper() {
        val r = MangaChapterSplitter.split(
            listOf("ch1/part1/001.jpg", "ch1/part2/001.jpg")
        )
        assertEquals(listOf("part1", "part2"), r.chapters.map { it.title })
    }

    /** 只要有一页直接在根上，就不存在「公共外层」，不许剥。 */
    @Test
    fun rootFileBlocksWrapperStripping() {
        val r = MangaChapterSplitter.split(listOf("a.jpg", "ch1/001.jpg", "ch1/002.jpg"))
        assertEquals(listOf("", "ch1"), r.chapters.map { it.title })
        assertEquals(0, r.chapters[0].number)
    }

    @Test
    fun emptyInput_yieldsNothing() {
        val r = MangaChapterSplitter.split(emptyList())
        assertTrue(r.keys.isEmpty())
        assertTrue(r.chapters.isEmpty())
    }

    /** 反斜杠 / 前导斜杠要归一化（zip 条目名有 Windows 风格的历史包）。 */
    @Test
    fun backslashPaths_areNormalized() {
        val r = MangaChapterSplitter.split(listOf("ch1\\001.jpg", "/ch2/001.jpg"))
        assertEquals(listOf("ch1", "ch2"), r.chapters.map { it.title })
    }

    // ===== 标签 =====

    @Test
    fun label_prefersFolderName() {
        val titled = MangaChapter(number = 2, title = "第12话", startPage = 10, pageCount = 5)
        assertEquals("第12话", mangaChapterLabel(ctx, titled))

        val untitled = MangaChapter(number = 3, title = "  ", startPage = 20, pageCount = 5)
        assertTrue(mangaChapterLabel(ctx, untitled).contains("3"))

        val root = MangaChapter(number = 0, title = "", startPage = 0, pageCount = 2)
        assertTrue("根散图章要显示成第0章", mangaChapterLabel(ctx, root).contains("0"))
    }

    // ===== 清单持久化 / 页号归属 =====

    @Test
    fun chapters_jsonRoundTrip() {
        val list = listOf(
            MangaChapter(0, "", 0, 2),
            MangaChapter(1, "ch1", 2, 3),
        )
        assertEquals(list, MangaChapter.listFromJson(MangaChapter.listToJson(list)))
        assertTrue(MangaChapter.listFromJson("").isEmpty())
        assertTrue(MangaChapter.listFromJson("垃圾").isEmpty())
    }

    @Test
    fun chapterOfPage_mapsPageToChapter() {
        val manga = ImportedManga(
            id = 1, title = "t", localRoot = "/x", isArchive = false, coverPath = null,
            pageCount = 5, addedAt = 1L,
            chapters = listOf(
                MangaChapter(0, "", 0, 2),
                MangaChapter(1, "ch1", 2, 3),
            )
        )
        assertEquals(2, manga.chapterCount)
        assertEquals(1, manga.chapterOfPage(3)?.number)
        assertEquals(0, manga.chapterOfPage(1)?.number)
        // 越界 / 没有章节表
        assertEquals(null, manga.chapterOfPage(99))
        assertEquals(1, manga.copy(chapters = emptyList()).chapterCount)
    }

    @Test
    fun chapterIndexOf_isSharedByReaderAndPanel() {
        val chapters = listOf(
            MangaChapter(0, "", 0, 2),
            MangaChapter(1, "ch1", 2, 3),
        )
        assertEquals(0, chapterIndexOf(chapters, 0))
        assertEquals(0, chapterIndexOf(chapters, 1))
        assertEquals(1, chapterIndexOf(chapters, 2))
        assertEquals(1, chapterIndexOf(chapters, 4))
        assertEquals(0, chapterIndexOf(emptyList(), 3))
    }
}
