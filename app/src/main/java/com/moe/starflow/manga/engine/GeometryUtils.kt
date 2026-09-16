package com.moe.starflow.manga.engine
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.state.*
import com.moe.starflow.manga.render.*
import com.moe.starflow.manga.merge.*

import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import org.locationtech.jts.geom.Coordinate

/**
 * 几何运算工具类
 * 提供凸包、叉积、点在多边形内判断、轮廓内概率计算等通用几何算法
 */
object GeometryUtils {

    /**
     * 计算二维叉积 (OA × OB)
     * 正值表示 O→A→B 逆时针，负值表示顺时针，0 表示共线
     */
    fun cross(o: Coordinate, a: Coordinate, b: Coordinate): Double {
        return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    }

    /**
     * Andrew's monotone chain 凸包算法
     * 时间复杂度 O(n log n)
     *
     * @param points 输入点集
     * @return 凸包顶点列表（逆时针顺序）
     */
    fun convexHull(points: List<Coordinate>): List<Coordinate> {
        if (points.size < 3) return points.toList()

        val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
        val n = sorted.size

        val lower = mutableListOf<Coordinate>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(p)
        }

        val upper = mutableListOf<Coordinate>()
        for (i in n - 1 downTo 0) {
            val p = sorted[i]
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(p)
        }

        // 去掉首尾重复点
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)

        return (lower + upper).toMutableList()
    }

    /**
     * 射线法判断点是否在多边形内部
     *
     * @param px 点的 x 坐标
     * @param py 点的 y 坐标
     * @param polygon 多边形顶点列表
     * @return true 表示点在多边形内部
     */
    fun pointInPolygon(px: Long, py: Long, polygon: List<Coordinate>): Boolean {
        var inside = false
        val n = polygon.size
        var j = n - 1
        for (i in 0 until n) {
            val yi = polygon[i].y
            val yj = polygon[j].y
            val xi = polygon[i].x
            val xj = polygon[j].x

            if ((yi > py) != (yj > py) &&
                px < (xj - xi) * (py - yi) / (yj - yi) + xi
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * 射线法判断点是否在多边形内部（Double 版本）
     */
    fun pointInPolygon(px: Double, py: Double, polygon: List<Coordinate>): Boolean {
        var inside = false
        val n = polygon.size
        var j = n - 1
        for (i in 0 until n) {
            val yi = polygon[i].y
            val yj = polygon[j].y
            val xi = polygon[i].x
            val xj = polygon[j].x

            if ((yi > py) != (yj > py) &&
                px < (xj - xi) * (py - yi) / (yj - yi) + xi
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * 计算轮廓内的平均概率值
     * 等价于 Python 的 box_score_fast: fill contour with mask, compute mean prob
     *
     * @param probMap 概率图（一维数组，行优先）
     * @param width 概率图宽度
     * @param height 概率图高度
     * @param contour 轮廓点集
     * @return 轮廓内的平均概率值
     */
    fun boxScoreFast(
        probMap: FloatArray,
        width: Int,
        height: Int,
        contour: List<Coordinate>
    ): Float {
        // 获取轮廓的轴对齐外接矩形
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        for (p in contour) {
            val px = p.x.toInt(); val py = p.y.toInt()
            if (px < minX) minX = px
            if (py < minY) minY = py
            if (px > maxX) maxX = px
            if (py > maxY) maxY = py
        }

        // 裁剪到图像范围
        minX = maxOf(0, minX); minY = maxOf(0, minY)
        maxX = minOf(width - 1, maxX); maxY = minOf(height - 1, maxY)

        if (minX > maxX || minY > maxY) return 0f

        // 对 bbox 内每个像素，判断是否在轮廓内（射线法）
        var sum = 0f
        var count = 0
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                if (pointInPolygon(x.toLong(), y.toLong(), contour)) {
                    sum += probMap[y * width + x]
                    count++
                }
            }
        }

        return if (count > 0) sum / count else 0f
    }

    /**
     * 计算轮廓内的平均概率值（慢速版）。
     * 遍历 AABB 内每个像素，用射线法判断是否在 polygon 内求平均。
     * 使用 Double 累加提高精度，对应 Python box_score_slow。
     * 对应 RapidOCR Det.score_mode = "slow"。
     *
     * @param probMap 概率图（一维数组，行优先）
     * @param width 概率图宽度
     * @param height 概率图高度
     * @param contour 轮廓点集
     * @return 轮廓内的平均概率值
     */
    fun boxScoreSlow(
        probMap: FloatArray,
        width: Int,
        height: Int,
        contour: List<Coordinate>
    ): Float {
        // 获取轮廓的轴对齐外接矩形
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        for (p in contour) {
            val px = p.x.toInt(); val py = p.y.toInt()
            if (px < minX) minX = px
            if (py < minY) minY = py
            if (px > maxX) maxX = px
            if (py > maxY) maxY = py
        }

        // 裁剪到图像范围
        minX = maxOf(0, minX); minY = maxOf(0, minY)
        maxX = minOf(width - 1, maxX); maxY = minOf(height - 1, maxY)

        if (minX > maxX || minY > maxY) return 0f

        // 对 bbox 内每个像素，判断是否在轮廓内（射线法，Double 版本）
        var sum = 0.0  // 使用 Double 累加提高精度
        var count = 0
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                if (pointInPolygon(x.toDouble(), y.toDouble(), contour)) {
                    sum += probMap[y * width + x]
                    count++
                }
            }
        }

        return if (count > 0) (sum / count).toFloat() else 0f
    }
}
