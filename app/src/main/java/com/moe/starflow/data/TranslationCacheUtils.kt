package com.moe.starflow.data
import com.moe.starflow.translate.widget.*

import android.graphics.Rect
import com.moe.starflow.manga.types.TextDirection
import com.moe.starflow.manga.types.TranslatedBubble
import com.moe.starflow.utils.LogCollector

/**
 * 翻译缓存工具（从 TranslationCacheManager companion 提取）：
 * 256-bit hash 统计/稀疏守卫 + 气泡 JSON 解析/序列化/重建。纯函数，无 DB 依赖。
 */
object TranslationCacheUtils {

    /**
     * 稀疏 hash 守卫阈值：256-bit dHash 总有效位 < 该值视为「无判别力 hash」，
     * 不参与任何 256-bit hash 相似度判定（缓存命中 + 历史分组两处共用）。
     *
     * 背景：纯色/几乎纯色页面 dHash 4 段几乎全 0（每段 1-3 bits）。两张低纹理页即使内容完全不同，
     * Hamming distance 也只有 2-3 bits → similarity = 1 - 3/256 = 0.988 远超 0.95 → 错误命中/合并。
     * ~6.25% 是 dHash 在低纹理页面下的经验上界，足以过滤此 FP class。
     */
    const val MIN_INFO_BITS = 16

    /**
     * 计算 256-bit dHash 总有效位（4 段 Long bits 之和）。
     * 稀疏守卫的唯一判断依据：必须用整体累加（≈ 4 段的 bitCount 之和），不能分段判断。
     */
    fun countInfoBits(pHash: Long, pHash2: Long, pHash3: Long, pHash4: Long): Int =
        java.lang.Long.bitCount(pHash) +
        java.lang.Long.bitCount(pHash2) +
        java.lang.Long.bitCount(pHash3) +
        java.lang.Long.bitCount(pHash4)

    /** LongArray 形式的便利重载。 */
    fun countInfoBits(hashes: LongArray): Int {
        require(hashes.size >= 4) { "需要至少 4 段 hash" }
        return countInfoBits(hashes[0], hashes[1], hashes[2], hashes[3])
    }

    /** 稀疏 hash 判定：infoBits < MIN_INFO_BITS → true。 */
    fun isSparseHash(pHash: Long, pHash2: Long, pHash3: Long, pHash4: Long): Boolean =
        countInfoBits(pHash, pHash2, pHash3, pHash4) < MIN_INFO_BITS

    fun isSparseHash(hashes: LongArray): Boolean = isSparseHash(hashes[0], hashes[1], hashes[2], hashes[3])

    /** 解析扩展气泡 JSON（含 fontSize + direction），兼容旧格式无 fs/dir 字段 */
    fun parseBubbleEntriesJson(json: String?, defaultFontSize: Float): List<BubbleJsonEntry> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val result = mutableListOf<BubbleJsonEntry>()
            val jsonArray = org.json.JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val rect = Rect(
                    obj.getInt("l"), obj.getInt("t"),
                    obj.getInt("r"), obj.getInt("b")
                )
                val fs = if (obj.has("fs")) obj.getDouble("fs").toFloat() else defaultFontSize
                val dir = if (obj.has("dir")) {
                    try { TextDirection.valueOf(obj.getString("dir")) }
                    catch (_: Exception) { TextDirection.VERTICAL_RL }
                } else {
                    TextDirection.VERTICAL_RL
                }
                result.add(BubbleJsonEntry(
                    rect, fs, dir,
                    obj.optDouble("angle", 0.0).toFloat(),
                    obj.optDouble("cx", rect.exactCenterX().toDouble()).toFloat(),
                    obj.optDouble("cy", rect.exactCenterY().toDouble()).toFloat(),
                    if (obj.has("originalText")) obj.getString("originalText") else null,
                    if (obj.has("translatedText")) obj.getString("translatedText") else null
                ))
            }
            result
        } catch (e: Exception) {
            LogCollector.e("TranslationCacheUtils", "parseBubbleEntriesJson failed", e)
            emptyList()
        }
    }

    /** 解析 "[1] text1\n[2] text2\n..." 格式的文本列表 */
    fun parseIndexedTextList(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        return try {
            text.split("\n").mapNotNull { line ->
                val match = Regex("^\\[\\d+\\]\\s?(.*)$").find(line.trim())
                match?.groupValues?.getOrNull(1)?.trim()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 新记录的文本与矩形一同保存；编号文本供展示和检索，不再作为区域分隔协议。 */
    fun readBubbleTexts(
        sourceText: String?,
        translatedText: String?,
        bubbleRectsJson: String?
    ): Pair<List<String>, List<String>> {
        val entries = parseBubbleEntriesJson(bubbleRectsJson, 16f)
        if (entries.isNotEmpty() && entries.all { it.originalText != null && it.translatedText != null }) {
            return entries.map { it.originalText.orEmpty() } to entries.map { it.translatedText.orEmpty() }
        }
        return parseIndexedTextList(sourceText) to parseIndexedTextList(translatedText)
    }

    /** 保存完整区域载荷；仅 OCR 的显示回填文本不能作为译文持久化。 */
    fun serializeBubbleRects(bubbles: List<TranslatedBubble>, includeTranslations: Boolean = true): String {
        val jsonArray = org.json.JSONArray()
        for (b in bubbles) {
            val obj = org.json.JSONObject()
            obj.put("l", b.rect.left)
            obj.put("t", b.rect.top)
            obj.put("r", b.rect.right)
            obj.put("b", b.rect.bottom)
            obj.put("fs", b.fontSize.toDouble())
            obj.put("dir", b.direction.name)
            obj.put("angle", b.angle.toDouble())
            obj.put("cx", b.centerX.takeIf { it >= 0f }?.toDouble() ?: b.rect.exactCenterX().toDouble())
            obj.put("cy", b.centerY.takeIf { it >= 0f }?.toDouble() ?: b.rect.exactCenterY().toDouble())
            obj.put("originalText", b.originalText)
            obj.put("translatedText", if (includeTranslations) b.translatedText else "")
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    /** 优先从结构化载荷重建；仅保留既有无正文 JSON 的读取回退。 */
    fun rebuildBubblesFromCache(
        originals: List<String>,
        translations: List<String>,
        bubbleRectsJson: String?,
        defaultFontSize: Float,
        bgColor: Int
    ): List<TranslatedBubble> {
        val entries = parseBubbleEntriesJson(bubbleRectsJson, defaultFontSize)
        if (entries.isEmpty() || (originals.isEmpty() && entries.all { it.originalText == null })) return emptyList()
        return entries.mapIndexed { idx, entry ->
            TranslatedBubble(
                rect = entry.rect,
                originalText = entry.originalText ?: originals.getOrElse(idx) { "" },
                translatedText = entry.translatedText ?: translations.getOrElse(idx) { "" },
                backgroundColor = bgColor,
                fontSize = entry.fontSize,
                direction = entry.direction,
                angle = entry.angle,
                centerX = entry.centerX,
                centerY = entry.centerY,
                fromCache = true
            )
        }
    }
}

/** 气泡 JSON 条目（与 parseBubbleEntriesJson 配对） */
data class BubbleJsonEntry(
    val rect: Rect,
    val fontSize: Float,
    val direction: TextDirection,
    val angle: Float = 0f,
    val centerX: Float = rect.exactCenterX(),
    val centerY: Float = rect.exactCenterY(),
    val originalText: String? = null,
    val translatedText: String? = null
)
