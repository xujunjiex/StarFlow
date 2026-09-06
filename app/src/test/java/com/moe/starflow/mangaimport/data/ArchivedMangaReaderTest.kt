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
