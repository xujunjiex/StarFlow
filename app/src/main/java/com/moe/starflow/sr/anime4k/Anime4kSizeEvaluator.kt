package com.moe.starflow.sr.anime4k

import kotlin.math.roundToInt

/**
 * Anime4K shader 里 `//!WIDTH` / `//!HEIGHT` 表达式（逆波兰）的求值器。
 *
 * 移植自 Kototoro 的 `Anime4kSizeEvaluator`。
 *
 * 表达式形如：
 * ```
 * //!WIDTH MAIN.w            → 引用 MAIN 纹理的宽
 * //!HEIGHT OUTPUT.h 2 /     → OUTPUT 的高除以 2
 * //!WIDTH conv2d_last_tf.w 2 *
 * ```
 * 支持 `+ - * /` 与 `<纹理名>.w/.h/.width/.height`，数字直接入栈。
 *
 * ⚠️ 引用了**不存在的纹理**时按 0 计（参考实现同样如此）—— 所以 `OUTPUT` / `NATIVE`
 * 这类本实现没有的纹理要由调用方预先塞进 [textureSizes]，否则表达式会静默算出 0，
 * 再被 `max(1, …)` 兜成 1×1 的纹理（表现为输出变成 1 像素）。
 */
object Anime4kSizeEvaluator {

    fun evaluate(expression: String, textureSizes: Map<String, Pair<Int, Int>>): Int {
        val tokens = expression.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val stack = mutableListOf<Float>()

        for (token in tokens) {
            when (token) {
                "+" -> { val b = stack.removeAt(stack.size - 1); val a = stack.removeAt(stack.size - 1); stack.add(a + b) }
                "-" -> { val b = stack.removeAt(stack.size - 1); val a = stack.removeAt(stack.size - 1); stack.add(a - b) }
                "*" -> { val b = stack.removeAt(stack.size - 1); val a = stack.removeAt(stack.size - 1); stack.add(a * b) }
                "/" -> { val b = stack.removeAt(stack.size - 1); val a = stack.removeAt(stack.size - 1); stack.add(a / b) }
                else -> when {
                    token.endsWith(".w") || token.endsWith(".width") ->
                        stack.add((textureSizes[token.substringBefore(".")]?.first ?: 0).toFloat())
                    token.endsWith(".h") || token.endsWith(".height") ->
                        stack.add((textureSizes[token.substringBefore(".")]?.second ?: 0).toFloat())
                    else -> token.toFloatOrNull()?.let { stack.add(it) }
                }
            }
        }
        return if (stack.isNotEmpty()) stack.last().roundToInt() else 0
    }
}
