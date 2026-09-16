package com.moe.starflow.mangaimport.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertEquals("001_译文.jpg", ExportNaming.translatedPairEntry("001.png", "_译文"))
        assertEquals("ch1/005_译文.jpg", ExportNaming.translatedPairEntry("ch1/005.jpg", "_译文"))
        // 后缀随界面语言：英文界面下同一页导出成 _translated
        assertEquals("001_translated.jpg", ExportNaming.translatedPairEntry("001.png", "_translated"))
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
            assertTrue(
                "同层条目重名: $k",
                ExportNaming.originalPairEntry(k) != ExportNaming.translatedPairEntry(k, "_译文"),
            )
        }
    }

    // ===== 去重（重名会让 ZipException 直接终止整包导出） =====

    @Test
    fun uniqueEntryNameKeepsFirstOccurrence() {
        val used = HashSet<String>()
        assertEquals("005.jpg", ExportNaming.uniqueEntryName("005.jpg", used))
        assertEquals("006.jpg", ExportNaming.uniqueEntryName("006.jpg", used))
    }

    @Test
    fun uniqueEntryNameSuffixesCollisions() {
        val used = HashSet<String>()
        assertEquals("005.jpg", ExportNaming.uniqueEntryName("005.jpg", used))
        assertEquals("005_2.jpg", ExportNaming.uniqueEntryName("005.jpg", used))
        assertEquals("005_3.jpg", ExportNaming.uniqueEntryName("005.jpg", used))
    }

    @Test
    fun uniqueEntryNameHandlesDirectoryAndExtensionEdges() {
        val used = HashSet<String>()
        assertEquals("ch1/005.jpg", ExportNaming.uniqueEntryName("ch1/005.jpg", used))
        assertEquals("ch1/005_2.jpg", ExportNaming.uniqueEntryName("ch1/005.jpg", used))
        // 无扩展名：后缀直接接在末尾
        assertEquals("005", ExportNaming.uniqueEntryName("005", used))
        assertEquals("005_2", ExportNaming.uniqueEntryName("005", used))
        // 目录名里有点（`v1.2/005`）：不能被当成扩展名切错位置
        assertEquals("v1.2/005", ExportNaming.uniqueEntryName("v1.2/005", used))
        assertEquals("v1.2/005_2", ExportNaming.uniqueEntryName("v1.2/005", used))
    }

    /** 同一目录下 `005.png` 与 `005.jpg` 都映射成 `005.jpg`（译文扩展名统一）→ 必须去重。 */
    @Test
    fun crossKeyCollisionIsResolved() {
        val used = HashSet<String>()
        val names = listOf("ch1/005.png", "ch1/005.jpg").map {
            ExportNaming.uniqueEntryName(ExportNaming.translatedEntry(it), used)
        }
        assertEquals(listOf("ch1/005.jpg", "ch1/005_2.jpg"), names)
        assertEquals(names.size, names.toSet().size)
    }

    /**
     * 双语包被导出后再导入当新书读：原包里会真的有一个 `005_译文.jpg`。它作为**原文条目**时
     * 那个名字已被第一页的译文占用 → 只能改名。「谁先写谁保名」：结果确定、条目名唯一，
     * **不会**让 `ZipOutputStream` 抛 duplicate entry 把整包废掉。
     */
    @Test
    fun bilingualReimportCollisionIsResolved() {
        val used = HashSet<String>()
        val out = mutableListOf<String>()
        for (key in listOf("005.jpg", "005_译文.jpg")) {
            out += ExportNaming.uniqueEntryName(ExportNaming.originalPairEntry(key), used)
            out += ExportNaming.uniqueEntryName(ExportNaming.translatedPairEntry(key, "_译文"), used)
        }
        assertEquals(
            listOf("005.jpg", "005_译文.jpg", "005_译文_2.jpg", "005_译文_译文.jpg"),
            out,
        )
        assertEquals("zip 内条目名必须唯一", out.size, out.toSet().size)
    }
}
