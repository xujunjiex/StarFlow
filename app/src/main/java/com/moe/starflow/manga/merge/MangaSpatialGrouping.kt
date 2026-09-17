package com.moe.starflow.manga.merge
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.state.*
import com.moe.starflow.manga.*

import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import android.graphics.Rect
import com.moe.starflow.utils.LogCollector

/**
 * 漫画空间聚类/分批切分纯算法（从 MangaFloatingService 阶段 1 提取）。
 * 无服务/UI 依赖，全部输入参数化，可独立测试。
 */
object MangaSpatialGrouping {
    private const val TAG = "MangaSpatialGrouping"

    const val CLUSTER_THRESHOLD = 250f   // 空间聚类加权距离阈值

    // 单字符噪声类别（标点、符号）
    private val SINGLE_CHAR_NOISE_CATEGORIES = setOf(
        CharCategory.OTHER_PUNCTUATION,
        CharCategory.DASH_PUNCTUATION,
        CharCategory.START_PUNCTUATION,
        CharCategory.END_PUNCTUATION,
        CharCategory.MATH_SYMBOL,
        CharCategory.OTHER_SYMBOL
    )

    /**
     * `CroppedBubble` 的阅读顺序排序：从上到下、从右到左。
     *
     * ⚠️ **固定右→左，不随 `Manga_Text_Direction` 变** —— 唯一调用方是 RT-DETR-V2
     * 增量路径（`IncrementalBatchPipeline.rtDetrMangaOcr`），而 RT-DETR 只识别日文，
     * 右→左就是它唯一正确的列序。**不要**给它接 `config.textDirection`。
     *
     * 需要按设置取列序的是 **PP-OCRv5/v6** 路径，走 [sortByReadingOrder]。
     */
    fun sortByMangaReadingOrder(bubbles: List<CroppedBubble>): List<CroppedBubble> =
        sortByReadingOrder(bubbles, { it.rect }, TextDirection.VERTICAL_RL)

    /**
     * 按阅读顺序排序：从上到下 + 列序随 [verticalDirection]。
     *
     * ⚠️ **只给 PP-OCRv5/v6 路径用**（当前是 `DetectionBridge.detectAndCropPPOcrV5/V6Lines`）。
     * `Manga_Text_Direction` 这个设置**只适配 PP 系列模型** —— ML Kit 不适合复杂漫画场景、
     * RT-DETR 只认日文，两者都固定右→左，不接这个参数。
     *
     * 泛型是因为有多个载体（`CroppedBubble` / `CroppedTextLine`）要按同一规则排；
     * 曾在别处各写一份（`DetectionBridge` 两个 detectAndCrop 写死右→左），
     * 新增载体请走这里，不要再抄一份比较器。
     */
    fun <T> sortByReadingOrder(
        items: List<T>,
        getRect: (T) -> Rect,
        verticalDirection: TextDirection
    ): List<T> {
        val isRl = verticalDirection != TextDirection.VERTICAL_LR
        return items.sortedWith(
            compareBy<T> { getRect(it).top }
                .thenByDescending { if (isRl) getRect(it).left else -getRect(it).left }
        )
    }

    class UnionFind(n: Int) {
        private val parent = IntArray(n) { it }
        private val rank = IntArray(n)
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var i = x
            while (i != r) { val p = parent[i]; parent[i] = r; i = p }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra == rb) return
            when {
                rank[ra] < rank[rb] -> parent[ra] = rb
                rank[ra] > rank[rb] -> parent[rb] = ra
                else -> { parent[rb] = ra; rank[ra]++ }
            }
        }
    }

    /**
     * 按 AABB 空间距离聚类，加权距离 dy×5 + dx。
     * 垂直接近的行更容易归为同一组（漫画同行文字水平可远但垂直接近）。
     */
    fun <T> groupByProximity(sorted: List<T>, getRect: (T) -> Rect, tag: String): List<List<T>> {
        if (sorted.size <= 1) return listOf(sorted)
        val rects = sorted.map { getRect(it) }
        val uf = UnionFind(sorted.size)
        for (i in rects.indices) {
            for (j in i + 1 until rects.size) {
                val ri = rects[i]; val rj = rects[j]
                val dx = maxOf(0, maxOf(rj.left - ri.right, ri.left - rj.right))
                val dy = maxOf(0, maxOf(rj.top - ri.bottom, ri.top - rj.bottom))
                if (dy * 5f + dx < CLUSTER_THRESHOLD) uf.union(i, j)
            }
        }
        val groups = mutableMapOf<Int, MutableList<T>>()
        for (i in sorted.indices) groups.getOrPut(uf.find(i)) { mutableListOf() }.add(sorted[i])
        val result = groups.values.toList()
        LogCollector.d(TAG, "groupByProximity($tag): ${sorted.size} 行 → ${result.size} 组 ${result.joinToString { "${it.size}行" }}")
        return result
    }

    /** 按组边界切分，不拆开任何组。 */
    fun <T> splitAtGroupBoundaries(groups: List<List<T>>, fraction: Int = 2, divisor: Int = 5): Pair<List<T>, List<T>> {
        val total = groups.sumOf { it.size }
        val target = total * fraction / divisor
        var cum = 0; var splitIdx = 0
        for ((i, g) in groups.withIndex()) { cum += g.size; if (cum >= target) { splitIdx = i + 1; break } }
        if (splitIdx == 0 && groups.isNotEmpty()) splitIdx = 1
        val first = groups.take(splitIdx).flatten()
        val second = groups.drop(splitIdx).flatten()
        LogCollector.d(TAG, "splitAtGroupBoundaries: target=$target, 第一批=${first.size} (${splitIdx}组), 第二批=${second.size} (${groups.size - splitIdx}组)")
        return first to second
    }

    /**
     * 将 TextBlockInfo 列表转换为 BubbleRegion 列表。
     * textDirection 由调用方传入（原 MangaFloatingService 内部读 config.textDirection）。
     */
    fun textBlocksToBubbleRegions(
        textBlocks: List<TextBlockInfo>,
        textDirection: TextDirection
    ): List<BubbleRegion> {
        return textBlocks.filter { block ->
            if (block.boundingBox == null) return@filter false
            // 过滤单字符纯标点噪声
            val cleaned = block.text.replace("\n", "").trim()
            if (cleaned.length == 1 && cleaned[0].category in SINGLE_CHAR_NOISE_CATEGORIES) {
                LogCollector.d(TAG, "过滤单字符噪声: \"$cleaned\" [${block.boundingBox}]")
                return@filter false
            }
            true
        }.map { block ->
            val rect = block.boundingBox!!
            val isVertical = block.inferredVertical()  // 真实边长推断（抗旋转），缺失才 AABB；ML Kit 路径的竖排误判源头
            BubbleRegion(
                rect = rect,
                texts = listOf(block.text),
                fontSize = if (isVertical) rect.width().toFloat() else rect.height().toFloat(),
                direction = if (isVertical) textDirection else TextDirection.HORIZONTAL,
                angle = block.angle,
                centerX = block.centerX,
                centerY = block.centerY
            )
        }
    }
}
