package com.moe.starflow.mangaimport.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入进度口径的纯逻辑回归（不依赖 Android）。
 *
 * 这里钉死两条容易写错的性质：
 * 1. **字节优先、其次文件数**：压缩包走字节（源文件大小可查），图片夹走文件数（SAF 逐文件查
 *    length 太贵）；两者都未知 → -1（UI 走不确定进度条），而不是 0。
 * 2. 超额（copyStream 的字节数可能略大于源长度声明）必须夹到 100，不能溢出到 101 让
 *    ProgressBar 越界。
 */
class ImportProgressTest {

    @Test
    fun percent_byteBased() {
        assertEquals(50, ImportProgress(ImportPhase.COPYING, copiedBytes = 50, totalBytes = 100).percent)
    }

    @Test
    fun percent_fileCountBased() {
        assertEquals(75, ImportProgress(ImportPhase.COPYING, copiedFiles = 3, totalFiles = 4).percent)
    }

    @Test
    fun percent_scanningIsIndeterminate() {
        assertEquals(-1, ImportProgress(ImportPhase.SCANNING).percent)
    }

    @Test
    fun percent_bytesWinOverFileCount() {
        // 两个口径都可用时以字节为准：9/10 文件听起来像 90%，但实际只搬了 10% 的字节
        assertEquals(
            10,
            ImportProgress(ImportPhase.COPYING, copiedFiles = 9, totalFiles = 10, copiedBytes = 10, totalBytes = 100).percent
        )
    }

    @Test
    fun percent_fileCountUsedWhenTotalBytesUnknown() {
        // 目录：totalBytes 恒为 0（不逐文件查 length），此时必须回落到文件数
        assertEquals(
            20,
            ImportProgress(ImportPhase.COPYING, copiedFiles = 2, totalFiles = 10, copiedBytes = 999, totalBytes = 0).percent
        )
    }

    @Test
    fun percent_clampedTo100() {
        assertEquals(100, ImportProgress(ImportPhase.COPYING, copiedBytes = 200, totalBytes = 100).percent)
    }

    @Test
    fun percent_zeroTotalFilesIsIndeterminate() {
        // 空夹（0 张图）：既没有字节也没有文件数 → 不确定，而不是 0%
        assertEquals(-1, ImportProgress(ImportPhase.COPYING, copiedFiles = 0, totalFiles = 0).percent)
    }

    @Test
    fun toPlaceholder_carriesIdTitleAndProgress() {
        val task = ImportTask(
            id = 7,
            title = "某漫画",
            isArchive = false,
            addedAt = 1234L,
            progress = ImportProgress(ImportPhase.COPYING, copiedFiles = 1, totalFiles = 4)
        )
        val placeholder = task.toPlaceholder()

        assertTrue(placeholder.importing)
        assertEquals(7L, placeholder.id)
        assertEquals("某漫画", placeholder.title)
        assertEquals(1234L, placeholder.addedAt)
        assertEquals(25, placeholder.importPercent)
        assertEquals(ImportPhase.COPYING, placeholder.importPhase)
        // 占位没有本地路径/封面/页数：置空是刻意的（书架据此跳过「文件丢失」推导）
        assertEquals("", placeholder.localRoot)
        assertNull(placeholder.coverPath)
        assertEquals(0, placeholder.pageCount)
        assertFalse(placeholder.lost)
    }
}
