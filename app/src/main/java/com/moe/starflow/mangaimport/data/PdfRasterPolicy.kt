package com.moe.starflow.mangaimport.data

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * PDF 页栅格化的**唯一分辨率口径**（纯函数，JVM 单测覆盖）。
 *
 * ## 为什么必须"唯一"
 * 气泡坐标、原文/译文、超分输入输出、导出，全都活在**同一个像素空间**里。
 * PDF 与图片/zip 最大的不同是：图片有一个"原生尺寸"（文件里写死的），而 PDF 页只有
 * **点（pt）尺寸**，像素尺寸是**我们自己选的**。所以一旦两处算法不一致，
 * 结果就是"OCR 认出来的框画在别的地方" —— 而且看起来像随机错位，极难排查。
 *
 * ⇒ 任何需要"这一页多少像素"的地方（`ReaderPageSource.originalWidth/Height`、
 *   `loadFull`、`loadWebtoon`、`loadThumb`、导出）**都必须调这里**，不许各自乘系数。
 *
 * ## 口径
 * - 基准 **300 DPI**（`RASTER_DPI`）：扫描漫画的常见分辨率，也是 OCR 的舒适区
 *   （A5 页 ≈ 1748×2480，B5 页 ≈ 2150×3035）
 * - 长边上限 [MAX_LONG_SIDE]、总像素上限 [MAX_PIXELS]：防超大页 OOM；
 *   连续滚动的上屏尺寸由 [webtoonSize] 按目标宽度算出（与 [MAX_PIXELS] 同口径）
 * - 长边下限 [MIN_LONG_SIDE]：页面 pt 很小（异常文件）时给 OCR 一个最低可用分辨率
 * - 同一页 pt 尺寸 → **永远同一个像素尺寸**（可重入、可复现，与设备无关）
 */
object PdfRasterPolicy {

    /** 栅格化基准 DPI。改它 = 改所有 PDF 页的像素空间（老译文的坐标会全部失配）。 */
    const val RASTER_DPI: Int = 300

    private const val PT_PER_INCH: Double = 72.0

    /** 单页长边像素上限（与 [MAX_PIXELS] 一起防 OOM）。 */
    const val MAX_LONG_SIDE: Int = 4096

    /** 单页总像素上限（≈48MB ARGB），与阅读器采样解码同一口径。 */
    const val MAX_PIXELS: Int = 12_000_000

    /**
     * 连续滚动单页的**绝对高度上限**（相对目标宽）：`目标宽 × 12`。
     *
     * 只防异常页（宽高比读错/超长条）把位图和行高撑爆；正常漫画页远低于这个值。
     * 与 [MAX_PIXELS] 一起约束 [webtoonSize]。
     */
    const val MAX_WEBTOON_ASPECT: Int = 12

    /** 长边像素下限：异常小的页面也要保证 OCR 有字可认。 */
    const val MIN_LONG_SIDE: Int = 600

    /**
     * 权威尺寸位图的缓存预算。
     *
     * ⚠️ **必须 ≥ 单页最大字节数**（[MAX_PIXELS] × 4B = 48MB），否则 `LruCache` 会出现
     * "刚放进去就被自己挤出来"——A4 页 2480×3508 = **34.8MB**，用 32MB 的预算就等于**缓存恒不命中**，
     * 同一页在一次"显示 + OCR + 超分 + 导出"里被重渲 2~4 次（每次 300~600ms），而且每次都要抢渲染锁。
     * 取 64MB：覆盖上限页（48MB）+ 一张 A5（17MB），与既有预算同量级
     * （webtoon 采样缓存 60MB、译文渲染缓存 100MB）。
     * 守卫：`PdfRasterPolicyTest.theCacheBudgetCanHoldTheLargestPage`。
     */
    const val CANONICAL_CACHE_BUDGET_BYTES: Int = 64 * 1024 * 1024

    /**
     * 这一页的**权威像素尺寸**（`[宽, 高]`，ARGB_8888）。
     *
     * @param pageWidthPt `PdfRenderer.Page.width`（点，1/72 英寸）
     * @param pageHeightPt `PdfRenderer.Page.height`（点）
     */
    fun canonicalSize(pageWidthPt: Int, pageHeightPt: Int): IntArray {
        val w = pageWidthPt.coerceAtLeast(1).toDouble()
        val h = pageHeightPt.coerceAtLeast(1).toDouble()
        val longPt = maxOf(w, h)
        var scale = RASTER_DPI / PT_PER_INCH
        // ① 长边上限
        if (longPt * scale > MAX_LONG_SIDE) scale = MAX_LONG_SIDE / longPt
        // ② 总像素上限（在①之后再压，绝不放宽①）
        val pixels = (w * scale) * (h * scale)
        if (pixels > MAX_PIXELS) scale *= sqrt(MAX_PIXELS / pixels)
        // ③ 长边下限（只在页面小得离谱时才放大）
        if (longPt * scale < MIN_LONG_SIDE) scale = MIN_LONG_SIDE / longPt
        return intArrayOf(
            (w * scale).roundToInt().coerceAtLeast(1),
            (h * scale).roundToInt().coerceAtLeast(1),
        )
    }

    /** [canonicalSize] 的便捷重载：入参是 `[宽pt, 高pt]`。 */
    fun canonicalSizeOf(pageSizePt: IntArray): IntArray =
        canonicalSize(pageSizePt.getOrElse(0) { 0 }, pageSizePt.getOrElse(1) { 0 })

    /**
     * 按整数采样率缩小（缩略图用）。
     *
     * ⚠️ 只给缩略图这类"固定 1/2/4"的场景用；**连续滚动的上屏图不要用它**
     *    （2 的幂会把宽于屏幕的页砍成半宽，见 [webtoonSize]）。
     */
    fun downscale(canonical: IntArray, sample: Int): IntArray {
        val s = sample.coerceAtLeast(1)
        val w = canonical.getOrElse(0) { 1 }.coerceAtLeast(1)
        val h = canonical.getOrElse(1) { 1 }.coerceAtLeast(1)
        return intArrayOf((w / s).coerceAtLeast(1), (h / s).coerceAtLeast(1))
    }

    /**
     * 连续滚动（Webtoon）一页在目标宽度下**应该解码成多大**（`[宽, 高]`）。
     *
     * 与 [canonicalSize] 的区别：这里不是"权威尺寸"，而是**上屏尺寸**，所以：
     * - 宽 = `min(原宽, 目标宽)`，**只缩不放**；
     * - 高按原宽高比；
     * - 超过内存上限时**按比例**继续缩小（[MAX_WEBTOON_ASPECT] 的绝对高 + [MAX_PIXELS] 总像素）。
     *
     * ⚠️ **不要退回"2 的幂采样率"**（曾经的 `sampleFor`）：`BitmapFactory.inSampleSize` 只有
     * 1/2/4…，原图 1600 宽、目标 1220 时会解成 800 宽，再被 ImageView 拉回 1220 —— 1.53 倍
     * 插值放大，整页发虚（图源明明比屏幕宽）。这里给出精确尺寸：解码侧先按"≥ 该宽"的幂解码，
     * 再精确缩到该尺寸。
     *
     * ⚠️ 这是**唯一**的上屏尺寸判据：图片路径（`ReaderPageSource.loadWebtoon`）与 PDF 路径
     * （`PdfPageRenderer.renderSampledToWidth`）都调它 —— 两边各写一份的话，同一页算出的尺寸
     * 会不同，译图的气泡缩放系数也就对不上。守卫：`PdfRasterPolicyTest`。
     */
    fun webtoonSize(width: Int, height: Int, targetWidth: Int): IntArray {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val target = targetWidth.coerceAtLeast(1)
        val maxHeight = target.toLong() * MAX_WEBTOON_ASPECT
        var outW = minOf(w, target)
        var outH = (h.toLong() * outW / w).coerceAtLeast(1L).toInt()
        var scale = 1.0
        if (outH > maxHeight) scale = minOf(scale, maxHeight.toDouble() / outH)
        val pixels = outW.toLong() * outH
        if (pixels > MAX_PIXELS) scale = minOf(scale, sqrt(MAX_PIXELS.toDouble() / pixels))
        if (scale < 1.0) {
            outW = (outW * scale).toInt().coerceAtLeast(1)
            outH = (h.toLong() * outW / w).coerceAtLeast(1L).toInt()
            // 取整后可能又踩线，再各收一次（最多两轮，必然收敛）
            if (outH > maxHeight) {
                outW = (outW.toLong() * maxHeight / outH).toInt().coerceAtLeast(1)
                outH = (h.toLong() * outW / w).coerceAtLeast(1L).toInt()
            }
            if (outW.toLong() * outH > MAX_PIXELS) {
                val shrink = sqrt(MAX_PIXELS.toDouble() / (outW.toLong() * outH))
                outW = (outW * shrink).toInt().coerceAtLeast(1)
                outH = (h.toLong() * outW / w).coerceAtLeast(1L).toInt()
            }
        }
        return intArrayOf(outW, outH)
    }

    fun pixelCount(size: IntArray): Int =
        size.getOrElse(0) { 0 }.coerceAtLeast(0) * size.getOrElse(1) { 0 }.coerceAtLeast(0)
}
