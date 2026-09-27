package com.moe.starflow.novel.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 读取上限策略本身的单测（各解析器都靠它防 OOM）。
 *
 * **纯 JVM 测试**：`NovelReadLimits` 不碰 Android API，所以这里不需要 Robolectric。
 * 上限一律用 `limit` 参数注入小值来测 —— 真按 8MB / 100MB 造样本只会让测试又慢又占内存
 * （真实上限的端到端行为由各解析器的测试守）。
 */
class NovelReadLimitsTest {

    @get:Rule val tmp = TemporaryFolder()

    /**
     * 超限异常必须是 `ZipException`：`NovelImportManager.classify` 只认 `ZipException →
     * NOT_ARCHIVE`，换成别的类型会掉进 `UNKNOWN`（用户看到「未知错误」而不是「文件损坏」）。
     *
     * 走真实读取路径取异常（静态类型是 `Exception?`，`is` 断言才有意义）。
     */
    @Test
    fun `超限异常必须是 ZipException`() {
        var e: Exception? = null
        try {
            NovelReadLimits.readFullyAtMost(ByteArray(8).inputStream(), limit = 4, what = "x")
        } catch (ex: Exception) {
            e = ex
        }
        assertTrue("实际: ${e?.let { it::class.java }}", e is ZipException)
    }

    @Test
    fun `readAtMost 只读开头且不报错`() {
        val data = ByteArray(4096) { it.toByte() }
        val head = data.inputStream().use { NovelReadLimits.readAtMost(it, 100) }
        assertEquals(100, head.size)
        assertEquals(0, head.first().toInt())
        assertEquals(99, head.last().toInt())
    }

    /** 流比上限短：返回全部，不补零、不报错。 */
    @Test
    fun `readAtMost 对上更短的流返回全部`() {
        val head = byteArrayOf(1, 2, 3).inputStream().use { NovelReadLimits.readAtMost(it, 100) }
        assertEquals(3, head.size)
    }

    @Test
    fun `readFullyAtMost 超过上限抛异常 刚好等于上限放行`() {
        val exact = NovelReadLimits.readFullyAtMost(ByteArray(1024).inputStream(), 1024, "x")
        assertEquals(1024, exact.size)
        var e: Exception? = null
        try {
            NovelReadLimits.readFullyAtMost(ByteArray(1025).inputStream(), 1024, "x")
        } catch (ex: Exception) {
            e = ex
        }
        assertTrue("实际: $e", e is NovelReadLimits.TooLargeException)
    }

    @Test
    fun `readZipEntry 超过上限抛异常`() {
        val zip = zipWith("a.zip", "big.xhtml" to ByteArray(4096))
        ZipFile(zip).use { zf ->
            val entry = zf.getEntry("big.xhtml")
            assertEquals(4096, NovelReadLimits.readZipEntry(zf, entry, limit = 4096).size)
            var e: Exception? = null
            try {
                NovelReadLimits.readZipEntry(zf, entry, limit = 4095)
            } catch (ex: Exception) {
                e = ex
            }
            assertTrue("实际: $e", e is NovelReadLimits.TooLargeException)
        }
    }

    /** 超限文件要在**读之前**就被 [File.length] 拒绝（不然内存已经花出去了）。 */
    @Test
    fun `readFile 按文件长度拒绝超限文件`() {
        val big = File(tmp.root, "big.txt").apply { writeBytes(ByteArray(4096)) }
        var e: Exception? = null
        try {
            NovelReadLimits.readFile(big, limit = 1024)
        } catch (ex: Exception) {
            e = ex
        }
        assertTrue("实际: $e", e is NovelReadLimits.TooLargeException)
        assertEquals("未超限时正常读回", 4096, NovelReadLimits.readFile(big, limit = 4096).size)
    }

    @Test
    fun `checkEntryCount 超过上限抛异常`() {
        val zip = zipWith("b.zip", "a" to ByteArray(1), "b" to ByteArray(1), "c" to ByteArray(1))
        ZipFile(zip).use { zf ->
            NovelReadLimits.checkEntryCount(zf, limit = 3) // 恰好等于上限：放行
            var e: Exception? = null
            try {
                NovelReadLimits.checkEntryCount(zf, limit = 2)
            } catch (ex: Exception) {
                e = ex
            }
            assertTrue("实际: $e", e is NovelReadLimits.TooLargeException)
        }
    }

    /** 单本累计配额：每一条都不大，加起来超了也要拒（zip 炸弹就是靠量堆出来的）。 */
    @Test
    fun `ZipBudget 累计超限抛异常`() {
        val budget = NovelReadLimits.ZipBudget()
        budget.spend("ch1.xhtml", 1) // 正常一小条：不报
        var e: Exception? = null
        try {
            budget.spend("ch2.xhtml", Int.MAX_VALUE)
        } catch (ex: Exception) {
            e = ex
        }
        assertTrue("实际: $e", e is NovelReadLimits.TooLargeException)
    }

    private fun zipWith(name: String, vararg entries: Pair<String, ByteArray>): File {
        val f = File(tmp.root, name)
        ZipOutputStream(f.outputStream()).use { zos ->
            entries.forEach { (n, bytes) ->
                zos.putNextEntry(ZipEntry(n)); zos.write(bytes); zos.closeEntry()
            }
        }
        return f
    }
}
