package com.moe.starflow.manga.state
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.*
import com.moe.starflow.manga.render.*
import com.moe.starflow.manga.merge.*

import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import com.moe.starflow.utils.TextSimilarity

/**
 * 区域级翻译缓存（文本相似度匹配复用），从 MangaFloatingService 阶段 4a 提取。
 * 纯逻辑：无 UI/服务依赖，模糊匹配算法可独立测试。
 *
 * ⚠️ **必须线程安全**：实例是**跨页共享**的（阅读器控制器把它交给 N 个并发翻译工人，
 * 见 `ReaderTranslationController.textCache()`）。裸 `mutableListOf` 会让「A 在 findExact
 * 迭代」与「B 在 add」撞出 `ConcurrentModificationException`（被上层 catch 成
 * PROCESS_EXCEPTION，表现为某一页偶发翻译失败，换一批又好了）。所有读写一律
 * `@Synchronized`，**不要加无锁的快捷方法**。
 */
class RegionCacheManager {
    data class TranslatedRegion(
        val ocrText: String,
        val ocrTextHash: Int,
        val translation: String,
        val translatedAt: Long = System.currentTimeMillis()
    )

    private val _regions = mutableListOf<TranslatedRegion>()

    /** 快照（不是活视图）：调用方拿着它迭代时不会被别的线程的结构性修改掀翻。 */
    val regions: List<TranslatedRegion>
        @Synchronized get() = _regions.toList()

    companion object {
        const val MAX_CACHED_REGIONS = 50           // 最大缓存区域数
        const val REGION_IOU_THRESHOLD = 0.4f       // 区域重叠判定阈值
        const val REGION_TTL_MS = 300_000L          // 区域缓存有效期 5 分钟
    }

    @Synchronized
    fun size(): Int = _regions.size

    /** 加入缓存（自动淘汰最早的超限区域）。 */
    @Synchronized
    fun add(region: TranslatedRegion) {
        _regions.add(region)
        evictOldRegions()
    }

    @Synchronized
    fun remove(region: TranslatedRegion) {
        _regions.remove(region)
    }

    @Synchronized
    fun clear() {
        _regions.clear()
    }

    /** 精确匹配（hashCode + 文本一致）。 */
    @Synchronized
    fun findExact(text: String, hash: Int): TranslatedRegion? =
        _regions.find { it.ocrTextHash == hash && it.ocrText == text }

    /** 淘汰过旧的缓存区域（超出 MAX_CACHED_REGIONS）。 */
    private fun evictOldRegions() {
        if (_regions.size > MAX_CACHED_REGIONS) {
            val removeCount = _regions.size - MAX_CACHED_REGIONS
            repeat(removeCount) { _regions.removeAt(0) }
        }
    }

    /** 清除过期的缓存区域（超过 TTL）。 */
    @Synchronized
    fun evictExpiredRegions(now: Long = System.currentTimeMillis()) {
        _regions.removeAll { now - it.translatedAt > REGION_TTL_MS }
    }

    /**
     * 模糊文本匹配：查找与 targetText 最相似的条目。
     * 使用 OCR 感知的加权编辑距离，相似字符（如カ/力）替换代价更低。
     *
     * @return 最佳匹配的 TranslatedRegion，或 null。
     */
    @Synchronized
    fun findFuzzyMatch(targetText: String): TranslatedRegion? {
        if (targetText.isEmpty()) return null

        // 获取自适应阈值
        val threshold = TextSimilarity.getAdaptiveThreshold(targetText.length)
        if (threshold <= 0.0f) return null  // 太短，必须精确匹配

        val normalizedTarget = TextSimilarity.normalize(targetText)
        if (normalizedTarget.isEmpty()) return null

        var bestMatch: TranslatedRegion? = null
        var bestDistance = threshold + 1.0f
        var checkedCount = 0
        var lengthSkipCount = 0

        for (region in _regions) {
            val normalizedCache = TextSimilarity.normalize(region.ocrText)

            // 长度快速过滤
            if (kotlin.math.abs(normalizedTarget.length - normalizedCache.length).toFloat() > threshold) {
                lengthSkipCount++
                continue
            }

            // 加权编辑距离（带 early exit）
            val distance = TextSimilarity.weightedLevenshtein(normalizedTarget, normalizedCache, bestDistance)
            checkedCount++
            if (distance < bestDistance) {
                bestDistance = distance
                bestMatch = region
            }
        }

        return if (bestMatch != null && bestDistance <= threshold) bestMatch else null
    }
}
