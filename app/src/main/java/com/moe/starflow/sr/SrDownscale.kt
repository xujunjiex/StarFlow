package com.moe.starflow.sr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.moe.starflow.utils.LogCollector
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 大图超分前的**预处理**：把图压到"引擎吃得下 + 跑得快"的尺寸，再交给超分模型。
 *
 * ## 为什么（用户口径 2026-10）
 * 「对较大尺寸的图片进行超分，短边压缩对齐到 1080p 再超分……减轻超分模型的压力，
 * 同时可以快速通过超分模型放大尺寸」。一张 2000x3000 的页直接 2x 就是 12MP ——
 * 重档位要几十秒，超过像素上限的还会直接判失败。
 *
 * ## ⚠️ 只对齐短边是**不够的**（实现时实测出来的）
 * `NcnnSrEngine.maxInputPixels = 10MP / scale²`（2x → **2.5MP**，Real-ESRGAN 4x → 0.625MP），
 * 超了这个数引擎直接判「图太大」跳过。也就是说：
 * - 2000x3000 = **6MP 的页今天就已经翻不动了**（引擎上限 2.5MP），用户看到的是「图太大无法超分」；
 * - 而"短边 1080"并不保证够小：1080x2592（1:2.4 的长条）就是 2.8MP，仍然超限；
 *   Real-ESRGAN 的 0.625MP 更是连 1080x1080 都吃不下。
 *
 * 所以 [plan] 是**两个约束一起解**：先按用户口径把短边对齐 1080，再按引擎的
 * `maxInputPixels` 继续等比缩到能吃下为止。两个都不需要缩时返回 null（调用方走原路）。
 *
 * ## 三条约束分别怎么落
 * 1. **压缩不损失画质**：只在**内存里**用 `Canvas` + `FILTER_BITMAP_FLAG` 缩放，
 *    **不重新编码**（没有 JPEG 二次损失）；且 [apply] **分步减半**再收到目标 ——
 *    一步 2.8 倍下采样会漏采样，在网点/线条上出现摩尔纹。
 * 2. **体积不超过原来的像素**：[clampToOriginalPixels] 把产物收敛到「像素数 ≤ 原图」，
 *    所以走压缩路径的结果**永远不比原图大**（落盘体积与内存都受益）。
 * 3. **输出尺寸允许 ≤ 原图** —— 因此调用方（`SrProcessor`）判「是不是真超分」时
 *    必须拿**喂给引擎的那张图**比，不能拿原图比（见那里的注释）。
 */
object SrDownscale {

    private const val TAG = "SrDownscale"

    /** 短边目标：1080p（用户口径）。 */
    const val SHORT_SIDE_TARGET = 1080

    /**
     * 拿不到引擎上限时的兜底（= ncnn 2x 的真实上限）。
     *
     * 引擎取不到（没选模型 / 文件缺失）时不拿它当失败 —— 真正的原因稍后由
     * `SuperResolutionEngines.applySteps` 给出（那里才有"没模型 / 文件不在 / 加载失败"的细分）。
     */
    const val DEFAULT_MAX_FEED_PIXELS = 2_500_000L

    data class Plan(val width: Int, val height: Int)

    /**
     * 算出压缩目标。**两个约束都要满足**；本来就不用缩 → null（调用方走原路）。
     *
     * @param maxFeedPixels 引擎能吃的最大输入像素数（`SuperResolutionEngine.maxInputPixels`）；
     *   ≤ 0 表示未知 → 用 [DEFAULT_MAX_FEED_PIXELS]。
     */
    fun plan(srcW: Int, srcH: Int, maxFeedPixels: Long = DEFAULT_MAX_FEED_PIXELS): Plan? {
        if (srcW <= 0 || srcH <= 0) return null
        val cap = if (maxFeedPixels > 0) maxFeedPixels else DEFAULT_MAX_FEED_PIXELS

        // ① 短边对齐到 1080（用户口径）——短边本来就 ≤ 1080 时不动
        val short = minOf(srcW, srcH)
        var k = if (short > SHORT_SIDE_TARGET) SHORT_SIDE_TARGET.toDouble() / short else 1.0

        // ② 再保证喂进引擎的像素数不超上限（否则引擎直接判「图太大」，超分永远出不来）
        val pixels = srcW.toDouble() * srcH * k * k
        if (pixels > cap) k *= sqrt(cap / pixels)

        if (k >= 1.0) return null
        // ⚠️ 取整必须**向下**再兜一层：`roundToInt` 会把 1020.6 抬成 1021，对 1080x2592 这种
        //    正好卡在上限边缘的尺寸就是"压完还差 429 像素"→ 引擎照样判「图太大」，白压一场。
        //    上限是引擎的硬契约（`>` 即拒绝），所以这里必须**保证 ≤**，不能"大约"。
        var w = max(1, kotlin.math.floor(srcW * k).toInt())
        var h = max(1, kotlin.math.floor(srcH * k).toInt())
        while (w.toLong() * h > cap && (w > 1 || h > 1)) {
            if (w > 1) w--
            if (h > 1) h--
        }
        return Plan(w, h)
    }

    /** 产物是否超过原图的**像素数**（超过就要收敛）。 */
    fun needsClamp(outW: Int, outH: Int, srcW: Int, srcH: Int): Boolean =
        outW > 0 && outH > 0 && srcW > 0 && srcH > 0 &&
            outW.toLong() * outH > srcW.toLong() * srcH

    /**
     * 缩放到 [plan]。**分步**：每步最多缩一半，避免一次大倍率下采样的漏采样。
     *
     * @return 缩放结果；**可能就是 `src` 本身**（尺寸已经一致时）→ 调用方要判 `!==` 再决定回收。
     *   中间产物由本函数内部回收，不会泄漏。
     */
    fun apply(src: Bitmap, plan: Plan): Bitmap {
        var cur = src
        var owned: Bitmap? = null
        while (cur.width > plan.width * 2 && cur.height > plan.height * 2) {
            val next = scaleTo(cur, max(1, cur.width / 2), max(1, cur.height / 2)) ?: break
            owned?.recycle()
            owned = next
            cur = next
        }
        if (cur.width == plan.width && cur.height == plan.height) return cur
        val out = scaleTo(cur, plan.width, plan.height) ?: cur
        // 只有"这一步产出了新图"时才回收中间产物；`out === owned` 说明缩放在这一步失败了
        if (out !== owned) owned?.recycle()
        return out
    }

    /**
     * 产物收敛：等比缩到**像素数 ≤ 原图**。
     *
     * @return 收敛后的新图；**不需要收敛时返回 null**（调用方保留原产物，不做无谓的一次缩放）。
     */
    fun clampToOriginalPixels(out: Bitmap, srcW: Int, srcH: Int): Bitmap? {
        if (!needsClamp(out.width, out.height, srcW, srcH)) return null
        val k = sqrt((srcW.toDouble() * srcH) / (out.width.toDouble() * out.height))
        val w = max(1, (out.width * k).roundToInt())
        val h = max(1, (out.height * k).roundToInt())
        LogCollector.d(TAG, "产物收敛: ${out.width}x${out.height} → ${w}x${h}（原图 ${srcW}x${srcH}）")
        return scaleTo(out, w, h)
    }

    /** 单步缩放（高质量过滤）。失败返回 null —— 超分是"锦上添花"，绝不因此抛给上层。 */
    private fun scaleTo(src: Bitmap, w: Int, h: Int): Bitmap? = try {
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { dst ->
            Canvas(dst).drawBitmap(
                src, null, Rect(0, 0, w, h),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
            )
        }
    } catch (e: Throwable) {
        LogCollector.e(TAG, "缩放失败 ${src.width}x${src.height} → ${w}x$h", e)
        null
    }
}
