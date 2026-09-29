package com.moe.starflow.sr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.nio.ByteBuffer

/**
 * 基准用的**测试图**（用户口径 2026-10：「用标注 1K 漫画测试每个模型」）。
 *
 * 把一张真实漫画页（宽 1000 = "1K"）缩到目标宽，然后填进 NCHW 缓冲。
 * 与产品路径同一套补齐口径：四边各补 [PAD]，再对齐到 16 的倍数，**边缘像素复制**
 * （补黑边会被模型放大成可见边框）。
 *
 * ⚠️ 为什么要真图而不是全 0：卷积耗时本身就与内容基本无关，但真图能同时暴露
 * 数据相关的问题（denormal、极端亮度）；而且用户明确要求用真实 1K 漫画页测。
 */
object SrBenchInput {

    const val PAD = 48

    /** 目标宽（"1K 漫画"）。高按原图长宽比推出来，见 [width]/[height]。 */
    private const val TARGET_W = 1000

    @Volatile
    private var bmp: Bitmap? = null

    @Volatile
    var sourceLabel: String = "全 0（没找到测试图）"
        private set

    /** 实际送入网络的源尺寸（不含补边）。 */
    @Volatile
    var width: Int = TARGET_W
        private set

    @Volatile
    var height: Int = 1456
        private set

    /** 读 `files/sr_bench_input.{png,webp,jpg}` 并缩到 [TARGET_W] 宽。 */
    fun load(app: Context) {
        val base = app.getExternalFilesDir(null) ?: return
        for (n in listOf("sr_bench_input.png", "sr_bench_input.webp", "sr_bench_input.jpg")) {
            val f = File(base, n)
            if (!f.isFile) continue
            val raw = BitmapFactory.decodeFile(f.absolutePath) ?: continue
            val w = if (raw.width > TARGET_W) TARGET_W else raw.width
            val h = (raw.height.toLong() * w / raw.width).toInt().coerceAtLeast(1)
            val scaled = if (w == raw.width) raw else Bitmap.createScaledBitmap(raw, w, h, true)
            if (scaled !== raw) raw.recycle()
            bmp = scaled
            width = w
            height = h
            sourceLabel = "${f.name}（原 ${raw.width}x${raw.height} → ${w}x$h）"
            return
        }
        width = TARGET_W
        height = 1456
    }

    fun recycle() {
        bmp?.let { if (!it.isRecycled) it.recycle() }
        bmp = null
    }

    fun align16(v: Int) = ((v + 15) / 16) * 16

    /** 按当前测试图填 NCHW 缓冲（fp32 / fp16）。 */
    fun fill(buf: ByteBuffer, iw: Int, ih: Int, fp16: Boolean) {
        val src = bmp
        val w = width
        val h = height
        val argb = IntArray(w * h)
        src?.getPixels(argb, 0, w, 0, 0, w, h)
        val plane = iw * ih
        val fb = if (fp16) null else buf.asFloatBuffer()
        val sb = if (fp16) buf.asShortBuffer() else null
        for (c in 0 until 3) {
            val shift = when (c) { 0 -> 16; 1 -> 8; else -> 0 }
            var y = 0
            while (y < ih) {
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

    /** IEEE754 半精度编码（与 `AnimeJaNaiEngine` 同一实现） */
    private fun floatToHalf(f: Float): Short {
        val bits = java.lang.Float.floatToIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        val exp = ((bits ushr 23) and 0xFF) - 127 + 15
        var mant = bits and 0x7FFFFF
        if (exp <= 0) return sign.toShort()
        if (exp >= 31) return (sign or 0x7C00).toShort()
        mant = mant shr 13
        return (sign or (exp shl 10) or mant).toShort()
    }
}
