/*
 * Copyright (C) 2024 murangogo
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with this library; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */

package translationapi.customtranslation

import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义翻译 API 的「JSON 响应路径」解析工具（自定义文本 / 自定义图片共用）。
 *
 * 支持的写法（与上游萌译同一套语法）：
 *   - result
 *   - data.translation
 *   - data.list[0].text
 *   - data.items[0][1].value
 *
 * 语法说明：
 *   - "."   分隔对象（JSONObject）的属性名；
 *   - "[n]" 访问数组（JSONArray）中第 n 个元素（下标从 0 开始）；
 *   - 二者可任意组合，如 a.b[0].c、a[0][1] 等。
 *
 * ⚠️ 属性名中不能包含 "."、"["、"]" 这三个分隔字符（与上游一致）。
 *
 * ⚠️ **不要退回「按 '.' 切分、只认 JSONObject」的老写法**：那样 `data.list[0].text` 这种
 * 常见响应（多数自建/聚合接口把译文放在数组里）会直接报 "Invalid JSON path"，而用户在
 * 配置页看到的只是一句解析失败。本文件是这条语法的**唯一实现**，两个自定义接口都调它。
 */
object JsonPathParser {

    // 路径中的单个访问单元：对象属性 或 数组下标
    private sealed class Token {
        data class Key(val name: String) : Token()
        data class Index(val index: Int) : Token()
    }

    /**
     * 按路径取出目标节点的**原始值**（可能是 String/Number/Boolean/JSONObject/JSONArray 或 [JSONObject.NULL]）。
     *
     * @throws IllegalArgumentException  路径语法非法（空路径、下标非法等）
     * @throws IllegalStateException     路径与实际 JSON 结构不匹配（类型不符或键不存在）
     * @throws IndexOutOfBoundsException 数组下标越界
     */
    fun valueOf(root: JSONObject, path: String): Any? {
        val tokens = tokenize(path)
        require(tokens.isNotEmpty()) { "JSON path is empty" }

        var current: Any = root
        val traversed = StringBuilder() // 记录已走过的路径，便于报错时定位
        fun here(): String = traversed.toString().ifEmpty { "<root>" }

        for (token in tokens) {
            current = when (token) {
                is Token.Key -> {
                    val obj = current as? JSONObject ?: throw IllegalStateException(
                        "Node at \"${here()}\" is not a JSON object, cannot read key \"${token.name}\""
                    )
                    if (!obj.has(token.name)) {
                        throw IllegalStateException("Key \"${token.name}\" not found at \"${here()}\"")
                    }
                    traversed.append(if (traversed.isEmpty()) token.name else ".${token.name}")
                    obj.get(token.name)
                }

                is Token.Index -> {
                    val arr = current as? JSONArray ?: throw IllegalStateException(
                        "Node at \"${here()}\" is not a JSON array, cannot use index [${token.index}]"
                    )
                    if (token.index >= arr.length()) {
                        throw IndexOutOfBoundsException(
                            "Index [${token.index}] out of bounds at \"${here()}\" (length ${arr.length()})"
                        )
                    }
                    traversed.append("[${token.index}]")
                    arr.get(token.index)
                }
            }
        }
        return current
    }

    /** 同 [valueOf]，但把结果转成字符串（上游旧 API 的语义）。 */
    fun parse(root: JSONObject, path: String): String = valueOf(root, path).toString()

    /**
     * 将路径字符串解析为 Token 序列。
     *
     * 每个以 "." 分隔的段可以是：
     *   - 纯属性名：name
     *   - 属性名 + 一个或多个下标：name[0]、name[0][1]
     *   - 纯下标（紧跟在 "." 之后，较少见）：[0]
     */
    private fun tokenize(path: String): List<Token> {
        val tokens = mutableListOf<Token>()

        for (segment in path.split('.')) {
            val bracketStart = segment.indexOf('[')
            val key = if (bracketStart == -1) segment else segment.substring(0, bracketStart)

            // 段内既无属性名也无下标 —— 说明路径中出现了空段（如 a..b、.a、a. ）
            if (key.isEmpty() && bracketStart == -1) {
                throw IllegalArgumentException("Empty segment in path: \"$path\"")
            }

            if (key.isNotEmpty()) {
                if (key.contains(']')) {
                    throw IllegalArgumentException("Unexpected ']' in segment: \"$segment\"")
                }
                tokens.add(Token.Key(key))
            }

            // 解析属性名之后连续的 [index] 部分
            var i = if (bracketStart == -1) segment.length else bracketStart
            while (i < segment.length) {
                if (segment[i] != '[') {
                    throw IllegalArgumentException(
                        "Unexpected character '${segment[i]}' in segment: \"$segment\""
                    )
                }
                val close = segment.indexOf(']', i)
                if (close == -1) {
                    throw IllegalArgumentException("Missing ']' in segment: \"$segment\"")
                }
                val indexText = segment.substring(i + 1, close)
                if (indexText.isEmpty() || indexText.any { !it.isDigit() }) {
                    throw IllegalArgumentException(
                        "Invalid array index \"[$indexText]\" in segment: \"$segment\""
                    )
                }
                val index = indexText.toIntOrNull() ?: throw IllegalArgumentException(
                    "Array index \"[$indexText]\" is too large in segment: \"$segment\""
                )
                tokens.add(Token.Index(index))
                i = close + 1
            }
        }

        return tokens
    }
}
