package com.moe.starflow.sr

import android.content.Context
import android.graphics.Bitmap
import androidx.preference.PreferenceManager
import com.moe.starflow.sr.anime4k.Anime4kMode
import com.moe.starflow.utils.LogCollector

/**
 * 阅读器链路对一页图片的增强入口（"先超分再翻译"的落点）。
 *
 * ## 为什么输出尺寸必须等于输入尺寸
 * `ReaderPageSource.loadFull` 出来的 bitmap 同时喂给**两个**下游：
 * 1. OCR/检测 —— 希望它更清晰（这就是超分的目的）
 * 2. 译文渲染 —— 可是 `bubbleRects` 是**持久化坐标**，之后还会在**原图**上重渲染
 *    （`PageTranslationCodec.fromRow → renderOverlay`，比如 `MangaViewerActivity`、导出、重开阅读器）
 *
 * 如果让 OCR 跑在 2x 图上，检出的框就是 2x 坐标；等以后按原图重渲染时整片译文会**成倍错位** ——
 * 而且这种错位**只有"翻回去/重开"才现形**，当页看着完全正常，是最难查的一类问题。
 *
 * 所以这里统一做：**超分 → 缩回原尺寸**。
 * 收益一点没少：OCR 引擎（PP-OCR）本来就会把输入缩放到自己的工作尺寸，真正决定识别率的是
 * **信息质量**而不是喂进去的像素多少；先放大再缩回等于一次「去噪 + 锐化 + 去 JPEG 块效应」，
 * 低分辨率/高压缩的图源正好吃这一口。同时坐标空间、内存占用、渲染路径全都不变。
 *
 * （实测口径也正是这个：`tools/sr-research` 的「缩回 PSNR」就是"超分结果缩回原尺寸再比对原图"。）
 *
 * ## 为什么要应用级 Context
 * `ReaderPageSource` 的构造点在 `MangaReaderActivity` 与 `ReaderTranslationController` 里，
 * 给它加 Context 参数要动这两个文件；这里用一个进程级持有者，避免为了一个只读的
 * `getExternalFilesDir` 去改两个大文件。**只持有 applicationContext，不会泄漏 Activity。**
 */
object SrPageEnhancer {

    private const val TAG = "SrPageEnhancer"

    @Volatile
    private var appContext: Context? = null

    /** 由 `StarFlowApplication.onCreate` 调一次（幂等） */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 增强是否可用（没初始化上下文时一律不可用 → 调用方走原图） */
    val isAvailable: Boolean get() = appContext != null

    /**
     * 影响增强结果的**全部**输入的指纹（没初始化上下文 → null）。
     *
     * 开关 / 选的模型 / Anime4K 档位 任一变化都会让它变 → 调用方据此作废缓存。
     * 用字符串而不是散着比几个字段：以后新增影响输出的设置，只要往这里加一项，
     * 所有调用点自动跟着失效，不会出现"改了设置但缓存还是旧图"。
     */
    fun signature(): String? {
        val ctx = appContext ?: return null
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        return buildString {
            append(if (SrSettings.isEnabledForReader(prefs)) '1' else '0')
            append('|').append(SrModelManager.getActiveKey(prefs)?.name ?: "-")
            append('|').append(if (Anime4kMode.isEnabled(prefs)) Anime4kMode.fromPrefs(prefs).id else "-")
        }
    }

    /**
     * 阅读器链路增强一页。
     *
     * @return **与 [src] 同尺寸**的新 bitmap；未开开关 / 没选模型且没开 Anime4K / 任何失败 → **null**
     *   （调用方直接用原图，绝不因为增强失败而翻不了页或翻不了译）
     */
    fun enhanceForReader(src: Bitmap): Bitmap? = enhance(src, forGame = false, readerSwitch = true)

    /**
     * 截图 / 录屏链路增强一张图（游戏或漫画模式）。
     *
     * @param forGame true=游戏模式，false=漫画模式。两者各自有开关，且**都受个性化里的总开关约束**
     *   （`SrSettings.isEnabledForGame/Comic` 内部已经 AND 了总开关）。
     *
     * @return **与 [src] 同尺寸**的新 bitmap；开关未开 / 无可用的增强 / 任何失败 → **null**
     *
     * ## ⚠️ 接入位置：**裁剪之后、进 OCR 之前**，绝不能挂在截图入口
     * `MangaFloatingService.processMangaScreenshot` 收到的整屏截图**同时参与 pHash 计算**，
     * 而 pHash 是缓存键（256-bit 扩展 hash）。在那一层增强 = **缓存键整体变化** →
     * 与用户已存的译文/图片缓存**全部失配**（表现为历史记录里的缓存再也命中不了、重复 OCR）。
     * 所以调用点要找「已经裁成 crop 区域、正要喂给检测/识别」的那一步。
     *
     * 也正因为本函数**保证不改尺寸**，挂在裁剪后的图上不会影响 `bubbleRects` 的坐标空间。
     */
    fun enhanceForCapture(src: Bitmap, forGame: Boolean): Bitmap? =
        enhance(src, forGame = forGame, readerSwitch = false)

    /**
     * @param readerSwitch true=走阅读器独立开关；false=走截图链路的模式开关（受总开关约束）
     */
    private fun enhance(src: Bitmap, forGame: Boolean, readerSwitch: Boolean): Bitmap? {
        val ctx = appContext ?: return null
        if (src.width <= 0 || src.height <= 0) return null
        return try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            val out = if (readerSwitch) {
                SuperResolutionEngines.upscaleForReader(ctx, prefs, src)
            } else {
                SuperResolutionEngines.upscaleIfEnabled(ctx, prefs, src, forGame = forGame)
            } ?: return null
            val w = src.width
            val h = src.height
            if (out.width == w && out.height == h) {
                out
            } else {
                // 缩回原尺寸（见类注释：坐标空间必须不变）
                val normalized = Bitmap.createScaledBitmap(out, w, h, true)
                if (normalized !== out) out.recycle()
                normalized
            }
        } catch (e: Throwable) {
            LogCollector.e(TAG, "增强失败，回退原图", e)
            null
        }
    }
}
