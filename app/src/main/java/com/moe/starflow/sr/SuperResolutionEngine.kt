package com.moe.starflow.sr

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import com.moe.starflow.download.ModelDownloadRepository
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
     * 输入像素上限（超过就直接跳过）。
     *
     * ⚠️ 暴露出来是为了让上层能给出**带数字**的提示 —— 用户口径：
     * 「不要搞这么模糊的提示信息，到底是什么原因无法超分写清楚」。
     * 只说"尺寸太大"用户没法判断该不该换模型；说"本页 1800×2600 = 4.7MP，
     * 超过 Anime4K 的 1.5MP 上限"他立刻知道要改用超分模型。
     */
    val maxInputPixels: Long

    /**
     * 处理一张图。
     *
     * @return 处理后的新 Bitmap；**失败一律返回 null**（不抛异常）——
     *   超分/增强是"锦上添花"的一步，失败必须让调用方无痛回退到原图。
     */
    fun upscale(src: Bitmap): Bitmap?

    /**
     * **[upscale] 上一次返回 null 的技术细节**（成功时为 null）。
     *
     * 例如 `output 100x100, expected 120x120`、`model halo 72 px exceeds padding 48 px`。
     * 刻意用**短 ASCII 句**：它要原样进日志、也可能被用户"点一下复制"发出来，不做本地化最好定位。
     *
     * ⚠️ **只在"同一线程、期间没有别的调用"时有效**。这个约束在当前架构下成立：
     * 超分与 OCR 共用 `OcrLock`，同一时刻全项目只有一次超分在跑。
     */
    fun lastFailDetail(): String?

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

    /**
     * 取超分模型引擎的结果：拿到引擎，或者**为什么没拿到**。
     *
     * ⚠️ 刻意不返回 `SuperResolutionEngine?` —— 那样上层只能给出"没模型/没下载/加载失败"
     * 三合一的糊话，而用户明确要求「到底是什么原因写清楚」。
     */
    class EnginePick(
        val engine: SuperResolutionEngine?,
        val reason: SrFailReason? = null,
        val detail: String? = null,
    )

    /** 按当前选择取超分模型引擎（同一模型复用，切模型重建）。 */
    @Synchronized
    fun obtain(context: Context, prefs: SharedPreferences): EnginePick {
        val key = SrModelManager.getActiveKey(prefs)
            ?: return EnginePick(null, SrFailReason.NO_MODEL_SELECTED)
        val info = ModelDownloadRepository.getInstance(context).getModelInfo(key)
        val name = info?.files?.firstOrNull()?.fileName
        val file = SrModelManager.modelFile(context, key)
        if (file == null || !file.isFile || file.length() <= 0L) {
            return EnginePick(
                null, SrFailReason.MODEL_FILE_MISSING,
                detail = (name ?: key.name) + " @ " + (SrModelManager.modelDir(context)?.absolutePath ?: "?"),
            )
        }

        cachedEngine?.let { if (cachedKey == key) return EnginePick(it) }
        releaseSrModel()

        val engine = createEngine(key, file)
            ?: return EnginePick(null, SrFailReason.ENGINE_INIT_FAILED, detail = "${key.name} (${file.name})")
        cachedKey = key
        cachedEngine = engine
        LogCollector.i(TAG, "超分引擎就绪: $key (${file.name}, ${file.length() / 1024}KB)")
        return EnginePick(engine)
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

    /** 按当前档位取 Anime4K 引擎（换档位重建）。 */
    @Synchronized
    fun obtainAnime4k(context: Context, prefs: SharedPreferences): EnginePick {
        val mode = Anime4kMode.fromPrefs(prefs)
        cachedAnime4k?.let { if (cachedAnime4kMode == mode) return EnginePick(it) }
        releaseAnime4k()

        val engine = try {
            Anime4kEngine(context.applicationContext, mode).also {
                if (!it.initialize()) {
                    LogCollector.e(TAG, "Anime4K 初始化失败: ${mode.id}")
                    return EnginePick(null, SrFailReason.ANIME4K_INIT_FAILED, detail = mode.id)
                }
            }
        } catch (e: Throwable) {
            LogCollector.e(TAG, "Anime4K 创建异常: ${mode.id}", e)
            return EnginePick(null, SrFailReason.ANIME4K_INIT_FAILED, detail = "${mode.id}: ${e.message}")
        }
        cachedAnime4kMode = mode
        cachedAnime4k = engine
        LogCollector.i(TAG, "Anime4K 就绪: ${mode.id}")
        return EnginePick(engine)
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
     *
     * @return 成功给图；失败**必给具体原因**（[SrOutcome.reason]）
     */
    fun upscaleForReader(context: Context, prefs: SharedPreferences, src: Bitmap): SrOutcome =
        applySteps(context, prefs, src)

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
     *
     * ## 失败原因的判断顺序（**每一步都对上一条具体文案**）
     * 1. 什么工序都没有 → [SrFailReason.NOTHING_ENABLED]（超分关着 且 Anime4K 没开）
     * 2. 引擎取不到 → 原因由 [obtain]/[obtainAnime4k] 给（没选模型 / 文件不在 / 加载失败）
     * 3. 源图超上限 → 带上**实际像素数与上限**（[SuperResolutionEngine.maxInputPixels]）
     * 4. 引擎返回 null → [SrFailReason.INFERENCE_FAILED] + [SuperResolutionEngine.lastFailDetail]
     */
    private fun applySteps(context: Context, prefs: SharedPreferences, src: Bitmap): SrOutcome {
        if (src.width <= 0 || src.height <= 0) {
            return SrOutcome.fail(SrFailReason.PAGE_LOAD_FAILED, "bitmap ${src.width}x${src.height}")
        }
        val steps = resolveSteps(
            srEnabled = SrSettings.isEnabledForReader(prefs),
            srModelUsable = isSrModelUsable(context, prefs),
            anime4kEnabled = Anime4kMode.isEnabled(prefs),
        )
        if (steps.isEmpty()) {
            // 细分：是"没选模型"还是"选了但文件不在"还是"压根什么都没开"——
            // 这三种用户要做的事完全不同（去选 / 去下载 / 去开开关）
            val reason = when {
                SrSettings.isEnabledForReader(prefs) && SrModelManager.getActiveKey(prefs) == null ->
                    SrFailReason.NO_MODEL_SELECTED
                SrSettings.isEnabledForReader(prefs) && !isSrModelUsable(context, prefs) ->
                    SrFailReason.MODEL_FILE_MISSING
                else -> SrFailReason.NOTHING_ENABLED
            }
            return SrOutcome.fail(reason)
        }

        for (step in steps) {
            val pick = when (step) {
                SrStep.SR_MODEL -> obtain(context, prefs)
                SrStep.ANIME4K -> obtainAnime4k(context, prefs)
            }
            val engine = pick.engine
                ?: return SrOutcome.fail(pick.reason ?: SrFailReason.EXCEPTION, pick.detail)

            val pixels = src.width.toLong() * src.height.toLong()
            if (pixels > engine.maxInputPixels) {
                val reason = if (step == SrStep.ANIME4K) {
                    SrFailReason.ANIME4K_SOURCE_TOO_LARGE
                } else {
                    SrFailReason.SOURCE_TOO_LARGE
                }
                return SrOutcome.fail(
                    reason,
                    detail = "${src.width}x${src.height} = %.1fMP > %.1fMP"
                        .format(pixels / 1_000_000.0, engine.maxInputPixels / 1_000_000.0),
                )
            }

            val out = try {
                engine.upscale(src)
            } catch (e: Throwable) {
                LogCollector.e(TAG, "工序 $step 异常", e)
                null
            }
            if (out == null) {
                LogCollector.d(TAG, "工序 $step 未产出: ${engine.lastFailDetail()}")
                return SrOutcome.fail(
                    SrFailReason.INFERENCE_FAILED,
                    detail = engine.lastFailDetail(),
                )
            }
            return SrOutcome.ok(out)
        }
        return SrOutcome.fail(SrFailReason.EXCEPTION)
    }

    /**
     * **只做 Anime4K** 的显示增强（阅读器的底图路径用）。
     *
     * 为什么单独开一个：阅读器的超分模型底图是**从磁盘读**的（`SrStore.load`），
     * 不走引擎；而 Anime4K 没有落盘产物、只能实时跑。所以这条路径只用 Anime4K，
     * 且失败**只记日志**（底图是锦上添花，不该因为增强失败弹提示打扰用户）。
     */
    fun enhanceWithAnime4k(context: Context, prefs: SharedPreferences, src: Bitmap): SrOutcome {
        val pick = obtainAnime4k(context, prefs)
        val engine = pick.engine
            ?: return SrOutcome.fail(pick.reason ?: SrFailReason.ANIME4K_INIT_FAILED, pick.detail)
        val pixels = src.width.toLong() * src.height.toLong()
        if (pixels > engine.maxInputPixels) {
            return SrOutcome.fail(
                SrFailReason.ANIME4K_SOURCE_TOO_LARGE,
                detail = "${src.width}x${src.height} = %.1fMP > %.1fMP"
                    .format(pixels / 1_000_000.0, engine.maxInputPixels / 1_000_000.0),
            )
        }
        val out = try {
            engine.upscale(src)
        } catch (e: Throwable) {
            LogCollector.e(TAG, "Anime4K 工序异常", e)
            null
        }
        return out?.let { SrOutcome.ok(it) }
            ?: SrOutcome.fail(SrFailReason.INFERENCE_FAILED, engine.lastFailDetail())
    }

    /**
     * 「超分模型可用」= 选了模型**且**文件在。
     *
     * 公开是因为**显示底图**那条路径（`ReaderTranslationController.baseSig`）也要用同一个判据 ——
     * 它决定"这一页该显示超分底图还是 Anime4K/原图"，两边各写一份必然漂移。
     */
    fun isSrModelUsable(context: Context, prefs: SharedPreferences): Boolean {
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
