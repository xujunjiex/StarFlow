package com.moe.starflow.sr

import android.graphics.Bitmap
import com.moe.starflow.sr.ncnn.SrNcnnNative
import com.moe.starflow.utils.LogCollector
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ncnn + Vulkan 超分引擎（waifu2x / SRMD / Real-CUGAN / Real-ESRGAN **四个引擎族共用这一个类**）。
 *
 * 与 `AnimeJaNaiEngine`（ONNX Runtime，CPU）的关系：**替代品，不是补充**。
 * 同一个 cunet 模型实测：ONNX+CPU 10.3 s / ncnn+Vulkan 1.6 s（**6~8 倍**），
 * 换后端才是这条链路的关键收益（见 `tools/sr-research/超分模型选型报告.md`）。
 *
 * ## 契约（与 [SuperResolutionEngine] 一致）
 * - 返回 **scale 倍**的新 Bitmap；**上游 `SuperResolutionEngines.applySteps` 会缩回原尺寸**
 *   （`bubbleRects` 是持久化坐标，坐标空间必须始终等于原图）
 * - **任何失败都返回 null**（没选模型/文件缺失/超尺寸/推理异常/OOM），调用方无痛回退原图
 *
 * ## 参数从哪来
 * `family` / `scale` / `noise` / `prepadding` 都来自 `downloadinfo.json` 的模型条目 ——
 * **不在代码里写死**：不同模型族的语义完全不同（waifu2x 的 noise 是降噪档 -1~3，
 * SRMD 的 noise 是退化强度 -1~10，Real-CUGAN/Real-ESRGAN 忽略它）。
 *
 * ## 内存上限（必须留）
 * 输出位图是输入的 `scale²` 倍像素。4x 模型（Real-ESRGAN）在同样输入下是 2x 的 **4 倍**内存，
 * 不按倍率收紧就是必 OOM。这里按「**输出像素上限**」反推输入上限，与 AnimeJaNaiEngine 的
 * 2.5 MP（2x）口径保持一致。
 */
class NcnnSrEngine(
    private val paramFile: File,
    private val binFile: File,
    private val family: Int,
    override val scale: Int,
    private val noise: Int,
    private val prepadding: Int,
    /** ≤0 = 交给原生侧按显存自动选（与 nihui 各工具 main.cpp 一致） */
    private val tileSize: Int = 0,
) : SuperResolutionEngine {

    /**
     * 原生实例句柄。**跨线程读写**：`initialize()` 在创建线程写，`upscale()` 在推理线程读，
     * `release()` 在释放线程清零 —— 必须 `@Volatile`，否则可能读到过期值。
     * （读到过期值不会崩：原生侧有存活登记表兜底，只是白跑一次注定失败的 process。）
     */
    @Volatile
    private var handle: Long = 0L

    /** 输出像素上限：10 MP（与 AnimeJaNaiEngine 的 2.5MP@2x 等价） */
    private val maxOutputPixels: Long = 10_000_000L

    /**
     * 输入像素上限 = 输出上限 ÷ scale²。
     *
     * ⚠️ 必须**按倍率反推**：4x 模型（Real-ESRGAN）在同样输入下是 2x 的 **4 倍**内存，
     * 不收紧就是必 OOM。2x 时正好等于 2.5MP，与 [AnimeJaNaiEngine] 同口径。
     *
     * 暴露成 public 是为了让上层能给出**带数字**的提示（用户口径：「到底是什么原因
     * 无法超分写清楚」）—— 只说"尺寸太大"用户没法判断，说"本页 4.7MP 超过 2.5MP 上限"
     * 他立刻知道该换模型。
     */
    override val maxInputPixels: Long = maxOutputPixels / (scale.toLong() * scale.toLong())

    /**
     * 上次失败的**短 ASCII 细节**（接口契约：原样进日志 / 可能被用户复制发出来，不做本地化）。
     *
     * ⚠️ 只在「同一线程、期间没有别的调用」时有效 —— 超分与 OCR 共用 `OcrLock`，
     * 同一时刻全项目只有一次超分在跑，这个约束成立。
     */
    @Volatile
    private var failDetail: String? = null

    override fun lastFailDetail(): String? = failDetail

    /** ⚠️ `@Synchronized`：并发调用会各建一个原生实例，后者覆盖前者 → 泄漏一个 net。 */
    @Synchronized
    fun initialize(): Boolean {
        if (handle != 0L) return true
        return try {
            val h = SrNcnnNative.create(family, paramFile.absolutePath, binFile.absolutePath, GPU_ID)
            if (h == 0L) {
                LogCollector.e(TAG, "原生引擎创建失败: ${paramFile.name} (family=$family)")
                false
            } else {
                handle = h
                LogCollector.i(
                    TAG,
                    "ncnn 超分就绪: ${paramFile.name} family=$family scale=$scale noise=$noise " +
                        "prepad=$prepadding gpu=${runCatching { SrNcnnNative.gpuName() }.getOrDefault("?")}"
                )
                true
            }
        } catch (t: Throwable) {
            // UnsatisfiedLinkError（.so 缺失）也走这里 —— 不让它把 app 带崩
            LogCollector.e(TAG, "ncnn 引擎初始化异常: ${paramFile.name}", t)
            false
        }
    }

    override fun upscale(src: Bitmap): Bitmap? {
        val h = handle
        if (h == 0L) {
            failDetail = "engine not initialized"
            return null
        }
        val w = src.width
        val ht = src.height
        if (w <= 0 || ht <= 0) return null

        // 上限守卫：按**输出**像素算（4x 与 2x 的内存差 4 倍）
        val outPixels = w.toLong() * ht.toLong() * scale * scale
        if (outPixels > maxOutputPixels) {
            failDetail = "source ${w}x$ht @${scale}x -> ${outPixels / 1000}K px > " +
                "${maxOutputPixels / 1_000_000}MP output cap"
            LogCollector.d(TAG, "跳过超分：$failDetail")
            return null
        }

        val ow = w * scale
        val oh = ht * scale
        val inBytes = w * ht * 3
        val outBytes = ow * oh * 3

        var inBuf: ByteBuffer? = null
        var outBuf: ByteBuffer? = null
        try {
            inBuf = ByteBuffer.allocateDirect(inBytes).order(ByteOrder.nativeOrder())
            outBuf = ByteBuffer.allocateDirect(outBytes).order(ByteOrder.nativeOrder())
            // Bitmap(ARGB_8888) -> 紧凑 RGB8。
            // ⚠️ 同样**逐行**取：`IntArray(w*ht)` 在 2.5MP 输入下就是 10MB 临时数组，
            //    逐行后只有 `w*4` 字节。
            val ib = inBuf
            val rowIn = IntArray(w)
            var o = 0
            for (y in 0 until ht) {
                src.getPixels(rowIn, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    val c = rowIn[x]
                    ib.put(o, (c shr 16).toByte())      // R
                    ib.put(o + 1, (c shr 8).toByte())   // G
                    ib.put(o + 2, c.toByte())           // B
                    o += 3
                }
            }

            val ok = SrNcnnNative.process(
                h, inBuf, outBuf, w, ht, scale, noise, prepadding, tileSize
            )
            if (!ok) {
                failDetail = "ncnn process failed: ${paramFile.name} ${w}x$ht @${scale}x"
                LogCollector.e(TAG, failDetail!!)
                return null
            }

            // 紧凑 RGB8 -> Bitmap。
            // ⚠️ **逐行写**，不要 `IntArray(ow*oh)` 攒完再 setPixels：4x 模型下一次要 37MB，
            //    叠加输出位图(37MB) + DirectBuffer(28MB)，峰值破 100MB，低堆机型必 OOM。
            //    逐行后临时内存只有 `ow*4` 字节。
            val outBmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            val rowOut = IntArray(ow)
            var q = 0
            for (y in 0 until oh) {
                for (x in 0 until ow) {
                    val r = outBuf.get(q).toInt() and 0xFF
                    val g = outBuf.get(q + 1).toInt() and 0xFF
                    val b = outBuf.get(q + 2).toInt() and 0xFF
                    rowOut[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    q += 3
                }
                outBmp.setPixels(rowOut, 0, ow, 0, y, ow, 1)
            }
            return outBmp
        } catch (t: Throwable) {
            // 超分失败必须静默降级：调用方拿 null 用原图，绝不因为它翻不了页
            failDetail = "exception ${t.javaClass.simpleName}: ${t.message?.take(80)}"
            LogCollector.e(TAG, "ncnn 超分异常: ${paramFile.name} ${w}x$ht", t)
            return null
        }
        // DirectByteBuffer 由 GC 回收：这里不显式 free，靠作用域结束 + GC。
        // （逐行处理后临时对象很小；DirectBuffer 本身最多 10MP×3 ≈ 30MB，可接受）
    }

    override fun release() {
        val h = handle
        handle = 0L
        if (h != 0L) {
            runCatching { SrNcnnNative.release(h) }
                .onFailure { LogCollector.w(TAG, "release: ${it.message}") }
        }
    }

    private companion object {
        const val TAG = "NcnnSrEngine"

        /** 0 = 第一个 Vulkan 设备。无 Vulkan 时原生侧会自动降级到 CPU 并打日志。 */
        const val GPU_ID = 0
    }
}
