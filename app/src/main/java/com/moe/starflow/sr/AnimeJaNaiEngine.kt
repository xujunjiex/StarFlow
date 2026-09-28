package com.moe.starflow.sr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import com.moe.starflow.utils.LogCollector
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AnimeJaNai（2x，ONNX）超分引擎。
 *
 * 输入 NCHW float32 / 0..1 / RGB，输出同布局、长宽各 2 倍 —— 与上游
 * `mpv-upscale-2x_animejanai` 的调用约定一致（本地用 onnxruntime 复现过，
 * 见 `tools/sr-research/mobile_onnx_test.py`）。
 *
 * ⚠️ **必须把 H/W 补齐到 16 的倍数**：这些 SPAN 图里有 `Mod`/`Shape`/`Reshape` 做的
 * 内部分块（unshuffle），尺寸不是 16 倍数时最后一块会读越界。上游 mpv 实现同样补齐。
 * 本地实测：补 16 后 512×512 / 1280×1854 都能跑通。
 *
 * ⚠️ **模型是 fp16 权重**。ORT 的 CPU EP 对 fp16 会插 Cast 转 fp32 执行（能跑，但有额外开销）；
 * 若真机上发现特别慢，需要把模型转成 fp32（本机已验证转换可行，见
 * `tools/sr-research/quant_test.py` 的 to_fp32 思路）。
 *
 * ⚠️ **内存上限**：一次推理要在堆上放 3×iw×ih 的 float 输入 + 3×2iw×2ih 的 float 输出
 * （2x 时输出 float 是输入的 4 倍）。不加限制，A4 尺寸的页面会直接把手机 OOM 掉 ——
 * 所以这里照 koto 的做法设 [MAX_INPUT_PIXELS] 上限，超了**直接不超分**（返回 null，走原图）。
 */
class AnimeJaNaiEngine(private val modelFile: File) : SuperResolutionEngine {

    override val scale: Int = 2

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String = "input"

    /** 输入像素上限（2x 后是 4 倍）：2.5MP ≈ 1500×1667，输出 10MP / 40MB bitmap */
    private val maxInputPixels: Long = 2_500_000L

    fun initialize(): Boolean {
        return try {
            val e = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setMemoryPatternOptimization(true)
                setCPUArenaAllocator(true)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                // 超分是纯卷积，线程给足；但别吃满所有核（前台还有 UI 与 OCR 要跑）
                setIntraOpNumThreads(
                    Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                )
            }
            val s = e.createSession(modelFile.absolutePath, options)
            env = e
            session = s
            inputName = s.inputNames.firstOrNull() ?: "input"
            LogCollector.d(TAG, "初始化完成: ${modelFile.name}, input=$inputName, " +
                    "threads=${Runtime.getRuntime().availableProcessors().coerceIn(1, 4)}")
            true
        } catch (e: Throwable) {
            LogCollector.e(TAG, "初始化失败: ${modelFile.name}", e)
            release()
            false
        }
    }

    override fun upscale(src: Bitmap): Bitmap? {
        val e = env ?: return null
        val s = session ?: return null

        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null

        // 上限守卫：宁可原图，也不要把手机打 OOM
        if (w.toLong() * h.toLong() > maxInputPixels) {
            LogCollector.d(TAG, "跳过超分：输入 ${w}x$h 超过 ${maxInputPixels / 1000}K 像素上限")
            return null
        }

        // H/W 补到 16 的倍数（SPAN 内部分块要求）
        val padW = (16 - w % 16) % 16
        val padH = (16 - h % 16) % 16
        val iw = w + padW
        val ih = h + padH

        val plane = iw * ih
        val inBuf = ByteBuffer.allocateDirect(plane * 3 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

        val argb = IntArray(w * h)
        src.getPixels(argb, 0, w, 0, 0, w, h)

        // 三个通道各填一遍（NCHW）；右侧/下侧的补齐用边缘像素复制，
        // 避免出现黑边被模型放大成可见边框
        for (c in 0 until 3) {
            val shift = when (c) { 0 -> 16; 1 -> 8; else -> 0 }
            var y = 0
            while (y < ih) {
                val srcY = if (y < h) y else h - 1
                val rowBase = srcY * w
                var x = 0
                while (x < iw) {
                    val srcX = if (x < w) x else w - 1
                    inBuf.put(((argb[rowBase + srcX] shr shift) and 0xFF) / 255f)
                    x++
                }
                y++
            }
        }
        inBuf.rewind()

        var tensor: OnnxTensor? = null
        var result: OrtSession.Result? = null
        try {
            tensor = OnnxTensor.createTensor(e, inBuf, longArrayOf(1, 3, ih.toLong(), iw.toLong()))
            result = s.run(mapOf(inputName to tensor))
            val out = result[0] as? OnnxTensor ?: run {
                LogCollector.e(TAG, "输出不是张量: ${result[0]?.javaClass?.simpleName}")
                return null
            }
            val outBuf = out.floatBuffer
            val ow = iw * scale
            val oh = ih * scale
            val need = ow.toLong() * oh.toLong() * 3
            if (outBuf.limit().toLong() < need) {
                LogCollector.e(TAG, "输出张量尺寸不符: limit=${outBuf.limit()} 期望>=$need")
                return null
            }

            val owOut = w * scale
            val ohOut = h * scale
            val outPx = IntArray(owOut * ohOut)
            val planeOut = ow * oh
            for (y in 0 until ohOut) {
                val srcRow = y * ow
                val dstRow = y * owOut
                for (x in 0 until owOut) {
                    val i = srcRow + x
                    val r = (outBuf.get(i) * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val g = (outBuf.get(i + planeOut) * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val b = (outBuf.get(i + planeOut * 2) * 255f + 0.5f).toInt().coerceIn(0, 255)
                    outPx[dstRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return Bitmap.createBitmap(owOut, ohOut, Bitmap.Config.ARGB_8888).apply {
                setPixels(outPx, 0, owOut, 0, 0, owOut, ohOut)
            }
        } catch (t: Throwable) {
            // 超分失败必须静默降级：调用方拿 null 用原图，绝不因为它翻不了页
            LogCollector.e(TAG, "推理失败 (${w}x$h, fp16 模型在 CPU EP 上可能不支持)", t)
            return null
        } finally {
            runCatching { tensor?.close() }
            runCatching { result?.close() }
        }
    }

    override fun release() {
        runCatching { session?.close() }
        session = null
        env = null
    }

    private companion object {
        const val TAG = "AnimeJaNaiEngine"
    }
}
