package com.moe.starflow.manga.engine
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.state.*
import com.moe.starflow.manga.render.*
import com.moe.starflow.manga.merge.*
import com.moe.starflow.manga.*

import com.moe.starflow.manga.config.*
import android.graphics.Rect
import android.graphics.RectF
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.types.BubbleRegion
import com.moe.starflow.manga.types.TextDirection
import com.moe.starflow.utils.LogCollector
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 气泡检测器 — 基于 manga-image-translator 的 textline_merge 算法。
 *
 * 流程：TextBlockInfo → TextLine → 图构建（connected components）→ MST 分割 → majority vote 方向 → 阅读排序。
 *
 * 参考：`.reference/manga-image-translator/manga_translator/textline_merge/__init__.py`
 */
object BubbleDetector {

    private const val TAG = "BubbleDetector"

    /**
     * 内部表示：检测到的一行文字。
     * 对应 manga-image-translator 的 Quadrilateral（AABB 特化版本）。
     */
    private data class TextLine(
        val rect: RectF,
        val fontSize: Float,
        val isVertical: Boolean,
        val angle: Float = 0f,  // AABB 角度为 0
        val text: String = ""
    ) {
        val centroidX: Float get() = rect.centerX()
        val centroidY: Float get() = rect.centerY()
        val aspectRatio: Float get() = if (rect.height() > 0) rect.width() / rect.height() else 1f
        val direction: Char get() = if (isVertical) 'v' else 'h'
    }

    // ========== 边缘对齐检查（对应 generic.py L673-685） ==========

    /**
     * 检查两个横排 box 是否边缘对齐（左/右边缘）。
     * 对应 Python: abs(x1 - x2) < charSize * tol or abs(x1+w1 - (x2+w2)) < charSize * tol
     */
    private fun hEdgeAligned(a: TextLine, b: TextLine, charSize: Float, tol: Float): Boolean {
        val aLeft = a.rect.left
        val aRight = a.rect.right
        val bLeft = b.rect.left
        val bRight = b.rect.right
        return abs(aLeft - bLeft) < charSize * tol ||
               abs(aRight - bRight) < charSize * tol
    }

    /**
     * 检查两个竖排 box 是否边缘对齐（上/下边缘）。
     * 对应 Python: abs(y1 - y2) < charSize * tol or abs(y1+h1 - (y2+h2)) < charSize * tol
     */
    private fun vEdgeAligned(a: TextLine, b: TextLine, charSize: Float, tol: Float): Boolean {
        val aTop = a.rect.top
        val aBottom = a.rect.bottom
        val bTop = b.rect.top
        val bBottom = b.rect.bottom
        return abs(aTop - bTop) < charSize * tol ||
               abs(aBottom - bBottom) < charSize * tol
    }

    /**
     * 对应 manga-image-translator 的 quadrilateral_can_merge_region（AABB 特化版）。
     *
     * 逐行对照 generic.py L653-698：
     * - L663: dist > discard_connection_gap * char_size → False
     * - L665: font_size_ratio > tol → False
     * - L667-670: aspect_ratio 交叉检查
     * - L673-685: axis-aligned 分支（边缘对齐 + 横竖排互斥检查）
     *
     * dispatch 调用参数：aspect_ratio_tol=1.3, font_size_ratio_tol=2,
     *   char_gap_tolerance=1, char_gap_tolerance2=3（ratio=1.9, discard_connection_gap=2 用默认值）
     */
    private fun canMergeRegion(a: TextLine, b: TextLine): Boolean {
        val charSize = min(a.fontSize, b.fontSize)
        if (charSize <= 0f) return false

        // --- 默认参数（generic.py L653 签名） ---
        val ratio = 1.9f                  // ratio = 1.9
        val discardConnectionGap = 2f     // discard_connection_gap = 2
        // --- dispatch 调用覆盖 ---
        val charGapTolerance = 1f         // char_gap_tolerance = 1
        val charGapTolerance2 = 3f        // char_gap_tolerance2 = 3
        val fontSizeRatioTol = 2f         // font_size_ratio_tol = 2
        val aspectRatioTol = 1.3f         // aspect_ratio_tol = 1.3

        // L660-662: 用 polygon distance（AABB 特化 = AABB 距离）
        val dist = aabbDistance(a, b)

        // L663-664: 距离太远
        if (dist > discardConnectionGap * charSize) return false

        // L665-666: 字体大小差异太大
        if (max(a.fontSize, b.fontSize) / charSize > fontSizeRatioTol) return false

        // L667-670: 宽高比交叉检查（一个很横 + 一个很竖 → 不合并）
        if (a.aspectRatio > aspectRatioTol && b.aspectRatio < 1f / aspectRatioTol) return false
        if (b.aspectRatio > aspectRatioTol && a.aspectRatio < 1f / aspectRatioTol) return false

        // L671-672: AABB 始终 axis-aligned
        // L673: if a_aa and b_aa:
        // L674:   if dist < char_size * char_gap_tolerance:
        if (dist < charSize * charGapTolerance) {
            // L675-676: 中心 X 对齐（像素级，不乘 charSize）
            val w1 = a.rect.width()
            val h1 = a.rect.height()
            val w2 = b.rect.width()
            val h2 = b.rect.height()
            val x1 = a.rect.left
            val x2 = b.rect.left

            // L675: abs(x1 + w1//2 - (x2 + w2//2)) < char_gap_tolerance2
            if (abs(x1 + w1 / 2 - (x2 + w2 / 2)) < charGapTolerance2) return true

            // L677-678: 一个横排 + 一个竖排 → 不合并
            if (w1 > h1 * ratio && h2 > w2 * ratio) return false
            if (w2 > h2 * ratio && h1 > w1 * ratio) return false

            // L681-682: 两个都横排 → 检查左右边缘对齐
            if (w1 > h1 * ratio || w2 > h2 * ratio) {
                return hEdgeAligned(a, b, charSize, charGapTolerance2)
            }
            // L683-684: 两个都竖排 → 检查上下边缘对齐
            if (h1 > w1 * ratio || h2 > w2 * ratio) {
                return vEdgeAligned(a, b, charSize, charGapTolerance2)
            }
            // L685: 不横不竖 → 不合并
            return false
        }
        // L686-687: dist >= charSize * charGapTolerance
        return false
    }

    // ========== MST 计算（对应 Python networkx MST） ==========

    /**
     * Kruskal MST 边。
     */
    private data class Edge(val u: Int, val v: Int, val weight: Float)

    /**
     * 并查集。
     */
    private class UnionFind(elements: Collection<Int>) {
        private val parent = elements.associateWith { it }.toMutableMap()
        private val rank = elements.associateWith { 0 }.toMutableMap()

        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]!!
            var node = x
            while (node != root) {
                val next = parent[node]!!
                parent[node] = root
                node = next
            }
            return root
        }

        fun union(x: Int, y: Int): Boolean {
            val rx = find(x)
            val ry = find(y)
            if (rx == ry) return false
            val rankX = rank[rx]!!
            val rankY = rank[ry]!!
            when {
                rankX < rankY -> parent[rx] = ry
                rankX > rankY -> parent[ry] = rx
                else -> { parent[ry] = rx; rank[rx] = rankX + 1 }
            }
            return true
        }
    }

    /**
     * Kruskal MST：从完全图中提取最小生成树。
     * 对应 Python: nx.algorithms.tree.minimum_spanning_edges(G, algorithm='kruskal', data=True)
     *
     * @return MST 的 N-1 条边，按 weight 升序
     */
    private fun kruskalMST(indices: List<Int>, lines: List<TextLine>): List<Edge> {
        // 构建所有边
        val allEdges = mutableListOf<Edge>()
        for (i in indices.indices) {
            for (j in i + 1 until indices.size) {
                val u = indices[i]
                val v = indices[j]
                allEdges.add(Edge(u, v, aabbDistance(lines[u], lines[v])))
            }
        }
        // 按 weight 升序排列（Kruskal 贪心）
        allEdges.sortBy { it.weight }

        // 贪心选边
        val uf = UnionFind(indices)
        val mstEdges = mutableListOf<Edge>()
        for (edge in allEdges) {
            if (uf.union(edge.u, edge.v)) {
                mstEdges.add(edge)
                if (mstEdges.size == indices.size - 1) break
            }
        }
        return mstEdges
    }

    // ========== split_text_region（对应 textline_merge/__init__.py L10-83） ==========

    /**
     * 对应 manga-image-translator 的 split_text_region。
     * 用 MST + 标准差判断是否需要拆分。
     *
     * 逐行对照 Python：
     * - L20-23: case 1（单元素）
     * - L25-39: case 2（双元素，距离+角度检查）
     * - L42-49: case 3+（完全图 → MST → 排序 → 检查最大边）
     * - L50-54: 统计量（fontsize, std, mean, std_threshold）
     * - L56-59: max_poly_distance, max_centroid_alignment
     * - L66-70: should_keep 条件
     * - L72-83: 拆分（去掉最大边 → 递归）
     */
    private fun splitTextRegion(
        lines: List<TextLine>,
        connectedIndices: Set<Int>,
        gamma: Float = 0.5f,
        sigma: Float = 2f
    ): List<Set<Int>> {
        val indices = connectedIndices.toList()

        // L20-23: case 1
        if (indices.size == 1) return listOf(setOf(indices[0]))

        // L25-39: case 2
        if (indices.size == 2) {
            val a = lines[indices[0]]
            val b = lines[indices[1]]
            val fs = max(a.fontSize, b.fontSize)
            val dist = aabbDistance(a, b)
            val angleDiff = abs(a.angle - b.angle)
            return if (dist < (1 + gamma) * fs && angleDiff < 0.2f * Math.PI.toFloat()) {
                listOf(setOf(indices[0], indices[1]))
            } else {
                listOf(setOf(indices[0]), setOf(indices[1]))
            }
        }

        // L42-49: case 3+ — 构建 MST
        val mstEdges = kruskalMST(indices, lines)
        if (mstEdges.isEmpty()) return listOf(connectedIndices)

        // L49: 按 weight 降序排列 MST 边
        val sortedEdges = mstEdges.sortedByDescending { it.weight }
        val distances = sortedEdges.map { it.weight }

        // L51-54: 统计量
        val fontSize = lines.slice(indices).map { it.fontSize }.average().toFloat()
        val distancesStd = if (distances.size > 1) {
            val mean = distances.average().toFloat()
            sqrt(distances.map { (it - mean) * (it - mean) }.average()).toFloat()
        } else 0f
        val distancesMean = distances.average().toFloat()
        val stdThreshold = max(0.3f * fontSize + 5f, 5f)

        // L56-59: max_poly_distance, max_centroid_alignment
        val maxEdge = sortedEdges.first()
        val b1 = lines[maxEdge.u]
        val b2 = lines[maxEdge.v]
        val maxPolyDist = aabbDistance(b1, b2)
        val maxCentroidAlignment = min(
            abs(b1.centroidX - b2.centroidX),
            abs(b1.centroidY - b2.centroidY)
        )

        // L66-70: should_keep 条件
        val shouldKeep = (maxEdge.weight <= distancesMean + distancesStd * sigma
                || maxEdge.weight <= fontSize * (1 + gamma))
                && (distancesStd < stdThreshold
                || (maxPolyDist == 0f && maxCentroidAlignment < 5f))

        if (shouldKeep) {
            // L70: 不拆分
            return listOf(connectedIndices)
        } else {
            // L72-83: 拆分 — 去掉最大 MST 边，用剩余 MST 边构建子图
            // 对应 Python:
            //   G = nx.Graph()
            //   for idx in connected_region_indices: G.add_node(idx)
            //   for edge in edges[1:]: G.add_edge(edge[0], edge[1])
            val remainingEdges = sortedEdges.drop(1)

            // 用剩余 MST 边构建并查集 → 找连通分量
            val uf = UnionFind(indices)
            for (edge in remainingEdges) {
                uf.union(edge.u, edge.v)
            }

            val components = mutableMapOf<Int, MutableSet<Int>>()
            for (idx in indices) {
                val root = uf.find(idx)
                components.getOrPut(root) { mutableSetOf() }.add(idx)
            }

            // L81-82: 递归拆分
            val result = mutableListOf<Set<Int>>()
            for (component in components.values) {
                result.addAll(splitTextRegion(lines, component, gamma, sigma))
            }
            return result
        }
    }

    // ========== AABB 距离 ==========

    private fun aabbDistance(a: TextLine, b: TextLine): Float {
        val dx = max(0f, max(a.rect.left - b.rect.right, b.rect.left - a.rect.right))
        val dy = max(0f, max(a.rect.top - b.rect.bottom, b.rect.top - a.rect.bottom))
        return sqrt(dx * dx + dy * dy)
    }

    // ========== 主入口 ==========

    /**
     * 检测气泡区域（带 config 版本）。
     *
     * 流程（对齐 textline_merge/__init__.py 的 dispatch）：
     * 1. TextBlockInfo → TextLine（对应 Quadrilateral）
     * 2. canMergeRegion 构建图 → connected components（L128-136）
     * 3. splitTextRegion MST 拆分（L139-141）
     * 4. majority vote + 排序 + 合并文字（L144-182）
     */
    fun detectBubbles(textBlocks: List<TextBlockInfo>, config: MangaModeConfig): List<BubbleRegion> {
        if (textBlocks.isEmpty()) return emptyList()
        LogCollector.d(TAG, "detectBubbles: 输入 ${textBlocks.size} 个文字块")

        // Step 1: TextBlockInfo → TextLine
        val textLines = textBlocks.map { block ->
            val rect = if (block.boundingBox != null) {
                RectF(block.boundingBox)
            } else if (block.cornerPoints != null && block.cornerPoints.size >= 4) {
                val xs = block.cornerPoints.map { it.x.toFloat() }
                val ys = block.cornerPoints.map { it.y.toFloat() }
                RectF(xs.min(), ys.min(), xs.max(), ys.max())
            } else {
                RectF(0f, 0f, 0f, 0f)
            }
            // ML Kit 的 block.isVertical 通常是 null（OCRBridge 不设置）。
            // 方向推断统一走 block.inferredVertical()：cornerPoints 真实边长（抗旋转，对齐 PP-OCR），
            // 缺失才用 AABB 宽高比。⚠️ 不能用 config.textDirection——它恒为 RL/LR，会把横排块全部误判成竖排。
            val isVertical = block.inferredVertical()
            LogCollector.d(TAG, "detectBubbles: block='${block.text.take(15)}' bbox=${rect} h${rect.height()} w${rect.width()} → vertical=$isVertical")
            val fontSize = min(rect.width(), rect.height()).toFloat()
            TextLine(rect = rect, fontSize = fontSize, isVertical = isVertical, text = block.text)
        }

        return doDetect(textLines, config.verticalFlow.toTextDirection())
    }

    /**
     * 无 config 版本，从 TextBlockInfo.isVertical 推断方向。
     */
    fun detectBubbles(textBlocks: List<TextBlockInfo>): List<BubbleRegion> {
        if (textBlocks.isEmpty()) return emptyList()
        LogCollector.d(TAG, "detectBubbles(no config): 输入 ${textBlocks.size} 个文字块")

        val textLines = textBlocks.map { block ->
            val rect = if (block.boundingBox != null) {
                RectF(block.boundingBox)
            } else if (block.cornerPoints != null && block.cornerPoints.size >= 4) {
                val xs = block.cornerPoints.map { it.x.toFloat() }
                val ys = block.cornerPoints.map { it.y.toFloat() }
                RectF(xs.min(), ys.min(), xs.max(), ys.max())
            } else {
                RectF(0f, 0f, 0f, 0f)
            }
            val isVertical = block.inferredVertical()
            val fontSize = min(rect.width(), rect.height()).toFloat()
            TextLine(rect = rect, fontSize = fontSize, isVertical = isVertical, text = block.text)
        }

        return doDetect(textLines)
    }

    /**
     * 核心检测逻辑（两个 detectBubbles 重载共用）。
     * @param verticalDirection 竖排时的列方向（RL 或 LR），由调用方根据 config 传入
     */
    private fun doDetect(textLines: List<TextLine>, verticalDirection: TextDirection = TextDirection.VERTICAL_RL): List<BubbleRegion> {
        val n = textLines.size

        // Step 2: canMergeRegion 构建图 → connected components
        // 对应 textline_merge/__init__.py L128-136
        val adjacency = Array(n) { mutableSetOf<Int>() }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (canMergeRegion(textLines[i], textLines[j])) {
                    adjacency[i].add(j)
                    adjacency[j].add(i)
                }
            }
        }

        val visited = BooleanArray(n)
        val connectedComponents = mutableListOf<Set<Int>>()
        for (i in 0 until n) {
            if (visited[i]) continue
            val component = mutableSetOf<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(i)
            visited[i] = true
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                component.add(node)
                for (neighbor in adjacency[node]) {
                    if (!visited[neighbor]) {
                        visited[neighbor] = true
                        queue.add(neighbor)
                    }
                }
            }
            connectedComponents.add(component)
        }
        LogCollector.d(TAG, "doDetect: 连通分量 ${connectedComponents.size} 个")

        // Step 3: splitTextRegion MST 拆分
        // 对应 textline_merge/__init__.py L139-141
        val regionIndices = mutableListOf<Set<Int>>()
        for (component in connectedComponents) {
            regionIndices.addAll(splitTextRegion(textLines, component))
        }
        LogCollector.d(TAG, "doDetect: 拆分后 ${regionIndices.size} 个区域")

        // Step 4: 构建 BubbleRegion
        // 对应 textline_merge/__init__.py L144-182
        val bubbles = mutableListOf<BubbleRegion>()
        for (nodeSet in regionIndices) {
            val nodes = nodeSet.toList()
            val lines = nodes.map { textLines[it] }

            // L158-172: majority vote 方向
            val directionCounts = lines.groupBy { it.direction }.mapValues { it.value.size }
            val majorityDir = directionCounts.maxByOrNull { it.value }?.key ?: 'h'

            // L175-178: 排序（横排 Y 升序恒左→右；竖排按 `Manga_Text_Direction` 取列序）
            // ⚠️ ML Kit 与 PP 的区别：PP 是行列级识别器（每 box = 一行/一列，几何直接可判）；
            // ML Kit 的 block 是它版式分析的产物，**顺序插不进去** —— 所以这里只能对
            // 「本函数合并出来的组」内部的成员行重排。若 ML Kit 把一个竖排整列当成**单个 block**，
            // 该列的读序就封在 block 内部、这里无从干预（见 `OCRTextRecognizer` 的方向重排版）。
            val isRl = verticalDirection != TextDirection.VERTICAL_LR
            val sortedNodes = if (majorityDir == 'h') {
                nodes.sortedBy { textLines[it].centroidY }
            } else if (isRl) {
                nodes.sortedByDescending { textLines[it].centroidX }
            } else {
                nodes.sortedBy { textLines[it].centroidX }
            }

            // 合并文字
            val combinedTexts = sortedNodes.map { textLines[it].text }
            if (combinedTexts.all { it.isBlank() }) continue

            // 合并 bounding box
            val rects = sortedNodes.map { textLines[it].rect }
            val unionRect = RectF(
                rects.minOf { it.left },
                rects.minOf { it.top },
                rects.maxOf { it.right },
                rects.maxOf { it.bottom }
            )

            // L199: font_size = min
            val minFontSize = lines.minOf { it.fontSize }

            val textDirection = if (majorityDir == 'v') verticalDirection else TextDirection.HORIZONTAL

            val unionRectInt = Rect(
                unionRect.left.toInt(), unionRect.top.toInt(),
                unionRect.right.toInt(), unionRect.bottom.toInt()
            )

            bubbles.add(BubbleRegion(rect = unionRectInt, texts = combinedTexts, fontSize = minFontSize, direction = textDirection))
            LogCollector.d(TAG, "doDetect: 区域 '${combinedTexts.first().take(20)}' fs=${String.format("%.1f", minFontSize)}, dir=$textDirection, lines=${lines.size}")
        }

        LogCollector.d(TAG, "doDetect: 输出 ${bubbles.size} 个气泡")
        return bubbles
    }
}
