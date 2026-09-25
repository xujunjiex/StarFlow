package com.moe.starflow.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 用**真实的**模型文件验证量化校验与重打标（opt-in，默认跳过）。
 *
 * 跑法（先把真机上的 gguf 拉到本机）：
 * ```
 * $env:LLAMACPP_REAL_GGUF='C:\path\Hy-MT2-1.8B-1.25Bit.gguf'
 * $env:LLAMACPP_REAL_GGUF_2BIT='C:\path\Hy-MT2-1.8B-2Bit.gguf'
 * $env:LLAMACPP_REAL_GGUF_Q4KM='C:\path\Hy-MT2-1.8B-Q4_K_M.gguf'
 * .\gradlew.bat --no-daemon :app:testDebugUnitTest --tests com.moe.starflow.llamacpp.GgufTypeRetagRealFileTest
 * ```
 *
 * 为什么必须跑真文件：合成样本只能证明逻辑自洽，证明不了**真实元数据规模**（120818 个 vocab token、
 * 354 个张量）与**各家私有量化**的尺寸。三个真实样本分别覆盖：
 *  - 1.25-bit（42 号 @42 字节/256）→ 必须判 NEEDS_RETAG；
 *  - 2-bit（40 号 @65 字节/256，第三种私有量化）→ 必须判 UNSUPPORTED；
 *  - Q4_K_M（标准 Q4_K/Q6_K/F32）→ 必须判 SUPPORTED（**校验器不能误拦正常模型**）。
 */
class GgufTypeRetagRealFileTest {

    private fun env(name: String): File? =
        System.getenv(name)?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile }

    private val f125 get() = env("LLAMACPP_REAL_GGUF")
    private val f2bit get() = env("LLAMACPP_REAL_GGUF_2BIT")
    private val fq4km get() = env("LLAMACPP_REAL_GGUF_Q4KM")

    @Test
    fun parseRealFile_typeHistogramMatchesKnownModel() {
        val src = f125
        assumeTrue("未提供 LLAMACPP_REAL_GGUF，跳过", src != null)

        val offsets = GgufTypeRetag.findTypeFieldOffsets(src!!)
        // 官方 1.25-bit 文件：354 个张量，其中 224 个 1.25-bit（类型 42）、129 个 F32、1 个 Q6_K(14)
        assertEquals(354, offsets.size)
        val hist = offsets.groupingBy { it.second }.eachCount()
        assertEquals(224, hist[GgufTypeRetag.TYPE_LEGACY_1_25BIT])
        assertEquals(129, hist[0])
        assertEquals(1, hist[14])
    }

    @Test
    fun real1_25Bit_needsRetag_andRetagKeepsLayoutConsistent() {
        val src = f125
        assumeTrue("未提供 LLAMACPP_REAL_GGUF，跳过", src != null)
        assertEquals(GgufQuantCheck.Compat.NEEDS_RETAG, GgufQuantCheck.check(src!!))

        val work = File.createTempFile("retag-real-", ".gguf")
        try {
            src.copyTo(work, overwrite = true)
            assertTrue(GgufTypeRetag.ensureRetagged(work))
            val hist = GgufTypeRetag.findTypeFieldOffsets(work).groupingBy { it.second }.eachCount()
            assertEquals("改写后不应再有 42 号张量", null, hist[GgufTypeRetag.TYPE_LEGACY_1_25BIT])
            assertEquals(224, hist[GgufTypeRetag.TYPE_STQ1_0])
            // 改写后引擎按 43=STQ1_0 读，布局必须自洽
            assertEquals(GgufQuantCheck.Compat.SUPPORTED, GgufQuantCheck.check(work))

            val marker = LlamaCppPaths.retagMarker(work)
            assertTrue(marker.isFile)
            assertEquals(GgufTypeRetag.md5(work), marker.readText())
            assertTrue(GgufTypeRetag.ensureRetagged(work))   // 幂等
            assertEquals(marker.readText(), GgufTypeRetag.md5(work))
        } finally {
            LlamaCppPaths.retagMarker(work).delete()
            work.delete()
        }
    }

    @Test
    fun real2Bit_isDetectedAsUnsupported() {
        val src = f2bit
        assumeTrue("未提供 LLAMACPP_REAL_GGUF_2BIT，跳过", src != null)
        // 40 号 @65 字节/256 值：既不是 NVFP4(144) 也不是 Q2_0(72)/STQ1_0(42)
        assertEquals(GgufQuantCheck.Compat.UNSUPPORTED, GgufQuantCheck.check(src!!))
    }

    @Test
    fun realQ4KM_isSupported() {
        val src = fq4km
        assumeTrue("未提供 LLAMACPP_REAL_GGUF_Q4KM，跳过", src != null)
        // 标准量化必须判 SUPPORTED —— 这是「校验器不误拦」的护栏
        assertEquals(GgufQuantCheck.Compat.SUPPORTED, GgufQuantCheck.check(src!!))
    }
}
