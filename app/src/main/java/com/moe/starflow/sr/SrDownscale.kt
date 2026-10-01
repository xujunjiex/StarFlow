package com.moe.starflow.sr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.moe.starflow.utils.LogCollector
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 大图超分前的**预处理**：把图压到"引擎吃得下 + 跑得快"的尺寸，再交给超分模型。
 *
 * ## 为什么（用户口径 2026-10）
 * 「对较大尺寸的图片进行超分，短边压缩对齐到 1080p 再超分……减轻超分模型的压力，
 * 同时可以快速通过超分模型放大尺寸」。一张 2000x3000 的页直接 2x 就是 12MP ——
 * 重档位要几十秒，超过像素上限的还会直接判失败。
 *
 * ## 只**压缩输入**，绝不缩产物
 * 本对象只管「喂进引擎之前」这一步。**超分产物原样落盘，不做任何额外缩放** ——
 * 用户口径：「压缩尺寸后给超分的图片，体积不能超过原来的像素和大小」说的是**输入**；
 * 而「超分后肯定比原图大啊」。产物的空间由**两道既有的闸**管住，不需要在这里再造一条：
 * 1. 引擎自己的输出上限（`NcnnSrEngine.maxOutputPixels` = 10MP，超了它直接不跑）；
 * 2. 超分缓存的**总容量**上限 + LRU 淘汰（[SrStore.manageCache]，默认 512MB）。
 *
 * ⚠️ **别再给产物加"不超过原图 N 倍"这类单页预算**（2026-10 试过一版，已删）：
 * 空间是**总容量**管的，不是单页尺寸管的；按单页倍数卡会得到自相矛盾的结果 ——
 * 小图（0.58MP → 2x 后 2.3MP = 4 倍）被砍掉一半，而大图（6MP → 压缩 → 7MP = 1.17 倍）
 * 反而一刀不砍。砍的全是最需要放大的低分辨率页。守卫：
 * `SrReaderWiringTest.theUpscaledProductIsStoredAsIs`。
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
 * ## 压缩怎么做到"不损失画质"
 * 只在**内存里**用 `Canvas` + `FILTER_BITMAP_FLAG` 缩放，**不重新编码**（没有 JPEG 二次损失）；
 * 且 [apply] **分步减半**再收到目标 —— 一步 2.8 倍下采样会漏采样，在网点/线条上出现摩尔纹。
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
     * ⚠️ 返回的尺寸**恒小于入参**（`k < 1`）—— 这是"压缩图不超过原图像素"的构造性保证。
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
