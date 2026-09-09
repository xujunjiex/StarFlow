package com.moe.starflow.manga.engine
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.state.*
import com.moe.starflow.manga.render.*
import com.moe.starflow.manga.merge.*
import com.moe.starflow.manga.*

import com.moe.starflow.manga.config.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.types.CroppedBubble
import com.moe.starflow.manga.types.CroppedTextLine
import com.moe.starflow.manga.types.DetectedBubble
import com.moe.starflow.manga.types.MLKitDebugResult
import com.moe.starflow.manga.types.RTDetrV2DebugResult
import com.moe.starflow.utils.LogCollector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val DEBUG_TAG = "DetectionBridge"

/**
 * 统一检测桥接层。
 *
 * 支持：
 * - ML Kit: 检测 + 识别一体化
 */
object DetectionBridge {

    private const val TAG = "DetectionBridge"

    private const val BATCH_SIZE = 16

    /**
     * 按阅读顺序对结果排序。
     * 与 sortByReadingOrder 类似，但作用于 TextBlockInfo 结果。
     */
    private fun sortResultsByReadingOrder(results: List<TextBlockInfo>): List<TextBlockInfo> {
        if (results.isEmpty()) return results

        val isVertical = results.count { r ->
            r.boundingBox?.let { it.height() > it.width() } ?: false
        } > results.size / 2

        return if (isVertical) {
            results.sortedWith(compareBy({ r -> -((r.boundingBox?.centerX() ?: 0)) }, { r -> r.boundingBox?.centerY() ?: 0 }))
        } else {
            results.sortedWith(compareBy({ r -> r.boundingBox?.centerY() ?: 0 }, { r -> r.boundingBox?.centerX() ?: 0 }))
        }
    }

    /**
     * 为 CTC 准备输入图片：对齐参考项目 get_transformed_region 的透视校正流程。
     *
     * 对齐 generic.py get_transformed_region 的逻辑：
     * 1. 对 QuadBox 做 AABB 裁剪（不加额外 padding）
     * 2. 用 DLT 算法计算从 QuadBox 角点到目标矩形的单应矩阵
     * 3. 透视变换校正文字区域
     * 4. 竖排：w=48, h=48*ratio → rotate CCW 90°
     * 5. 横排：h=48, w=48/ratio
     */
    internal fun prepareCtcInputs(
        croppedBitmaps: List<Bitmap>,
        mergedGroups: List<List<QuadBox>>,
        @Suppress("UNUSED_PARAMETER") expandedRects: List<Rect>,
        globalIsVertical: Boolean
    ): List<Bitmap> {
        val result = mutableListOf<Bitmap>()
        for (i in croppedBitmaps.indices) {
            val crop = croppedBitmaps[i]
            val group = mergedGroups.getOrElse(i) { emptyList() }

            // 判断该组的方向：优先用 QuadBox.assignedDirection
            val isVertical = group.firstOrNull()?.let {
                it.assignedDirection == "v"
            } ?: globalIsVertical

            // 参考项目 ratio = ||v_vec|| / ||h_vec||（结构线比例）
            val ratio = group.firstOrNull()?.structRatio
                ?: (crop.height.toFloat() / crop.width.toFloat().coerceAtLeast(1f))

            val textHeight = 48f
            val cropW = crop.width.toFloat().coerceAtLeast(1f)
            val cropH = crop.height.toFloat().coerceAtLeast(1f)

            val targetW: Int
            val targetH: Int
            if (isVertical) {
                targetW = textHeight.toInt()
                targetH = (textHeight * ratio).toInt().coerceAtLeast(1)
            } else {
                targetH = textHeight.toInt()
                targetW = (textHeight / ratio).toInt().coerceAtLeast(1)
            }

            // 简单缩放（createBitmap + setScale 保证输出尺寸 = target）
            val matrix = android.graphics.Matrix()
            val scaleX = targetW.toFloat() / cropW
            val scaleY = targetH.toFloat() / cropH
            matrix.setScale(scaleX, scaleY)

            if (isVertical) {
                matrix.postRotate(-90f)
            }

            val transformed = Bitmap.createBitmap(
                crop, 0, 0, crop.width, crop.height,
                matrix, true
            )
            LogCollector.d(TAG, "CTC预处理[$i]: isVertical=$isVertical, crop=${crop.width}x${crop.height}, ratio=${String.format("%.3f", ratio)}, target=${targetW}x${targetH}, result=${transformed.width}x${transformed.height}")
            result.add(transformed)
        }
        return result
    }

    /**
     * DLT 算法计算单应矩阵（findHomography）。
     * 求解 4 组点对应的 8 参数透视变换 H（3x3 矩阵，H33=1）。
     * 对齐 cv2.findHomography(src_pts, dst_pts)。
     *
     * @return 9 元素数组表示行优先的 3x3 矩阵，或 null（退化情况）
     */
    private fun computeHomography(
        src: Array<android.graphics.PointF>,
        dst: Array<android.graphics.PointF>
    ): DoubleArray? {
        // 构建 8x8 线性系统 Ah = b
        // 对应方程：
        //   x' = (h1*x + h2*y + h3) / (h7*x + h8*y + 1)
        //   y' = (h4*x + h5*y + h6) / (h7*x + h8*y + 1)
        val A = Array(8) { DoubleArray(8) }
        val b = DoubleArray(8)

        for (i in 0 until 4) {
            val x = src[i].x.toDouble()
            val y = src[i].y.toDouble()
            val xp = dst[i].x.toDouble()
            val yp = dst[i].y.toDouble()

            // x' 方程: x'*h7*x + x'*h8*y - h1*x - h2*y = -h3 + x'  →  h3 系数 = 1
            A[i][0] = -x;     A[i][1] = -y;     A[i][2] = 1.0
            A[i][3] = 0.0;    A[i][4] = 0.0;    A[i][5] = 0.0
            A[i][6] = xp * x; A[i][7] = xp * y
            b[i] = xp

            // y' 方程
            A[i + 4][0] = 0.0;    A[i + 4][1] = 0.0;    A[i + 4][2] = 0.0
            A[i + 4][3] = -x;     A[i + 4][4] = -y;     A[i + 4][5] = 1.0
            A[i + 4][6] = yp * x; A[i + 4][7] = yp * y
            b[i + 4] = yp
        }

        val h = solveLinearSystem(A, b) ?: return null
        return doubleArrayOf(
            h[0], h[1], h[2],
            h[3], h[4], h[5],
            h[6], h[7], 1.0
        )
    }

    /**
     * 高斯消元法求解线性方程组 Ax = b（部分主元选取）。
     */
    private fun solveLinearSystem(A: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        // 构建增广矩阵 [A|b]
        val aug = Array(n) { i ->
            DoubleArray(n + 1) { j -> if (j < n) A[i][j] else b[i] }
        }

        // 前向消元 + 部分主元
        for (col in 0 until n) {
            var maxRow = col
            var maxVal = kotlin.math.abs(aug[col][col])
            for (row in col + 1 until n) {
                val v = kotlin.math.abs(aug[row][col])
                if (v > maxVal) { maxVal = v; maxRow = row }
            }
            if (maxVal < 1e-10) return null
            if (maxRow != col) {
                val tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp
            }
            for (row in col + 1 until n) {
                val factor = aug[row][col] / aug[col][col]
                for (j in col..n) aug[row][j] -= factor * aug[col][j]
            }
        }

        // 回代
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            x[i] = aug[i][n]
            for (j in i + 1 until n) x[i] -= aug[i][j] * x[j]
            x[i] /= aug[i][i]
        }
        return x
    }

    /**
     * 透视变换（warpPerspective）。
     * 对齐 cv2.warpPerspective(src, H, (w, h))。
     * 使用逆映射 + 双线性插值，对源图做单次 getPixels 批量读取。
     *
     * @param src 源图片
     * @param H 9 元素行优先 3x3 单应矩阵（dst → src 映射）
     * @param outW 输出宽度
     * @param outH 输出高度
     * @return 透视校正后的图片
     */
    private fun warpPerspective(src: Bitmap, H: DoubleArray, outW: Int, outH: Int): Bitmap {
        val srcW = src.width
        val srcH = src.height
        val dst = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)

        // 批量读取源像素（OOB 区域视为白色 0xFFFFFFFF）
        val srcPixels = IntArray(srcW * srcH)
        src.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

        val dstPixels = IntArray(outW * outH)

        // 计算 H 的逆矩阵（dst→src → src→dst 用于回代验证，实际用 dst→src 做逆映射）
        val inv = invertMatrix3x3(H) ?: run {
            // 逆矩阵不存在（退化），返回白色图
            java.util.Arrays.fill(dstPixels, -1)
            dst.setPixels(dstPixels, 0, outW, 0, 0, outW, outH)
            return dst
        }

        for (dy in 0 until outH) {
            for (dx in 0 until outW) {
                // 逆映射：dst (dx,dy) → src (sx,sy)
                val w = inv[6] * dx + inv[7] * dy + inv[8]
                if (w < 1e-8 && w > -1e-8) { dstPixels[dy * outW + dx] = -1; continue }
                val sx = (inv[0] * dx + inv[1] * dy + inv[2]) / w
                val sy = (inv[3] * dx + inv[4] * dy + inv[5]) / w

                // 双线性插值
                val x0 = sx.toInt() - if (sx < 0) 1 else 0
                val y0 = sy.toInt() - if (sy < 0) 1 else 0
                val x1 = x0 + 1
                val y1 = y0 + 1
                val fx = (sx - x0).toFloat()
                val fy = (sy - y0).toFloat()

                // getPixels 数组索引（OOB → -1 → 映射到白色）
                val i00 = if (x0 in 0 until srcW && y0 in 0 until srcH) y0 * srcW + x0 else -1
                val i10 = if (x1 in 0 until srcW && y0 in 0 until srcH) y0 * srcW + x1 else -1
                val i01 = if (x0 in 0 until srcW && y1 in 0 until srcH) y1 * srcW + x0 else -1
                val i11 = if (x1 in 0 until srcW && y1 in 0 until srcH) y1 * srcW + x1 else -1

                val c00 = if (i00 >= 0) srcPixels[i00] else -1
                val c10 = if (i10 >= 0) srcPixels[i10] else -1
                val c01 = if (i01 >= 0) srcPixels[i01] else -1
                val c11 = if (i11 >= 0) srcPixels[i11] else -1

                // 每通道双线性插值
                val r = bilinearChannel(c00 ushr 16 and 0xFF, c10 ushr 16 and 0xFF,
                    c01 ushr 16 and 0xFF, c11 ushr 16 and 0xFF, fx, fy)
                val g = bilinearChannel(c00 ushr 8 and 0xFF, c10 ushr 8 and 0xFF,
                    c01 ushr 8 and 0xFF, c11 ushr 8 and 0xFF, fx, fy)
                val b = bilinearChannel(c00 and 0xFF, c10 and 0xFF,
                    c01 and 0xFF, c11 and 0xFF, fx, fy)

                dstPixels[dy * outW + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        dst.setPixels(dstPixels, 0, outW, 0, 0, outW, outH)
        return dst
    }

    private fun bilinearChannel(c00: Int, c10: Int, c01: Int, c11: Int, fx: Float, fy: Float): Int {
        val v = c00 * (1 - fx) * (1 - fy) + c10 * fx * (1 - fy) +
                c01 * (1 - fx) * fy + c11 * fx * fy
        return v.toInt().coerceIn(0, 255)
    }

    /**
     * 3x3 矩阵求逆（行优先 9 元素数组）。
     */
    private fun invertMatrix3x3(m: DoubleArray): DoubleArray? {
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) -
                  m[1] * (m[3] * m[8] - m[5] * m[6]) +
                  m[2] * (m[3] * m[7] - m[4] * m[6])
        if (kotlin.math.abs(det) < 1e-12) return null
        val invDet = 1.0 / det
        return doubleArrayOf(
            (m[4] * m[8] - m[5] * m[7]) * invDet,
            (m[2] * m[7] - m[1] * m[8]) * invDet,
            (m[1] * m[5] - m[2] * m[4]) * invDet,
            (m[5] * m[6] - m[3] * m[8]) * invDet,
            (m[0] * m[8] - m[2] * m[6]) * invDet,
            (m[2] * m[3] - m[0] * m[5]) * invDet,
            (m[3] * m[7] - m[4] * m[6]) * invDet,
            (m[1] * m[6] - m[0] * m[7]) * invDet,
            (m[0] * m[4] - m[1] * m[3]) * invDet
        )
    }

    /**
     * 使用 ML Kit 检测+识别（或 manga-ocr 混合模式）。
     * 这是原有逻辑的封装，保持向后兼容。
     */
    suspend fun detectWithMLKit(
        bitmap: Bitmap,
        language: String,
        useMangaOcr: Boolean
    ): List<TextBlockInfo> {
        return if (useMangaOcr && MangaOcrBridge.isAvailable()) {
            LogCollector.d(TAG, "使用 ML Kit 检测 + manga-ocr 识别")
            MangaOcrBridge.recognizeWithLocation(bitmap, language)
        } else {
            LogCollector.d(TAG, "使用 ML Kit 检测 + 识别")
            OCRBridge.recognizeWithLocation(language, bitmap)
        }
    }

    private fun cropBitmap(bitmap: Bitmap, rect: Rect): Bitmap {
        val padding = 10
        val left = maxOf(0, rect.left - padding)
        val top = maxOf(0, rect.top - padding)
        val right = minOf(bitmap.width, rect.right + padding)
        val bottom = minOf(bitmap.height, rect.bottom + padding)

        val width = right - left
        val height = bottom - top

        if (width <= 0 || height <= 0) {
            LogCollector.w(TAG, "裁剪区域无效: left=$left, top=$top, right=$right, bottom=$bottom")
            return bitmap
        }

        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    /**
     * 计算两个矩形的 IoU (Intersection over Union)。
     */
    private fun calcIoU(a: Rect, b: Rect): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        val interArea = maxOf(0, interRight - interLeft) * maxOf(0, interBottom - interTop).toFloat()
        val unionArea = (a.right - a.left) * (a.bottom - a.top).toFloat() +
                (b.right - b.left) * (b.bottom - b.top).toFloat() - interArea
        return if (unionArea > 0) interArea / unionArea else 0f
    }

    /**
     * 计算 containment：inner 有多少比例被 outer 包含。
     * 返回值 1.0 表示 inner 完全在 outer 内部。
     */
    private fun calcContainment(outer: Rect, inner: Rect): Float {
        val interLeft = maxOf(outer.left, inner.left)
        val interTop = maxOf(outer.top, inner.top)
        val interRight = minOf(outer.right, inner.right)
        val interBottom = minOf(outer.bottom, inner.bottom)
        val interArea = maxOf(0, interRight - interLeft) * maxOf(0, interBottom - interTop).toFloat()
        val innerArea = (inner.right - inner.left) * (inner.bottom - inner.top).toFloat()
        return if (innerArea > 0) interArea / innerArea else 0f
    }

    /**
     * 判断是否是纯符号模式（如 ". . . " 或 "· · ·"）
     */
    private fun isDotOnlyPattern(text: String): Boolean {
        if (text.isBlank()) return true
        // 统计原始文本中点号类字符的占比
        val dotChars = text.count { it == '.' || it == '·' || it == '…' }
        // 点号占比超过 80% 才认为是纯符号
        return dotChars > 0 && dotChars >= text.length * 0.8
    }


    /**
     * RT-DETR-V2 气泡检测 + 指定 OCR 引擎识别。
     *
     * 流程：
     * 1. ComicBubbleDetector 检测气泡/文字区域 → List<DetectedBubble>
     * 2. 过滤 classId==0（无文字气泡），只保留 text_bubble(1) + text_free(2)
     * 3. 逐个裁剪区域 → MangaOcr 识别
     * 4. 返回 TextBlockInfo 列表
     *
     * @param bitmap 输入图片
     * @param language OCR 语言代码
     * @return TextBlockInfo 列表
     */
    suspend fun detectWithRTDetrV2(
        bitmap: Bitmap,
        @Suppress("UNUSED_PARAMETER") language: String,
        @Suppress("UNUSED_PARAMETER") context: Context,
        keepTextFree: Boolean = false
    ): List<TextBlockInfo> {
        try {
            LogCollector.d(TAG, "使用 RT-DETR-V2 + MangaOcr 检测文字区域...")

            // Step 1: RT-DETR-V2 检测（返回所有类别）
            val allBubbles = ComicBubbleDetector.detectBubbles(bitmap)
            if (allBubbles.isEmpty()) {
                LogCollector.d(TAG, "RT-DETR-V2 未检测到文字区域")
                return emptyList()
            }
            LogCollector.d(TAG, "RT-DETR-V2 原始检测到 ${allBubbles.size} 个区域")

            // Step 2: 分类处理
            // - text_bubble(绿色, classId=1): 直接保留
            // - text_free(蓝色, classId=2): keepTextFree 时保留，否则丢弃
            // - bubble(红色, classId=0): 压缩15%后保留（减轻OCR压力）
            val greenBubbles = allBubbles.filter { it.classId == 1 || (keepTextFree && it.classId == 2) }
            val redBubbles = allBubbles.filter { it.classId == 0 }.map { bubble ->
                // 压缩15%，去除气泡外框多余的边距
                val cx = (bubble.rect.left + bubble.rect.right) / 2
                val cy = (bubble.rect.top + bubble.rect.bottom) / 2
                val halfW = (bubble.rect.right - bubble.rect.left) / 2 * 0.85f
                val halfH = (bubble.rect.bottom - bubble.rect.top) / 2 * 0.85f
                bubble.copy(rect = Rect(
                    (cx - halfW).toInt().coerceAtLeast(0),
                    (cy - halfH).toInt().coerceAtLeast(0),
                    (cx + halfW).toInt(),
                    (cy + halfH).toInt()
                ))
            }

            // Step 3: 去重 — 红色区域与绿色区域有重叠时，丢弃红色（优先用更紧凑的绿色）
            val dedupedBubbles = greenBubbles.toMutableList()
            for (red in redBubbles) {
                // 红色完全包裹绿色时丢弃红色（绿色面积90%以上在红色内部）
                val fullyContained = greenBubbles.any { green -> calcContainment(red.rect, green.rect) > 0.9f }
                if (!fullyContained) {
                    dedupedBubbles.add(red)
                }
            }

            // Step 4: 按 confidence 降序排序
            val sortedBubbles = dedupedBubbles.sortedByDescending { it.confidence }

            LogCollector.d(TAG, "RT-DETR-V2 过滤后 ${sortedBubbles.size} 个区域 (text_bubble=${allBubbles.count { it.classId == 1 }}, text_free=${if (keepTextFree) "保留(${allBubbles.count { it.classId == 2 }})" else "丢弃"}, bubble保留=${dedupedBubbles.size - greenBubbles.size})")

            // Step 5: 裁剪图片（10px padding）
            val croppedBitmaps = sortedBubbles.map { bubble -> cropBitmap(bitmap, bubble.rect) }

            // Step 6: MangaOcr 识别
            val results = mutableListOf<TextBlockInfo>()
            val texts = MangaOcrRecognizer.recognizeBatch(croppedBitmaps)
            for (i in sortedBubbles.indices) {
                val text = texts[i].trim()
                if (text.isNotBlank() && !isDotOnlyPattern(text)) {
                    val rect = sortedBubbles[i].rect
                    val isVertical = rect.height() > rect.width()
                    results.add(TextBlockInfo(
                        text = text,
                        boundingBox = rect,
                        cornerPoints = null,
                        isVertical = isVertical
                    ))
                    LogCollector.d(TAG, "RT-DETR-V2(MangaOcr) [$i]: rect=$rect, class=${sortedBubbles[i].classId}, text='$text', isVertical=$isVertical")
                }
            }

            // 释放裁剪的图片
            for (cropped in croppedBitmaps) {
                if (cropped !== bitmap) cropped.recycle()
            }

            LogCollector.d(TAG, "RT-DETR-V2 + MangaOcr 完成，共 ${results.size} 个文字块")
            return results

        } catch (e: Exception) {
            LogCollector.e(TAG, "RT-DETR-V2 检测失败", e)
            throw e
        }
    }

    /**
     * RT-DETR-V2 检测+裁剪，不做 OCR 识别。
     * 用于分批渲染场景：先检测所有气泡位置，再分批调用 OCR。
     *
     * @return 按 confidence 降序排列的裁剪结果列表
     */
    suspend fun detectAndCropRTDetrV2(
        bitmap: Bitmap,
        keepTextFree: Boolean = false
    ): List<CroppedBubble> = withContext(Dispatchers.IO) {
        LogCollector.d(TAG, "detectAndCropRTDetrV2: 开始检测, keepTextFree=$keepTextFree...")

        // Step 1: RT-DETR-V2 检测
        val allBubbles = ComicBubbleDetector.detectBubbles(bitmap)
        if (allBubbles.isEmpty()) {
            LogCollector.d(TAG, "detectAndCropRTDetrV2: 未检测到文字区域")
            return@withContext emptyList()
        }
        LogCollector.d(TAG, "detectAndCropRTDetrV2: 原始检测到 ${allBubbles.size} 个区域")

        // Step 2: 分类处理
        // - text_bubble(classId=1): 始终保留
        // - text_free(classId=2): keepTextFree 时保留，否则丢弃
        // - bubble(classId=0): 压缩 15% 后保留
        val greenBubbles = allBubbles.filter { it.classId == 1 || (keepTextFree && it.classId == 2) }
        val redBubbles = allBubbles.filter { it.classId == 0 }.map { bubble ->
            val cx = (bubble.rect.left + bubble.rect.right) / 2
            val cy = (bubble.rect.top + bubble.rect.bottom) / 2
            val halfW = (bubble.rect.right - bubble.rect.left) / 2 * 0.85f
            val halfH = (bubble.rect.bottom - bubble.rect.top) / 2 * 0.85f
            bubble.copy(rect = Rect(
                (cx - halfW).toInt().coerceAtLeast(0),
                (cy - halfH).toInt().coerceAtLeast(0),
                (cx + halfW).toInt(),
                (cy + halfH).toInt()
            ))
        }

        // Step 3: 去重
        val dedupedBubbles = greenBubbles.toMutableList()
        for (red in redBubbles) {
            val fullyContained = greenBubbles.any { green -> calcContainment(red.rect, green.rect) > 0.9f }
            if (!fullyContained) {
                dedupedBubbles.add(red)
            }
        }

        // Step 4: 按 confidence 降序排序
        val sortedBubbles = dedupedBubbles.sortedByDescending { it.confidence }
        LogCollector.d(TAG, "detectAndCropRTDetrV2: 过滤后 ${sortedBubbles.size} 个区域 (text_bubble=${allBubbles.count { it.classId == 1 }}, text_free=${if (keepTextFree) "保留(${allBubbles.count { it.classId == 2 }})" else "丢弃"}, bubble保留=${dedupedBubbles.size - greenBubbles.size})")

        // Step 5: 裁剪图片（10px padding）
        val cropped = sortedBubbles.map { bubble ->
            CroppedBubble(
                croppedBitmap = cropBitmap(bitmap, bubble.rect),
                rect = bubble.rect,
                classId = bubble.classId,
                confidence = bubble.confidence
            )
        }

        LogCollector.d(TAG, "detectAndCropRTDetrV2: 完成，${cropped.size} 个裁剪区域")
        cropped
    }

    /**
     * 对裁剪好的气泡图片调用 OCR 识别。
     * 识别完成后释放裁剪图片。
     *
     * @param croppedBubbles 裁剪结果列表
     * @param language 语言代码
     * @return TextBlockInfo 列表
     */
    suspend fun recognizeCroppedBubbles(
        croppedBubbles: List<CroppedBubble>,
        @Suppress("UNUSED_PARAMETER") language: String
    ): List<TextBlockInfo> = withContext(Dispatchers.IO) {
        if (croppedBubbles.isEmpty()) return@withContext emptyList()

        LogCollector.d(TAG, "recognizeCroppedBubbles: ${croppedBubbles.size} 个气泡, engine=MangaOcr")

        val croppedBitmaps = croppedBubbles.map { it.croppedBitmap }
        val results = mutableListOf<TextBlockInfo>()

        val texts = MangaOcrRecognizer.recognizeBatch(croppedBitmaps)
        for (i in croppedBubbles.indices) {
            val text = texts[i].trim()
            if (text.isNotBlank() && !isDotOnlyPattern(text)) {
                val bubble = croppedBubbles[i]
                val isVertical = bubble.rect.height() > bubble.rect.width()
                results.add(TextBlockInfo(
                    text = text,
                    boundingBox = bubble.rect,
                    cornerPoints = null,
                    isVertical = isVertical
                ))
                LogCollector.d(TAG, "recognizeCroppedBubbles(MangaOcr) [$i]: rect=${bubble.rect}, text='$text', isVertical=$isVertical")
            }
        }

        // 释放裁剪图片
        for (cropped in croppedBubbles) {
            cropped.croppedBitmap.recycle()
        }

        LogCollector.d(TAG, "recognizeCroppedBubbles: 完成，${results.size} 个文字块")
        results
    }

    /**
     * 流式识别裁剪气泡：decoder 每完成一个就返回结果，不等全部完成。
     *
     * @return Channel<Pair<Int, TextBlockInfo>> (索引, 识别结果)
     */
    suspend fun recognizeCroppedBubblesStreaming(
        croppedBubbles: List<CroppedBubble>
    ): kotlinx.coroutines.channels.Channel<Pair<Int, TextBlockInfo>> {
        val channel = kotlinx.coroutines.channels.Channel<Pair<Int, TextBlockInfo>>(kotlinx.coroutines.channels.Channel.UNLIMITED)

        if (croppedBubbles.isEmpty()) {
            channel.close()
            return channel
        }

        LogCollector.d(TAG, "recognizeCroppedBubblesStreaming: ${croppedBubbles.size} 个气泡, engine=MangaOcr")

        val croppedBitmaps = croppedBubbles.map { it.croppedBitmap }
        val ocrChannel = MangaOcrRecognizer.recognizeStreaming(croppedBitmaps)

        // 转换 OCR 结果为 TextBlockInfo
        for ((i, text) in ocrChannel) {
            val trimmed = text.trim()
            if (trimmed.isNotBlank() && !isDotOnlyPattern(trimmed)) {
                val bubble = croppedBubbles[i]
                val isVertical = bubble.rect.height() > bubble.rect.width()
                channel.send(Pair(i, TextBlockInfo(
                    text = trimmed,
                    boundingBox = bubble.rect,
                    cornerPoints = null,
                    isVertical = isVertical
                )))
                LogCollector.d(TAG, "recognizeCroppedBubblesStreaming(MangaOcr) [$i]: rect=${bubble.rect}, text='$trimmed', isVertical=$isVertical")
            }
        }

        channel.close()
        return channel
    }

    /**
     * RT-DETR-V2 调试模式：只检测，不翻译，显示所有类别的检测框。
     */
    fun detectWithRTDetrV2Debug(bitmap: Bitmap, keepTextFree: Boolean = false): RTDetrV2DebugResult {
        val allBubbles = ComicBubbleDetector.detectBubblesAllClasses(bitmap)
        LogCollector.d(TAG, "RT-DETR-V2 Debug: 检测到 ${allBubbles.size} 个区域")

        for ((idx, b) in allBubbles.withIndex()) {
            val className = when (b.classId) {
                0 -> "bubble"
                1 -> "text_bubble"
                2 -> "text_free"
                else -> "unknown"
            }
            LogCollector.d(TAG, "  [$idx] rect=[${b.rect.left},${b.rect.top},${b.rect.right},${b.rect.bottom}] class=$className(${b.classId}) conf=${String.format("%.3f", b.confidence)}")
        }

        // 计算最终提交区域（与 detectWithRTDetrV2 一致的逻辑）
        val greenBubbles = allBubbles.filter { it.classId == 1 || (keepTextFree && it.classId == 2) }
        val redBubbles = allBubbles.filter { it.classId == 0 }.map { bubble ->
            // 压缩15%，去除气泡外框多余的边距
            val cx = (bubble.rect.left + bubble.rect.right) / 2
            val cy = (bubble.rect.top + bubble.rect.bottom) / 2
            val halfW = (bubble.rect.right - bubble.rect.left) / 2 * 0.85f
            val halfH = (bubble.rect.bottom - bubble.rect.top) / 2 * 0.85f
            bubble.copy(rect = Rect(
                (cx - halfW).toInt().coerceAtLeast(0),
                (cy - halfH).toInt().coerceAtLeast(0),
                (cx + halfW).toInt(),
                (cy + halfH).toInt()
            ))
        }
        val finalRegions = greenBubbles.map { it.rect }.toMutableList()
        for (red in redBubbles) {
            val fullyContained = greenBubbles.any { green -> calcContainment(red.rect, green.rect) > 0.9f }
            if (!fullyContained) {
                finalRegions.add(red.rect)
            }
        }

        return RTDetrV2DebugResult(
            allBubbles = allBubbles,
            textBubbles = allBubbles.filter { it.classId == 1 },
            textFree = allBubbles.filter { it.classId == 2 },
            emptyBubbles = allBubbles.filter { it.classId == 0 },
            finalRegions = finalRegions
        )
    }

    /**
     * 统一 OCR 入口：根据检测引擎和 OCR 引擎整数 ID 自动路由到对应检测+识别方法。
     * 供 MangaViewerActivity 等非服务组件直接调用。
     *
     * @param bitmap 输入图片
     * @param language 语言代码
     * @param detEngine 检测引擎整数 ID（对应 DetEngine.value）
     * @param ocrEngine OCR 引擎整数 ID（对应 OcrEngine.value）
     * @param context Context
     * @return TextBlockInfo 列表
     */
    suspend fun runOCR(
        bitmap: Bitmap,
        language: String,
        detEngine: Int,
        ocrEngine: Int,
        context: Context
    ): List<TextBlockInfo> {
        val det = DetEngine.fromValue(detEngine)
        val ocr = OcrEngine.fromValue(ocrEngine)
        return when (det) {
            DetEngine.MLKIT -> {
                LogCollector.d(TAG, "使用 ML Kit 检测+识别, language=$language")
                OCRBridge.recognizeWithLocation(language, bitmap)
            }
            DetEngine.RT_DETR_V2 -> {
                LogCollector.d(TAG, "使用 RT-DETR-V2 检测, ocr=$ocr, language=$language")
                detectWithRTDetrV2(bitmap, language, context)
            }
            DetEngine.PP_OCR_V5 -> {
                LogCollector.d(TAG, "使用 PP-OCRv5 独立检测+识别, language=$language")
                detectWithPPOcrV5(bitmap, language, context)
            }
            DetEngine.PP_OCR_V6 -> {
                LogCollector.d(TAG, "使用 PP-OCRv6 独立检测+识别, language=$language")
                detectWithPPOcrV6(bitmap, language, context)
            }
        }
    }

    /**
     * 将 OCR 结果转换为 BubbleRegion 列表（正常翻译和重翻共用）。
     */
    fun ocrToBubbleRegions(
        ocrResults: List<TextBlockInfo>,
        textDirection: TextDirection = TextDirection.VERTICAL_RL
    ): List<BubbleRegion> {
        return ocrResults.filter { it.boundingBox != null }.map { block ->
            val rect = block.boundingBox!!
            val isVertical = block.inferredVertical()  // 真实边长推断（抗旋转），缺失才 AABB
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

    /**
     * PP-OCRv5 增量渲染：det 检测全部文字行 → 逐行裁剪。
     * 不做 cls/rec/分组，后续分批识别 + TextLineMerger 合并。
     *
     * @return 按漫画阅读顺序排列的裁剪文字行列表
     */
    suspend fun detectAndCropPPOcrV5Lines(
        context: android.content.Context,
        bitmap: Bitmap
    ): List<CroppedTextLine> = withContext(Dispatchers.IO) {
        LogCollector.d(TAG, "detectAndCropPPOcrV5Lines: 开始检测")

        // det 检测全部文字行框
        val boxes = PPOcrV5Engine.runDetForBoxes(context, bitmap)
        if (boxes.isEmpty()) {
            LogCollector.d(TAG, "detectAndCropPPOcrV5Lines: 未检测到文字区域")
            return@withContext emptyList()
        }
        LogCollector.d(TAG, "detectAndCropPPOcrV5Lines: 检测到 ${boxes.size} 个文字行")

        // 逐行裁剪（透视校正）
        val result = boxes.map { box ->
            var xMin = Float.MAX_VALUE; var yMin = Float.MAX_VALUE
            var xMax = Float.MIN_VALUE; var yMax = Float.MIN_VALUE
            for (k in box.indices step 2) {
                if (box[k] < xMin) xMin = box[k]
                if (box[k] > xMax) xMax = box[k]
            }
            for (k in 1 until box.size step 2) {
                if (box[k] < yMin) yMin = box[k]
                if (box[k] > yMax) yMax = box[k]
            }
            val rect = Rect(
                xMin.toInt().coerceAtLeast(0),
                yMin.toInt().coerceAtLeast(0),
                xMax.toInt().coerceAtMost(bitmap.width - 1),
                yMax.toInt().coerceAtMost(bitmap.height - 1)
            )
            // 从 QuadBox 计算倾斜角
            val tl = PointF(box[0], box[1])
            val tr = PointF(box[2], box[3])
            val topDx = tr.x - tl.x
            val topDy = tr.y - tl.y
            var angle = Math.toDegrees(kotlin.math.atan2(topDy, topDx).toDouble()).toFloat()
            if (kotlin.math.abs(angle) <= 3f) angle = 0f

            val pts = PPOcrV5Engine.boxToQuadPointsPublic(box)
            val crop = PPOcrDetGeometry.getRotateCropImage(bitmap, pts)
            CroppedTextLine(crop, rect, angle, rect.exactCenterX(), rect.exactCenterY())
        }

        // 按漫画阅读顺序排序（从上到下，从右到左）
        val sorted = result.sortedWith(
            compareBy<CroppedTextLine> { it.rect.top }
                .thenByDescending { it.rect.left }
        )

        LogCollector.d(TAG, "detectAndCropPPOcrV5Lines: ${boxes.size} 行裁剪完成")
        sorted
    }

    /**
     * PP-OCRv6 增量渲染：det 检测全部文字行 → 逐行裁剪。
     * 不做 cls/rec/分组，后续分批识别 + TextLineMerger 合并。
     *
     * @return 按漫画阅读顺序排列的裁剪文字行列表
     */
    suspend fun detectAndCropPPOcrV6Lines(
        context: android.content.Context,
        bitmap: Bitmap
    ): List<CroppedTextLine> = withContext(Dispatchers.IO) {
        LogCollector.d(TAG, "detectAndCropPPOcrV6Lines: 开始检测")

        // det 检测全部文字行框
        val boxes = PPOcrV6Engine.runDetForBoxes(context, bitmap)
        if (boxes.isEmpty()) {
            LogCollector.d(TAG, "detectAndCropPPOcrV6Lines: 未检测到文字区域")
            return@withContext emptyList()
        }
        LogCollector.d(TAG, "detectAndCropPPOcrV6Lines: 检测到 ${boxes.size} 个文字行")

        // 逐行裁剪（透视校正）
        val result = boxes.map { box ->
            var xMin = Float.MAX_VALUE; var yMin = Float.MAX_VALUE
            var xMax = Float.MIN_VALUE; var yMax = Float.MIN_VALUE
            for (k in box.indices step 2) {
                if (box[k] < xMin) xMin = box[k]
                if (box[k] > xMax) xMax = box[k]
            }
            for (k in 1 until box.size step 2) {
                if (box[k] < yMin) yMin = box[k]
                if (box[k] > yMax) yMax = box[k]
            }
            val rect = Rect(
                xMin.toInt().coerceAtLeast(0),
                yMin.toInt().coerceAtLeast(0),
                xMax.toInt().coerceAtMost(bitmap.width - 1),
                yMax.toInt().coerceAtMost(bitmap.height - 1)
            )
            // 从 QuadBox 计算倾斜角
            val tl = PointF(box[0], box[1])
            val tr = PointF(box[2], box[3])
            val topDx = tr.x - tl.x
            val topDy = tr.y - tl.y
            var angle = Math.toDegrees(kotlin.math.atan2(topDy, topDx).toDouble()).toFloat()
            if (kotlin.math.abs(angle) <= 3f) angle = 0f

            val pts = PPOcrV6Engine.boxToQuadPointsPublic(box)
            val crop = PPOcrDetGeometry.getRotateCropImage(bitmap, pts)
            CroppedTextLine(crop, rect, angle, rect.exactCenterX(), rect.exactCenterY())
        }

        // 按漫画阅读顺序排序（从上到下，从右到左）
        val sorted = result.sortedWith(
            compareBy<CroppedTextLine> { it.rect.top }
                .thenByDescending { it.rect.left }
        )

        LogCollector.d(TAG, "detectAndCropPPOcrV6Lines: ${boxes.size} 行裁剪完成")
        sorted
    }

    /**
     * 使用 PP-OCRv5 独立检测 + 识别（det + cls + rec 完整流水线）。
     *
     * @param bitmap 输入图片
     * @param language 语言代码
     * @return TextBlockInfo 列表
     */
    suspend fun detectWithPPOcrV5(
        bitmap: Bitmap,
        language: String,
        context: Context
    ): List<TextBlockInfo> {
        try {
            LogCollector.d(TAG, "使用 PP-OCRv5 独立检测+识别, language=$language")

            val (recLang, _) = PPOcrV5Engine.resolveRecLang(context, language)
            if (recLang == null) {
                LogCollector.w(TAG, "PP-OCRv5 不支持的语言: $language")
                return emptyList()
            }

            val result = withContext(Dispatchers.IO) {
                PPOcrV5Engine.runOCR(context, bitmap, recLang, useDet = true)
            }

            // 转换为 TextLine（识别后的文字行）
            val textLines = result.texts.indices.mapNotNull { i ->
                val text = result.texts[i]
                if (text.isBlank() || result.scores[i] < 0.5f) return@mapNotNull null

                val box = result.boxes.getOrNull(i) ?: return@mapNotNull null
                if (box.size < 8) return@mapNotNull null

                // 4 顶点：TL, TR, BR, BL
                val tl = android.graphics.PointF(box[0], box[1])
                val tr = android.graphics.PointF(box[2], box[3])
                val br = android.graphics.PointF(box[4], box[5])
                val bl = android.graphics.PointF(box[6], box[7])
                val quadPoints = arrayOf(tl, tr, br, bl)

                val topDx = tr.x - tl.x
                val topDy = tr.y - tl.y
                val topLen = kotlin.math.sqrt((topDx * topDx + topDy * topDy).toDouble()).toFloat()
                val leftDx = bl.x - tl.x
                val leftDy = bl.y - tl.y
                val leftLen = kotlin.math.sqrt((leftDx * leftDx + leftDy * leftDy).toDouble()).toFloat()

                var angle = kotlin.math.atan2(topDy, topDx) * 180f / Math.PI.toFloat()
                if (kotlin.math.abs(angle) <= 3f) angle = 0f

                val isVertical = leftLen > topLen * 1.5f
                val fontSize = if (isVertical) topLen else leftLen

                var xMin = Float.MAX_VALUE
                var yMin = Float.MAX_VALUE
                var xMax = Float.MIN_VALUE
                var yMax = Float.MIN_VALUE
                for (k in box.indices step 2) {
                    if (box[k] < xMin) xMin = box[k]
                    if (box[k] > xMax) xMax = box[k]
                }
                for (k in 1 until box.size step 2) {
                    if (box[k] < yMin) yMin = box[k]
                    if (box[k] > yMax) yMax = box[k]
                }
                val rect = Rect(
                    xMin.toInt().coerceAtLeast(0),
                    yMin.toInt().coerceAtLeast(0),
                    xMax.toInt().coerceAtMost(bitmap.width - 1),
                    yMax.toInt().coerceAtMost(bitmap.height - 1)
                )
                val center = android.graphics.PointF(rect.exactCenterX(), rect.exactCenterY())

                PPOcrTextLine(
                    rect = rect, text = text, fontSize = fontSize,
                    isVertical = isVertical, score = result.scores[i],
                    angle = angle, quadPoints = quadPoints, center = center
                )
            }

            // 识别后合并（对齐参考项目 merge_bboxes_text_region）
            TextRegionMerger.refreshParams(context)
            // 竖排方向遵循用户配置（Manga_Text_Direction）
            val textDirection = if (com.moe.starflow.utils.CustomPreference.getInstance(context).getString("Manga_Text_Direction", "0") == "1")
                TextDirection.VERTICAL_LR else TextDirection.VERTICAL_RL
            val allMerged = TextRegionMerger.merge(textLines.map { TextRegion(quad = QuadBox(it.quadPoints), text = it.text, score = it.score) }, verticalDirection = textDirection)
            // 合并后内容过滤：丢弃空白、单字符、纯符号、短数字
            val mergedRegions = allMerged.filter { region ->
                val text = region.texts.joinToString("").trim()
                val discard = text.isEmpty() || text.length == 1 ||
                    text.all { !it.isLetterOrDigit() } ||
                    (text.length <= 2 && text.all { it.isDigit() })
                if (discard) LogCollector.d(TAG, "PP-OCRv5 内容丢弃: \"${text.take(20)}\"")
                !discard
            }
            LogCollector.d(TAG, "PP-OCRv5 TextLineMerger: ${textLines.size} 行 → ${allMerged.size} 合并 → 内容丢弃${allMerged.size - mergedRegions.size} → ${mergedRegions.size} 输出")

            // 转换为 TextBlockInfo
            val textBlocks = mergedRegions.map { region ->
                TextBlockInfo(
                    text = region.texts.joinToString("\n"),
                    boundingBox = region.rect,
                    cornerPoints = null,
                    isVertical = region.direction == TextDirection.VERTICAL_RL || region.direction == TextDirection.VERTICAL_LR,
                    angle = region.angle,
                    centerX = region.center.x,
                    centerY = region.center.y
                )
            }

            LogCollector.d(TAG, "PP-OCRv5 独立完成，共 ${textBlocks.size} 个文本区域")
            return textBlocks

        } catch (e: Exception) {
            LogCollector.e(TAG, "PP-OCRv5 检测失败", e)
            throw e
        }
    }

    /**
     * 使用 PP-OCRv6 独立检测 + 识别（det + cls + rec 完整流水线）。
     * V6 支持多语言，无需 resolveRecLang。
     *
     * @param bitmap 输入图片
     * @param language 语言代码
     * @return TextBlockInfo 列表
     */
    suspend fun detectWithPPOcrV6(
        bitmap: Bitmap,
        language: String,
        context: Context
    ): List<TextBlockInfo> {
        try {
            LogCollector.d(TAG, "使用 PP-OCRv6 独立检测+识别, language=$language")

            val result = withContext(Dispatchers.IO) {
                PPOcrV6Engine.runOCR(context, bitmap, useDet = true)
            }

            // 转换为 TextLine（识别后的文字行）
            val textLines = result.texts.indices.mapNotNull { i ->
                val text = result.texts[i]
                if (text.isBlank() || result.scores[i] < 0.5f) return@mapNotNull null

                val box = result.boxes.getOrNull(i) ?: return@mapNotNull null
                if (box.size < 8) return@mapNotNull null

                // 4 顶点：TL, TR, BR, BL
                val tl = android.graphics.PointF(box[0], box[1])
                val tr = android.graphics.PointF(box[2], box[3])
                val br = android.graphics.PointF(box[4], box[5])
                val bl = android.graphics.PointF(box[6], box[7])
                val quadPoints = arrayOf(tl, tr, br, bl)

                val topDx = tr.x - tl.x
                val topDy = tr.y - tl.y
                val topLen = kotlin.math.sqrt((topDx * topDx + topDy * topDy).toDouble()).toFloat()
                val leftDx = bl.x - tl.x
                val leftDy = bl.y - tl.y
                val leftLen = kotlin.math.sqrt((leftDx * leftDx + leftDy * leftDy).toDouble()).toFloat()

                var angle = kotlin.math.atan2(topDy, topDx) * 180f / Math.PI.toFloat()
                if (kotlin.math.abs(angle) <= 3f) angle = 0f

                val isVertical = leftLen > topLen * 1.5f
                val fontSize = if (isVertical) topLen else leftLen

                var xMin = Float.MAX_VALUE
                var yMin = Float.MAX_VALUE
                var xMax = Float.MIN_VALUE
                var yMax = Float.MIN_VALUE
                for (k in box.indices step 2) {
                    if (box[k] < xMin) xMin = box[k]
                    if (box[k] > xMax) xMax = box[k]
                }
                for (k in 1 until box.size step 2) {
                    if (box[k] < yMin) yMin = box[k]
                    if (box[k] > yMax) yMax = box[k]
                }
                val rect = Rect(
                    xMin.toInt().coerceAtLeast(0),
                    yMin.toInt().coerceAtLeast(0),
                    xMax.toInt().coerceAtMost(bitmap.width - 1),
                    yMax.toInt().coerceAtMost(bitmap.height - 1)
                )
                val center = android.graphics.PointF(rect.exactCenterX(), rect.exactCenterY())

                PPOcrTextLine(
                    rect = rect, text = text, fontSize = fontSize,
                    isVertical = isVertical, score = result.scores[i],
                    angle = angle, quadPoints = quadPoints, center = center
                )
            }

            // 识别后合并（对齐参考项目 merge_bboxes_text_region）
            TextRegionMerger.refreshParams(context)
            // 竖排方向遵循用户配置（Manga_Text_Direction）
            val textDirection = if (com.moe.starflow.utils.CustomPreference.getInstance(context).getString("Manga_Text_Direction", "0") == "1")
                TextDirection.VERTICAL_LR else TextDirection.VERTICAL_RL
            val allMerged = TextRegionMerger.merge(textLines.map { TextRegion(quad = QuadBox(it.quadPoints), text = it.text, score = it.score) }, verticalDirection = textDirection)
            // 合并后内容过滤：丢弃空白、单字符、纯符号、短数字
            val mergedRegions = allMerged.filter { region ->
                val text = region.texts.joinToString("").trim()
                val discard = text.isEmpty() || text.length == 1 ||
                    text.all { !it.isLetterOrDigit() } ||
                    (text.length <= 2 && text.all { it.isDigit() })
                if (discard) LogCollector.d(TAG, "PP-OCRv6 内容丢弃: \"${text.take(20)}\"")
                !discard
            }
            LogCollector.d(TAG, "PP-OCRv6: ${textLines.size} 行 → ${allMerged.size} 合并 → 内容丢弃${allMerged.size - mergedRegions.size} → ${mergedRegions.size} 输出")

            // 转换为 TextBlockInfo
            val textBlocks = mergedRegions.map { region ->
                TextBlockInfo(
                    text = region.texts.joinToString("\n"),
                    boundingBox = region.rect,
                    cornerPoints = null,
                    isVertical = region.direction == TextDirection.VERTICAL_RL || region.direction == TextDirection.VERTICAL_LR,
                    angle = region.angle,
                    centerX = region.center.x,
                    centerY = region.center.y
                )
            }

            LogCollector.d(TAG, "PP-OCRv6 独立完成，共 ${textBlocks.size} 个文本区域")
            return textBlocks

        } catch (e: Exception) {
            LogCollector.e(TAG, "PP-OCRv6 检测失败", e)
            throw e
        }
    }

    /**
     * ML Kit 调试模式：返回所有 ML Kit 识别数据（block/line/element 全部层级）
     */
    suspend fun detectWithMLKitDebug(
        bitmap: Bitmap,
        language: String
    ): MLKitDebugResult = suspendCancellableCoroutine { continuation ->
        val recognizer = when (language) {
            "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "zh", "zh-CN", "zh-TW" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }

        LogCollector.d(TAG, "ML Kit Debug: bitmap=${bitmap.width}x${bitmap.height}, lang=$language")
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { result ->
                var totalLines = 0
                var totalElements = 0

                val debugBlocks = result.textBlocks.map { block ->
                    val lines = block.lines.map { line ->
                        totalLines++
                        val elements = line.elements.map { element ->
                            totalElements++
                            MLKitDebugElement(
                                elementText = element.text,
                                elementRect = element.boundingBox,
                                elementCorners = element.cornerPoints?.let { pts ->
                                    Array(pts.size) { i -> pts[i] }
                                }
                            )
                        }
                        MLKitDebugLine(
                            lineText = line.text,
                            lineRect = line.boundingBox,
                            lineCorners = line.cornerPoints?.let { pts ->
                                Array(pts.size) { i -> pts[i] }
                            },
                            angle = line.angle,
                            elements = elements
                        )
                    }

                    MLKitDebugBlock(
                        blockText = block.text,
                        blockRect = block.boundingBox,
                        blockCorners = block.cornerPoints?.let { pts ->
                            Array(pts.size) { i -> pts[i] }
                        },
                        lines = lines,
                        language = block.recognizedLanguage
                    )
                }

                val result_obj = MLKitDebugResult(
                    textBlocks = debugBlocks,
                    totalLines = totalLines,
                    totalElements = totalElements,
                    detectedLanguage = result.textBlocks.firstOrNull()?.recognizedLanguage
                )

                LogCollector.d(TAG, "ML Kit Debug: 完成, blocks=${debugBlocks.size}, lines=$totalLines, elements=$totalElements")
                continuation.resume(result_obj)
            }
            .addOnFailureListener { e ->
                LogCollector.e(TAG, "ML Kit Debug: 失败", e)
                continuation.resumeWithException(e)
            }
            .addOnCompleteListener {
                recognizer.close()
            }
    }
}
