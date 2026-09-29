package com.moe.starflow.sr

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import com.moe.starflow.utils.LogCollector
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 超分模型引擎（ONNX，2x）。11 个模型的**唯一**实现。
 *
 * ## ⚠️ 硬契约（改动前必读）
 * ```
 * out.width  == src.width  * scale      // 精确相等，不是"大约"
 * out.height == src.height * scale
 * // 且内容对齐：out(2x, 2y) ↔ src(x, y)
 * ```
 * 这条契约是**整个超分渲染的地基**：底图按 `baseScale` 放大画 overlay 时，
 * "源坐标 → 底图坐标"必须是**纯缩放**。契约破了就会变成"译文整体偏一点/缩一点"，
 * 而且**不崩不报错**，只有肉眼能发现。
 *
 * ## 实测：模型原始输出**并不是**输入的精确 2 倍
 * 2026-10 用 11 个真实模型在 128/256/512 三种输入下实测（`tools/sr-research/_sr_out_size.py`）：
 *
 * | 模型族 | 输出比 2× 少 | 每边（源像素） | 是否与输入尺寸相关 |
 * |---|---|---|---|
 * | AnimeJaNai HD/SD ×5 | **0** | 0 | — |
 * | waifu2x **cunet** ×4 | **72** | 18 | **无关（固定）** |
 * | waifu2x **swin_unet** ×2 | **32** | 8 | **无关（固定）** |
 *
 * 即：模型内部按感受野裁掉了固定边框（不是比例缩放）。所以**绝不能假设输出 = 2×**：
 * - 直接取左上角 → 内容整体平移
 * - 校验"不小于" → cunet/swin 全部判失败（这正是修复前"超分没效果"的原因之一）
 *
 * **本类的做法**：四边各补 [PAD] 个源像素 → 推理 → 按探测出的 halo **精确裁出** `src×2`。
 * 补边用**边缘像素复制**（不要补黑边，黑边会被放大成可见边框）。裁剪窗口精确到像素，
 * 因此产物满足上面的硬契约。
 *
 * halo 在 [initialize] 里用一次极小推理**探测**得到（[probeHalo]），不写死 ——
 * 以后换模型族（或模型更新）不用改代码。
 *
 * ## ⚠️ 张量类型：必须按模型声明的元素类型喂
 * AnimeJaNai 那 5 个模型的**输入/输出张量本身是 `float16`**（不是"fp16 权重 + fp32 I/O"）。
 * 喂 float32 会被 ORT 直接拒（`INVALID_ARGUMENT`）—— 这也是修复前"超分没效果"的原因之二。
 * 所以这里读 `session.inputInfo` 的 `TensorInfo.type`，fp16 走 `ShortBuffer` + 手写 half 转换。
 *
 * ## 其它
 * - **H/W 补到 16 的倍数**：AnimeJaNai 的 SPAN 图里有 `Mod`/`Shape`/`Reshape` 做的内部分块
 *   （unshuffle），不是 16 倍数时最后一块读越界。补边已按 16 对齐。
 * - **内存上限** [MAX_INPUT_PIXELS]：一次推理要在堆上放 `3×输入` 的 float + `3×(2x输出)` 的
 *   输出；2x 时输出是输入的 4 倍。不加限制，A4 页面会直接 OOM —— 超了就**返回 null 不超分**。
 * - **失败一律静默降级**（返回 null）：超分是锦上添花，绝不因为它让用户翻不了页。
 */
class AnimeJaNaiEngine(private val modelFile: File) : SuperResolutionEngine {

    override val scale: Int = 2

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String = "input"

    /** 模型输入/输出是否是 float16（AnimeJaNai 那 5 个是） */
    private var fp16: Boolean = false

    /**
     * 探测得到的「输出比 `2 × 输入` 少多少输出像素」。0 = 模型不裁边框（AnimeJaNai）。
     * cunet = 72、swin_unet = 32。**必须是 4 的倍数**，否则无法按整数像素裁出精确 2x。
     */
    private var haloOutPx: Int = 0

    /** 输入像素上限（2x 后是 4 倍）：2.5MP ≈ 1500×1667，输出 10MP / 40MB bitmap */
    override val maxInputPixels: Long = 2_500_000L

    /**
     * 上一次 [upscale] 失败的技术细节（成功时置回 null）。
     *
     * ⚠️ 用**短 ASCII 句**：它要原样进日志、也可能被用户"点一下复制"发出来，不做本地化最好定位。
     * ⚠️ 只在"同一线程、期间没有别的调用"时有效 —— 超分与 OCR 共用 `OcrLock`，
     *    同一时刻全项目只有一次超分在跑，所以这个约束成立。
     */
    private var failDetail: String? = null

    override fun lastFailDetail(): String? = failDetail

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
            fp16 = readIsFp16(s)
            haloOutPx = probeHalo(s)
            if (haloOutPx % 4 != 0) {
                // 无法按整数源像素裁出精确 2x → 契约保证不了，宁可不超分
                LogCollector.e(TAG, "halo=$haloOutPx 不是 4 的倍数，无法保证精确 ${scale}x：${modelFile.name}")
                release()
                return false
            }
            LogCollector.d(TAG, "初始化完成: ${modelFile.name}, input=$inputName, fp16=$fp16, " +
                    "halo=${haloOutPx}px(${haloOutPx / 4}/边), threads=${Runtime.getRuntime().availableProcessors().coerceIn(1, 4)}")
            true
        } catch (e: Throwable) {
            LogCollector.e(TAG, "初始化失败: ${modelFile.name}", e)
            release()
            false
        }
    }

    private fun readIsFp16(s: OrtSession): Boolean = try {
        val info = s.inputInfo[inputName]?.info
        (info as? TensorInfo)?.type == OnnxJavaType.FLOAT16
    } catch (e: Throwable) {
        LogCollector.w(TAG, "读取输入类型失败，按 float32 处理: ${e.message}")
        false
    }

    /**
     * 用一次极小推理探测边框裁剪量：喂 `PROBE×PROBE`（16 对齐）空图，
     * `halo = 2*PROBE - 实际输出边长`。
     *
     * 探测失败（异常/输出异常）→ 返回 0（按"模型不裁边框"处理）并记 W 日志；
     * 后续推理若因此对不上，[upscale] 里的尺寸校验会把它拦下来。
     */
    private fun probeHalo(s: OrtSession): Int {
        val e = env ?: return 0
        return try {
            val n = PROBE
            val buf = allocateInput(n, n)
            val t = createInputTensor(e, buf, n, n)
            val r = s.run(mapOf(inputName to t))
            try {
                val out = r[0] as? OnnxTensor
                val ow = out?.info?.shape?.getOrNull(3)?.toInt() ?: -1
                val halo = n * scale - ow
                if (halo < 0 || halo >= n * scale / 2) {
                    LogCollector.w(TAG, "halo 探测异常(输出宽=$ow, 探测输入=$n)，按 0 处理: ${modelFile.name}")
                    0
                } else {
                    halo
                }
            } finally {
                runCatching { t.close() }
                runCatching { r.close() }
            }
        } catch (e2: Throwable) {
            LogCollector.w(TAG, "halo 探测失败，按 0 处理: ${e2.message}")
            0
        }
    }

    override fun upscale(src: Bitmap): Bitmap? {
        failDetail = null
        val e = env ?: run { failDetail = "engine not initialized (env=null)"; return null }
        val s = session ?: run { failDetail = "engine not initialized (session=null)"; return null }

        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) {
            failDetail = "empty source ${w}x$h"
            return null
        }

        // 上限守卫：宁可原图，也不要把手机打 OOM（调用方已按 maxInputPixels 前置判过，这里兜底）
        if (w.toLong() * h.toLong() > maxInputPixels) {
            LogCollector.d(TAG, "跳过超分：输入 ${w}x$h 超过 ${maxInputPixels / 1000}K 像素上限")
            failDetail = "source ${w}x$h > $maxInputPixels px"
            return null
        }

        // 四边各补 PAD，再整体对齐到 16 的倍数（多出来的补在右下角）
        val iw = align16(w + PAD * 2)
        val ih = align16(h + PAD * 2)

        // halo 是"输出像素"口径；每边裁掉的源像素 = halo/4（halo%4==0 已由 initialize 保证）
        val haloPerSideSrc = haloOutPx / 4
        val padLeft = PAD
        val padTop = PAD
        if (padLeft <= haloPerSideSrc || padTop <= haloPerSideSrc) {
            LogCollector.e(TAG, "补边不足（pad=$PAD, halo/边=$haloPerSideSrc）: ${modelFile.name}")
            failDetail = "model halo ${haloPerSideSrc}px/side exceeds padding ${PAD}px"
            return null
        }
        // 目标窗口在【补边后输出】里的起点：输出列 0 ↔ 补边后源列 haloPerSideSrc
        val startX = 2 * (padLeft - haloPerSideSrc)
        val startY = 2 * (padTop - haloPerSideSrc)

        val inBuf = allocateInput(iw, ih)
        try {
            fillInput(src, inBuf, iw, ih, w, h)
            inBuf.rewind()

            var tensor: OnnxTensor? = null
            var result: OrtSession.Result? = null
            try {
                tensor = createInputTensor(e, inBuf, iw, ih)
                result = s.run(mapOf(inputName to tensor))
                val out = result[0] as? OnnxTensor ?: run {
                    LogCollector.e(TAG, "输出不是张量: ${result[0]?.javaClass?.simpleName}")
                    failDetail = "unexpected output type: ${result[0]?.javaClass?.simpleName}"
                    return null
                }
                val shape = out.info.shape
                val ow = shape.getOrNull(3)?.toInt() ?: 0
                val oh = shape.getOrNull(2)?.toInt() ?: 0

                // 硬校验：输出必须正好等于 2×补边尺寸 − halo（否则说明模型的裁剪行为与探测不一致）
                val expectW = iw * scale - haloOutPx
                val expectH = ih * scale - haloOutPx
                if (ow != expectW || oh != expectH) {
                    LogCollector.e(TAG, "输出尺寸不符: 实际 ${ow}x$oh，期望 ${expectW}x$expectH " +
                            "(iw=$iw ih=$ih halo=$haloOutPx) —— 拒绝产出（保不住精确 ${scale}x 契约）")
                    failDetail = "output ${ow}x$oh, expected ${expectW}x$expectH (iw=$iw ih=$ih halo=$haloOutPx)"
                    return null
                }
                val owOut = w * scale
                val ohOut = h * scale
                if (startX < 0 || startY < 0 || startX + owOut > ow || startY + ohOut > oh) {
                    LogCollector.e(TAG, "裁剪窗口越界: start=($startX,$startY) 目标=${owOut}x$ohOut 输出=${ow}x$oh")
                    failDetail = "crop out of range: start=($startX,$startY) target=${owOut}x$ohOut out=${ow}x$oh"
                    return null
                }

                val outPx = IntArray(owOut * ohOut)
                val plane = ow * oh
                if (fp16) {
                    val sb = out.shortBuffer
                    readPlaneFp16(sb, 0, startX, startY, ow, owOut, ohOut, outPx, 16)
                    readPlaneFp16(sb, plane, startX, startY, ow, owOut, ohOut, outPx, 8)
                    readPlaneFp16(sb, plane * 2, startX, startY, ow, owOut, ohOut, outPx, 0)
                } else {
                    val fb = out.floatBuffer
                    readPlaneFp32(fb, 0, startX, startY, ow, owOut, ohOut, outPx, 16)
                    readPlaneFp32(fb, plane, startX, startY, ow, owOut, ohOut, outPx, 8)
                    readPlaneFp32(fb, plane * 2, startX, startY, ow, owOut, ohOut, outPx, 0)
                }
                return Bitmap.createBitmap(owOut, ohOut, Bitmap.Config.ARGB_8888).apply {
                    setPixels(outPx, 0, owOut, 0, 0, owOut, ohOut)
                }
            } finally {
                runCatching { tensor?.close() }
                runCatching { result?.close() }
            }
        } catch (t: Throwable) {
            // 超分失败必须静默降级：调用方拿 null 用原图，绝不因为它翻不了页
            LogCollector.e(TAG, "推理失败 (${w}x$h, fp16=$fp16, ${modelFile.name})", t)
            failDetail = "inference threw ${t.javaClass.simpleName}: ${t.message}"
            return null
        }
    }

    // ---------- 张量读写 ----------

    /** 分配 NCHW 输入缓冲（fp16 用 ShortBuffer，否则 FloatBuffer），容量 = 3×iw×ih */
    private fun allocateInput(iw: Int, ih: Int): ByteBuffer {
        val bytesPer = if (fp16) 2 else 4
        return ByteBuffer.allocateDirect(iw * ih * 3 * bytesPer).order(ByteOrder.nativeOrder())
    }

    private fun createInputTensor(e: OrtEnvironment, buf: ByteBuffer, iw: Int, ih: Int): OnnxTensor {
        val shape = longArrayOf(1, 3, ih.toLong(), iw.toLong())
        return if (fp16) OnnxTensor.createTensor(e, buf.asShortBuffer(), shape)
        else OnnxTensor.createTensor(e, buf.asFloatBuffer(), shape)
    }

    /**
     * 把 [src] 写进 NCHW 缓冲：NCHW 三段各填一遍（R/G/B），
     * 右/下与四边的补齐一律**边缘像素复制** —— 补黑边会被模型放大成可见边框。
     */
    private fun fillInput(src: Bitmap, buf: ByteBuffer, iw: Int, ih: Int, w: Int, h: Int) {
        val argb = IntArray(w * h)
        src.getPixels(argb, 0, w, 0, 0, w, h)
        val plane = iw * ih
        val fb = if (fp16) null else buf.asFloatBuffer()
        val sb = if (fp16) buf.asShortBuffer() else null
        for (c in 0 until 3) {
            val shift = when (c) { 0 -> 16; 1 -> 8; else -> 0 }
            var y = 0
            while (y < ih) {
                // 上下都向外复制边缘行
                val srcY = (y - PAD).coerceIn(0, h - 1)
                val rowBase = srcY * w
                var x = 0
                while (x < iw) {
                    val srcX = (x - PAD).coerceIn(0, w - 1)
                    val v = ((argb[rowBase + srcX] shr shift) and 0xFF) / 255f
                    val idx = c * plane + y * iw + x
                    if (fp16) sb!!.put(idx, floatToHalf(v)) else fb!!.put(idx, v)
                    x++
                }
                y++
            }
        }
    }

    private inline fun readPlaneFp32(
        fb: java.nio.FloatBuffer, base: Int,
        startX: Int, startY: Int, ow: Int, owOut: Int, ohOut: Int, outPx: IntArray, shift: Int
    ) {
        for (y in 0 until ohOut) {
            val srcRow = base + (startY + y) * ow + startX
            val dstRow = y * owOut
            for (x in 0 until owOut) {
                val v = (fb.get(srcRow + x) * 255f + 0.5f).toInt().coerceIn(0, 255)
                outPx[dstRow + x] = outPx[dstRow + x] or (v shl shift) or ALPHA
            }
        }
    }

    private inline fun readPlaneFp16(
        sb: java.nio.ShortBuffer, base: Int,
        startX: Int, startY: Int, ow: Int, owOut: Int, ohOut: Int, outPx: IntArray, shift: Int
    ) {
        for (y in 0 until ohOut) {
            val srcRow = base + (startY + y) * ow + startX
            val dstRow = y * owOut
            for (x in 0 until owOut) {
                val v = (halfToFloat(sb.get(srcRow + x)) * 255f + 0.5f).toInt().coerceIn(0, 255)
                outPx[dstRow + x] = outPx[dstRow + x] or (v shl shift) or ALPHA
            }
        }
    }

    override fun release() {
        runCatching { session?.close() }
        session = null
        env = null
    }

    private companion object {
        const val TAG = "AnimeJaNaiEngine"

        /** 四边各补的源像素数。必须 > 任何模型的 halo/边（实测最大 18/边），且留足余量 */
        const val PAD = 48

        const val PROBE = 64
        const val ALPHA = 0xFF shl 24

        fun align16(v: Int): Int = ((v + 15) / 16) * 16

        /** IEEE 754 half 的位运算转换（值域 0..1，但按通用实现写以免以后喂别的范围） */
        fun floatToHalf(f: Float): Short {
            val bits = java.lang.Float.floatToIntBits(f)
            val sign = (bits ushr 16) and 0x8000
            val exp = (bits ushr 23) and 0xFF
            val mant = bits and 0x7FFFFF
            if (exp == 0xFF) return (sign or 0x7C00 or (if (mant != 0) 0x200 else 0)).toShort()
            val e = exp - 127 + 15
            if (e >= 0x1F) return (sign or 0x7C00).toShort()
            if (e <= 0) {
                if (e < -10) return sign.toShort()
                val m = mant or 0x800000
                val shift = 14 - e
                var half = m ushr shift
                if ((m ushr (shift - 1)) and 1 == 1) half += 1
                return (sign or half).toShort()
            }
            var m = mant ushr 13
            if ((mant ushr 12) and 1 == 1) {
                m += 1
                if (m == 0x400) { m = 0; return (sign or ((e + 1) shl 10)).toShort() }
            }
            return (sign or (e shl 10) or m).toShort()
        }

        fun halfToFloat(h: Short): Float {
            val bits = h.toInt() and 0xFFFF
            val sign = (bits and 0x8000) shl 16
            val exp = (bits ushr 10) and 0x1F
            var mant = bits and 0x3FF
            return when {
                exp == 0 -> {
                    if (mant == 0) java.lang.Float.intBitsToFloat(sign)
                    else {
                        // 次正规数：规格化
                        while (mant and 0x400 == 0) { mant = mant shl 1 }
                        mant = mant and 0x3FF
                        java.lang.Float.intBitsToFloat(sign or ((127 - 15) shl 23) or (mant shl 13))
                    }
                }
                exp == 0x1F -> java.lang.Float.intBitsToFloat(sign or 0x7F800000 or (mant shl 13))
                else -> java.lang.Float.intBitsToFloat(sign or ((exp - 15 + 127) shl 23) or (mant shl 13))
            }
        }
    }
}
