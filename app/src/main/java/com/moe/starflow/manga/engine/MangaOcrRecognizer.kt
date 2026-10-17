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
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.moe.starflow.utils.LogCollector
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Collections

/**
 * manga-ocr ONNX 推理引擎
 *
 * 使用 ViT Encoder + BERT Decoder 进行漫画文字识别。
 * 支持多 Session 并行推理以提升吞吐量。
 */
object MangaOcrRecognizer {

    private const val TAG = "MangaOcrRecognizer"
    private const val IMAGE_SIZE = 224
    private const val MAX_NEW_TOKENS = 300

    // ONNX 环境和会话池
    private var ortEnv: OrtEnvironment? = null
    private var encoderSessions: List<OrtSession> = emptyList()
    private var decoderSessions: List<OrtSession> = emptyList()
    private var tokenizer: MangaOcrTokenizer? = null
    @Volatile private var appContext: Context? = null

    // Session 数量：取 CPU 核心数，限制在 2-4 之间
    private val sessionCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    @Volatile
    var isInitialized = false
        private set

    // 初始化/释放锁，防止并发 release() 中断初始化
    private val initLock = Any()

    // 保护 session.run() 与 release() 不并发
    private val sessionLock = ReentrantLock()

    /**
     * 初始化模型
     *
     * @param context Context
     * @param modelDir 模型目录（仅用于 assets 模式）
     * @param useAssets 是否从 assets 加载，false 时从下载目录加载
     */
    suspend fun initialize(context: Context, modelDir: String = "manga_ocr", useAssets: Boolean = true) {
        synchronized(initLock) {
            OcrExecution.checkCancellation()
            if (isInitialized) return
            appContext = context.applicationContext

            try {
                PpRecRecognizer.withMemoryGuard(context, TAG, "manga-ocr initialization") {
                    LogCollector.d(TAG, "开始初始化 manga-ocr 模型 (useAssets=$useAssets, sessions=$sessionCount)...")

                    val env = OrtEnvironment.getEnvironment()
                    ortEnv = env

                    OrtSession.SessionOptions().use { sessionOptions ->
                        sessionOptions.apply {
                            setMemoryPatternOptimization(true)
                            setCPUArenaAllocator(true)
                            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                            setIntraOpNumThreads(2)
                        }

                        // 加载 encoder（1 个 session，支持动态 batch_size）
                        val encoderPath = if (useAssets) {
                            copyAssetToCache(context, "$modelDir/manga_ocr_encoder.onnx")
                        } else {
                            MangaOcrModelFiles.getEncoderFile(context).absolutePath
                        }
                        encoderSessions = listOf(env.createSession(encoderPath, sessionOptions))
                        LogCollector.d(TAG, "Encoder 加载完成 (1 session, 支持动态 batch)")

                        // 加载 decoder（多个 session）
                        val decoderPath = if (useAssets) {
                            copyAssetToCache(context, "$modelDir/manga_ocr_decoder.onnx")
                        } else {
                            MangaOcrModelFiles.getDecoderFile(context).absolutePath
                        }
                        val decoders = mutableListOf<OrtSession>()
                        decoderSessions = decoders
                        repeat(sessionCount) {
                            OcrExecution.checkCancellation()
                            val decoder = env.createSession(decoderPath, sessionOptions)
                            try { decoders.add(decoder) } catch (e: Throwable) { decoder.close(); throw e }
                        }
                        LogCollector.d(TAG, "Decoder 加载完成 ($sessionCount sessions)")

                        // 加载 tokenizer
                        tokenizer = MangaOcrTokenizer(context).apply {
                            if (useAssets) {
                                loadFromAssets(modelDir)
                            } else {
                                loadFromFile(MangaOcrModelFiles.getVocabFile(context))
                            }
                        }
                        LogCollector.d(TAG, "Tokenizer 加载完成")

                        isInitialized = true
                        LogCollector.d(TAG, "manga-ocr 模型初始化完成 ($sessionCount sessions)")
                    }
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                releaseInternal()
                throw e
            } catch (e: Exception) {
                LogCollector.e(TAG, "初始化失败", e)
                releaseInternal()
                throw e
            }
        }
    }

    /**
     * 识别图片中的文字（使用 session 0）
     */
    fun recognize(bitmap: Bitmap): String {
        val context = appContext ?: throw IllegalStateException("MangaOcrRecognizer 未初始化")
        return PpRecRecognizer.withMemoryGuard(context, TAG, "manga-ocr recognition") {
            recognizeWithSession(bitmap, 0)
        }
    }

    /**
     * 使用指定 session 识别图片中的文字
     */
    private fun recognizeWithSession(bitmap: Bitmap, sessionIndex: Int): String {
        if (!isInitialized) {
            throw IllegalStateException("MangaOcrRecognizer 未初始化")
        }

        val idx = sessionIndex % encoderSessions.size
        val encSession = encoderSessions[idx]
        val decSession = decoderSessions[idx]

        try {
            // 1. 预处理与 encoder 推理。Result 持有输出 tensor，统一在 finally 关闭。
            val tokenIds = preprocessImage(bitmap).use { inputTensor ->
                sessionLock.withLock {
                    OcrExecution.run(encSession, Collections.singletonMap("pixel_values", inputTensor))
                }.use { encoderResults ->
                    val encoderOutputs = encoderResults.get("last_hidden_state").get() as OnnxTensor
                    runDecoderWithSession(decSession, encoderOutputs)
                }
            }

            // 2. 解码为文字
            return tokenizer!!.decode(tokenIds)

        } catch (e: Exception) {
            LogCollector.e(TAG, "识别失败 (session $idx)", e)
            throw e
        }
    }

    /**
     * 批量识别入口，内部逐个调用 [recognize]。
     *
     * MangaOCR 的 Encoder 接收固定尺寸 [N,3,224,224]，
     * 裁剪图片宽高比各异，统一 resize 到正方形会严重扭曲文字。
     * 因此所谓 batch 只是循环调用单图识别，不存在真正的批量加速。
     *
     * @param bitmaps 待识别的裁剪图片列表
     * @return 识别结果列表，与输入顺序一一对应
     */
    suspend fun recognizeBatch(bitmaps: List<Bitmap>): List<String> = OcrExecution.cancellable {
        recognizeBatchCancellable(bitmaps)
    }

    private suspend fun recognizeBatchCancellable(bitmaps: List<Bitmap>): List<String> {
        if (!isInitialized) {
            throw IllegalStateException("MangaOcrRecognizer 未初始化")
        }
        if (bitmaps.isEmpty()) return emptyList()
        if (bitmaps.size == 1) return listOf(recognize(bitmaps[0]))

        try {
            val context = appContext ?: throw IllegalStateException("MangaOcrRecognizer 未初始化")
            return PpRecRecognizer.withMemoryGuardSuspend(
                context, TAG, "manga-ocr batch recognition count=${bitmaps.size}"
            ) {
                val encSession = encoderSessions[0]
                val tok = tokenizer!!

                // 1. 统一 resize 到 [N, 3, 224, 224] 并堆叠（但 resize 扭曲问题未解决）
                val inputTensor = preprocessImages(bitmaps)
                try {
                    // 2. Encoder 一次推理
                    LogCollector.d(TAG, "Encoder batch 推理: ${bitmaps.size} 张图片")
                    val t0 = System.currentTimeMillis()
                    val encoderResults = sessionLock.withLock {
                        OcrExecution.run(encSession, Collections.singletonMap("pixel_values", inputTensor))
                    }
                    try {
                        val encoderOutputs = encoderResults.get("last_hidden_state").get() as OnnxTensor
                        // ORT 的 floatBuffer 会复制完整张量。每个样本共用一次快照，避免 N 次复制整批输出。
                        val encoderBuffer = encoderOutputs.floatBuffer
                        LogCollector.d(TAG, "Encoder batch 完成: ${System.currentTimeMillis() - t0}ms")

                        // 3. 逐个 Decoder 解码
                        val t1 = System.currentTimeMillis()
                        val results = coroutineScope {
                            (0 until bitmaps.size).map { i ->
                                async(Dispatchers.Default) {
                                    OcrExecution.cancellable {
                                        extractSingleBatch(encoderBuffer, i, encoderOutputs.info.shape).use { singleHidden ->
                                            val tokenIds = runDecoderWithSession(
                                                decoderSessions[i % decoderSessions.size], singleHidden
                                            )
                                            tok.decode(tokenIds)
                                        }
                                    }
                                }
                            }.awaitAll()
                        }
                        LogCollector.d(TAG, "Decoder 完成: ${System.currentTimeMillis() - t1}ms, 共 ${bitmaps.size} 个")
                        results
                    } finally {
                        encoderResults.close()
                    }
                } finally {
                    inputTensor.close()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OcrMemoryException) {
            throw e

        } catch (e: Exception) {
            LogCollector.e(TAG, "批量识别失败，回退到逐个识别", e)
            val context = appContext ?: throw IllegalStateException("MangaOcrRecognizer 未初始化")
            return PpRecRecognizer.withMemoryGuardSuspend(
                context, TAG, "manga-ocr per-image fallback count=${bitmaps.size}"
            ) { bitmaps.map { recognize(it) } }
        }
    }

    /**
     * 流式批量识别：encoder 一次推理，decoder 并行解码，每个完成立即返回。
     * @return Channel<Pair<Int, String>> (索引, 文字)，按完成顺序返回
     */
    suspend fun recognizeStreaming(bitmaps: List<Bitmap>): Channel<Pair<Int, String>> = OcrExecution.cancellable {
        recognizeStreamingCancellable(bitmaps)
    }

    private suspend fun recognizeStreamingCancellable(bitmaps: List<Bitmap>): Channel<Pair<Int, String>> {
        if (!isInitialized) {
            throw IllegalStateException("MangaOcrRecognizer 未初始化")
        }
        val channel = Channel<Pair<Int, String>>(Channel.UNLIMITED)

        if (bitmaps.isEmpty()) {
            channel.close()
            return channel
        }
        if (bitmaps.size == 1) {
            channel.send(Pair(0, recognize(bitmaps[0])))
            channel.close()
            return channel
        }

        try {
            val context = appContext ?: throw IllegalStateException("MangaOcrRecognizer 未初始化")
            PpRecRecognizer.withMemoryGuardSuspend(
                context, TAG, "manga-ocr streaming recognition count=${bitmaps.size}"
            ) {
                val encSession = encoderSessions[0]
                val tok = tokenizer!!
                val inputTensor = preprocessImages(bitmaps)
                try {
                    // 1. Encoder 一次推理
                    LogCollector.d(TAG, "Encoder batch 推理: ${bitmaps.size} 张图片")
                    val t0 = System.currentTimeMillis()
                    val encoderResults = sessionLock.withLock {
                        OcrExecution.run(encSession, Collections.singletonMap("pixel_values", inputTensor))
                    }
                    try {
                        val encoderOutputs = encoderResults.get("last_hidden_state").get() as OnnxTensor
                        val encoderBuffer = encoderOutputs.floatBuffer
                        LogCollector.d(TAG, "Encoder batch 完成: ${System.currentTimeMillis() - t0}ms")

                        // 2. Decoder 并行，每个完成立即发送到 channel
                        val t1 = System.currentTimeMillis()
                        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
                        val totalCount = bitmaps.size
                        coroutineScope {
                            (0 until bitmaps.size).map { i ->
                                launch(Dispatchers.Default) {
                                    try {
                                        OcrExecution.cancellable {
                                            extractSingleBatch(encoderBuffer, i, encoderOutputs.info.shape).use { singleHidden ->
                                                val tokenIds = runDecoderWithSession(
                                                    decoderSessions[i % decoderSessions.size], singleHidden
                                                )
                                                val text = tok.decode(tokenIds)
                                                channel.send(Pair(i, text))
                                                LogCollector.d(TAG, "Decoder [$i] 完成: ${System.currentTimeMillis() - t1}ms")
                                            }
                                        }
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: OcrMemoryException) {
                                        throw e
                                    } catch (e: Exception) {
                                        LogCollector.e(TAG, "Decoder [$i] 失败", e)
                                    } finally {
                                        // 最后一个完成的 decoder 关闭 channel
                                        if (completedCount.incrementAndGet() == totalCount) {
                                            channel.close()
                                            LogCollector.d(TAG, "Decoder 全部完成: ${System.currentTimeMillis() - t1}ms")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    finally {
                        encoderResults.close()
                    }
                } finally {
                    inputTensor.close()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            channel.close(e)
            throw e
        } catch (e: OcrMemoryException) {
            channel.close(e)
            throw e
        } catch (e: Exception) {
            LogCollector.e(TAG, "流式识别失败", e)
            channel.close()
        }

        return channel
    }

    /**
     * 预处理单张图片：resize + normalize
     */
    private fun preprocessImage(bitmap: Bitmap): OnnxTensor {
        val resized = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)
        try {
            val floatBuffer = FloatBuffer.allocate(3 * IMAGE_SIZE * IMAGE_SIZE)
            val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
            resized.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

            // CHW 格式，RGB 通道，归一化到 [-1, 1]
            for (c in 0 until 3) {
                for (i in pixels.indices) {
                    val pixel = pixels[i]
                    val value = when (c) {
                        0 -> ((pixel shr 16 and 0xFF) / 255.0f - 0.5f) / 0.5f  // R
                        1 -> ((pixel shr 8 and 0xFF) / 255.0f - 0.5f) / 0.5f   // G
                        2 -> ((pixel and 0xFF) / 255.0f - 0.5f) / 0.5f         // B
                        else -> 0f
                    }
                    floatBuffer.put(value)
                }
            }
            floatBuffer.rewind()

            return OnnxTensor.createTensor(
                ortEnv!!, floatBuffer, longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())
            )
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    }

    /**
     * 批量预处理：将 N 张图片堆叠为 [N, 3, 224, 224] tensor。
     * 所有图片按 CHW 格式排列，归一化到 [-1, 1]。
     */
    private fun preprocessImages(bitmaps: List<Bitmap>): OnnxTensor {
        val N = bitmaps.size
        val channelSize = IMAGE_SIZE * IMAGE_SIZE
        val floatBuffer = FloatBuffer.allocate(Math.multiplyExact(N, 3 * channelSize))

        for (bitmap in bitmaps) {
            OcrExecution.checkCancellation()
            val resized = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)
            try {
                val pixels = IntArray(channelSize)
                resized.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

                // CHW 格式，RGB 通道，归一化到 [-1, 1]
                for (c in 0 until 3) {
                    for (i in pixels.indices) {
                        val pixel = pixels[i]
                        val value = when (c) {
                            0 -> ((pixel shr 16 and 0xFF) / 255.0f - 0.5f) / 0.5f  // R
                            1 -> ((pixel shr 8 and 0xFF) / 255.0f - 0.5f) / 0.5f   // G
                            2 -> ((pixel and 0xFF) / 255.0f - 0.5f) / 0.5f         // B
                            else -> 0f
                        }
                        floatBuffer.put(value)
                    }
                }
            } finally {
                if (resized !== bitmap) resized.recycle()
            }
        }

        floatBuffer.rewind()
        return OnnxTensor.createTensor(
            ortEnv!!, floatBuffer,
            longArrayOf(N.toLong(), 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())
        )
    }

    /**
     * 从 batched encoder outputs 中提取第 i 个 batch 的 hidden states。
     * 输入: [N, 197, 768] OnnxTensor
     * 输出: [1, 197, 768] OnnxTensor
     */
    private fun extractSingleBatch(fullBuffer: FloatBuffer, index: Int, shape: LongArray): OnnxTensor {
        require(shape.size == 3 && index >= 0 && index.toLong() < shape[0]) {
            "Invalid encoder output shape/index"
        }
        val seqLen = Math.toIntExact(shape[1])
        val hiddenSize = Math.toIntExact(shape[2])

        val count = Math.multiplyExact(seqLen, hiddenSize)
        val offset = Math.multiplyExact(index, count)
        val buffer = FloatBuffer.allocate(count)
        val sample = fullBuffer.duplicate().apply {
            position(offset)
            limit(Math.addExact(offset, count))
        }
        buffer.put(sample)
        buffer.rewind()

        return OnnxTensor.createTensor(ortEnv!!, buffer, longArrayOf(1, seqLen.toLong(), hiddenSize.toLong()))
    }

    /**
     * 使用指定 session 运行 Decoder（自回归生成，无 KV cache）
     */
    private fun runDecoderWithSession(decSession: OrtSession, encoderHiddenStates: OnnxTensor): List<Int> {
        val tok = tokenizer!!
        val generatedTokens = mutableListOf<Int>()

        // 初始输入: [CLS] token
        var currentIds = mutableListOf(tok.getBosTokenId())

        for (step in 0 until MAX_NEW_TOKENS) {
            OcrExecution.checkCancellation()
            // 传完整序列 [1, seq_len]（decoder 支持动态 seq_len）
            val inputIdsArray = currentIds.map { it.toLong() }.toLongArray()
            val inputIdsBuffer = java.nio.LongBuffer.wrap(inputIdsArray)
            val inputIdsTensor = OnnxTensor.createTensor(ortEnv!!, inputIdsBuffer, longArrayOf(1, inputIdsArray.size.toLong()))
            val nextTokenId = inputIdsTensor.use { input ->
                val inputs = mapOf(
                    "input_ids" to input,
                    "encoder_hidden_states" to encoderHiddenStates
                )
                sessionLock.withLock { OcrExecution.run(decSession, inputs) }.use { outputs ->
                    // 获取最后一个 token 的 logits。floatBuffer 本身仍是 ORT 的完整输出副本，
                    // 但直接扫描末尾切片，避免再复制一份 vocab 数组。
                    val logitsTensor = outputs.get("logits").get() as OnnxTensor
                    val shape = logitsTensor.info.shape
                    require(shape.size == 3 && shape[0] == 1L && shape[1] == currentIds.size.toLong()) {
                        "Invalid manga-ocr logits shape: ${shape.contentToString()}"
                    }
                    val vocabSize = Math.toIntExact(shape[2])
                    require(vocabSize > 0) { "Invalid manga-ocr vocabulary size: $vocabSize" }
                    val logits = logitsTensor.floatBuffer
                    val lastLogitsStart = Math.multiplyExact(currentIds.size - 1, vocabSize)

                    var bestId = 0
                    var bestValue = Float.NEGATIVE_INFINITY
                    for (tokenId in 0 until vocabSize) {
                        val value = logits.get(lastLogitsStart + tokenId)
                        if (value > bestValue) {
                            bestValue = value
                            bestId = tokenId
                        }
                    }
                    bestId
                }
            }

            // 检查 EOS
            if (nextTokenId == tok.getEosTokenId()) break

            generatedTokens.add(nextTokenId)
            currentIds.add(nextTokenId)
        }

        return generatedTokens
    }

    /**
     * 将 assets 文件复制到缓存目录。
     * 通过 APK 版本号判断是否需要更新：APK 升级后才重新复制，避免每次启动都复制大文件。
     * 如果是 .onnx 文件，同时复制对应的 .onnx.data 文件。
     */
    private fun copyAssetToCache(context: Context, assetPath: String): String {
        val fileName = assetPath.substringAfterLast("/")
        val cacheFile = context.cacheDir.resolve(fileName)

        // 使用 asset 文件大小 + APK 版本作为缓存 key，assets 内容变化时自动失效
        val prefs = context.getSharedPreferences("manga_ocr_cache", Context.MODE_PRIVATE)
        val assetSize = try { context.assets.open(assetPath).use { it.available().toLong() } } catch (_: Exception) { 0L }
        val apkVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } catch (e: Exception) { 0L }
        val currentKey = "$apkVersion-$assetSize"
        val cachedKey = prefs.getString("cache_key", "")

        if (cacheFile.exists() && cachedKey == currentKey) {
            LogCollector.d(TAG, "模型已缓存且版本一致，跳过复制: ${cacheFile.absolutePath}")
        } else {
            LogCollector.d(TAG, "复制 assets 文件: $assetPath -> ${cacheFile.absolutePath}")
            context.assets.open(assetPath).use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            LogCollector.d(TAG, "复制完成: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
            // 复制外部数据文件 (.onnx.data)
            if (assetPath.endsWith(".onnx")) {
                val dataAssetPath = "$assetPath.data"
                val dataFileName = dataAssetPath.substringAfterLast("/")
                val dataCacheFile = context.cacheDir.resolve(dataFileName)
                try {
                    context.assets.open(dataAssetPath).use { input ->
                        dataCacheFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    LogCollector.d(TAG, "外部数据文件已复制: ${dataCacheFile.absolutePath} (${dataCacheFile.length()} bytes)")
                } catch (e: Exception) {
                    LogCollector.d(TAG, "外部数据文件不存在，跳过: $dataAssetPath")
                }
            }
            prefs.edit().putString("cache_key", currentKey).apply()
        }
        return cacheFile.absolutePath
    }

    /**
     * 释放资源
     */
    fun release() {
        synchronized(initLock) {
            releaseInternal()
        }
    }

    private fun releaseInternal() {
        sessionLock.lock()
        try {
            encoderSessions.forEach { try { it.close() } catch (_: Exception) {} }
            decoderSessions.forEach { try { it.close() } catch (_: Exception) {} }
            ortEnv?.close()
        } catch (e: Exception) {
            LogCollector.e(TAG, "释放资源失败", e)
        } finally {
            encoderSessions = emptyList()
            decoderSessions = emptyList()
            ortEnv = null
            tokenizer = null
            isInitialized = false
            sessionLock.unlock()
        }
    }
}
