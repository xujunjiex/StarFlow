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
import com.moe.starflow.R
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.moe.starflow.manga.types.DetectedBubble
import com.moe.starflow.utils.LogCollector
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * RT-DETR-v2 漫画气泡/文字区域检测器（ONNX Runtime 推理）。
 *
 * 模型: RT-DETR-v2 r50vd, fine-tuned on ~11k manga/comic images.
 * 输入: images [1, 3, 640, 640] + orig_target_sizes [1, 2]
 * 输出: labels [1, 300], boxes [1, 300, 4], scores [1, 300]
 * 类别: 0=bubble(无文字气泡), 1=text_bubble(气泡内文字), 2=text_free(自由文字)
 */
object ComicBubbleDetector {

    private const val TAG = "ComicBubbleDetector"
    private const val INPUT_SIZE = 640
    private const val MODEL_DIR = "bubble_detector"
    private const val MODEL_FILE = "model.onnx"

    // ImageNet normalization
    private val IMAGE_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val IMAGE_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    private const val RESCALE_FACTOR = 1.0f / 255.0f

    // NMS 阈值
    private const val NMS_IOU_THRESHOLD = 0.5f

    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null

    @Volatile
    var isInitialized = false
        private set

    // 保护 session/env 与推理不并发：release() 等推理完成后再关闭
    private val sessionLock = ReentrantLock()

    /**
     * 初始化模型。仅从 filesDir 加载（需先下载）。
     */
    suspend fun initialize(context: Context) {
        if (isInitialized) return

        val modelFile = RTDetrModelFiles.getFilesDirModelFile(context)
        if (!modelFile.exists() || modelFile.length() == 0L) {
            throw IllegalStateException(context.getString(R.string.error_det_model_missing))
        }

        try {
            LogCollector.d(TAG, "开始初始化 RT-DETR-v2 气泡检测模型...")

            ortEnv = OrtEnvironment.getEnvironment()

            val sessionOptions = OrtSession.SessionOptions().apply {
                setMemoryPatternOptimization(false)
                setCPUArenaAllocator(false)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
            }

            LogCollector.d(TAG, "从 filesDir 加载模型: ${modelFile.absolutePath}")
            session = ortEnv!!.createSession(modelFile.absolutePath, sessionOptions)

            isInitialized = true
            LogCollector.d(TAG, "RT-DETR-v2 气泡检测模型初始化完成")
        } catch (e: Exception) {
            LogCollector.e(TAG, "RT-DETR-v2 初始化失败", e)
            release()
            throw e
        }
    }

    /**
     * 检测图片中的文字气泡区域。
     *
     * @param bitmap 原始截图
     * @param confThreshold 置信度阈值，默认 0.4
     * @return 检测到的气泡/文字区域列表（已过滤 classId=0，已 NMS 去重）
     */
    fun detectBubbles(bitmap: Bitmap, confThreshold: Float = 0.4f): List<DetectedBubble> = sessionLock.withLock {
        if (!isInitialized) {
            throw IllegalStateException("ComicBubbleDetector 未初始化")
        }

        try {
            val origW = bitmap.width
            val origH = bitmap.height

            // 预处理
            val inputTensor = preprocessImage(bitmap)
            val sizeTensor = OnnxTensor.createTensor(
                ortEnv!!,
                java.nio.LongBuffer.wrap(longArrayOf(origW.toLong(), origH.toLong())),
                longArrayOf(1, 2)
            )

            // 推理
            val inputs = mapOf(
                "images" to inputTensor,
                "orig_target_sizes" to sizeTensor
            )
            val results = session!!.run(inputs)
            inputTensor.close()
            sizeTensor.close()

            // 解析输出
            val labelsTensor = results.get("labels").get() as OnnxTensor
            val boxesTensor = results.get("boxes").get() as OnnxTensor
            val scoresTensor = results.get("scores").get() as OnnxTensor

            val labels = OnnxUtils.extractLongArray(labelsTensor)
            val boxes = OnnxUtils.extractFloatArray(boxesTensor)
            val scores = OnnxUtils.extractFloatArray(scoresTensor)

            labelsTensor.close()
            boxesTensor.close()
            scoresTensor.close()
            results.close()

            // 后处理：NMS 去重（不过滤类别，由调用方决定保留哪些）
            postprocessAllClasses(labels, boxes, scores, confThreshold)

        } catch (e: Exception) {
            LogCollector.e(TAG, "检测失败", e)
            throw e
        }
    }

    /**
     * 调试用：返回所有类别的检测结果（含 classId=0 的空气泡）。
     */
    fun detectBubblesAllClasses(bitmap: Bitmap, confThreshold: Float = 0.3f): List<DetectedBubble> = sessionLock.withLock {
        if (!isInitialized) {
            throw IllegalStateException("ComicBubbleDetector 未初始化")
        }

        try {
            val origW = bitmap.width
            val origH = bitmap.height
            val inputTensor = preprocessImage(bitmap)
            val sizeTensor = OnnxTensor.createTensor(
                ortEnv!!,
                java.nio.LongBuffer.wrap(longArrayOf(origW.toLong(), origH.toLong())),
                longArrayOf(1, 2)
            )
            val results = session!!.run(mapOf("images" to inputTensor, "orig_target_sizes" to sizeTensor))
            inputTensor.close()
            sizeTensor.close()

            val labelsTensor = results.get("labels").get() as OnnxTensor
            val boxesTensor = results.get("boxes").get() as OnnxTensor
            val scoresTensor = results.get("scores").get() as OnnxTensor
            val labels = OnnxUtils.extractLongArray(labelsTensor)
            val boxes = OnnxUtils.extractFloatArray(boxesTensor)
            val scores = OnnxUtils.extractFloatArray(scoresTensor)
            labelsTensor.close()
            boxesTensor.close()
            scoresTensor.close()
            results.close()

            // 调试模式：不过滤 classId=0，全部返回
            val candidates = mutableListOf<Triple<Int, RectF, Float>>()
            for (i in 0 until labels.size) {
                if (scores[i] < confThreshold) continue
                val x1 = boxes[i * 4]; val y1 = boxes[i * 4 + 1]
                val x2 = boxes[i * 4 + 2]; val y2 = boxes[i * 4 + 3]
                candidates.add(Triple(i, RectF(x1, y1, x2, y2), scores[i]))
            }
            val keepIndices = nms(candidates.map { it.second }, candidates.map { it.third }, NMS_IOU_THRESHOLD)
            keepIndices.map { idx ->
                val (origIdx, box, score) = candidates[idx]
                DetectedBubble(
                    rect = Rect(box.left.toInt().coerceAtLeast(0), box.top.toInt().coerceAtLeast(0), box.right.toInt(), box.bottom.toInt()),
                    classId = labels[origIdx].toInt(),
                    confidence = score
                )
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "调试检测失败", e)
            throw e
        }
    }

    /**
     * 后处理：过滤类别和置信度，NMS 去重。
     */
    private fun postprocess(
        labels: LongArray,
        boxes: FloatArray,
        scores: FloatArray,
        confThreshold: Float
    ): List<DetectedBubble> {
        val numQueries = labels.size // 300

        // 第一步：收集有效检测
        val candidates = mutableListOf<Triple<Int, RectF, Float>>() // (index, box, score)
        for (i in 0 until numQueries) {
            val classId = labels[i].toInt()
            val score = scores[i]

            // 跳过低置信度
            if (score < confThreshold) continue
            // 跳过 bubble 类别（无文字气泡）
            if (classId == 0) continue

            // boxes 格式: xyxy (左上x, 左上y, 右下x, 右下y)，已映射到原图坐标
            val x1 = boxes[i * 4]
            val y1 = boxes[i * 4 + 1]
            val x2 = boxes[i * 4 + 2]
            val y2 = boxes[i * 4 + 3]

            candidates.add(Triple(i, RectF(x1, y1, x2, y2), score))
        }

        if (candidates.isEmpty()) {
            LogCollector.d(TAG, "后处理: 无有效检测结果")
            return emptyList()
        }

        LogCollector.d(TAG, "后处理: 过滤后 ${candidates.size} 个候选 (置信度>=$confThreshold, 排除bubble)")

        // 第二步：NMS (按类别独立做)
        val keepIndices = nms(candidates.map { it.second }, candidates.map { it.third }, NMS_IOU_THRESHOLD)

        val result = keepIndices.map { idx ->
            val (origIdx, box, score) = candidates[idx]
            val classId = labels[origIdx].toInt()
            DetectedBubble(
                rect = Rect(
                    box.left.toInt().coerceAtLeast(0),
                    box.top.toInt().coerceAtLeast(0),
                    box.right.toInt(),
                    box.bottom.toInt()
                ),
                classId = classId,
                confidence = score
            )
        }

        LogCollector.d(TAG, "后处理完成: ${result.size} 个检测结果")
        for ((idx, b) in result.withIndex()) {
            LogCollector.d(TAG, "  [$idx] rect=[${b.rect.left},${b.rect.top},${b.rect.right},${b.rect.bottom}] class=${b.classId} conf=${String.format("%.3f", b.confidence)}")
        }

        return result
    }

    /**
     * 后处理：不过滤类别，NMS 去重，返回所有检测结果。
     * 由调用方决定保留哪些类别。
     */
    private fun postprocessAllClasses(
        labels: LongArray,
        boxes: FloatArray,
        scores: FloatArray,
        confThreshold: Float
    ): List<DetectedBubble> {
        val numQueries = labels.size

        val candidates = mutableListOf<Triple<Int, RectF, Float>>() // (index, box, score)
        for (i in 0 until numQueries) {
            if (scores[i] < confThreshold) continue
            val x1 = boxes[i * 4]; val y1 = boxes[i * 4 + 1]
            val x2 = boxes[i * 4 + 2]; val y2 = boxes[i * 4 + 3]
            candidates.add(Triple(i, RectF(x1, y1, x2, y2), scores[i]))
        }

        if (candidates.isEmpty()) {
            LogCollector.d(TAG, "后处理: 无有效检测结果")
            return emptyList()
        }

        LogCollector.d(TAG, "后处理(全部类别): 过滤后 ${candidates.size} 个候选 (置信度>=$confThreshold)")

        val keepIndices = nms(candidates.map { it.second }, candidates.map { it.third }, NMS_IOU_THRESHOLD)
        val result = keepIndices.map { idx ->
            val (origIdx, box, score) = candidates[idx]
            DetectedBubble(
                rect = Rect(
                    box.left.toInt().coerceAtLeast(0),
                    box.top.toInt().coerceAtLeast(0),
                    box.right.toInt(),
                    box.bottom.toInt()
                ),
                classId = labels[origIdx].toInt(),
                confidence = score
            )
        }

        LogCollector.d(TAG, "后处理完成: ${result.size} 个检测结果")
        for ((idx, b) in result.withIndex()) {
            LogCollector.d(TAG, "  [$idx] rect=[${b.rect.left},${b.rect.top},${b.rect.right},${b.rect.bottom}] class=${b.classId} conf=${String.format("%.3f", b.confidence)}")
        }

        return result
    }

    /**
     * Non-Maximum Suppression。
     */
    private fun nms(boxes: List<RectF>, scores: List<Float>, iouThreshold: Float): List<Int> {
        val sortedIndices = scores.indices.sortedByDescending { scores[it] }.toMutableList()
        val keep = mutableListOf<Int>()

        while (sortedIndices.isNotEmpty()) {
            val best = sortedIndices.removeAt(0)
            keep.add(best)

            val iterator = sortedIndices.iterator()
            while (iterator.hasNext()) {
                val other = iterator.next()
                if (iou(boxes[best], boxes[other]) > iouThreshold) {
                    iterator.remove()
                }
            }
        }

        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        val interArea = maxOf(0f, interRight - interLeft) * maxOf(0f, interBottom - interTop)
        val unionArea = a.width() * a.height() + b.width() * b.height() - interArea

        return if (unionArea > 0) interArea / unionArea else 0f
    }

    /**
     * 预处理：resize 640×640 → rescale /255 → ImageNet normalize → CHW 格式。
     *
     * 对齐 RTDetrImageProcessor:
     * - resize 到 640×640（双线性插值）
     * - rescale factor = 1/255
     * - normalize with ImageNet mean/std
     */
    private fun preprocessImage(bitmap: Bitmap): OnnxTensor {
        // Resize 到 640×640
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)

        // 提取像素
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (resized !== bitmap) resized.recycle()

        // CHW 格式，RGB 通道
        val floatBuffer = FloatBuffer.allocate(3 * INPUT_SIZE * INPUT_SIZE)
        for (c in 0 until 3) {
            for (pixel in pixels) {
                val rawValue = when (c) {
                    0 -> (pixel shr 16 and 0xFF) // R
                    1 -> (pixel shr 8 and 0xFF)  // G
                    2 -> (pixel and 0xFF)         // B
                    else -> 0
                }
                // rescale + normalize
                val value = (rawValue * RESCALE_FACTOR - IMAGE_MEAN[c]) / IMAGE_STD[c]
                floatBuffer.put(value)
            }
        }
        floatBuffer.rewind()

        return OnnxTensor.createTensor(
            ortEnv!!, floatBuffer,
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )
    }

    /**
     * 释放资源。
     */
    fun release() {
        sessionLock.lock()
        try {
            session?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            LogCollector.e(TAG, "释放资源失败", e)
        } finally {
            session = null
            ortEnv = null
            isInitialized = false
            sessionLock.unlock()
        }
    }
}
