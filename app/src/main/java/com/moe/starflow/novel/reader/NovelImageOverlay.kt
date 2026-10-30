package com.moe.starflow.novel.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.LruCache
import androidx.preference.PreferenceManager
import com.moe.starflow.data.TranslationCacheManager
import com.moe.starflow.manga.render.OverlayRenderer
import com.moe.starflow.manga.types.TextDirection
import com.moe.starflow.manga.types.TranslatedBubble
import com.moe.starflow.novel.translate.NovelImageTranslation
import com.moe.starflow.novel.translate.NovelImageVisual
import com.moe.starflow.utils.CustomPreference

/**
 * 插图的**译文覆盖层**（把载荷画回图片上）与它的渲染缓存。
 *
 * 逐项对齐漫画阅读器的渲染出口（`ReaderTranslationController.renderBubbles`）：
 * ```
 * 译文态 = OverlayRenderer.renderOverlay(useOriginalText = false)
 * 原文态 = OverlayRenderer.renderOverlay(useOriginalText = true)   ← 把 OCR 原文画回原位
 * 纯图态 = 不调 renderOverlay，直接画底图
 * ```
 * 落点（渲染缓存的键）比漫画多一个维度：漫画一页只有"哪一页 + 哪个态"，而插图还有
 * **载荷版本**（重新翻译后矩形/文字都会变）—— 少了它重翻完屏幕上还是旧译文。
 *
 * ⚠️ **`renderScale` 按"这张图最终画多大"给**（2026-10-04 真机反馈：小尺寸插图的译文糊成一团）。
 * 原来的取舍写的是"统一 1f、省内存"，但那只对"底图 ≥ 显示尺寸"的图成立：插图允许放大到 3×
 * （`NovelImageMetrics.MAX_UPSCALE`），一张 400px 宽的小图被拉到 ~1000px 显示时，
 * 覆盖层仍按 400px 光栅化 → 拉上去的**字形是插值出来的像素** → 糊。
 * 漫画那边早就踩过同一条（`manga/CLAUDE.md`「低分辨率漫画译文模糊」）：字形的清晰度由
 * **落笔时的 CTM** 决定，所以只要让文字在"已被放大的坐标系"里画，就自然清晰。
 * 现在按 [renderScaleFor] 给（夹在 `[1, 2]`，×2 是 4 倍像素的内存代价）。
 */
internal object NovelImageOverlay {

    /** 渲染结果缓存上限：一屏最多一两张插图 × 三个态，48MB 足够。 */
    private const val MAX_CACHE_BYTES = 48 * 1024 * 1024

    /**
     * 覆盖层的**超采样上限**（与漫画同一个值，见 `OverlayRenderer.MAX_RENDER_SCALE`）。
     *
     * ⚠️ 不能再往上抬：倍率是**平方级**的内存代价（×2 = 4 倍像素），而输出还进 48MB 的 LRU。
     */
    private const val MAX_RENDER_SCALE = 2f

    /**
     * 覆盖层该按多少倍率光栅化（纯函数，可普通 JVM 单测）。
     *
     * - `drawnWidth <= baseWidth` → **1f**（底图本来就够大，画的时候是缩小，文字已经清晰）
     * - `drawnWidth > baseWidth`（小图被放大显示）→ 按比例放大，**封顶 [MAX_RENDER_SCALE]**
     */
    internal fun renderScaleFor(drawnWidth: Int, baseWidth: Int): Float =
        (drawnWidth.toFloat() / baseWidth.coerceAtLeast(1)).coerceIn(1f, MAX_RENDER_SCALE)

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 与 [NovelImageStore] 一起在阅读器退出时释放（它们成对占用内存）。 */
    fun clear() = cache.evictAll()

    /**
     * 取某段插图在 [visual] 态下要画的位图（缓存未命中就现渲）。
     *
     * 返回 null 表示**这一态不需要覆盖层**（纯图态）或渲染不出来（底图解码失败）——
     * 调用方回落成"直接画底图"。
     *
     * @param targetWidth 这一张图在屏幕上的**绘制宽度**（渲染分辨率就按它，缩放不变形）
     */
    fun of(
        context: Context,
        image: NovelImage,
        payload: NovelImageTranslation,
        visual: NovelImageVisual,
        targetWidth: Int,
    ): Bitmap? {
        if (visual == NovelImageVisual.PLAIN) return null
        // 图片接口只返回文本：没有坐标可画，阅读器用文本弹窗展示。
        if (payload.blocks.isEmpty()) return null
        // ⚠️ 底图先取（它自己有缓存，命中就是一次 LRU 查找）：`renderScale` 由**实际底图宽**算出来，
        // 而底图宽取决于采样率（解码缓存按文件路径存、可能先被别的目标宽解过）—— 把倍率写进键里，
        // 才不会拿"上一次用另一个倍率渲的那张"顶上（那会糊一点点，且很难查）。
        val base = NovelImageStore.bitmapOf(image, targetWidth) ?: return null
        // 文字按**最终绘制尺寸**光栅化（见类注释）：底图比目标小 = 这张图要被放大显示
        val renderScale = renderScaleFor(targetWidth, base.width)
        val key = "${image.file.absolutePath}|${visual.name}|${payload.hashCode()}|$targetWidth|$renderScale"
        cache.get(key)?.let { if (!it.isRecycled) return it }
        // ⚠️ 载荷的坐标是**原图像素**（OCR 时已换算回原图坐标系），而底图是按显示宽度
        // **采样解码**出来的（原图 2000 宽、这里只有 900）—— 矩形与字号都要按同一个倍率缩回去，
        // 否则译文会整体偏向图的右下、大半画在图外（`NovelImageOverlayTest` 的缩放假守卫卡这条）。
        val scale = base.width.toFloat() / image.width.coerceAtLeast(1)
        val rendered = try {
            val cfg = TranslationCacheManager(context).getOverlayConfig(
                PreferenceManager.getDefaultSharedPreferences(context),
            )
            OverlayRenderer.renderOverlay(
                original = base,
                regions = bubblesOf(
                    payload,
                    useOriginal = visual == NovelImageVisual.ORIGINAL,
                    scale = scale,
                    boundsW = base.width,
                    boundsH = base.height,
                ),
                fontSize = cfg.fontSize,
                autoFit = cfg.autoFit,
                textColor = cfg.textColor,
                bgColor = cfg.bgColor,
                useOriginalText = visual == NovelImageVisual.ORIGINAL,
                verticalDirection = cfg.textDirection,
                fontTypeface = OverlayRenderer.loadResultTypeface(context, CustomPreference.getInstance(context)),
                align = cfg.horizontalAlign,
                trackingRatio = cfg.trackingRatio,
                leadingRatio = cfg.leadingRatio,
                mergeOverlap = cfg.mergeOverlap,
                replacementRules = cfg.replacementRules,
                density = context.resources.displayMetrics.density,
                renderScale = renderScale,
                baseScale = 1f,
            )
        } catch (e: OutOfMemoryError) {
            cache.evictAll()
            null
        } catch (e: Exception) {
            null
        }
        if (rendered != null) cache.put(key, rendered)
        return rendered
    }

    /**
     * 载荷 → 漫画那套 `TranslatedBubble`（渲染器的输入）。
     *
     * ⚠️ **必须按底图倍率缩放**：载荷里的矩形/字号是**原图像素**，而底图通常是采样解码的
     * （`NovelImageStore.bitmapOf(image, 显示宽度)`）—— 不缩就是"译文整体偏移 + 大半在图外"。
     *
     * ⚠️ **坐标要夹进底图内**（`boundsW/H`）：越界矩形会让 `OverlayRenderer` 的排版内核
     * 算出荒唐的白块尺寸而抛异常（真机上是"这张图永远不显示覆盖层"）。载荷来自历史数据/手改过，
     * 不能假设它一定合法；夹完仍是空矩形（或退化）的直接丢掉。
     *
     * ⚠️ **竖排只认载荷里的 `vertical`**（OCR 时判定的），不读设置里的"竖排方向"：
     * 那个设置管的是**列序**（右→左 / 左→右），小说插图里没有"整页竖排阅读顺序"这回事，
     * 硬套会把本来横排的插图文字按列画。列序统一按日漫惯例（右→左）。
     */
    internal fun bubblesOf(
        payload: NovelImageTranslation,
        useOriginal: Boolean,
        /** 原图 → 底图 的倍率（1f = 底图就是原图尺寸）。 */
        scale: Float = 1f,
        boundsW: Int = Int.MAX_VALUE,
        boundsH: Int = Int.MAX_VALUE,
    ): List<TranslatedBubble> =
        payload.blocks.mapNotNull { b ->
            val left = (b.left * scale).toInt().coerceIn(0, (boundsW - 1).coerceAtLeast(0))
            val top = (b.top * scale).toInt().coerceIn(0, (boundsH - 1).coerceAtLeast(0))
            val right = (b.right * scale).toInt().coerceIn(left + 1, boundsW.coerceAtLeast(left + 1))
            val bottom = (b.bottom * scale).toInt().coerceIn(top + 1, boundsH.coerceAtLeast(top + 1))
            if (right <= left || bottom <= top) return@mapNotNull null
            TranslatedBubble(
                rect = Rect(left, top, right, bottom),
                originalText = b.original,
                translatedText = if (useOriginal) b.original else b.translated,
                backgroundColor = Color.argb(200, 255, 255, 255),
                fontSize = (b.fontSize * scale).coerceAtLeast(1f),
                direction = if (b.vertical) TextDirection.VERTICAL_RL else TextDirection.HORIZONTAL,
            )
        }
}
