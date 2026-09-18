package com.moe.starflow.manga.engine
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.state.*
import com.moe.starflow.manga.render.*
import com.moe.starflow.manga.merge.*
import com.moe.starflow.manga.*

import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.PointF
import com.moe.starflow.utils.LogCollector
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.operation.buffer.BufferOp

/**
 * PP-OCR det 后处理共享几何/图像逻辑（PPOcrV5Engine/PPOcrV6Engine 提取，完全一致部分）。
 * 纯函数：无引擎状态，det 阈值参数由调用方传入。
 */
object PPOcrDetGeometry {

    data class BoxScoreResult(
        val boxes: List<FloatArray>,
        val scores: List<Float>,
        /**
         * `boxes[i]` / `scores[i]` 在**输入**里的下标。
         *
         * 有了它就能同步重排任意与之平行的数组（如 `OcrResult.texts`），
         * 不必用 `===` 反查 —— 那种写法在重复框上会静默取错文字。
         */
        val sourceIndices: List<Int> = boxes.indices.toList()
    ) {
        fun <T> reorder(parallel: List<T>): List<T> =
            if (parallel.size != sourceIndices.size) parallel
            else sourceIndices.map { parallel[it] }
    }

    data class MiniBoxResult(
        val points: List<PointF>?,
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float
    )

    /**
     * 过滤检测结果：裁剪到图像范围 + 最小尺寸检查 + 大框过滤。
     */
    fun filterDetRes(
        boxes: List<FloatArray>,
        scores: List<Float>,
        h: Int,
        w: Int,
        detMinSize: Int,
        largeBoxEnabled: Boolean,
        largeBoxRatio: Float
    ): BoxScoreResult {
        val resultBoxes = mutableListOf<FloatArray>()
        val resultScores = mutableListOf<Float>()
        for (i in boxes.indices) {
            val box = boxes[i]
            val clipped = FloatArray(8)
            for (j in 0 until 4) {
                clipped[j * 2] = box[j * 2].coerceIn(0f, (w - 1).toFloat())
                clipped[j * 2 + 1] = box[j * 2 + 1].coerceIn(0f, (h - 1).toFloat())
            }

            // 计算宽度和高度
            val widthA = sqrt(((clipped[4] - clipped[6]) * (clipped[4] - clipped[6]) +
                    (clipped[5] - clipped[7]) * (clipped[5] - clipped[7])).toDouble()).toFloat()
            val widthB = sqrt(((clipped[2] - clipped[0]) * (clipped[2] - clipped[0]) +
                    (clipped[3] - clipped[1]) * (clipped[3] - clipped[1])).toDouble()).toFloat()
            val boxWidth = max(widthA, widthB)

            val heightA = sqrt(((clipped[2] - clipped[4]) * (clipped[2] - clipped[4]) +
                    (clipped[3] - clipped[5]) * (clipped[3] - clipped[5])).toDouble()).toFloat()
            val heightB = sqrt(((clipped[0] - clipped[6]) * (clipped[0] - clipped[6]) +
                    (clipped[1] - clipped[7]) * (clipped[1] - clipped[7])).toDouble()).toFloat()
            val boxHeight = max(heightA, heightB)

            if (boxWidth < detMinSize || boxHeight < detMinSize) continue

            // 大框过滤（可选）：宽/高/面积超过图片比例阈值时丢弃
            if (largeBoxEnabled) {
                val ratio = largeBoxRatio
                val imgArea = w.toFloat() * h.toFloat()
                val boxArea = boxWidth * boxHeight
                if (boxWidth > w * ratio || boxHeight > h * ratio || boxArea > imgArea * ratio) {
                    continue
                }
            }

            resultBoxes.add(clipped)
            if (i < scores.size) resultScores.add(scores[i])
        }
        return BoxScoreResult(resultBoxes, resultScores)
    }

    /**
     * 扫描顺序（`Manga_Text_Direction`）：false = 右→左（传统日漫，默认），true = 左→右。
     *
     * ⚠️ **唯一消费方是 [sortDetCandidates]** —— 它按几何重排，所以这里只需一个标志，
     * 不要去改 [findContours] 的扫描方向：那会变成**第二套排序机制**（而 `runDet` 末尾的
     * `sortDetCandidates` 会把扫描序完全覆盖，改了也没有用户可见效果）。
     * 本仓库吃过「两份平行实现」的亏，排序权威保持一处。
     */
    @Volatile
    var verticalScanFlowIsLr: Boolean = false

    /**
     * findContours: BFS 连通域 (对应 cv2.findContours)
     */
    fun findContours(mask: Bitmap, w: Int, h: Int, detMinSize: Int): List<List<Point>> {
        val visited = BooleanArray(w * h)
        val contours = mutableListOf<List<Point>>()
        val pixels = IntArray(w * h)
        mask.getPixels(pixels, 0, w, 0, 0, w, h)

        val dx = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
        val dy = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (visited[idx] || pixels[idx] != Color.WHITE) continue

                val component = mutableListOf<Point>()
                val queue = ArrayDeque<Int>()
                queue.add(idx)
                visited[idx] = true

                while (queue.isNotEmpty()) {
                    val cur = queue.removeFirst()
                    val cy = cur / w
                    val cx = cur % w
                    component.add(Point(cx, cy))

                    for (d in 0 until 8) {
                        val nx = cx + dx[d]
                        val ny = cy + dy[d]
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                        val nIdx = ny * w + nx
                        if (!visited[nIdx] && pixels[nIdx] == Color.WHITE) {
                            visited[nIdx] = true
                            queue.add(nIdx)
                        }
                    }
                }

                if (component.size >= detMinSize) {
                    contours.add(component)
                }
            }
        }

        return contours
    }

    /**
     * 诊断日志开关。**默认关闭、零开销**（每次调用一次 volatile 读）。
     *
     * 由调试面板的 `enableDebugLogging` 一类入口置位（与 `TextRegionMerger` 同一套约定）。
     * 开启后 [sortDetCandidates] 会逐框打印**几何判定的依据**：每个框的
     * `(left,top,right,bottom)`、宽高、判成竖排还是横排、以及最终排列结果。
     *
     * 排「识别方向与横竖屏的关系」这类问题时**必须**看这些数 ——
     * 只打印拼接后的文本看不出判定对错（顺序对也可能是巧合，反之亦然）。
     */
    @Volatile
    var enableDebugLogging: Boolean = false

    /**
     * 把 det 结果排成**阅读顺序**（框数组为 `[x0,y0,x1,y1,x2,y2,x3,y3]`）。
     *
     * - 判定为**横排**的框（宽 > 高）→ 按 `top` 升序、同 top 按 `left` 升序（左→右、上→下）
     * - 判定为**竖排**的框（高 > 宽）→ 按 [verticalScanFlowIsLr] 取列序：
     *   - 右→左（默认）：`left` 降序、同列按 `top` 升序
     *   - 左→右：`left` 升序、同列按 `top` 升序
     *
     * ⚠️ **横排恒定左→右**：扫描序同时决定识别顺序与源文拼接顺序，
     * 而 `Manga_Text_Direction` 的语义是「**竖排**文字的列排列方向」，
     * 横排不该被它反转（否则送进翻译的横排句子会被倒过来）。
     *
     * 竖排但宽高接近（差值 < [ORIENT_ASPECT_TOL] px）时按横排处理 —— 那是歧义框，
     * 取左→右比按用户偏好猜稳。
     *
     * ⚠️ `boxes` 与 `scores` 是**平行数组**，重排必须同步，否则识别分数会串到别的框上。
     * 两者长度不一致时（[filterDetRes] 的越界守卫会造出这种状态）只重排 boxes、
     * scores 原样返回，并记一条 W 级日志 —— 该状态本身就该被看见。
     */
    fun sortDetCandidates(
        boxes: List<FloatArray>,
        scores: List<Float>,
        verticalScanFlowIsLr: Boolean
    ): BoxScoreResult {
        if (boxes.size <= 1) return BoxScoreResult(boxes.toList(), scores.toList())

        fun top(b: FloatArray) = boxTop(b)
        fun left(b: FloatArray) = boxLeft(b)
        fun right(b: FloatArray) = boxRight(b)
        fun bottom(b: FloatArray) = boxBottom(b)
        fun isVertical(b: FloatArray) =
            (bottom(b) - top(b)) > (right(b) - left(b)) + ORIENT_ASPECT_TOL

        val perm = boxes.indices.sortedWith { ia, ib ->
            val a = boxes[ia]
            val b = boxes[ib]
            val va = isVertical(a)
            val vb = isVertical(b)
            when {
                va && vb -> {
                    val la = left(a)
                    val lb = left(b)
                    if (la != lb) {
                        if (verticalScanFlowIsLr) la.compareTo(lb) else lb.compareTo(la)
                    } else {
                        top(a).compareTo(top(b))
                    }
                }
                !va && !vb -> {
                    val ta = top(a)
                    val tb = top(b)
                    if (ta != tb) ta.compareTo(tb) else left(a).compareTo(left(b))
                }
                // 混排（同一页既有竖排列又有横排标题）：竖排整体在前
                else -> if (va) -1 else 1
            }
        }

        val sortedBoxes = perm.map { boxes[it] }
        if (enableDebugLogging) {
            logSortDecision(boxes, perm, verticalScanFlowIsLr)
        }
        if (scores.size != boxes.size) {
            LogCollector.w(
                "PPOcrDetGeometry",
                "sortDetCandidates: boxes=${boxes.size} 与 scores=${scores.size} 长度不一致，scores 不重排"
            )
            return BoxScoreResult(sortedBoxes, scores, perm)
        }
        return BoxScoreResult(sortedBoxes, perm.map { scores[it] }, perm)
    }

    /**
     * 逐框打印**判定依据**。受 [enableDebugLogging] 控制。
     *
     * 输出形状刻意做成「一行结果 + 每框一行几何」，便于直接比对：
     * - `order=` 最终排列（用原始下标表示）
     * - 每框 `#i (l,t,r,b) WxH vert=true/false` —— **vert 就是走哪个分支的依据**
     *
     * 排查方向问题时看 `vert`：全 false 说明这些框被判成了横排（排序会退化成 top 主键
     * = 自上而下），与设置无关。
     */
    private fun logSortDecision(
        boxes: List<FloatArray>,
        perm: List<Int>,
        verticalScanFlowIsLr: Boolean
    ) {
        LogCollector.d(
            "PPOcrDetGeometry",
            "sortDetCandidates: n=${boxes.size}, LR=$verticalScanFlowIsLr, 输入序=$perm, " +
                "输出序=${perm.map { boxes[it] }}"
        )
        for (i in boxes.indices) {
            val b = boxes[i]
            val l = boxLeft(b); val t = boxTop(b); val r = boxRight(b); val bt = boxBottom(b)
            val w = r - l; val h = bt - t
            LogCollector.d(
                "PPOcrDetGeometry",
                "  #$i (${l.toInt()},${t.toInt()},${r.toInt()},${bt.toInt()}) " +
                    "${w.toInt()}x${h.toInt()} vert=${h > w + ORIENT_ASPECT_TOL}"
            )
        }
    }

    /**
     * det 输入边长的 **32 对齐**：四舍五入到最近的 32 倍数，下限 32。
     *
     * ⚠️ **必须四舍五入，不能整除截断**。官方 RapidOCR 是
     * `resize_h = int(round(resize_h / 32) * 32)`（`ch_ppocr_det/utils.py:100`），
     * 截断版 `(x / 32) * 32` 会**最多白扔 31px**，而且对**两个轴各自独立**生效 ——
     * 小尺寸裁剪时占比极高且两轴不等，等于把图**压扁**：
     *
     * ```
     * 94x258 → 官方 96x256        截断 64x256   （横 −32% / 纵 −0.8%）
     * 148x253 → 官方 160x256      截断 128x224  （横 −13.5% / 纵 −11.5%）
     * ```
     *
     * 实测后果：竖排小字被压到认不出，det 框数暴涨（本该 3 个框出了 10 个）、
     * 每框只认出零散一两个字（「武部沙織」→「武框織」）。
     * **框选范围放大就恢复正常** —— 尺寸越大，被截掉的固定份额占比越小，
     * 这正是该 bug 的判别特征。
     */
    fun alignTo32(value: Int): Int = max(32, (value / 32f).roundToInt() * 32)

    /** 框的 AABB 上边界（`boxes` 元素为 `[x0,y0,…,x3,y3]`）。 */
    fun boxTop(b: FloatArray): Float = minOf(b[1], b[3], b[5], b[7])

    /** 框的 AABB 左边界。 */
    fun boxLeft(b: FloatArray): Float = minOf(b[0], b[2], b[4], b[6])

    /** 框的 AABB 右边界。 */
    fun boxRight(b: FloatArray): Float = maxOf(b[0], b[2], b[4], b[6])

    /** 框的 AABB 下边界。 */
    fun boxBottom(b: FloatArray): Float = maxOf(b[1], b[3], b[5], b[7])

    /** 竖/横排判定的宽高容差（px）：差值小于它视为歧义框，按横排（左→右）处理。 */
    private const val ORIENT_ASPECT_TOL = 2f

    /**
     * getMiniBoxes: 凸包 + 最小外接矩形
     */
    fun getMiniBoxes(contour: List<Point>): MiniBoxResult {
        if (contour.size < 3) {
            return MiniBoxResult(null, 0f, 0f, 0f, 0f)
        }

        val ptsD = contour.map { Coordinate(it.x.toDouble(), it.y.toDouble()) }
        val hull = GeometryUtils.convexHull(ptsD)
        if (hull.size < 3) {
            return MiniBoxResult(null, 0f, 0f, 0f, 0f)
        }

        // 旋转卡壳：遍历凸包每条边作为候选方向
        var minArea = Float.MAX_VALUE
        var bestBox: MiniBoxResult? = null

        for (i in hull.indices) {
            val j = (i + 1) % hull.size
            val edgeX = (hull[j].x - hull[i].x).toFloat()
            val edgeY = (hull[j].y - hull[i].y).toFloat()
            val edgeLen = sqrt(edgeX * edgeX + edgeY * edgeY)
            if (edgeLen < 1e-6f) continue

            val ux = edgeX / edgeLen
            val uy = edgeY / edgeLen
            val vx = -uy
            val vy = ux

            var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
            var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE

            for (k in hull.indices) {
                val dx = (hull[k].x - hull[i].x).toFloat()
                val dy = (hull[k].y - hull[i].y).toFloat()
                val projU = dx * ux + dy * uy
                val projV = dx * vx + dy * vy
                if (projU < minU) minU = projU
                if (projU > maxU) maxU = projU
                if (projV < minV) minV = projV
                if (projV > maxV) maxV = projV
            }

            val w = maxU - minU
            val h = maxV - minV
            val area = w * h

            if (area < minArea) {
                minArea = area
                val midU = (minU + maxU) / 2
                val midV = (minV + maxV) / 2
                val cx = hull[i].x.toFloat() + midU * ux + midV * vx
                val cy = hull[i].y.toFloat() + midU * uy + midV * vy

                // 构建四角点并排序：TL, TR, BR, BL
                val corners = arrayOf(
                    PointF(cx - w / 2 * ux - h / 2 * vx, cy - w / 2 * uy - h / 2 * vy),
                    PointF(cx + w / 2 * ux - h / 2 * vx, cy + w / 2 * uy - h / 2 * vy),
                    PointF(cx + w / 2 * ux + h / 2 * vx, cy + w / 2 * uy + h / 2 * vy),
                    PointF(cx - w / 2 * ux + h / 2 * vx, cy - w / 2 * uy + h / 2 * vy)
                )
                val sorted = orderPointsClockwise(corners)
                bestBox = MiniBoxResult(sorted.toList(), cx, cy, w, h)
            }
        }

        return bestBox ?: MiniBoxResult(null, 0f, 0f, 0f, 0f)
    }

    /**
     * orderPointsClockwise: 排序四角点 → TL, TR, BR, BL
     */
    fun orderPointsClockwise(pts: Array<PointF>): Array<PointF> {
        val rect = arrayOfNulls<PointF>(4)

        // TL: 最小和
        val s = FloatArray(4) { pts[it].x + pts[it].y }
        rect[0] = pts[s.indices.minByOrNull { s[it] }!!]
        // BR: 最大和
        rect[2] = pts[s.indices.maxByOrNull { s[it] }!!]

        // TR: 最小差 (y - x)
        val d = FloatArray(4) { pts[it].y - pts[it].x }
        rect[1] = pts[d.indices.minByOrNull { d[it] }!!]
        // BL: 最大差
        rect[3] = pts[d.indices.maxByOrNull { d[it] }!!]

        @Suppress("UNCHECKED_CAST")
        return rect as Array<PointF>
    }

    /**
     * unclip: Vatti unclip (JTS BufferOp)
     */
    fun unclip(box: List<PointF>, unclipRatio: Double): List<List<Coordinate>> {
        val area = polygonArea(box)
        val perimeter = polygonPerimeter(box)
        if (perimeter < 1e-6) return emptyList()

        val distance = area * unclipRatio / perimeter

        val factory = GeometryFactory()
        val coords = Array(box.size + 1) { i ->
            if (i < box.size) Coordinate(box[i].x.toDouble(), box[i].y.toDouble())
            else Coordinate(box[0].x.toDouble(), box[0].y.toDouble()) // close ring
        }
        val poly = try {
            factory.createPolygon(coords)
        } catch (e: Exception) {
            return emptyList()
        }

        val buffered = try {
            BufferOp.bufferOp(poly, distance)
        } catch (e: Exception) {
            return emptyList()
        } ?: return emptyList()

        if (buffered.isEmpty) return emptyList()

        val result = mutableListOf<List<Coordinate>>()
        for (i in 0 until buffered.numGeometries) {
            val coordsArr = buffered.getGeometryN(i).coordinates
            result.add(coordsArr.toList())
        }
        return result
    }

    fun polygonArea(box: List<PointF>): Double {
        var area = 0.0
        val n = box.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += box[i].x * box[j].y - box[j].x * box[i].y
        }
        return abs(area) / 2.0
    }

    fun polygonPerimeter(box: List<PointF>): Double {
        var peri = 0.0
        val n = box.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            val dx = (box[j].x - box[i].x).toDouble()
            val dy = (box[j].y - box[i].y).toDouble()
            peri += sqrt(dx * dx + dy * dy)
        }
        return peri
    }

    /**
     * 透视裁剪 + 自动旋转竖排文字。
     *
     * @param bitmap 原图
     * @param points 4 个顶点 [TL, TR, BR, BL]（原图坐标）
     * @return 裁剪后的正向文字图片
     */
    fun getRotateCropImage(bitmap: Bitmap, points: Array<PointF>): Bitmap {
        // 1. 计算目标尺寸
        val tl = points[0]; val tr = points[1]
        val br = points[2]; val bl = points[3]

        val widthA = sqrt(((br.x - bl.x) * (br.x - bl.x) + (br.y - bl.y) * (br.y - bl.y)).toDouble()).toFloat()
        val widthB = sqrt(((tr.x - tl.x) * (tr.x - tl.x) + (tr.y - tl.y) * (tr.y - tl.y)).toDouble()).toFloat()
        val maxWidth = max(widthA, widthB).roundToInt().coerceIn(4, bitmap.width)
        val heightA = sqrt(((tr.x - br.x) * (tr.x - br.x) + (tr.y - br.y) * (tr.y - br.y)).toDouble()).toFloat()
        val heightB = sqrt(((tl.x - bl.x) * (tl.x - bl.x) + (tl.y - bl.y) * (tl.y - bl.y)).toDouble()).toFloat()
        val maxHeight = max(heightA, heightB).roundToInt().coerceIn(4, bitmap.height)

        // 2. 使用 Android Canvas + Matrix 做透视裁剪（硬件加速，替代纯 Java 像素循环）
        val srcPts = floatArrayOf(
            tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y
        )
        val dstPts = floatArrayOf(
            0f, 0f, (maxWidth - 1).toFloat(), 0f,
            (maxWidth - 1).toFloat(), (maxHeight - 1).toFloat(), 0f, (maxHeight - 1).toFloat()
        )
        val matrix = android.graphics.Matrix()
        matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)

        val cropImg = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(cropImg)
        canvas.drawBitmap(bitmap, matrix, null)

        // 3. 竖排文字自动旋转 90° CCW
        if (cropImg.height >= cropImg.width * 1.5f) {
            val rotMatrix = android.graphics.Matrix().apply { setRotate(-90f) }
            val rotated = Bitmap.createBitmap(cropImg, 0, 0, cropImg.width, cropImg.height, rotMatrix, true)
            if (rotated !== cropImg) cropImg.recycle()
            return rotated
        }

        return cropImg
    }
}
