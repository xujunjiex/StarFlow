package com.moe.starflow.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 用**真实的** Hy-MT2 1.25-bit 模型文件验证重打标解析（opt-in，默认跳过）。
 *
 * 跑法（把真机上的 gguf 拉到本机后）：
 * ```
 * $env:LLAMACPP_REAL_GGUF='C:\path\to\Hy-MT2-1.8B-1.25Bit.gguf'
 * .\gradlew.bat --no-daemon :app:testDebugUnitTest --tests com.moe.starflow.llamacpp.GgufTypeRetagRealFileTest
 * ```
 *
 * 为什么值得留着：GGUF 解析是「一旦写错就把用户 461MB 模型改坏」的代码，
 * 合成样本（GgufTypeRetagTest）只能证明逻辑自洽，证明不了**真实文件的元数据规模**
 * （120818 个 vocab token 的字符串数组、354 个张量）下偏移计算仍然正确。
 */
class GgufTypeRetagRealFileTest {

    private fun realFile(): File? =
        System.getenv("LLAMACPP_REAL_GGUF")?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile }

    @Test
    fun parseRealFile_typeHistogramMatchesKnownModel() {
        val src = realFile()
        assumeTrue("未提供 LLAMACPP_REAL_GGUF，跳过真实文件验证", src != null)

        val offsets = GgufTypeRetag.findTypeFieldOffsets(src!!)
        // 真机上的官方文件：354 个张量，其中 224 个 1.25-bit（类型 42）、129 个 F32、1 个 Q6_K(14)
        assertEquals(354, offsets.size)
        val hist = offsets.groupingBy { it.second }.eachCount()
        assertEquals(224, hist[GgufTypeRetag.TYPE_LEGACY_1_25BIT])
        assertEquals(129, hist[0])
        assertEquals(1, hist[14])
    }

    @Test
    fun retagRealCopy_rewritesEveryLegacyTypeAndRecordsMd5() {
        val src = realFile()
        assumeTrue("未提供 LLAMACPP_REAL_GGUF，跳过真实文件验证", src != null)

        val work = File.createTempFile("retag-real-", ".gguf")
        try {
            src!!.copyTo(work, overwrite = true)
            assertTrue(GgufTypeRetag.ensureRetagged(work))

            val hist = GgufTypeRetag.findTypeFieldOffsets(work).groupingBy { it.second }.eachCount()
            assertEquals("改写后不应再有 42 号张量", null, hist[GgufTypeRetag.TYPE_LEGACY_1_25BIT])
            assertEquals(224, hist[GgufTypeRetag.TYPE_STQ1_0])

            val marker = LlamaCppPaths.retagMarker(work)
            assertTrue(marker.isFile)
            val marked = marker.readText()
            assertEquals("标记里的 MD5 必须是改写后文件的 MD5", GgufTypeRetag.md5(work), marked)
            // 幂等：再调用一次不改文件、MD5 不变
            assertTrue(GgufTypeRetag.ensureRetagged(work))
            assertEquals(marked, GgufTypeRetag.md5(work))
        } finally {
            LlamaCppPaths.retagMarker(work).delete()
            work.delete()
        }
    }
}
