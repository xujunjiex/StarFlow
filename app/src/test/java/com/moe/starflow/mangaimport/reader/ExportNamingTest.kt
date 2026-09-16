package com.moe.starflow.mangaimport.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 打包下载的命名规则：**沿用原压缩包的文件名与序号**，不按「第几个已翻译页」重编号。
 * 原包 1..10 页只翻了 1/2/5/6 → 导出 001/002/005/006（不是 1/2/3/4）。
 */
class ExportNamingTest {

    @Test
    fun translatedKeepsOriginalIndex() {
        // 只翻译 1/2/5/6 页：每页各自用自己的原序号，不是连续重编
        val keys = listOf("001.jpg", "002.jpg", "005.jpg", "006.jpg")
        assertEquals(
            listOf("001.jpg", "002.jpg", "005.jpg", "006.jpg"),
            keys.map { ExportNaming.translatedEntry(it) },
        )
    }

    @Test
    fun translatedForcesJpegExtension() {
        // 译文统一 JPEG 输出 → 扩展名一律 .jpg，主干保留
        assertEquals("005.jpg", ExportNaming.translatedEntry("005.png"))
        assertEquals("cover.jpg", ExportNaming.translatedEntry("cover.webp"))
        assertEquals("005.jpg", ExportNaming.translatedEntry("005.jpeg"))
    }

    @Test
    fun translatedKeepsDirectoryPrefix() {
        // ⚠️ 不能拍平成纯文件名：zip 里重名会让 ZipOutputStream 抛 duplicate entry，整包导出失败
        assertEquals("ch1/005.jpg", ExportNaming.translatedEntry("ch1/005.png"))
        assertEquals("a/b/7.jpg", ExportNaming.translatedEntry("a/b/7.jpg"))
    }

    @Test
    fun translatedHandlesOddNames() {
        assertEquals("005.jpg", ExportNaming.translatedEntry("005"))        // 无扩展名
        assertEquals("005.1.jpg", ExportNaming.translatedEntry("005.1.png")) // 多个点
        assertEquals(".hidden.jpg", ExportNaming.translatedEntry(".hidden")) // 点在开头 = 无主干
    }

    @Test
    fun pairEntryAddsSuffixBeforeExtension() {
        assertEquals("001_译文.jpg", ExportNaming.translatedPairEntry("001.png"))
        assertEquals("ch1/005_译文.jpg", ExportNaming.translatedPairEntry("ch1/005.jpg"))
    }

    @Test
    fun pairOriginalKeepsRawName() {
        // 双语包的原文条目原样搬运：原包叫什么就是什么（含原扩展名）
        assertEquals("005.png", ExportNaming.originalPairEntry("005.png"))
        assertEquals("ch1/005.jpg", ExportNaming.originalPairEntry("ch1/005.jpg"))
    }

    @Test
    fun pairEntriesNeverCollideWithOriginals() {
        // 同层混放的前提：原文与译文条目名必须不同，否则 putNextEntry 直接抛异常
        val keys = listOf("001.jpg", "002.png", "ch1/005.jpg")
        for (k in keys) {
            assert(ExportNaming.originalPairEntry(k) != ExportNaming.translatedPairEntry(k)) {
                "同层条目重名: $k"
            }
        }
    }
}
