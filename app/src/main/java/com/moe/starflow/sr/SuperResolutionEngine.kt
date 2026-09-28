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
 * ## 每一步的产物都**与原图同尺寸**
 * 超分模型输出 2x，这里**立即缩回原尺寸**再交给下一步。原因（改动前必读）：
 * `bubbleRects` 是**持久化坐标**，之后还会在**原图**上重渲染；若让 OCR 跑在 2x 图上，
 * 检出的框就是 2x 坐标 → 以后按原图重渲染时整片译文成倍错位，而且**只有"翻回去/重开"才现形**。
 * 收益一点没少：OCR 引擎本来就会把输入缩到自己的工作尺寸，决定识别率的是**信息质量**；
 * 先放大再缩回 = 一次「去噪 + 锐化 + 去 JPEG 块效应」。
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
     * 两道工序**互相独立**：
     * - `SR_MODEL`：需要「超分开关打开」**且**「模型已选且文件在」
     * - `ANIME4K`：只看 Anime4K 自己的开关（调色面板里那个），**不受超分开关影响**
     *
     * 顺序固定 **先放大、后描线**：反过来的话 Anime4K 会在放大前的图上工作，
     * 放大又会把它的描线结果糊掉，等于白跑。
     */
    fun resolveSteps(
        srEnabled: Boolean,
        srModelUsable: Boolean,
        anime4kEnabled: Boolean
    ): List<SrStep> = buildList {
        if (srEnabled && srModelUsable) add(SrStep.SR_MODEL)
        if (anime4kEnabled) add(SrStep.ANIME4K)
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

    /** 依次执行工序；每一步都保证**输出与输入同尺寸**。任何一步失败就退回该步的输入 */
    private fun applySteps(
        context: Context,
        prefs: SharedPreferences,
        src: Bitmap,
        steps: List<SrStep>
    ): Bitmap? {
        var cur: Bitmap? = null
        var last: Bitmap = src
        for (step in steps) {
            val out = try {
                when (step) {
                    SrStep.SR_MODEL -> obtain(context, prefs)?.upscale(last)
                    SrStep.ANIME4K -> obtainAnime4k(context, prefs)?.upscale(last)
                }
            } catch (e: Throwable) {
                LogCollector.e(TAG, "工序 $step 异常", e)
                null
            }
            if (out == null) {
                LogCollector.d(TAG, "工序 $step 未产出，跳过（保留上一步结果）")
                continue
            }
            // ⚠️ 关键：把产物**缩回原尺寸**再进下一步 —— 坐标空间必须始终等于原图
            val normalized = if (out.width == last.width && out.height == last.height) {
                out
            } else {
                Bitmap.createScaledBitmap(out, last.width, last.height, true).also {
                    if (it !== out) out.recycle()
                }
            }
            // 释放上一步的中间产物（不是原始 src）
            if (last !== src && last !== normalized) last.recycle()
            last = normalized
            cur = normalized
        }
        return cur
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
