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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import com.moe.starflow.R
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.types.DebugRecResult
import com.moe.starflow.manga.types.DetBox
import com.moe.starflow.manga.types.OcrResult
import com.moe.starflow.manga.types.RecResult
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.operation.buffer.BufferOp
import java.nio.FloatBuffer
import java.util.EnumMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// det 后处理共享类型（提取到 PPOcrDetGeometry，保留内部引用简洁）

// ============================================================================
// PP-OCRv5 OCR Engine
// ============================================================================

/**
 * PP-OCRv5 完整 OCR 引擎（det + rec）。
 *
 * 对齐 RapidOCR Python 实现：det → crop → rec → CTCLabelDecode。
 * 所有模型输入名均为 `x`。方向分类（cls）已删除。
 */
object PPOcrV5Engine {

    private const val TAG = "PPOcrV5Engine"

    // det 后处理共享类型（4c-3 提取到 PPOcrDetGeometry）

    // -----------------------------------------------------------------------
    // Det 常量 (ch_ppocr_det/utils.py DetPreProcess + DBPostProcess)
    // -----------------------------------------------------------------------
    // 用户可调参数的"默认值"已迁到 PPOcrDefault（manga/PPOcrParams.kt）作为单一来源。
    // 这里保留 *_DEFAULT 常量仅用于字段初始化（冷启动 fallback），refreshParams() 会从 prefs 覆盖。
    private val DET_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
    private val DET_STD = floatArrayOf(0.5f, 0.5f, 0.5f)
    private const val DET_THRESH = 0.1f
    private const val DET_BOX_THRESH_DEFAULT = 0.3f       // must match PPOcrDefault.DET_BOX_THRESH_V5
    private const val DET_UNCLIP_RATIO_DEFAULT = 1.6     // must match PPOcrDefault.DET_UNCLIP_RATIO
    private const val DET_MAX_CANDIDATES = 100
    private const val DET_MIN_SIZE = 3

    // -----------------------------------------------------------------------
    // Cls 常量 (ch_ppocr_cls/main.py)
    // -----------------------------------------------------------------------
    // cls removed — direction classification is no longer used

    // -----------------------------------------------------------------------
    // Rec 常量 (ch_ppocr_rec/main.py)
    // -----------------------------------------------------------------------
    private const val REC_IMG_HEIGHT = 48
    private const val REC_IMG_CHANNELS = 3
    private const val REC_BATCH_NUM = 16

    // -----------------------------------------------------------------------
    // 全局常量
    // -----------------------------------------------------------------------
    private const val TEXT_SCORE_THRESH_DEFAULT = 0.5f   // must match PPOcrDefault.TEXT_SCORE_THRESH

    // -----------------------------------------------------------------------
    // 用户可调参数（从 SharedPreferences 动态读取）
    // -----------------------------------------------------------------------
    @Volatile private var detBoxThresh = DET_BOX_THRESH_DEFAULT
    @Volatile private var detUnclipRatio = DET_UNCLIP_RATIO_DEFAULT
    @Volatile private var textScoreThresh = TEXT_SCORE_THRESH_DEFAULT
    @Volatile private var largeBoxEnabled = false
    @Volatile private var largeBoxRatio = 0.6f  // 宽/高/面积占图片比例阈值
    @Volatile private var limitSideLen = 1200        // Det.limit_side_len（must match PPOcrDefault.LIMIT_SIDE_LEN）
    @Volatile private var limitType = "max"          // Det.limit_type: "min" | "max"

    /**
     * 从 SharedPreferences 刷新可调参数。
     * 在每次 OCR 前调用，确保用户调整的滑块立即生效。
     */
    fun refreshParams(context: Context) {
        val prefs = CustomPreference.getInstance(context)
        // 默认值单一来源见 PPOcrDefault；prefs key 见 PPOcrKey。改默认值时只动 PPOcrParams.kt。
        detBoxThresh = PPOcrPrefs.boxThreshV5(prefs)
        detUnclipRatio = PPOcrPrefs.unclipRatio(prefs).toDouble()
        textScoreThresh = PPOcrPrefs.textScoreV5(prefs)
        largeBoxEnabled = PPOcrPrefs.largeBoxEnabled(prefs)
        largeBoxRatio = PPOcrPrefs.largeBoxRatio(prefs)
        limitSideLen = PPOcrPrefs.limitSideLen(prefs)
        limitType = PPOcrPrefs.limitType(prefs)
        // 竖排扫描/识别流向（Manga_Text_Direction）：影响 det 候选顺序 → 识别顺序 → 源文拼接顺序
        PPOcrDetGeometry.verticalScanFlowIsLr =
            VerticalFlow.fromPref(prefs.getString("Manga_Text_Direction", "0")) == VerticalFlow.LR
    }

    // -----------------------------------------------------------------------
    // Rec 语言枚举
    // -----------------------------------------------------------------------
    /**
     * Rec 模型枚举（全部 PP-OCRv5 mobile）。
     * ZH: 中英日混合（~16MB，内置）
     * JA: 日文专用（复用 ZH 模型，日文由 ZH 模型覆盖）
     * EN: 英文专用（~7.5MB，可选下载）
     * KO: 韩文专用（~13MB，可选下载）
     * RU: 俄文/西里尔文字（~7.7MB，可选下载）
     */
    enum class RecLang(val code: String) {
        ZH("zh"),
        JA("zh"),   // 日文走 rec_zh（PP-OCRv5 中英日混合）
        EN("en"),
        KO("ko"),
        RU("ru");

        fun recModelFile(): String = "ppocrv5/rec_$code.onnx"
        fun dictFile(): String = "ppocrv5/rec_${code}_dict.txt"
    }

    // -----------------------------------------------------------------------
    // ONNX 会话
    // -----------------------------------------------------------------------
    @Volatile
    private var ortEnv: OrtEnvironment? = null
    @Volatile
    private var detSession: OrtSession? = null
    private val recSessions = EnumMap<RecLang, OrtSession>(RecLang::class.java)

    // 字典：blank(0) + dict_chars + space(end)
    private var dictionary: Map<RecLang, List<String>> = emptyMap()

    @Volatile
    var isInitialized = false
        private set

    // rec 会话加载锁
    private val recLocks = EnumMap<RecLang, Any>(RecLang::class.java)

    // 初始化/释放锁
    private val lock = Any()

    // ========================================================================
    // 初始化 / 释放
    // ========================================================================

    /**
     * 初始化引擎：加载 det ONNX 会话 + 字典。
     * rec 会话按需懒加载（首次调用 [getRecSession] 时）。
     */
    fun initialize(context: Context) {
        synchronized(lock) {
            if (isInitialized) return

            try {
                LogCollector.d(TAG, "开始初始化 PP-OCRv5 引擎...")

                ortEnv = OrtEnvironment.getEnvironment()

                val sessionOpts = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setMemoryPatternOptimization(true)
                    setCPUArenaAllocator(true)
                    setIntraOpNumThreads(4)
                }

                // det（从 filesDir 加载）
                LogCollector.d(TAG, "加载 det 模型...")
                val detFile = java.io.File(PPOcrModelFiles.getModelDir(context), "det_v5.onnx")
                if (!detFile.exists() || detFile.length() == 0L) {
                    LogCollector.e(TAG, "v5 det 模型未下载，请先在模型管理中下载")
                    throw IllegalStateException("PP-OCRv5 检测模型未下载，请在模型管理中下载")
                }
                val detBytes = detFile.readBytes()
                detSession = ortEnv!!.createSession(detBytes, sessionOpts)
                LogCollector.d(TAG, "det 模型加载完成 (${detBytes.size / 1024}KB)")

                // cls removed — direction classification is no longer used

                // 初始化 rec 锁
                for (lang in RecLang.entries) {
                    recLocks[lang] = Any()
                }

                // 字典
                loadDictionary(context)

                isInitialized = true
                LogCollector.d(TAG, "PP-OCRv5 引擎初始化完成")

            } catch (e: Exception) {
                LogCollector.e(TAG, "PP-OCRv5 初始化失败", e)
                release()
                throw e
            }
        }
    }

    /**
     * 懒加载 rec 会话（线程安全）
     * 所有语言从 filesDir 加载（需用户下载）。
     */
    private fun getRecSession(context: Context, lang: RecLang): OrtSession? {
        recSessions[lang]?.let { return it }

        synchronized(recLocks[lang]!!) {
            recSessions[lang]?.let { return it }

            return try {
                LogCollector.d(TAG, "懒加载 rec 模型: ${lang.code}...")
                val opts = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setMemoryPatternOptimization(true)
                    setCPUArenaAllocator(true)
                    setIntraOpNumThreads(4)
                }

                val bytes = if (lang == RecLang.ZH || lang == RecLang.JA) {
                    // 从 filesDir 加载（原内置，现需下载）
                    val recFile = java.io.File(PPOcrModelFiles.getModelDir(context), "rec_zh.onnx")
                    if (!recFile.exists() || recFile.length() == 0L) {
                        LogCollector.w(TAG, "rec_zh 模型未下载")
                        return null
                    }
                    LogCollector.d(TAG, "从 filesDir 加载 rec 模型: ${lang.code}")
                    recFile.readBytes()
                } else {
                    // 可选模型，从 filesDir 加载
                    val modelFile = PPOcrModelFiles.getRecModelFile(context, lang.code)
                    if (modelFile == null) {
                        LogCollector.w(TAG, "rec 模型 ${lang.code} 未下载")
                        return null
                    }
                    LogCollector.d(TAG, "从 filesDir 加载 rec 模型: ${lang.code}")
                    modelFile.readBytes()
                }
                val session = ortEnv!!.createSession(bytes, opts)
                recSessions[lang] = session
                LogCollector.d(TAG, "rec 模型 ${lang.code} 加载完成")
                session
            } catch (e: Exception) {
                LogCollector.e(TAG, "rec 模型 ${lang.code} 加载失败", e)
                throw e
            }
        }
    }

    /**
     * 加载字典文件：blank(0) + dict_chars + space(end)
     * 仅从 filesDir 读取，不 fallback assets。
     */
    private fun loadDictionary(context: Context) {
        val dicts = mutableMapOf<RecLang, List<String>>()
        val modelDir = PPOcrModelFiles.getModelDir(context)

        for (lang in RecLang.entries) {
            try {
                val dictFileName = if (lang == RecLang.JA) "rec_zh_dict.txt"
                    else "rec_${lang.code}_dict.txt"
                val dictFile = java.io.File(modelDir, dictFileName)
                if (!dictFile.exists() || dictFile.length() == 0L) {
                    LogCollector.w(TAG, "字典 ${lang.code} 未下载，跳过 ($dictFileName)")
                    continue
                }
                LogCollector.d(TAG, "从 filesDir 加载字典: ${lang.code} ($dictFileName)")
                val lines = dictFile.bufferedReader().readLines().filter { it.isNotEmpty() }
                val dict = mutableListOf<String>()
                dict.add("blank") // index 0
                dict.addAll(lines)
                dict.add(" ")     // end
                dicts[lang] = dict
                LogCollector.d(TAG, "字典 ${lang.code}: ${dict.size} 条 (含 blank+space)")
            } catch (e: Exception) {
                LogCollector.e(TAG, "字典 ${lang.code} 加载失败", e)
            }
        }
        dictionary = dicts
    }

    /**
     * 释放所有资源
     */
    fun release() {
        synchronized(lock) {
            try {
                detSession?.close()
                recSessions.values.forEach { try { it.close() } catch (_: Exception) {} }
                ortEnv?.close()
            } catch (e: Exception) {
                LogCollector.e(TAG, "释放资源失败", e)
            } finally {
                detSession = null
                recSessions.clear()
                ortEnv = null
                dictionary = emptyMap()
                isInitialized = false
            }
        }
    }

    /**
     * 将 source language 字符串映射到 RecLang。
     * 日文走 JA（= rec_zh，PP-OCRv5 中英日混合模型）。
     */
    fun getRecLang(language: String): RecLang? = when (language.lowercase()) {
        "zh", "zh-tw", "chinese", "chinese (taiwan)", "中文", "繁体中文" -> RecLang.ZH
        "ja", "japanese", "日本語" -> RecLang.JA
        "en", "english", "英语" -> RecLang.EN
        "ko", "korean", "한국어" -> RecLang.KO
        "ru", "russian", "俄文" -> RecLang.RU
        else -> null
    }

    /**
     * 检查指定语言的 rec 模型是否可用（已下载到 filesDir）。
     * 所有语言（包括 ZH/JA）均需下载。
     */
    fun isRecModelAvailable(context: Context, lang: RecLang): Boolean {
        return if (lang == RecLang.ZH || lang == RecLang.JA) {
            PPOcrModelFiles.isV5RecZhDownloaded(context)
        } else {
            PPOcrModelFiles.isRecModelDownloaded(context, lang.code)
        }
    }

    /**
     * 解析实际使用的 rec 语言（带 fallback）。
     * - ZH/JA：始终可用（内置）
     * - EN：已下载用 EN，否则 fallback 到 ZH（ch 模型也支持英文识别）
     * - KO：已下载用 KO，否则返回 null（需提示用户下载）
     * - RU：已下载用 RU，否则返回 null（需提示用户下载）
     *
     * @return Pair<实际RecLang, 提示消息?> 提示消息不为 null 时应展示给用户
     */
    fun resolveRecLang(context: Context, language: String): Pair<RecLang?, String?> {
        val lang = getRecLang(language) ?: return Pair(null, null)

        val result = when (lang) {
            RecLang.ZH, RecLang.JA -> Pair(lang, null)
            RecLang.EN -> {
                if (isRecModelAvailable(context, RecLang.EN)) {
                    Pair(RecLang.EN, null)
                } else {
                    Pair(RecLang.ZH, null) // fallback: ch 模型支持英文
                }
            }
            RecLang.KO -> {
                if (isRecModelAvailable(context, RecLang.KO)) {
                    Pair(RecLang.KO, null)
                } else {
                    Pair(null, context.getString(R.string.ko_need_download_model))
                }
            }
            RecLang.RU -> {
                if (isRecModelAvailable(context, RecLang.RU)) {
                    Pair(RecLang.RU, null)
                } else {
                    Pair(null, context.getString(R.string.ru_need_download_model))
                }
            }
        }

        val resolved = result.first
        if (resolved != null) {
            val fallback = if (lang != resolved) " (fallback: ${lang.code}→${resolved.code})" else ""
            LogCollector.d(TAG, "识别模型: rec_${resolved.code}${fallback}, 请求语言: $language")
        } else {
            LogCollector.d(TAG, "识别模型: 无可用模型, 请求语言: $language, 提示: ${result.second}")
        }
        return result
    }

    // ========================================================================
    // DetPreProcess (ch_ppocr_det/utils.py)
    // ========================================================================

    /**
     * 检测预处理。
     * 返回 (FloatArray CHW, resizedH, resizedW)。
     */
    private fun preprocessDet(bitmap: Bitmap): Triple<FloatArray, Int, Int> {
        val srcH = bitmap.height
        val srcW = bitmap.width

        // 1. 计算缩放
        var resizeH = srcH
        var resizeW = srcW

        val ratio: Float
        if (limitType == "min") {
            val minSide = min(resizeH, resizeW).toFloat()
            if (minSide < limitSideLen) {
                ratio = limitSideLen / minSide
                resizeH = (resizeH * ratio).roundToInt()
                resizeW = (resizeW * ratio).roundToInt()
            } else {
                val maxSide = max(resizeH, resizeW).toFloat()
                if (maxSide > limitSideLen) {
                    ratio = limitSideLen / maxSide
                    resizeH = (resizeH * ratio).roundToInt()
                    resizeW = (resizeW * ratio).roundToInt()
                }
            }
        } else {
            val maxSide = max(resizeH, resizeW).toFloat()
            if (maxSide > limitSideLen) {
                ratio = limitSideLen / maxSide
                resizeH = (resizeH * ratio).roundToInt()
                resizeW = (resizeW * ratio).roundToInt()
            }
        }

        // 2. 对齐 32 的倍数
        // ⚠️ 必须走 PPOcrDetGeometry.alignTo32（**四舍五入**，对齐官方 RapidOCR）。
        // 曾写成 `(resizeH / 32) * 32`（整数截断）→ 两轴各自最多白扔 31px →
        // 小尺寸裁剪被**压扁**，竖排小字认不出、det 框数暴涨。见 alignTo32 的文档。
        resizeH = PPOcrDetGeometry.alignTo32(resizeH)
        resizeW = PPOcrDetGeometry.alignTo32(resizeW)

        // 2a. 安全保护：防止极薄横屏框选 + 用户误调 limit_side_len 到很大值时爆 OOM
        val absoluteMax = 4000
        if (resizeW > absoluteMax || resizeH > absoluteMax) {
            val capRatio = absoluteMax.toFloat() / max(resizeW, resizeH)
            resizeH = (resizeH * capRatio).roundToInt()
            resizeW = (resizeW * capRatio).roundToInt()
            resizeH = PPOcrDetGeometry.alignTo32(resizeH)
            resizeW = PPOcrDetGeometry.alignTo32(resizeW)
            LogCollector.w(TAG, "!!! det 尺寸超限已截断到 ${resizeW}x${resizeH}（原图 ${bitmap.width}x${bitmap.height}）")
        }

        // 3. Resize
        val resized = Bitmap.createScaledBitmap(bitmap, resizeW, resizeH, true)

        // 4. 提取像素
        val pixels = IntArray(resizeH * resizeW)
        resized.getPixels(pixels, 0, resizeW, 0, 0, resizeW, resizeH)
        if (resized !== bitmap) resized.recycle()

        // 5. HWC→CHW, normalize = (pixel/255 - mean) / std
        val floatArr = FloatArray(3 * resizeH * resizeW)
        for (c in 0 until 3) {
            for (i in 0 until (resizeH * resizeW)) {
                val pixel = pixels[i]
                val v = when (c) {
                    0 -> (pixel shr 16 and 0xFF) / 255.0f // R
                    1 -> (pixel shr 8 and 0xFF) / 255.0f  // G
                    2 -> (pixel and 0xFF) / 255.0f         // B
                    else -> 0f
                }
                floatArr[c * resizeH * resizeW + i] = (v - DET_MEAN[c]) / DET_STD[c]
            }
        }

        return Triple(floatArr, resizeH, resizeW)
    }

    // ========================================================================
    // DBPostProcess (ch_ppocr_det/utils.py)
    // ========================================================================

    /**
     * 检测后处理：pred → bounding boxes（原图坐标）。
     */
    private fun postprocessDet(
        pred: FloatArray,
        predH: Int,
        predW: Int,
        srcH: Int,
        srcW: Int
    ): PPOcrDetGeometry.BoxScoreResult {
        // 1. 阈值化
        val cBitmap = Bitmap.createBitmap(predW, predH, Bitmap.Config.ARGB_8888)
        for (i in 0 until predH * predW) {
            val v = if (pred[i] > DET_THRESH) Color.WHITE else Color.BLACK
            cBitmap.setPixel(i % predW, i / predW, v)
        }

        // 2. BFS 连通域
        val contours = PPOcrDetGeometry.findContours(cBitmap, predW, predH, DET_MIN_SIZE)
        cBitmap.recycle()

        if (contours.isEmpty()) return PPOcrDetGeometry.BoxScoreResult(emptyList(), emptyList())

        // 3. 限制候选数量
        val limitedContours = if (contours.size > DET_MAX_CANDIDATES) {
            contours.sortedByDescending { it.size }.take(DET_MAX_CANDIDATES)
        } else {
            contours
        }

        // 4. 处理每个连通域
        val boxes = mutableListOf<FloatArray>()
        val scores = mutableListOf<Float>()

        for (contour in limitedContours) {
            // getMiniBoxes
            val (boxPoints, _, _, w, h) = PPOcrDetGeometry.getMiniBoxes(contour)
            if (boxPoints == null) continue
            if (w < DET_MIN_SIZE || h < DET_MIN_SIZE) continue

            // 概率评分
            val boxCoords = boxPoints.map { Coordinate(it.x.toDouble(), it.y.toDouble()) }
            val score = GeometryUtils.boxScoreFast(pred, predW, predH, boxCoords)

            // box_thresh 过滤：低于阈值的候选框直接跳过
            if (score < detBoxThresh) continue

            // unclip
            val unclipBoxes = PPOcrDetGeometry.unclip(boxPoints, detUnclipRatio)
            for (ub in unclipBoxes) {
                val (expPts, _, _, ew, eh) = PPOcrDetGeometry.getMiniBoxes(ub.map { Point(it.x.roundToInt(), it.y.roundToInt()) })
                if (expPts == null) continue
                if (ew < DET_MIN_SIZE + 2 || eh < DET_MIN_SIZE + 2) continue

                val pts = expPts.map { PointF(it.x.toFloat(), it.y.toFloat()) }.toTypedArray()
                val mapped = mapBoxToOriginal(pts, predH.toFloat(), predW.toFloat(), srcH, srcW)
                boxes.add(mapped)
                scores.add(score)
            }
        }

        // 5. 过滤 + 按阅读顺序重排（竖排列序随 Manga_Text_Direction；横排恒左→右）
        val filtered = PPOcrDetGeometry.filterDetRes(
            boxes, scores, srcH, srcW, DET_MIN_SIZE, largeBoxEnabled, largeBoxRatio
        )
        return PPOcrDetGeometry.sortDetCandidates(
            filtered.boxes, filtered.scores, PPOcrDetGeometry.verticalScanFlowIsLr
        )
    }


    /**
     * 调试用检测结果：包含保留和被丢弃的选区
     */
    data class DebugDetResult(
        val keptBoxes: List<FloatArray>,
        val keptScores: List<Float>,
        val discardedBoxes: List<FloatArray>,
        val discardedScores: List<Float>,
        val discardedReasons: List<String>
    )









    // cls removed — direction classification code deleted; use recognizeBatch directly

    // ========================================================================
    // Rec (ch_ppocr_rec/main.py + CTCLabelDecode)
    // ========================================================================

    /**
     * 批量识别（按 wh_ratio 分组，batch 推理）。
     */
    fun recognizeBatch(context: Context, imgList: List<Bitmap>, lang: RecLang): List<RecResult> = synchronized(lock) {
        if (imgList.isEmpty()) return emptyList()

        val dict = dictionary[lang] ?: return imgList.map { RecResult("", 0f) }
        val session = getRecSession(context, lang) ?: return imgList.map { RecResult("", 0f) }

        val t0 = System.currentTimeMillis()
        val allResults = mutableListOf<RecResult>()

        var i = 0
        while (i < imgList.size) {
            val batchEnd = min(i + REC_BATCH_NUM, imgList.size)
            val batch = imgList.subList(i, batchEnd)

            // 找 batch 内最大 wh_ratio
            var maxWhRatio = 0f
            for (img in batch) {
                val r = img.width.toFloat() / img.height
                if (r > maxWhRatio) maxWhRatio = r
            }

            // 预处理
            val preprocessed = batch.map { recResizeNormImg(it, maxWhRatio) }
            val batchSize = preprocessed.size
            val recImgW = (REC_IMG_HEIGHT * maxWhRatio).roundToInt().coerceAtLeast(1)
            val channelSize = REC_IMG_HEIGHT * recImgW
            val totalSize = batchSize * REC_IMG_CHANNELS * channelSize

            val buffer = FloatBuffer.allocate(totalSize)
            for (arr in preprocessed) {
                buffer.put(arr)
            }
            buffer.rewind()

            val inputTensor = OnnxTensor.createTensor(
                ortEnv!!, buffer,
                longArrayOf(batchSize.toLong(), REC_IMG_CHANNELS.toLong(), REC_IMG_HEIGHT.toLong(), recImgW.toLong())
            )

            // 推理
            val results = session.run(mapOf("x" to inputTensor))
            inputTensor.close()

            var outputData: FloatArray
            var seqLen: Int
            var numClasses: Int
            try {
                var batchOutputTensor: OnnxTensor? = null
                for (name in session.outputNames) {
                    val value = results.get(name)
                    if (value.isPresent && value.get() is OnnxTensor) {
                        batchOutputTensor = value.get() as OnnxTensor
                        break
                    }
                }
                outputData = batchOutputTensor!!.floatBuffer.array()
                val outputShape = batchOutputTensor.info.shape // [batch, seq_len, num_classes]
                batchOutputTensor.close()
                seqLen = outputShape[1].toInt()
                numClasses = outputShape[2].toInt()
            } finally {
                results.close()
            }

            // CTCLabelDecode per sample
            for (j in 0 until batchSize) {
                val start = j * seqLen * numClasses
                val preds = outputData.sliceArray(start until start + seqLen * numClasses)
                val (text, score) = ctcLabelDecode(preds, seqLen, numClasses, dict)
                allResults.add(RecResult(text, score))
            }

            i = batchEnd
        }

        LogCollector.d(TAG, "recognizeBatch: ${imgList.size} 张, lang=${lang.code}, 耗时 ${System.currentTimeMillis() - t0}ms")
        allResults
    }

    /**
     * Rec 预处理：resize + pad + normalize [-1, 1]
     */
    private fun recResizeNormImg(bitmap: Bitmap, maxWhRatio: Float): FloatArray {
        val imgC = REC_IMG_CHANNELS // 3
        val imgH = REC_IMG_HEIGHT   // 48
        val imgW = (imgH * maxWhRatio).roundToInt().coerceAtLeast(1)

        val ratio = bitmap.width.toFloat() / bitmap.height
        var resizeW = ceil(imgH * ratio).toInt()
        if (resizeW > imgW) resizeW = imgW

        val resized = Bitmap.createScaledBitmap(bitmap, resizeW, imgH, true)

        // CHW, pad to (3, 48, imgW), normalize [-1, 1]
        val floatArr = FloatArray(imgC * imgH * imgW)
        val pixels = IntArray(resizeW * imgH)
        resized.getPixels(pixels, 0, resizeW, 0, 0, resizeW, imgH)
        if (resized !== bitmap) resized.recycle()

        for (c in 0 until 3) {
            val cOffset = c * imgH * imgW
            for (y in 0 until imgH) {
                for (x in 0 until resizeW) {
                    val pixel = pixels[y * resizeW + x]
                    val v = when (c) {
                        0 -> (pixel shr 16 and 0xFF) / 255.0f
                        1 -> (pixel shr 8 and 0xFF) / 255.0f
                        2 -> (pixel and 0xFF) / 255.0f
                        else -> 0f
                    }
                    floatArr[cOffset + y * imgW + x] = (v - 0.5f) / 0.5f
                }
            }
        }

        return floatArr
    }

    /**
     * CTCLabelDecode：argmax → remove_duplicate → filter blank(0) → join
     *
     * @param preds 扁平化预测 [seq_len * num_classes]
     * @param seqLen 序列长度
     * @param numClasses 类别数（= 字典大小）
     * @param dict 字典 [blank, char1, char2, ..., space]
     */
    private fun ctcLabelDecode(
        preds: FloatArray,
        seqLen: Int,
        numClasses: Int,
        dict: List<String>
    ): Pair<String, Float> {
        // 1. argmax
        val charIndices = IntArray(seqLen)
        val confidences = FloatArray(seqLen)
        for (t in 0 until seqLen) {
            val offset = t * numClasses
            var bestIdx = 0
            var bestProb = preds[offset]
            for (c in 1 until numClasses) {
                if (preds[offset + c] > bestProb) {
                    bestProb = preds[offset + c]
                    bestIdx = c
                }
            }
            charIndices[t] = bestIdx
            confidences[t] = bestProb
        }

        // 2. remove_duplicate + filter blank(0)
        val text = StringBuilder()
        var totalScore = 0f
        var count = 0
        var prevIdx = -1

        for (t in 0 until seqLen) {
            val idx = charIndices[t]
            if (idx > 0 && idx != prevIdx) {
                if (idx < dict.size) {
                    text.append(dict[idx])
                    totalScore += confidences[t]
                    count++
                }
            }
            prevIdx = idx
        }

        // 3. 过滤纯空格
        val resultText = text.toString()
        if (resultText.isBlank()) return Pair("", 0f)

        val avgScore = if (count > 0) totalScore / count else 0f
        return Pair(resultText, avgScore)
    }

    // ========================================================================
    // 主 pipeline
    // ========================================================================

    /**
     * 运行完整 OCR 流水线。
     *
     * @param bitmap 输入图片
     * @param recLang 识别语言
     * @param useDet 是否使用检测（false 则全图识别）
     * @return OCR 结果
     */
    fun runOCR(
        context: Context,
        bitmap: Bitmap,
        recLang: RecLang = RecLang.ZH,
        useDet: Boolean = true
    ): OcrResult {
        if (!isInitialized) throw IllegalStateException("PPOcrV5Engine 未初始化")
        if (bitmap.isRecycled) throw IllegalArgumentException("Bitmap 已回收")

        refreshParams(context)
        val t0 = System.currentTimeMillis()

        // 1. Det
        val (boxes, detTime) = if (useDet && detSession != null) {
            val dt = System.currentTimeMillis()
            val det = runDet(bitmap)
            Pair(det, System.currentTimeMillis() - dt)
        } else {
            // 无检测：全图作为一个 box
            Pair(listOf(floatArrayOf(
                0f, 0f,
                (bitmap.width - 1).toFloat(), 0f,
                (bitmap.width - 1).toFloat(), (bitmap.height - 1).toFloat(),
                0f, (bitmap.height - 1).toFloat()
            )), 0L)
        }

        // 2. Crop + Cls + Rec
        val clsTime: Long
        val recTime: Long
        val allTexts = mutableListOf<String>()
        val allScores = mutableListOf<Float>()

        // 2a. 裁剪
        val cropT0 = System.currentTimeMillis()
        val cropResults = mutableListOf<Triple<Bitmap, FloatArray, Int>>() // crop, box, originalIndex

        for ((idx, box) in boxes.withIndex()) {
            val pts = boxToQuadPoints(box)
            try {
                val crop = PPOcrDetGeometry.getRotateCropImage(bitmap, pts)
                cropResults.add(Triple(crop, box, idx))
            } catch (e: Exception) {
                LogCollector.w(TAG, "裁剪失败 box[$idx]: ${e.message}")
            }
        }
        val cropTime = System.currentTimeMillis() - cropT0

        // 2b. Cls removed — direction classification is no longer used
        clsTime = 0L

        // 2c. Rec
        val recT0 = System.currentTimeMillis()
        if (cropResults.isNotEmpty()) {
            val recResults = recognizeBatch(context, cropResults.map { it.first }, recLang)
            for (i in cropResults.indices) {
                if (i < recResults.size) {
                    allTexts.add(recResults[i].text)
                    allScores.add(recResults[i].score)
                } else {
                    allTexts.add("")
                    allScores.add(0f)
                }
            }
        }
        recTime = System.currentTimeMillis() - recT0

        // 3. 过滤低置信度
        val filtered = filterByTextScore(boxes, allTexts, allScores)

        // 调试：记录识别阶段丢弃的选区
        val recDebug = DebugRecResult(
            keptBoxes = filtered.boxes,
            keptTexts = filtered.texts,
            keptScores = filtered.scores,
            discardedBoxes = filtered.discardedBoxes,
            discardedTexts = filtered.discardedTexts,
            discardedScores = filtered.discardedScores
        )

        // 4. 释放裁剪图片
        for ((crop, _, _) in cropResults) {
            if (!crop.isRecycled) crop.recycle()
        }

        val totalTime = System.currentTimeMillis() - t0
        val elapseList = listOf(
            detTime / 1000f,
            cropTime / 1000f,
            clsTime / 1000f,
            recTime / 1000f,
            totalTime / 1000f
        )

        LogCollector.d(TAG, "runOCR: det=${boxes.size}(${detTime}ms), crop=${cropTime}ms, " +
                "cls=${clsTime}ms, rec=${allTexts.size}(${recTime}ms), total=${totalTime}ms")

        return OcrResult(
            boxes = filtered.boxes,
            texts = filtered.texts,
            scores = filtered.scores,
            elapseList = elapseList,
            recDebug = recDebug
        )
    }

    /**
     * 批量识别（直接委托 recognizeBatch，cls 已删除）。
     * 保留此方法以兼容调用方，避免修改 MangaFloatingService。
     */
    fun recognizeBatchWithCls(context: Context, imgList: List<Bitmap>, lang: RecLang): List<RecResult> {
        return recognizeBatch(context, imgList, lang)
    }

    /**
     * 将 OcrResult 转换为 TextLine 列表。
     * 统一 OcrResult → TextLineMerger 输入的转换逻辑。
     *
     * @param result OCR 结果
     * @param bitmapWidth 原图宽度（用于 Rect 裁剪）
     * @param bitmapHeight 原图高度（用于 Rect 裁剪）
     * @return 过滤后的 TextLine 列表（score < 0.5 或空白文本已剔除）
     */
    fun ocrResultToTextLines(
        result: OcrResult,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): List<PPOcrTextLine> {
        return result.texts.indices.mapNotNull { i ->
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

            // 顶部边向量与真实长度
            val topDx = tr.x - tl.x
            val topDy = tr.y - tl.y
            val topLen = sqrt((topDx * topDx + topDy * topDy).toDouble()).toFloat()
            // 左侧边向量与真实长度
            val leftDx = bl.x - tl.x
            val leftDy = bl.y - tl.y
            val leftLen = sqrt((leftDx * leftDx + leftDy * leftDy).toDouble()).toFloat()

            // 倾斜角（度）：顶部边与水平线夹角
            var angle = atan2(topDy, topDx) * 180f / Math.PI.toFloat()
            // ±3° 阈值：轻微倾斜归零（AA 文字角度一致便于合并判断）
            if (abs(angle) <= 3f) angle = 0f

            // 方向判断：用真实边长，左高 > 顶宽 * 1.5 才视为竖排
            val isVertical = leftLen > topLen * 1.5f

            // AABB 包围盒
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
                xMin.toInt().coerceAtLeast(0), yMin.toInt().coerceAtLeast(0),
                xMax.toInt().coerceAtMost(bitmapWidth - 1), yMax.toInt().coerceAtMost(bitmapHeight - 1)
            )
            // 用 QuadBox 真实边长计算 fontSize，不用 AABB（倾斜时 AABB 会放大）
            // 横排：文字高度 = leftLen；竖排：文字宽度 = topLen
            val fontSize = if (isVertical) topLen else leftLen
            val center = android.graphics.PointF(rect.exactCenterX(), rect.exactCenterY())
            PPOcrTextLine(
                rect = rect, text = text, fontSize = fontSize,
                isVertical = isVertical, score = result.scores[i],
                angle = angle, quadPoints = quadPoints, center = center
            )
        }
    }

    /**
     * 将 RecResult 列表 + 对应 Rect 列表转换为 TextLine 列表。
     * 用于增量渲染场景（先 det 裁剪，再 rec 识别）。
     *
     * @param recResults 识别结果列表
     * @param rects 对应的原图位置列表
     * @return 过滤后的 TextLine 列表
     */
    fun recResultsToTextLines(
        recResults: List<RecResult>,
        rects: List<Rect>,
        angles: List<Float> = emptyList(),
        centers: List<android.graphics.PointF> = emptyList()
    ): List<PPOcrTextLine> {
        val mergedInput = mutableListOf<PPOcrTextLine>()
        for (i in recResults.indices) {
            val r = recResults[i]
            if (r.text.isNotBlank() && r.score >= 0.5f && i < rects.size) {
                val rect = rects[i]
                // 增量路径：det 后裁剪 + rec，RecResult 不带原始 box
                // 只能从 angle 反推一个虚拟 quadPoints，让 TextLine 拿到方向信息
                val angle = angles.getOrElse(i) { 0f }
                val center = centers.getOrElse(i) { android.graphics.PointF(rect.exactCenterX(), rect.exactCenterY()) }
                val quadPoints = aabbToTiltedQuad(rect, angle, center)
                // 真实边长可从虚拟 quad 推出
                val rad = Math.toRadians(angle.toDouble())
                val w = rect.width().toFloat()
                val h = rect.height().toFloat()
                val realW = kotlin.math.abs(w * kotlin.math.cos(rad).toFloat() - h * kotlin.math.sin(rad).toFloat())
                val realH = kotlin.math.abs(w * kotlin.math.sin(rad).toFloat() + h * kotlin.math.cos(rad).toFloat())
                val isVertical = realH > realW * 1.5f
                val fontSize = if (isVertical) realW else realH
                mergedInput.add(PPOcrTextLine(
                    rect = rect, text = r.text, fontSize = fontSize,
                    isVertical = isVertical, score = r.score,
                    angle = angle, quadPoints = quadPoints, center = center
                ))
            }
        }
        return mergedInput
    }

    /**
     * 增量路径：从 AABB + angle 反推虚拟 quad 4 顶点。
     * 中心点固定不动，按 angle 旋转 AABB 4 角。
     */
    private fun aabbToTiltedQuad(
        rect: Rect,
        angleDeg: Float,
        center: android.graphics.PointF
    ): Array<android.graphics.PointF> {
        val rad = Math.toRadians(angleDeg.toDouble())
        val cosA = kotlin.math.cos(rad).toFloat()
        val sinA = kotlin.math.sin(rad).toFloat()
        val l = rect.left.toFloat()
        val t = rect.top.toFloat()
        val r = rect.right.toFloat()
        val b = rect.bottom.toFloat()
        val cx = center.x
        val cy = center.y
        fun rot(x: Float, y: Float): android.graphics.PointF {
            val dx = x - cx
            val dy = y - cy
            return android.graphics.PointF(cx + dx * cosA - dy * sinA, cy + dx * sinA + dy * cosA)
        }
        return arrayOf(rot(l, t), rot(r, t), rot(r, b), rot(l, b))
    }

    /**
     * 公开检测方法：返回原始坐标系的 box 数组。
     * 用于增量渲染场景的 det 阶段。
     */
    fun runDetForBoxes(context: Context, bitmap: Bitmap): List<FloatArray> {
        if (!isInitialized) throw IllegalStateException("PPOcrV5Engine 未初始化")
        refreshParams(context)
        val boxes = runDet(bitmap)
        LogCollector.d(TAG, "runDetForBoxes: ${boxes.size} 个文字行")
        return boxes
    }

    /**
     * 调试用检测：返回保留和被丢弃的选区。
     * 丢弃原因：score<box_thresh、尺寸过小、unclip过小
     */
    fun runDetForDebug(context: Context, bitmap: Bitmap): DebugDetResult {
        if (!isInitialized) throw IllegalStateException("PPOcrV5Engine 未初始化")
        refreshParams(context)

        val (input, detH, detW) = preprocessDet(bitmap)
        val buffer = FloatBuffer.wrap(input)
        val inputTensor = OnnxTensor.createTensor(
            ortEnv!!, buffer,
            longArrayOf(1, 3, detH.toLong(), detW.toLong())
        )
        val results = detSession!!.run(mapOf("x" to inputTensor))
        inputTensor.close()

        try {
            var detOutputTensor: OnnxTensor? = null
            for (name in detSession!!.outputNames) {
                val value = results.get(name)
                if (value.isPresent && value.get() is OnnxTensor) {
                    detOutputTensor = value.get() as OnnxTensor
                    break
                }
            }
            val outputData = detOutputTensor!!.floatBuffer.array()
            val outputShape = detOutputTensor.info.shape
            detOutputTensor.close()

            val predH = outputShape[2].toInt()
            val predW = outputShape[3].toInt()
            val pred = outputData.sliceArray(0 until predH * predW)

            return postprocessDetDebug(pred, predH, predW, bitmap.height, bitmap.width)
        } finally {
            results.close()
        }
    }

    // 调试模式阈值（与正常模式一致）
    private const val DET_DEBUG_THRESH = DET_THRESH

    /**
     * 调试版后处理：用更低阈值捕获弱检测区域，同时返回被丢弃的候选框
     */
    private fun postprocessDetDebug(
        pred: FloatArray,
        predH: Int,
        predW: Int,
        srcH: Int,
        srcW: Int
    ): DebugDetResult {
        // 1. 阈值化（使用调试阈值 0.05，远低于正常 0.3）
        val cBitmap = Bitmap.createBitmap(predW, predH, Bitmap.Config.ARGB_8888)
        for (i in 0 until predH * predW) {
            val v = if (pred[i] > DET_DEBUG_THRESH) Color.WHITE else Color.BLACK
            cBitmap.setPixel(i % predW, i / predW, v)
        }

        // 2. BFS 连通域
        val contours = PPOcrDetGeometry.findContours(cBitmap, predW, predH, DET_MIN_SIZE)
        cBitmap.recycle()

        if (contours.isEmpty()) return DebugDetResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        // 3. 限制候选数量
        val limitedContours = if (contours.size > DET_MAX_CANDIDATES) {
            contours.sortedByDescending { it.size }.take(DET_MAX_CANDIDATES)
        } else {
            contours
        }

        // 4. 处理每个连通域
        val keptBoxes = mutableListOf<FloatArray>()
        val keptScores = mutableListOf<Float>()
        val discBoxes = mutableListOf<FloatArray>()
        val discScores = mutableListOf<Float>()
        val discReasons = mutableListOf<String>()

        for (contour in limitedContours) {
            val (boxPoints, _, _, w, h) = PPOcrDetGeometry.getMiniBoxes(contour)
            if (boxPoints == null) continue
            if (w < DET_MIN_SIZE || h < DET_MIN_SIZE) continue

            val boxCoords = boxPoints.map { Coordinate(it.x.toDouble(), it.y.toDouble()) }
            val score = GeometryUtils.boxScoreFast(pred, predW, predH, boxCoords)

            // box_thresh 过滤
            if (score < detBoxThresh) {
                val pts = boxPoints.map { PointF(it.x.toFloat(), it.y.toFloat()) }.toTypedArray()
                val mapped = mapBoxToOriginal(pts, predH.toFloat(), predW.toFloat(), srcH, srcW)
                discBoxes.add(mapped)
                discScores.add(score)
                discReasons.add("score=${String.format("%.3f", score)}<${detBoxThresh}")
                continue
            }

            // unclip
            val unclipBoxes = PPOcrDetGeometry.unclip(boxPoints, detUnclipRatio)
            for (ub in unclipBoxes) {
                val (expPts, _, _, ew, eh) = PPOcrDetGeometry.getMiniBoxes(ub.map { Point(it.x.roundToInt(), it.y.roundToInt()) })
                if (expPts == null) continue
                if (ew < DET_MIN_SIZE + 2 || eh < DET_MIN_SIZE + 2) {
                    val pts = expPts.map { PointF(it.x.toFloat(), it.y.toFloat()) }.toTypedArray()
                    val mapped = mapBoxToOriginal(pts, predH.toFloat(), predW.toFloat(), srcH, srcW)
                    discBoxes.add(mapped)
                    discScores.add(score)
                    discReasons.add("unclip过小 ${ew.toInt()}×${eh.toInt()}")
                    continue
                }

                val pts = expPts.map { PointF(it.x.toFloat(), it.y.toFloat()) }.toTypedArray()
                val mapped = mapBoxToOriginal(pts, predH.toFloat(), predW.toFloat(), srcH, srcW)
                keptBoxes.add(mapped)
                keptScores.add(score)
            }
        }

        // 5. 过滤保留的
        val filtered = PPOcrDetGeometry.filterDetRes(keptBoxes, keptScores, srcH, srcW, DET_MIN_SIZE, largeBoxEnabled, largeBoxRatio)

        LogCollector.d(TAG, "det debug: 保留=${filtered.boxes.size}, 丢弃=${discBoxes.size} (连通域=${contours.size})")
        for (i in discBoxes.indices) {
            val b = discBoxes[i]
            LogCollector.d(TAG, "  丢弃[$i]: ${discReasons[i]} [${b[0].toInt()},${b[1].toInt()}→${b[4].toInt()},${b[5].toInt()}]")
        }

        return DebugDetResult(
            filtered.boxes, filtered.scores,
            discBoxes, discScores, discReasons
        )
    }

    /**
     * 公开 boxToQuadPoints：将 8 元素 box 数组转为 4 点。
     */
    fun boxToQuadPointsPublic(box: FloatArray): Array<PointF> = boxToQuadPoints(box)

    /**
     * 运行检测，返回原始坐标系的 box 数组。
     */
    private fun runDet(bitmap: Bitmap): List<FloatArray> = synchronized(lock) {
        val (input, detH, detW) = preprocessDet(bitmap)
        LogCollector.d(TAG, "det input: ${bitmap.width}x${bitmap.height} → ${detW}x${detH}")

        val buffer = FloatBuffer.wrap(input)
        val inputTensor = OnnxTensor.createTensor(
            ortEnv!!, buffer,
            longArrayOf(1, 3, detH.toLong(), detW.toLong())
        )

        val results = detSession!!.run(mapOf("x" to inputTensor))
        inputTensor.close()

        try {
            var detOutputTensor: OnnxTensor? = null
            for (name in detSession!!.outputNames) {
                val value = results.get(name)
                if (value.isPresent && value.get() is OnnxTensor) {
                    detOutputTensor = value.get() as OnnxTensor
                    break
                }
            }
            val outputData = detOutputTensor!!.floatBuffer.array()
            val outputShape = detOutputTensor.info.shape // [1, 1, H, W]
            detOutputTensor.close()

            val predH = outputShape[2].toInt()
            val predW = outputShape[3].toInt()
            val pred = outputData.sliceArray(0 until predH * predW)

            postprocessDet(pred, predH, predW, bitmap.height, bitmap.width).boxes
        } finally {
            results.close()
        }
    }

    /**
     * 将 8 元素 box 数组转为 4 点 [TL, TR, BR, BL]
     */
    private fun boxToQuadPoints(box: FloatArray): Array<PointF> {
        return arrayOf(
            PointF(box[0], box[1]),
            PointF(box[2], box[3]),
            PointF(box[4], box[5]),
            PointF(box[6], box[7])
        )
    }

    /**
     * 将 4 点映射回原图坐标系（pred 空间 → 原图空间）
     */
    private fun mapBoxToOriginal(
        pts: Array<PointF>,
        predH: Float,
        predW: Float,
        srcH: Int,
        srcW: Int
    ): FloatArray {
        val ratioH = srcH.toFloat() / predH
        val ratioW = srcW.toFloat() / predW

        return floatArrayOf(
            (pts[0].x * ratioW).coerceIn(0f, (srcW - 1).toFloat()),
            (pts[0].y * ratioH).coerceIn(0f, (srcH - 1).toFloat()),
            (pts[1].x * ratioW).coerceIn(0f, (srcW - 1).toFloat()),
            (pts[1].y * ratioH).coerceIn(0f, (srcH - 1).toFloat()),
            (pts[2].x * ratioW).coerceIn(0f, (srcW - 1).toFloat()),
            (pts[2].y * ratioH).coerceIn(0f, (srcH - 1).toFloat()),
            (pts[3].x * ratioW).coerceIn(0f, (srcW - 1).toFloat()),
            (pts[3].y * ratioH).coerceIn(0f, (srcH - 1).toFloat())
        )
    }

    // -----------------------------------------------------------------------
    // filterByTextScore
    // -----------------------------------------------------------------------

    private fun filterByTextScore(
        boxes: List<FloatArray>,
        texts: List<String>,
        scores: List<Float>
    ): FilteredResult {
        val fBoxes = mutableListOf<FloatArray>()
        val fTexts = mutableListOf<String>()
        val fScores = mutableListOf<Float>()
        val dBoxes = mutableListOf<FloatArray>()
        val dTexts = mutableListOf<String>()
        val dScores = mutableListOf<Float>()

        val n = min(boxes.size, min(texts.size, scores.size))
        for (i in 0 until n) {
            if (scores[i] > textScoreThresh) {
                fBoxes.add(boxes[i])
                fTexts.add(texts[i])
                fScores.add(scores[i])
            } else {
                dBoxes.add(boxes[i])
                dTexts.add(texts[i])
                dScores.add(scores[i])
            }
        }

        return FilteredResult(fBoxes, fTexts, fScores, dBoxes, dTexts, dScores)
    }

    private data class FilteredResult(
        val boxes: List<FloatArray>,
        val texts: List<String>,
        val scores: List<Float>,
        val discardedBoxes: List<FloatArray>,
        val discardedTexts: List<String>,
        val discardedScores: List<Float>
    )
}
