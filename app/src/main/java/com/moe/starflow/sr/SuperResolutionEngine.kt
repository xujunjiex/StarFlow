package com.moe.starflow.sr

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import com.moe.starflow.download.ModelKey
import com.moe.starflow.sr.anime4k.Anime4kEngine
import com.moe.starflow.sr.anime4k.Anime4kMode
import com.moe.starflow.utils.LogCollector
import java.io.File

/**
 * 超分/增强引擎抽象。
 *
 * 两个实现：
 * - `AnimeJaNaiEngine` / waifu2x（ONNX，**scale = 2**）：真超分，要下载模型
 * - `Anime4kEngine`（GLES3 shader，**scale = 1**）：只做同分辨率线条修复/锐化，不要下载
 *
 * 引擎**只管一张 Bitmap 进、处理后的 Bitmap 出**，不碰 prefs。组合逻辑收在
 * [SuperResolutionEngines.resolveSteps]，调用点只调 `upscale*` 系列。
 */
interface SuperResolutionEngine {

    /**
     * 放大倍率。**1 表示"不放大、只处理"**（Anime4K 就是这一档）——
     * 调用方不能假定 `scale >= 2`。
     */
    val scale: Int

    /**
     * 处理一张图。
     *
     * @return 处理后的新 Bitmap；**失败一律返回 null**（不抛异常）——
     *   超分/增强是"锦上添花"的一步，失败必须让调用方无痛回退到原图。
     */
    fun upscale(src: Bitmap): Bitmap?

    fun release()
}

/**
 * 要执行的一道工序。
 *
 * ⚠️ **工序是「可叠加」的，不是二选一**（2026-10 按用户口径改）：
 * 用户明确问过「为什么 Anime4K 不能和超分一起用」—— 之前的实现把它做成了
 * 「有模型就不跑 Anime4K」的互斥选择，那是错的：
 * 二者作用不冲突（一个**放大重建**、一个**同分辨率描线**），叠起来是「先放大再锐化」，
 * 正是画质链路的常规做法。（koto 那边是**单引擎下拉**、确实互斥 —— 但那是它的引擎选择器设计，
 * 不是"二者不能共存"的技术限制。）
 */
enum class SrStep {
    /** 用下载来的超分模型放大（2x，随后立即缩回原尺寸 —— 见 [SuperResolutionEngines] 类注释） */
    SR_MODEL,

    /** 用 Anime4K 同分辨率描线/锐化 */
    ANIME4K
}

/**
 * 超分引擎的门面：算出要跑哪些工序、按选择取引擎（带缓存）、统一失败降级。
 *
 * ⚠️ **超分只服务阅读器**（2026-10 用户口径）：截图 / 录屏链路（`MangaFloatingService` /
 * `FloatingBallService`）**不做超分**，那两个服务已恢复成「裁剪后直接喂 OCR」。
 * 原先的 `upscaleIfEnabled(forGame)` + 三个开关（总开关/游戏/漫画）已整体删除，
 * 现在唯一入口是 [upscaleForReader]。
 *
 * ⚠️ **全项目只从这里拿引擎**：`SrModelManager` 只回答"选了哪个/下没下"、
 * `SrSettings` 只回答"超分开关开没开"、`Anime4kMode` 只回答"Anime4K 开关与档位"，
 * 三者的组合判断收在 [resolveSteps] 里。
 *
 * ## 产物是**显示底图**，尺寸各自保留（v2，2026-10）
 * `SR_MODEL` 产出**精确 2x**（引擎的硬契约，见 [AnimeJaNaiEngine]），`ANIME4K` 产出 1x。
 * **不再缩回原尺寸** —— v1 那个归一化是为了喂 OCR（坐标空间要等于原图），
 * 而 v2 里 OCR 永远吃原图（超分与 OCR 解耦），归一化只会把刚补出来的细节扔掉。
 *
 * 渲染时用 `baseScale = out.width / src.width` 把源坐标映射到底图（`OverlayRenderer`）。
 */
object SuperResolutionEngines {

    private const val TAG = "SuperResolution"

    @Volatile private var cachedKey: ModelKey? = null
    @Volatile private var cachedEngine: SuperResolutionEngine? = null
    @Volatile private var cachedAnime4kMode: Anime4kMode? = null
    @Volatile private var cachedAnime4k: SuperResolutionEngine? = null

    /**
     * 算出这一轮要跑的工序（**纯函数**，单测直接调）。
     *
     * ## ⚠️ 两者是**互斥**的（2026-10 用户口径）
     * 「默认用 Anime4K 无，开启超分禁用这个」——
     * - `SR_MODEL`：需要「超分开关打开」**且**「模型已选且文件在」
     * - `ANIME4K`：只在**超分不可用时**才跑（用户没下模型 / 没选模型 / 超分开关关）
     *
     * 为什么必须互斥（而不是"先放大再描线"的叠加）：
     * `Anime4kEngine.MAX_INPUT_PIXELS = 1.5MP`（`Anime4kEngine.kt:67`，超限即跳过），
     * 而 1MP 的页 2x 之后就是 **4MP** —— 在超分底图上跑 Anime4K **必被跳过**。
     * 想叠加就得抬高上限，而 2x 图的 RGBA16F 中间纹理会直接 OOM。所以按互斥处理。
     *
     * ⇒ 语义变成：**超分模型 > Anime4K**，Anime4K 是"没有模型时的基础显示层"。
     */
    fun resolveSteps(
        srEnabled: Boolean,
        srModelUsable: Boolean,
        anime4kEnabled: Boolean
    ): List<SrStep> = buildList {
        val sr = srEnabled && srModelUsable
        if (sr) add(SrStep.SR_MODEL)
        if (!sr && anime4kEnabled) add(SrStep.ANIME4K)
    }

    /** 按当前选择取超分模型引擎（同一模型复用，切模型重建）。未选/未下载/失败 → null */
    @Synchronized
    fun obtain(context: Context, prefs: SharedPreferences): SuperResolutionEngine? {
        val key = SrModelManager.getActiveKey(prefs) ?: return null
        val file = SrModelManager.modelFile(context, key)
            ?.takeIf { it.isFile && it.length() > 0L } ?: return null

        cachedEngine?.let { if (cachedKey == key) return it }
        releaseSrModel()

        val engine = createEngine(key, file) ?: return null
        cachedKey = key
        cachedEngine = engine
        LogCollector.i(TAG, "超分引擎就绪: $key (${file.name}, ${file.length() / 1024}KB)")
        return engine
    }

    private fun createEngine(key: ModelKey, file: File): SuperResolutionEngine? = try {
        when (key) {
            ModelKey.SR_ANIMEJANAI_HD_BALANCED,
            ModelKey.SR_ANIMEJANAI_HD_PERFORMANCE,
            ModelKey.SR_ANIMEJANAI_HD_SHARP1_BALANCED,
            ModelKey.SR_ANIMEJANAI_HD_SHARP1_PERFORMANCE,
            ModelKey.SR_ANIMEJANAI_SD_COMPACT,
            ModelKey.SR_WAIFU2X_CUNET_N0,
            ModelKey.SR_WAIFU2X_CUNET_N1,
            ModelKey.SR_WAIFU2X_CUNET_N2,
            ModelKey.SR_WAIFU2X_CUNET_N3,
            ModelKey.SR_WAIFU2X_SWIN_N0,
            ModelKey.SR_WAIFU2X_SWIN_N1 -> AnimeJaNaiEngine(file).also {
                if (!it.initialize()) {
                    LogCollector.e(TAG, "超分模型初始化失败: $key")
                    return null
                }
            }
            else -> null
        }
    } catch (e: Throwable) {
        LogCollector.e(TAG, "超分引擎创建异常: $key", e)
        null
    }

    /** 按当前档位取 Anime4K 引擎（换档位重建）。初始化失败 → null */
    @Synchronized
    fun obtainAnime4k(context: Context, prefs: SharedPreferences): SuperResolutionEngine? {
        val mode = Anime4kMode.fromPrefs(prefs)
        cachedAnime4k?.let { if (cachedAnime4kMode == mode) return it }
        releaseAnime4k()

        val engine = try {
            Anime4kEngine(context.applicationContext, mode).also {
                if (!it.initialize()) {
                    LogCollector.e(TAG, "Anime4K 初始化失败: ${mode.id}")
                    return null
                }
            }
        } catch (e: Throwable) {
            LogCollector.e(TAG, "Anime4K 创建异常: ${mode.id}", e)
            return null
        }
        cachedAnime4kMode = mode
        cachedAnime4k = engine
        LogCollector.i(TAG, "Anime4K 就绪: ${mode.id}")
        return engine
    }

    /**
     * 阅读器链路：**超分开关**只管超分模型；Anime4K 由它自己的开关独立决定。
     *
     * ⚠️ 这里刻意**不用**超分开关去闸 Anime4K：Anime4K 是调色面板里的"画面观感"设置，
     * 用户在调色面板打开它却因为另一个面板的开关没开而毫无反应，正是之前
     * 「切了档、预览和阅读器都没效果」的根因。
     *
     * ⚠️ **超分只服务阅读器**（2026-10 用户口径）：截图/录屏链路不再超分，
     * 原来的 `upscaleIfEnabled(forGame)` 已删除。
     */
    fun upscaleForReader(context: Context, prefs: SharedPreferences, src: Bitmap): Bitmap? {
        val steps = resolveSteps(
            srEnabled = SrSettings.isEnabledForReader(prefs),
            srModelUsable = isSrModelUsable(context, prefs),
            anime4kEnabled = Anime4kMode.isEnabled(prefs)
        )
        if (steps.isEmpty()) return null
        return applySteps(context, prefs, src, steps)
    }

    /**
     * 依次执行工序。**产物尺寸各自保留**（不再缩回原尺寸）。
     *
     * ⚠️ **2026-10 v2：这里不再做"缩回原尺寸"的归一化**。原因：
     * - v1 的归一化是为了让产物能喂 OCR（坐标空间必须与原图一致）
     * - v2 超分与 OCR 解耦（OCR 永远吃原图），产物是**显示底图** → **2x 必须保留**，
     *   归一化会把刚补出来的细节直接扔掉（等于白做）
     * - 由于 [resolveSteps] 已保证两者**互斥**，这里实际只会跑一道工序，
     *   所以"输出尺寸 != 输入尺寸"完全正常：`SR_MODEL` 出 2x、`ANIME4K` 出 1x
     *
     * 调用方拿到的倍率 = `out.width / src.width`（渲染时作为 `baseScale`）。
     */
    private fun applySteps(
        context: Context,
        prefs: SharedPreferences,
        src: Bitmap,
        steps: List<SrStep>
    ): Bitmap? {
        var last: Bitmap? = null
        for (step in steps) {
            val input = last ?: src
            val out = try {
                when (step) {
                    SrStep.SR_MODEL -> obtain(context, prefs)?.upscale(input)
                    SrStep.ANIME4K -> obtainAnime4k(context, prefs)?.upscale(input)
                }
            } catch (e: Throwable) {
                LogCollector.e(TAG, "工序 $step 异常", e)
                null
            }
            if (out == null) {
                LogCollector.d(TAG, "工序 $step 未产出，跳过")
                continue
            }
            // 释放上一道的中间产物（不是原始 src）
            if (last != null && last !== out) last.recycle()
            last = out
        }
        return last
    }

    private fun isSrModelUsable(context: Context, prefs: SharedPreferences): Boolean {
        val key = SrModelManager.getActiveKey(prefs) ?: return false
        return SrModelManager.isDownloaded(context, key)
    }

    /** 释放超分模型引擎 */
    @Synchronized
    fun releaseSrModel() {
        cachedEngine?.let {
            runCatching { it.release() }.onFailure { e -> LogCollector.w(TAG, "releaseSrModel: ${e.message}") }
        }
        cachedEngine = null
        cachedKey = null
    }

    /** 释放 Anime4K（持有 EGL 上下文与 GL 资源） */
    @Synchronized
    fun releaseAnime4k() {
        cachedAnime4k?.let {
            runCatching { it.release() }.onFailure { e -> LogCollector.w(TAG, "releaseAnime4k: ${e.message}") }
        }
        cachedAnime4k = null
        cachedAnime4kMode = null
    }

    /** 全部释放 */
    @Synchronized
    fun release() {
        releaseSrModel()
        releaseAnime4k()
    }
}
