package com.moe.starflow.utils

import kotlin.math.ceil

/**
 * 粗略 token 估算（**不引入任何分词器**）。
 *
 * 用途只有两个，都不是精确计费：
 * 1. **上下文预算**：决定「最近几轮译文塞得进多少 token」，超了就把最旧的丢掉（见 `ContextBudget`）
 * 2. **单次请求超长预警**：一批文本估算超过用户设的阈值就弹窗提示（见批次预警）
 *
 * ⚠️ **刻意不精确**：真要精确就得对每个厂商各接一套分词器（火山/智谱/千问/DeepSeek 各不相同），
 * 而这里的判据是「大概多少」—— 差 20% 不影响把上下文控制在 8K 以内。
 *
 * 估值口径（按主流 BPE 分词器的经验值）：
 * - **CJK / 假名 / 韩文 / 全角标点 ≈ 1 token / 字**（这些字基本各占一个 token，常用词会略少）
 * - 其他字符（拉丁字母、数字、半角符号）≈ **1 token / 4 字符**
 * - 空白不计
 */
object TokenEstimator {

    /** 拉丁类字符多少个算一个 token。 */
    private const val CHARS_PER_TOKEN = 4

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (c in text) {
            when {
                c.isWhitespace() -> Unit
                isCjk(c) -> cjk++
                else -> other++
            }
        }
        return cjk + ceil(other.toDouble() / CHARS_PER_TOKEN).toInt()
    }

    fun estimateAll(texts: Iterable<String>): Int = texts.sumOf { estimate(it) }

    /** 一轮对话（原文 + 译文）的估算。 */
    fun estimatePair(source: String, target: String): Int = estimate(source) + estimate(target)

    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return when {
            code in 0x4E00..0x9FFF -> true      // 中日韩统一表意文字
            code in 0x3400..0x4DBF -> true      // 扩展 A
            code in 0xF900..0xFAFF -> true      // 兼容表意文字
            code in 0x3040..0x30FF -> true      // 平假名 / 片假名
            code in 0x31F0..0x31FF -> true      // 片假名扩展
            code in 0xAC00..0xD7AF -> true      // 谚文音节
            code in 0x1100..0x11FF -> true      // 谚文字母
            code in 0x3000..0x303F -> true      // 全角标点
            code in 0xFF00..0xFF60 -> true      // 全角字符
            else -> false
        }
    }
}
