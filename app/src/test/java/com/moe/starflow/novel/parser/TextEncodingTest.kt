package com.moe.starflow.novel.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * 编码探测守卫。
 *
 * 三条主线各有守卫，缺一条都会让某类小说整本乱码：
 * - **UTF-8**（现代默认，含 BOM 与无 BOM）
 * - **GB18030/GBK**（大陆网文，绝对多数）
 * - **Big5**（台港繁体）—— 这条最容易被写成「不可达」，因为 GB18030 能把 Big5 字节
 *   完整解出来，只有加上私用区判据才判得对。见 [TextEncoding] 类注释。
 */
class TextEncodingTest {

    private companion object {
        const val SIMPLIFIED = "第一章　风起\n\n他抬起头，看着远方的天空，心里想着那件事。这是个很长的故事，要从十年前说起。"
        const val TRADITIONAL = "第一章　風起\n\n他抬起頭，看著遠方的天空，心裡想著那件事。這是個很長的故事，要從十年前說起。"
        const val TRADITIONAL_2 = "林黛玉進了賈府，見了賈母，眾人皆笑。天下大勢，分久必合，合久必分。"
    }

    private val gb18030: Charset = Charset.forName("GB18030")
    private val big5: Charset = Charset.forName("Big5")
    private val gbk: Charset = Charset.forName("GBK")

    private fun utf8Bom(bytes: ByteArray) =
        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes

    // ===== BOM =====

    @Test
    fun `utf8 BOM 优先识别`() {
        val bytes = utf8Bom(SIMPLIFIED.toByteArray(Charsets.UTF_8))
        assertEquals(Charsets.UTF_8, TextEncoding.detect(bytes))
        assertEquals(SIMPLIFIED, TextEncoding.decode(bytes))
    }

    /** BOM 必须被剥掉：留在正文首字符会让切章正则的 `^\s*第` 失配（第一章识别不出来）。 */
    @Test
    fun `utf8 BOM 被剥掉而不是留成不可见字符`() {
        val out = TextEncoding.decode(utf8Bom(SIMPLIFIED.toByteArray(Charsets.UTF_8)))
        assertTrue("正文里不该含 BOM", !out.contains('﻿'))
        assertEquals("第", out.first().toString())
    }

    @Test
    fun `utf16 LE 与 BE 的 BOM 都被识别且剥掉`() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + SIMPLIFIED.toByteArray(Charsets.UTF_16LE)
        assertEquals(Charsets.UTF_16LE, TextEncoding.detect(le))
        assertEquals(SIMPLIFIED, TextEncoding.decode(le))

        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + SIMPLIFIED.toByteArray(Charsets.UTF_16BE)
        assertEquals(Charsets.UTF_16BE, TextEncoding.detect(be))
        assertEquals(SIMPLIFIED, TextEncoding.decode(be))
    }

    // ===== UTF-8 =====

    @Test
    fun `无 BOM 的 UTF-8 中文识别为 UTF-8`() {
        val bytes = SIMPLIFIED.toByteArray(Charsets.UTF_8)
        assertEquals(Charsets.UTF_8, TextEncoding.detect(bytes))
        assertEquals(SIMPLIFIED, TextEncoding.decode(bytes))
    }

    // ===== 简体 GBK / GB18030 =====

    /**
     * 不要求 detect 返回 `GBK` 这个 Charset 对象 —— GB18030 是 GBK 的**超集**，同一批字节
     * 用两者解出的结果完全一致。要守的是「每个字符都对」这个用户能感知的契约。
     */
    @Test
    fun `GBK 字节解出正确原文`() {
        val bytes = SIMPLIFIED.toByteArray(gbk)
        assertEquals(gb18030, TextEncoding.detect(bytes))
        assertEquals(SIMPLIFIED, TextEncoding.decode(bytes))
    }

    @Test
    fun `多组简体样本都不被误判成繁体`() {
        val samples = listOf(
            SIMPLIFIED,
            "他说：「这是个很长的故事。」",
            "林黛玉进了贾府，见了贾母，众人皆笑。",
            "天下大势，分久必合，合久必分。",
        )
        for (s in samples) {
            val out = TextEncoding.decode(s.toByteArray(gbk))
            assertEquals("GBK 样本被解错：$s", s, out)
        }
    }

    // ===== 繁体 Big5 =====

    /**
     * 核心守卫：Big5 字节必须判成 Big5 而不是 GB18030。
     *
     * 若去掉私用区判据（退回「能否完整解码」+ 候选顺序），GB18030 会排在 Big5 前面全吃下来，
     * 这条会失败 —— 它守的正是那一步。
     */
    @Test
    fun `Big5 字节识别为 Big5 且解出正确繁体原文`() {
        val bytes = TRADITIONAL.toByteArray(big5)
        assertEquals(big5, TextEncoding.detect(bytes))
        assertEquals(TRADITIONAL, TextEncoding.decode(bytes))
    }

    @Test
    fun `多组繁体样本都判成 Big5`() {
        for (s in listOf(TRADITIONAL, TRADITIONAL_2, "第一章　風起", "風")) {
            val out = TextEncoding.decode(s.toByteArray(big5))
            assertEquals("Big5 样本被解错：$s", s, out)
        }
    }

    /** 繁体被判成 GB18030 的后果就是这种乱码 —— 明确断言它**不会**发生。 */
    @Test
    fun `繁体不会被解成私用区乱码`() {
        val out = TextEncoding.decode(TRADITIONAL.toByteArray(big5))
        val privateCount = out.count { it.code in 0xE000..0xF8FF }
        assertEquals("解出的文本里不该有私用区字符", 0, privateCount)
        assertNotEquals(TRADITIONAL.toByteArray(gb18030).toString(Charsets.UTF_8), out)
    }

    // ===== 回退与边界 =====

    /**
     * 全部候选都解不开 → 回退 UTF-8 + REPLACE，**不抛异常**（导入不该因编码判不准中断）。
     * 0xFF 既不是合法的 GB18030 首字节，也不是合法的 UTF-8 起始字节。
     */
    @Test
    fun `全部候选失败时回退 UTF-8 且不抛异常`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x41)
        assertEquals(Charsets.UTF_8, TextEncoding.detect(bytes))
        val out = TextEncoding.decode(bytes)
        assertTrue(out.isNotEmpty())
        assertTrue("非法字节应被替换成替换符，而不是丢掉", out.contains('�'))
    }

    @Test
    fun `空字节数组返回空串`() {
        assertEquals("", TextEncoding.decode(ByteArray(0)))
    }

    /** 纯 ASCII 对任何编码都成立 → 按候选顺序落到 UTF-8。 */
    @Test
    fun `纯 ASCII 判为 UTF-8`() {
        val bytes = "Chapter 1: The Wind".toByteArray(Charsets.US_ASCII)
        assertEquals(Charsets.UTF_8, TextEncoding.detect(bytes))
        assertEquals("Chapter 1: The Wind", TextEncoding.decode(bytes))
    }

    /**
     * 探测只看前 64KB：超长文件也要判对。UTF-8 中文对 GB18030 是解不开的，
     * 所以即便采样把某个字符截半，UTF-8 仍是唯一完整可解者。
     */
    @Test
    fun `超长 UTF-8 文本仍判为 UTF-8`() {
        val long = SIMPLIFIED.repeat(4000)
        val bytes = long.toByteArray(Charsets.UTF_8)
        assertTrue("构造的样本必须真的超过采样上限", bytes.size > 64 * 1024)
        assertEquals(Charsets.UTF_8, TextEncoding.detect(bytes))
        assertEquals(long, TextEncoding.decode(bytes))
    }

    /**
     * 超长 GBK 文本：GBK 字节对 UTF-8 解不开、对 Big5 会解出大量私用区，
     * 所以只有 GB18030 能胜出。
     *
     * ⚠️ **必须逐个对齐试**（`pad = 0..3`）：采样是硬切 64KB，切点落在多字节字符中间时
     * 三个候选会**一起**严格解码失败，`detect` 就兜底成 UTF-8、整本乱码。只测一个对齐
     * 等于掷一次硬币 —— 原来的写法只断言 `decode() == 原文`，恰好那一个对齐能过，
     * 于是这条守卫是假绿（真机上一半的 GBK 小说整本乱码，测试全绿）。
     */
    @Test
    fun `超长 GBK 文本在任何采样对齐下都判对`() {
        val long = SIMPLIFIED.repeat(4000)
        for (pad in 0..3) {
            val bytes = ("A".repeat(pad) + long).toByteArray(gbk)
            assertTrue("pad=$pad 的样本必须真的超过采样上限", bytes.size > 64 * 1024)
            val decoded = TextEncoding.decode(bytes)
            assertTrue(
                "pad=$pad 被判成 ${TextEncoding.detect(bytes).name()}，正文出现替换符（整本乱码）",
                !decoded.contains('�'),
            )
            assertTrue("pad=$pad 的正文必须完整可读", decoded.contains("他抬起头，看着远方的天空"))
        }
    }

    /** 超长 Big5（繁体）同理 —— 私用区判据要能拿到**两个候选都解全**的采样才生效。 */
    @Test
    fun `超长 Big5 文本在任何采样对齐下都判对`() {
        val long = TRADITIONAL.repeat(4000)
        for (pad in 0..3) {
            val bytes = ("A".repeat(pad) + long).toByteArray(big5)
            assertTrue("pad=$pad 的样本必须真的超过采样上限", bytes.size > 64 * 1024)
            val decoded = TextEncoding.decode(bytes)
            assertTrue(
                "pad=$pad 被判成 ${TextEncoding.detect(bytes).name()}，正文出现替换符（整本乱码）",
                !decoded.contains('�'),
            )
            assertTrue("pad=$pad 的正文必须完整可读", decoded.contains("他抬起頭，看著遠方的天空"))
        }
    }
}
