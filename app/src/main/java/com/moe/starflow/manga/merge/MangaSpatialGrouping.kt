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
     * `CroppedBubble`（RT-DETR-V2 路径）的阅读顺序：从上到下，同高从右到左。
     *
     * ⚠️ **不要**把它并到 [sortByReadingOrder] 里 —— 两者的输入语义不同：
     * - 本函数的输入是**气泡**：气泡的宽高比*说明不了*阅读方向
     *   （一个 `w>h` 的气泡完全可能装的是竖排多列），日漫并排气泡就是要右→左
     * - [sortByReadingOrder] 的输入是**文字行/列**：`w>h` 即横排文字，恒左→右
     *
     * 曾把两者合并、给气泡套上「横排恒左→右」，直接把日漫的并排气泡读反。
     *
     * ⚠️ 已知脆弱点（未修，见问题报告）：`top` 是主键、`left` 仅在 top **完全相等**时
     * 才参与比较 —— 并排气泡顶部差 1px，列序就由噪声决定。真正的修法是按行分组后
     * 组内再取列序（`MangaFloatingService` 的 P1/P2 已有类似做法），风险较大，未动。
     */
    fun sortByMangaReadingOrder(bubbles: List<CroppedBubble>): List<CroppedBubble> =
        bubbles.sortedWith(
            compareBy<CroppedBubble> { it.rect.top }
                .thenByDescending { it.rect.left }
        )

    /**
     * 按阅读顺序排序：**竖排按列序取 x 主键**、横排按行取 y 主键。
     *
     * ⚠️ **竖排的主键必须是 x，不能是 y。**
     * 曾写成 `compareBy { top }.thenByDescending { left }` —— 那对横排是对的，
     * 但对竖排是错的：真实竖排各列的 `top` 不会像素级齐平，只要有一点差异，
     * `left` 这个次级键**永远不参与比较** → 列序完全由 y 噪声决定、设置静默失效。
     * 规则与 [com.moe.starflow.manga.engine.PPOcrDetGeometry.sortDetCandidates] 保持一致：
     * - 竖排（高 > 宽）：主键 x（RL 降序 / LR 升序），次键 y（同列上→下）
     * - 横排（宽 > 高）：主键 y（上→下），次键 x（行内左→右）—— **不受设置影响**
     *
     * 竖排在前、横排在后（同页混排时保证竖排内容先被读到）。
     */
    fun <T> sortByReadingOrder(
        items: List<T>,
        getRect: (T) -> Rect,
        verticalDirection: TextDirection
    ): List<T> {
        if (items.size <= 1) return items
        val isRl = verticalDirection != TextDirection.VERTICAL_LR
        fun isVertical(r: Rect) = r.height() > r.width() + ORIENT_ASPECT_TOL
        return items.sortedWith { a, b ->
            val ra = getRect(a)
            val rb = getRect(b)
            val va = isVertical(ra)
            val vb = isVertical(rb)
            when {
                va && vb -> {
                    val xa = if (isRl) -ra.right else ra.left
                    val xb = if (isRl) -rb.right else rb.left
                    if (xa != xb) xa.compareTo(xb) else ra.top.compareTo(rb.top)
                }
                !va && !vb -> {
                    if (ra.top != rb.top) ra.top.compareTo(rb.top)
                    else ra.left.compareTo(rb.left)
                }
                else -> if (va) -1 else 1
            }
        }
    }

    /** 竖/横排判定的宽高容差（px）：差值小于它视为歧义框，按横排（左→右）处理。 */
    private const val ORIENT_ASPECT_TOL = 2

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
