package com.moe.starflow.manga.merge
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.state.*
import com.moe.starflow.manga.*

import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import com.moe.starflow.utils.LogCollector
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * PP-OCRv5 文字行/区域合并器。
 *
 * 对齐 manga-image-translator textline_merge 算法：
 * - quadrilateral_can_merge_region()（generic.py:653-698）
 * - merge_bboxes_text_region()（textline_merge/__init__.py:110-182）
 * - split_text_region()（textline_merge/__init__.py:10-83）
 *
 * **统一入口**：OCR 前/后都通过 merge() 入口；text 字段决定是否拼接文字。
 *
 * **调试日志**：受 enableDebugLogging() 控制，默认关闭，零开销。
 */
object TextRegionMerger {

    private const val TAG = "TextRegionMerger"

    // ========== 硬编码参数（对齐 manga 调用值） ==========
    private const val RATIO = 1.9f                   // 方向判断阈值
    private const val ASPECT_RATIO_TOL = 1.3f        // 长宽比交叉阈值（manga 调用 1.3）
    private const val CHAR_GAP_TOLERANCE = 1f        // AA 分支 char gap（manga 调用 1）
    private const val FONT_SIZE_RATIO_AA = 2.0f      // AA 分支字号比（manga 调用 2.0）
    private const val TILTED_ANGLE_DIFF_MAX = 15f    // 15° 倾斜角度差
    private const val TILTED_FS_DIFF_MAX = 0.25f     // 字号差比

    // ========== 可调参数 ==========
    @Volatile private var discardConnectionGap: Float = MergeParams.DISCARD_CONNECTION_GAP_DEFAULT
    @Volatile private var charGapTolerance2: Float = MergeParams.CHAR_GAP_TOLERANCE2_DEFAULT
    @Volatile private var debugEnabled: Boolean = false
    /** 文本合并总开关：关闭时跳过合并（每个区域独立成组），适合表格/多栏等场景 */
    @Volatile private var mergeEnabled: Boolean = true

    /**
     * 启用/禁用调试日志（默认关闭，零开销）。
     */
    fun enableDebugLogging(enabled: Boolean) {
        debugEnabled = enabled
    }

    /** 是否启用文本合并（关闭时跳过合并）。默认开启。 */
    fun isMergeEnabled(): Boolean = mergeEnabled

    /**
     * 从 SharedPreferences 刷新可调参数。
     */
    fun refreshParams(context: Context) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        discardConnectionGap = prefs.getFloat(
            "merge_discard_gap",
            MergeParams.DISCARD_CONNECTION_GAP_DEFAULT
        ).coerceIn(MergeParams.MIN_DISCARD_GAP, MergeParams.MAX_DISCARD_GAP)
        charGapTolerance2 = prefs.getFloat(
            "merge_char_gap2",
            MergeParams.CHAR_GAP_TOLERANCE2_DEFAULT
        ).coerceIn(MergeParams.MIN_CHAR_GAP2, MergeParams.MAX_CHAR_GAP2)
        mergeEnabled = prefs.getBoolean("Manga_Text_Merge", true)
    }

    /**
     * 重置参数为默认值。
     */
    fun resetParams(context: Context) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .putFloat("merge_discard_gap", MergeParams.DISCARD_CONNECTION_GAP_DEFAULT)
            .putFloat("merge_char_gap2", MergeParams.CHAR_GAP_TOLERANCE2_DEFAULT)
            .apply()
        refreshParams(context)
    }

    // ========== 工具类 ==========

    /**
     * 加权平均。
     */
    private fun weightedAverage(values: List<Float>, weights: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val totalWeight = weights.sum()
        if (totalWeight <= 0f) return values.average().toFloat()
        return values.zip(weights).sumOf { (v, w) -> (v * w).toDouble() }.toFloat() / totalWeight
    }

    /**
     * AABB 距离（Chebyshev 距离）：两个 AABB 之间的最大轴向间隙。
     * 对齐 Shapely Polygon.distance()，重叠时返回 0。
     */
    private fun aabbDistance(a: TextRegion, b: TextRegion): Float {
        val ra = a.quad.aabb
        val rb = b.quad.aabb
        val dx = max(0, max(rb.left - ra.right, ra.left - rb.right))
        val dy = max(0, max(rb.top - ra.bottom, ra.top - rb.bottom))
        return max(dx, dy).toFloat()
    }

    /**
     * 从 quad 顶边向量计算文字角度（弧度）。
     * 不使用 QuadBox.angle（结构线方向可能反向 180°）。
     * 对齐 ocrResultToTextLines 的 atan2(topDy, topDx) 算法。
     */
    private fun quadTopEdgeAngle(quad: QuadBox): Float {
        val topDx = quad.pts[1].x - quad.pts[0].x
        val topDy = quad.pts[1].y - quad.pts[0].y
        return atan2(topDy, topDx)
    }

    /**
     * 从 quad 顶边向量计算文字角度（度），±3° 内归零。
     */
    private fun quadTopEdgeAngleDeg(quad: QuadBox): Float {
        var angleDeg = quadTopEdgeAngle(quad) * 180f / PI.toFloat()
        if (abs(angleDeg) <= 3f) angleDeg = 0f
        return angleDeg
    }

    /**
     * 判断近似轴对齐。
     * 从顶边向量计算角度，归一化到 [0, 180°) 后判断。
     */
    private fun isApproxAxisAligned(quad: QuadBox): Boolean {
        val angleDeg = abs(quadTopEdgeAngle(quad)) * 180f / PI.toFloat()
        val normalized = angleDeg % 180f
        return normalized <= 3f || normalized >= 177f
    }

    /**
     * 判断两个 TextRegion 是否应合并。
     * 完整对齐 manga generic.py:653-698 quadrilateral_can_merge_region。
     *
     * @return true 表示应合并
     */
    private fun canMergeRegion(a: TextRegion, b: TextRegion, aIndex: Int, bIndex: Int): Boolean {
        val charSize = min(a.quad.fontSize, b.quad.fontSize)
        if (charSize <= 0f) return false

        // 编号 = 输入框索引（与调试面板「原始识别」的 [i] 对应）
        val tagA = "[$aIndex]\"${(a.text ?: "").take(8)}\""
        val tagB = "[$bIndex]\"${(b.text ?: "").take(8)}\""

        val aAA = isApproxAxisAligned(a.quad)
        val bAA = isApproxAxisAligned(b.quad)

        // 距离粗筛（AA + Tilted 共用，对齐 Shapely Polygon.distance）
        val dist = aabbDistance(a, b)
        val maxGap = discardConnectionGap * charSize
        if (dist > maxGap) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → REJECT dist=${String.format("%.1f", dist)} > $maxGap")
            return false
        }

        // 字号比（AA + Tilted 共用）
        val fsRatio = max(a.quad.fontSize, b.quad.fontSize) / charSize
        if (fsRatio > FONT_SIZE_RATIO_AA) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → REJECT fsRatio=${String.format("%.2f", fsRatio)} > $FONT_SIZE_RATIO_AA")
            return false
        }

        // 宽高比交叉检查（AA + Tilted 共用）
        if (a.quad.aspectRatio > ASPECT_RATIO_TOL && b.quad.aspectRatio < 1f / ASPECT_RATIO_TOL) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → REJECT aspectRatio cross")
            return false
        }
        if (b.quad.aspectRatio > ASPECT_RATIO_TOL && a.quad.aspectRatio < 1f / ASPECT_RATIO_TOL) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → REJECT aspectRatio cross")
            return false
        }

        // 方向一致性（AA + Tilted 共用）
        // 近似正方形的框方向不可靠（对角线结构向量无意义），跳过方向检查
        val aSquare = a.quad.aspectRatio < ASPECT_RATIO_TOL
        val bSquare = b.quad.aspectRatio < ASPECT_RATIO_TOL
        if (!aSquare && !bSquare && a.quad.isVertical != b.quad.isVertical) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → REJECT direction mismatch")
            return false
        }

        // ========== AA 分支（manga L671-687）==========
        if (aAA && bAA) {
            // char_gap_tolerance（manga 调用 1.0）
            if (dist >= charSize * CHAR_GAP_TOLERANCE) {
                if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → AA REJECT dist=${String.format("%.1f", dist)} >= ${charSize * CHAR_GAP_TOLERANCE}")
                return false
            }
            val x1 = a.quad.aabb.left.toFloat()
            val w1 = a.quad.aabb.width().toFloat()
            val h1 = a.quad.aabb.height().toFloat()
            val x2 = b.quad.aabb.left.toFloat()
            val w2 = b.quad.aabb.width().toFloat()
            val h2 = b.quad.aabb.height().toFloat()
            val y1 = a.quad.aabb.top.toFloat()
            val y2 = b.quad.aabb.top.toFloat()

            // 三个阈值分工：
            //  - gapTol（间隔，管「相邻」）：宽松 2×字号，容纳行距（竖排同气泡行距实测 68-75px≈1.5×字号）
            //  - centerTol（中心对齐，管「同一句/同列」）：宽松 2×字号，最后一行 1-2 字也能合
            //  - edgeTol（边缘对齐，管「起始位置对齐」）：严格 1×字号，错位（起始点不同）就分
            // 优先级：中心对齐（最强）> 边缘对齐（次强）。中心距失败不兜底，错位绝不合并。
            val gapTol = max(charGapTolerance2, charSize * 2f)
            val centerTol = max(charGapTolerance2, charSize * 2f)
            val edgeTol = max(charGapTolerance2, charSize * 1f)

            val cx1 = x1 + w1 / 2f
            val cy1 = y1 + h1 / 2f
            val cx2 = x2 + w2 / 2f
            val cy2 = y2 + h2 / 2f

            // ① 中心对齐（2D）：x、y 中心都接近 → 同一位置的最紧密碎片，最强
            if (abs(cx1 - cx2) < centerTol && abs(cy1 - cy2) < centerTol) {
                if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → AA ACCEPT center aligned (2D)")
                return true
            }

            // ② 横排文字（宽 > 高）：多行上下堆叠 → 垂直行距小 && 左/右/中心任一对齐
            if (w1 > h1 * RATIO || w2 > h2 * RATIO) {
                // 垂直行距（上下是否紧挨）
                val vGap = max(0f, max(y1 - (y2 + h2), y2 - (y1 + h1)))
                // 左边缘 / 右边缘 / 水平中心 对齐
                val leftAligned = abs(x1 - x2) < edgeTol
                val rightAligned = abs((x1 + w1) - (x2 + w2)) < edgeTol
                val centerXAligned = abs(cx1 - cx2) < centerTol
                val accept = vGap < gapTol && (leftAligned || rightAligned || centerXAligned)
                if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → AA h-merge=$accept (vGap=${String.format("%.0f", vGap)}, L=$leftAligned R=$rightAligned C=$centerXAligned)")
                return accept
            }
            // ③ 竖排文字（高 > 宽）：多段左右排列 → 水平列距小 && 上边缘/垂直中心任一对齐
            if (h1 > w1 * RATIO || h2 > w2 * RATIO) {
                // 水平列距（左右是否紧挨）
                val hGap = max(0f, max(x1 - (x2 + w2), x2 - (x1 + w1)))
                // 上边缘（竖排列从顶部同一高度开始）或 垂直中心对齐
                val topAligned = abs(y1 - y2) < edgeTol
                val centerYAligned = abs(cy1 - cy2) < centerTol
                val accept = hGap < gapTol && (topAligned || centerYAligned)
                if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → AA v-merge=$accept (hGap=${String.format("%.0f", hGap)}, T=$topAligned C=$centerYAligned)")
                return accept
            }
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → AA REJECT no direction match")
            return false
        }

        // ========== Tilted 分支（manga L688-697）==========
        val angleDiff = abs(quadTopEdgeAngle(a.quad) - quadTopEdgeAngle(b.quad)) * 180f / PI.toFloat()
        if (angleDiff > TILTED_ANGLE_DIFF_MAX) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → TILTED REJECT angleDiff=${String.format("%.1f", angleDiff)} > $TILTED_ANGLE_DIFF_MAX")
            return false
        }
        val fsA = a.quad.fontSize
        val fsB = b.quad.fontSize
        val fsMin = min(fsA, fsB)
        val fsDiff = abs(fsA - fsB) / fsMin
        if (fsDiff > TILTED_FS_DIFF_MAX) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → TILTED REJECT fsDiff=${String.format("%.2f", fsDiff)} > $TILTED_FS_DIFF_MAX")
            return false
        }
        // 距离阈值：TILTED 倾斜文字同样必须紧邻——1.5×字号（与 AA 粗筛 discardConnectionGap 一致）。
        // 曾用 fsMin * charGapTolerance2（3×字号=135px）→ 相距很远但角度接近的倾斜气泡被误连
        // （用户日志：[9]+[11] TILTED ACCEPT 误合，9 与 11 分属不同气泡）。
        if (dist > fsMin * 1.5f) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → TILTED REJECT dist=${String.format("%.1f", dist)} > ${fsMin * 1.5f}")
            return false
        }
        if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → TILTED ACCEPT")
        return true
    }

    // ========== splitTextRegion（对齐 textline_merge/__init__.py L10-83） ==========

    /**
     * 拆分过大的文本区域。
     *
     * 核心思路：按阅读顺序排序后，**只在相邻行/列之间找断点**（相邻间隙显著偏大处切分）。
     * 绝不使用全对全 MST 建边——那会在不相邻的行之间连接（长行中心偏右、短行中心偏左，
     * 中心距离把"长行+长行"或"短行+短行"误连），导致交叉合并（如用户日志：
     * [4]长行被单独拆出、[5][6][7]短行连成错误一行，而 [4]+[5] 明明是一句话）。
     *
     * 排序后组内成员必然连续，从根本上杜绝交叉：结果只能是"相邻的行合并"或"整块合并"。
     */
    private fun splitTextRegion(
        regions: List<TextRegion>,
        connectedIndices: Set<Int>,
        verticalDirection: TextDirection = TextDirection.VERTICAL_RL,
        gamma: Float = 0.5f,
        sigma: Float = 2f
    ): List<Set<Int>> {
        val indices = connectedIndices.toList()
        if (indices.size == 1) return listOf(setOf(indices[0]))

        if (indices.size == 2) {
            // 2 元素组件无传递性：能组成连通分量说明 canMergeRegion 已认可这对
            // （AABB 间隙 < 1×字符宽 + 字号/方向/对齐校验）。阶段二在此没有"防过度合并"
            // 职责，直接保留——旧实现用中心距离 < 1.5×字号 重判，与 canMerge 的 AABB 间隙
            // 指标矛盾，导致竖排相邻行（如 [2][3]、[7][8]）被错误拆开。
            if (debugEnabled) LogCollector.d(TAG, "splitTextRegion[2]: keep（canMerge 已认可）")
            return listOf(setOf(indices[0], indices[1]))
        }

        // 方向投票（只统计有明确方向的框，正方形不参与）
        val directional = indices.filter { regions[it].quad.aspectRatio >= ASPECT_RATIO_TOL }
        val voters = if (directional.isNotEmpty()) directional else indices
        val vCount = voters.count { regions[it].quad.isVertical }
        val isVertical = vCount > voters.size - vCount

        // 按阅读顺序排序：横排 top→bottom（同 top 用 x 二级），竖排按列序
        // （VERTICAL_RL 右→左 / VERTICAL_LR 左→右；同列用 y 二级）
        val isRl = verticalDirection != TextDirection.VERTICAL_LR
        val sorted = if (isVertical) {
            indices.sortedWith(Comparator { a, b ->
                val xa = if (isRl) -regions[a].quad.aabb.right else regions[a].quad.aabb.left
                val xb = if (isRl) -regions[b].quad.aabb.right else regions[b].quad.aabb.left
                if (xa != xb) xa.compareTo(xb)
                else regions[a].quad.aabb.top.compareTo(regions[b].quad.aabb.top)
            })
        } else {
            indices.sortedWith(Comparator { a, b ->
                val ya = regions[a].quad.aabb.top
                val yb = regions[b].quad.aabb.top
                if (ya != yb) ya.compareTo(yb)
                else regions[a].quad.aabb.left.compareTo(regions[b].quad.aabb.left)
            })
        }

        // 相邻行/列的间隙（合并方向上的真实分离度）
        // 核心度量：垂直间隙 + 水平不重叠惩罚。
        // - 同一视觉行/列（垂直相邻 + 水平重叠）→ gap 小 → 合并
        // - 垂直相距远（不同气泡）→ 垂直间隙大 → gap 大 → 断开
        // - 水平不重叠（跨列/两栏）→ 水平惩罚大 → gap 大 → 断开
        // 修复背景：竖排曾用纯水平间隙（a.left-b.right），同列内垂直远离的元素水平间隙=0
        // → 被误判相邻合并（用户报"相距极远的两个气泡被合并"）。
        val gaps = mutableListOf<Float>()
        for (i in 0 until sorted.size - 1) {
            val a = regions[sorted[i]].quad.aabb
            val b = regions[sorted[i + 1]].quad.aabb
            // 垂直间隙：b 在 a 下方（排序后同列内 b 在 a 下）
            val vGap = max(0, b.top - a.bottom).toFloat()
            // 水平投影重叠：两行水平方向重叠多少（负数=完全分离）
            val hOverlap = min(a.right, b.right) - max(a.left, b.left)
            // 水平不重叠惩罚：重叠不足 2×charSize 时惩罚（跨列/两栏 → 大 gap 断开）
            val charSize = min(regions[sorted[i]].quad.fontSize, regions[sorted[i + 1]].quad.fontSize)
            val hPenalty = if (charSize > 0) max(0f, 2f * charSize - hOverlap) else 0f
            gaps.add(vGap + hPenalty)
        }

        val avgFontSize = indices.map { regions[it].quad.fontSize }.average().toFloat()
        // 断点检测：用「局部显著跳变」，不用全局 mean+std。
        // 全局 mean+std 会把大间隙计入均值/方差，阈值被抬高到覆盖该间隙（用户日志：
        // gaps=[67,64,129,70,71] threshold=129.3，129 被 0.3px 放过没断开）。
        // 正确：某间隙显著大于「相邻间隙的中位数/均值」（孤立大跳变）才断。
        val breakAt = BooleanArray(gaps.size)
        for (i in gaps.indices) {
            // 邻近窗口（最多 3 个邻居）的中位数
            val neighbors = ArrayList<Float>()
            for (k in (i - 2)..(i + 2)) {
                if (k in gaps.indices && k != i) neighbors.add(gaps[k])
            }
            if (neighbors.isEmpty()) continue
            val sortedNb = neighbors.sorted()
            val medianNb = if (sortedNb.size % 2 == 1) sortedNb[sortedNb.size / 2]
            else (sortedNb[sortedNb.size / 2 - 1] + sortedNb[sortedNb.size / 2]) / 2f
            // 间隙 > 中位数 × 1.6 且 > 字号 × 1.2 → 断开（孤立大跳变）
            // 用户日志组6 gaps=[67,64,129,70,71]：129 是组间间隙（该断），邻居中位 68.5，
            // 系数 2.0 时阈值 137 放过了（差 8px）；1.6 时阈值 109.6 → 129 断开 ✓。
            // 中位数 0 时（全紧贴）用字号兜底。
            val ratioThresh = if (medianNb > 1f) medianNb * 1.6f else avgFontSize * 1.2f
            if (gaps[i] > ratioThresh && gaps[i] > avgFontSize * 1.2f) {
                breakAt[i] = true
            }
        }

        if (debugEnabled) {
            val gapsStr = gaps.joinToString(",") { String.format("%.0f", it) }
            val breaksStr = breakAt.map { if (it) "X" else "." }.joinToString("")
            LogCollector.d(TAG, "splitTextRegion[${indices.size}] dir=${if (isVertical) "v" else "h"} " +
                "gaps=[$gapsStr] breaks=[$breaksStr] fontSize=${String.format("%.1f", avgFontSize)}")
        }

        // 从前往后找断点，把序列切成连续段
        val result = mutableListOf<Set<Int>>()
        var segStart = 0
        for (i in 0 until gaps.size) {
            if (breakAt[i]) {
                result.add(sorted.subList(segStart, i + 1).toSet())
                segStart = i + 1
            }
        }
        result.add(sorted.subList(segStart, sorted.size).toSet())
        return result
    }

    // ========== merge 主入口 ==========

    /**
     * 主入口：合并 text regions 为文本组。
     *
     * @param regions 待合并的 text region 列表
     * @param params 可调参数（不传则使用当前 refreshParams 后的值）
     * @return 合并后的 text region groups（按阅读顺序：横排 top→bottom，竖排 right→left）
     */
    fun merge(
        regions: List<TextRegion>,
        params: MergeParams = MergeParams(discardConnectionGap, charGapTolerance2),
        verticalDirection: TextDirection = TextDirection.VERTICAL_RL
    ): List<TextRegionGroup> {
        if (regions.isEmpty()) return emptyList()

        // 临时覆盖可调参数
        val savedGap = discardConnectionGap
        val savedGap2 = charGapTolerance2
        discardConnectionGap = params.discardConnectionGap
        charGapTolerance2 = params.charGapTolerance2

        try {
            if (regions.size == 1) {
                val region = regions[0]
                val rect = region.quad.aabb
                val quadPoints = arrayOf(
                    PointF(rect.left.toFloat(), rect.top.toFloat()),
                    PointF(rect.right.toFloat(), rect.top.toFloat()),
                    PointF(rect.right.toFloat(), rect.bottom.toFloat()),
                    PointF(rect.left.toFloat(), rect.bottom.toFloat())
                )
                val direction = if (region.quad.isVertical) verticalDirection else TextDirection.HORIZONTAL
                return listOf(
                    TextRegionGroup(
                        rect = rect,
                        quadPoints = quadPoints,
                        texts = listOf(region.text ?: ""),
                        direction = direction,
                        fontSize = region.quad.fontSize,
                        angle = quadTopEdgeAngleDeg(region.quad),
                        score = region.score,
                        center = PointF(rect.exactCenterX(), rect.exactCenterY()),
                        members = listOf(region),
                        memberIndices = listOf(0)
                    )
                )
            }

            if (debugEnabled) LogCollector.d(TAG, "merge: 输入 ${regions.size} 个 region")

            // 合并开关：关闭时跳过合并，每个 region 独立成组（表格/多栏等场景）
            if (!mergeEnabled) {
                if (debugEnabled) LogCollector.d(TAG, "merge: 合并已关闭（Manga_Text_Merge=false），每个 region 独立")
                return regions.mapIndexed { idx, region ->
                    val rect = region.quad.aabb
                    val quadPoints = arrayOf(
                        PointF(rect.left.toFloat(), rect.top.toFloat()),
                        PointF(rect.right.toFloat(), rect.top.toFloat()),
                        PointF(rect.right.toFloat(), rect.bottom.toFloat()),
                        PointF(rect.left.toFloat(), rect.bottom.toFloat())
                    )
                    val direction = if (region.quad.isVertical) verticalDirection else TextDirection.HORIZONTAL
                    TextRegionGroup(
                        rect = rect,
                        quadPoints = quadPoints,
                        texts = listOf(region.text ?: ""),
                        direction = direction,
                        fontSize = region.quad.fontSize,
                        angle = quadTopEdgeAngleDeg(region.quad),
                        score = region.score,
                        center = PointF(rect.exactCenterX(), rect.exactCenterY()),
                        members = listOf(region),
                        memberIndices = listOf(idx)
                    )
                }
            }

            // Step 1: canMergeRegion 建图 → 连通分量
            val n = regions.size
            val adjacency = Array(n) { mutableSetOf<Int>() }
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    if (canMergeRegion(regions[i], regions[j], i, j)) {
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
            if (debugEnabled) LogCollector.d(TAG, "merge: 连通分量 ${connectedComponents.size} 个")

            // Step 2: splitTextRegion MST 拆分
            val regionIndices = mutableListOf<Set<Int>>()
            for (component in connectedComponents) {
                regionIndices.addAll(splitTextRegion(regions, component, verticalDirection))
            }
            if (debugEnabled) LogCollector.d(TAG, "merge: 拆分后 ${regionIndices.size} 个区域")

            // Step 3: 方向投票 + 排序 + 合并
            val result = mutableListOf<TextRegionGroup>()
            for (nodeSet in regionIndices) {
                val nodes = nodeSet.toList()
                val members = nodes.map { regions[it] }

                // 方向投票（只统计有明确方向的框，正方形不参与投票）
                val directionalMembers = members.filter { it.quad.aspectRatio >= ASPECT_RATIO_TOL }
                val voters = if (directionalMembers.isNotEmpty()) directionalMembers else members
                val directionCounts = voters.groupBy { it.quad.isVertical }.mapValues { it.value.size }
                val majorityVertical = (directionCounts[true] ?: 0) > (directionCounts[false] ?: 0)
                val direction = if (majorityVertical) verticalDirection else TextDirection.HORIZONTAL

                // 按方向排序（同行/列时用 x/y 坐标做二级排序，保证稳定性）
                val sortedNodes = if (direction == TextDirection.HORIZONTAL) {
                    nodes.sortedWith(Comparator { a, b ->
                        val ya = regions[a].quad.centroidY
                        val yb = regions[b].quad.centroidY
                        if (ya != yb) ya.compareTo(yb) else regions[a].quad.centroidX.compareTo(regions[b].quad.centroidX)
                    })
                } else {
                    // RL = centroidX 降序（右列先）；LR = 升序。统一写成「升序 + RL 取反」，
                    // 避免两条分支各写一个方向、再把极性写反（本行出过这个错）。
                    val isRl = verticalDirection != TextDirection.VERTICAL_LR
                    nodes.sortedWith(Comparator { a, b ->
                        val xa = if (isRl) -regions[a].quad.centroidX else regions[a].quad.centroidX
                        val xb = if (isRl) -regions[b].quad.centroidX else regions[b].quad.centroidX
                        if (xa != xb) xa.compareTo(xb) else regions[a].quad.centroidY.compareTo(regions[b].quad.centroidY)
                    })
                }

                // AABB union
                val aabbs = sortedNodes.map { regions[it].quad.aabb }
                val unionRect = Rect(
                    aabbs.minOf { it.left },
                    aabbs.minOf { it.top },
                    aabbs.maxOf { it.right },
                    aabbs.maxOf { it.bottom }
                )

                val combinedTexts = sortedNodes.map { regions[it].text ?: "" }
                val minFontSize = members.minOf { it.quad.fontSize }
                val avgScore = members.map { it.score }.average().toFloat()
                val weightedAngle = weightedAverage(
                    members.map { quadTopEdgeAngleDeg(it.quad) },
                    members.map { it.quad.fontSize }
                )
                val mergedCenter = PointF(unionRect.exactCenterX(), unionRect.exactCenterY())

                // 中心加权 quad 角点（简化版：用 unionRect）
                val quadPoints = arrayOf(
                    PointF(unionRect.left.toFloat(), unionRect.top.toFloat()),
                    PointF(unionRect.right.toFloat(), unionRect.top.toFloat()),
                    PointF(unionRect.right.toFloat(), unionRect.bottom.toFloat()),
                    PointF(unionRect.left.toFloat(), unionRect.bottom.toFloat())
                )

                result.add(TextRegionGroup(
                    rect = unionRect,
                    quadPoints = quadPoints,
                    texts = combinedTexts,
                    direction = direction,
                    fontSize = minFontSize,
                    angle = weightedAngle,
                    score = avgScore,
                    center = mergedCenter,
                    members = members,
                    memberIndices = sortedNodes
                ))

                if (debugEnabled) {
                    // 组编号（result 顺序 index）+ 组内成员原始索引，与调试面板标签对应
                    val idx = result.size - 1
                    val memberStr = sortedNodes.joinToString(",")
                    LogCollector.d(TAG, "merge: 组$idx 成员[$memberStr] ${members.size}行, dir=$direction, " +
                            "fs=${String.format("%.1f", minFontSize)}, text='${combinedTexts.first().take(20)}'")
                }
            }

            if (debugEnabled) LogCollector.d(TAG, "merge: 输出 ${result.size} 个文本区域")
            return result
        } finally {
            // 恢复参数
            if (params.discardConnectionGap != savedGap ||
                params.charGapTolerance2 != savedGap2) {
                discardConnectionGap = savedGap
                charGapTolerance2 = savedGap2
            }
        }
    }
}
