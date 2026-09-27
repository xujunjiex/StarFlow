package com.moe.starflow.novel.parser

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 中文文本的编码探测。**这一步不能省**：国内 txt 小说 GBK/GB18030 是主流，
 * 直接按 UTF-8 读会整本乱码（且不报错 —— 用户只看到问号和方块，不知道该干什么）；
 * 台港繁体小说则是 Big5。
 *
 * ### 为什么不能只用「能否完整解码」判
 * 实测确认：**GB18030 的编码空间覆盖 Big5 与 GBK 的全部字节组合**，三种字节流（UTF-8 除外）
 * 互相都能被完整解码。所以「解得开」在三者之间没有区分力 —— 必须再加一个**像不像人话**的判据。
 *
 * ### 采用的判据：Unicode 私用区占比
 * GB18030 把大批 Big5 字节映射到**私用区**（U+E000–U+F8FF，GBK 的自定义区），而私用区
 * 不可能出现在真实小说正文里。实测（4 组样本，繁体 Big5 字节用 GB18030 解）：
 *
 * | 字节来源 | GB18030 解出的私用区占比 | Big5 解出的私用区占比 |
 * |---|---|---|
 * | 繁体 Big5 | **0.44 ~ 0.64** | 0.00 |
 * | 简体 GBK | 0.00 | 0.00 或解不开 |
 *
 * 于是：**私用区占比最低者胜**；占比相同时按 [CANDIDATES] 的先后（= 大陆优先）定胜负。
 * 这样三种输入都能判对，且**不需要字频表**。
 *
 * ⚠️ 已知取舍：真正的字频打分（常用字命中率）比私用区更细，但要多带一张常用字表且收益很小
 * —— 私用区判据在本功能面向的语料上已足够。若日后有「繁体被判成简体」的真实样本，
 * 再考虑升级判据，届时务必把样本固化成测试。
 *
 * ⚠️ [detect] / [decode] 都是纯计算，但大文件会整块读入；调用方应在主线程之外调用。
 */
object TextEncoding {

    /** 探测用的采样上限：整本读进内存只为探编码不值得。 */
    private const val SAMPLE_BYTES = 64 * 1024

    /**
     * 采样末尾最多往前退这么多字节去凑字符边界（最长 4 字节的编码 + 余量）。
     *
     * ⚠️ **不退这一下会整本乱码**（真实踩过）：[sampleOf] 是硬切的，切点落在多字节字符
     * 中间时，UTF-8 / GB18030 / Big5 **三个候选会一起**严格解码失败 → [detect] 走到
     * `?: Charsets.UTF_8` 兜底 → 整本按 UTF-8 解，用户看到满屏替换符。
     *
     * 64KB 只合三万个汉字，**任何真实小说都超过**，而切点是否落在字符中间取决于正文前
     * 65536 字节里单字节字符的个数 —— 等于掷硬币。注释原先写的「截断只会让该候选落选」
     * 只对「还有别的候选能解」成立，三个一起落选时就没有候选可用了。
     */
    private const val SAMPLE_TAIL_BACKOFF = 4

    /** 候选编码。**顺序即平手时的优先级**（大陆网文占绝对多数，故 GB18030 先于 Big5）。 */
    private val CANDIDATES: List<Charset> = listOfNotNull(
        Charsets.UTF_8,
        charsetOrNull("GB18030"),
        charsetOrNull("Big5"),
    )

    private fun charsetOrNull(name: String): Charset? =
        runCatching { Charset.forName(name) }.getOrNull()

    /**
     * 探测编码。优先级：BOM → 私用区占比最低者 → 平手按候选顺序 → 全解不开回退 UTF-8。
     */
    fun detect(bytes: ByteArray): Charset {
        bomCharset(bytes)?.let { return it }
        val sample = sampleOf(bytes)
        var best: Charset? = null
        var bestScore = Double.MAX_VALUE
        for (cs in CANDIDATES) {
            val decoded = decodeSample(sample, cs) ?: continue
            val score = privateUseRatio(decoded)
            // 严格小于：平手时保留**先出现**的候选（即优先级更高者）
            if (score < bestScore) {
                bestScore = score
                best = cs
            }
        }
        return best ?: Charsets.UTF_8
    }

    /**
     * 解码并**去掉 BOM**。
     *
     * ⚠️ BOM 必须去掉：留在正文开头会变成一个不可见字符，切章正则的 `^\s*第` 会因此失配、
     * 正文首字符也会多出一个 `﻿` —— 表现为「第一章没被判成章节」这种极难查的问题。
     */
    fun decode(bytes: ByteArray): String {
        val bom = bomCharset(bytes)
        val charset = bom ?: detect(bytes)
        val offset = bomLength(bytes)
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                .toString()
        } catch (e: Exception) {
            String(bytes, offset, bytes.size - offset, Charsets.UTF_8)
        }
    }

    /**
     * 采样：截断到半个多字节字符要由 [decodeSample] 退字节兜住。
     *
     * 探测用 REPORT 严格解码 —— 截断会让**该候选**落选，所以不能只截一次就不管了。
     */
    private fun sampleOf(bytes: ByteArray): ByteArray =
        if (bytes.size > SAMPLE_BYTES) bytes.copyOf(SAMPLE_BYTES) else bytes

    /**
     * 严格解这个采样；**末尾退几字节**再试，直到凑上字符边界。
     *
     * 只对**采样**这么做（真解码是宽容模式，末尾半个字符无害）：这里要的是
     * 「这个候选能不能完整表达这份字节流」，末尾半个字符是采样截出来的，不是文本的问题。
     */
    private fun decodeSample(sample: ByteArray, charset: Charset): String? {
        for (back in 0..SAMPLE_TAIL_BACKOFF) {
            val n = sample.size - back
            if (n <= 0) break
            decodeFully(if (n == sample.size) sample else sample.copyOf(n), charset)?.let { return it }
        }
        return null
    }

    /**
     * 私用区字符占「非 ASCII 字符」的比例；纯 ASCII（无中文）返回 0.0。
     *
     * 用 0.0 而不是「无意义」：纯英文文本对任何编码都成立，占比 0 会让它按候选顺序落到
     * UTF-8 —— 这正是想要的结果。
     */
    private fun privateUseRatio(text: String): Double {
        var nonAscii = 0
        var private = 0
        for (c in text) {
            if (c.code < 0x80) continue
            nonAscii++
            if (c.code in 0xE000..0xF8FF) private++
        }
        return if (nonAscii == 0) 0.0 else private.toDouble() / nonAscii
    }

    /** 用 REPORT 严格试解：有任何非法/不可映射字节即返回 null。 */
    private fun decodeFully(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    } catch (e: Exception) {
        null
    }

    private fun bomCharset(bytes: ByteArray): Charset? = when {
        isUtf8Bom(bytes) -> Charsets.UTF_8
        isUtf16LeBom(bytes) -> Charsets.UTF_16LE
        isUtf16BeBom(bytes) -> Charsets.UTF_16BE
        else -> null
    }

    private fun bomLength(bytes: ByteArray): Int = when {
        isUtf8Bom(bytes) -> 3
        isUtf16LeBom(bytes) || isUtf16BeBom(bytes) -> 2
        else -> 0
    }

    private fun isUtf8Bom(b: ByteArray) =
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()

    private fun isUtf16LeBom(b: ByteArray) =
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()

    private fun isUtf16BeBom(b: ByteArray) =
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()
}
