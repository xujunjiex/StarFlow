package com.moe.starflow.mangaimport.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchivedMangaReaderTest {

    @Test
    fun sortNaturally_numericAware() {
        val names = listOf("page10.jpg", "page2.jpg", "page1.jpg", "page20.jpg")
        val sorted = ArchivedMangaReader.sortNaturally(names)
        assertEquals(listOf("page1.jpg", "page2.jpg", "page10.jpg", "page20.jpg"), sorted)
    }

    @Test
    fun sortNaturally_mixedPrefix() {
        val names = listOf("Chapter2/1.jpg", "Chapter1/2.jpg", "Chapter1/1.jpg")
        val sorted = ArchivedMangaReader.sortNaturally(names)
        assertEquals(listOf("Chapter1/1.jpg", "Chapter1/2.jpg", "Chapter2/1.jpg"), sorted)
    }

    /**
     * 分章节目录的页序语义（用户最关心的那条）：**先按文件夹名数字自然序，再按夹内图片名数字自然序**。
     *
     * 实现上是「整条相对路径一次自然排序」，但目录段在字符串前面 → 它先决出胜负，等价于两级排序。
     * 导入时的页列表与阅读器的页枚举都用这个比较器，所以导入后看到的顺序 == 阅读器翻页顺序。
     */
    @Test
    fun sortNaturally_chapterDirsThenInnerPages() {
        val names = listOf("ch10/1.jpg", "ch2/10.jpg", "ch2/2.jpg", "ch2/1.jpg", "ch1/1.jpg")
        assertEquals(
            listOf("ch1/1.jpg", "ch2/1.jpg", "ch2/2.jpg", "ch2/10.jpg", "ch10/1.jpg"),
            ArchivedMangaReader.sortNaturally(names)
        )
    }

    /** zip 内 entry 同名混排（ch1/001.jpg 与 ch2/001.jpg 同名）：靠目录段区分，不会交错。 */
    @Test
    fun sortNaturally_sameBasenameInDifferentDirs() {
        val names = listOf("ch2/001.jpg", "ch1/002.jpg", "ch1/001.jpg")
        assertEquals(
            listOf("ch1/001.jpg", "ch1/002.jpg", "ch2/001.jpg"),
            ArchivedMangaReader.sortNaturally(names)
        )
    }

    @Test
    fun isImageFile_recognizesCommon() {
        assertTrue(ArchivedMangaReader.isImageFile("a.jpg"))
        assertTrue(ArchivedMangaReader.isImageFile("a.JPEG"))
        assertTrue(ArchivedMangaReader.isImageFile("a.png"))
        assertTrue(ArchivedMangaReader.isImageFile("a.webp"))
        assertFalse(ArchivedMangaReader.isImageFile("a.txt"))
        assertFalse(ArchivedMangaReader.isImageFile("a.zip"))
    }

    @Test
    fun listImageFilesInDir_scansAndSorts() {
        val dir = java.io.File.createTempFile("manga", "tmp").let { f ->
            f.delete()
            java.io.File(f.absolutePath).apply { mkdirs() }
        }
        try {
            java.io.File(dir, "page10.jpg").writeText("x")
            java.io.File(dir, "page2.png").writeText("x")
            java.io.File(dir, "README.txt").writeText("x")  // 非图片，应跳过
            java.io.File(dir, "page1.jpg").writeText("x")
            val result = ArchivedMangaReader.listImageFilesInDir(dir)
            assertEquals(listOf("page1.jpg", "page2.png", "page10.jpg"), result)
        } finally {
            dir.deleteRecursively()
        }
    }
}
