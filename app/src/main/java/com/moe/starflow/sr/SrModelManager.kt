package com.moe.starflow.sr

import android.content.Context
import android.content.SharedPreferences
import com.moe.starflow.R
import com.moe.starflow.download.ModelDownloadRepository
import com.moe.starflow.download.ModelKey
import java.io.File

/**
 * 超分（SR）模型的**唯一状态来源**：当前启用哪个模型 + 模型文件在哪。
 *
 * 与 OCR 侧的 `OcrEngineManager`（4 组引擎 + prefs 选择）设计对齐：
 * 模型管理页「超分」Tab 读写选择；超分引擎经 [SuperResolutionEngines] 走
 * [isDownloaded] + [modelFile] / [ncnnPair] 取文件，
 * 两边都只经过这里，不各自读 prefs。
 *
 * ⚠️ **落盘目录必须与下载流水线一致**：`ModelDownloadRepository.baseDirFor` 与
 * `ModelDownloadService.baseDirFor` 的超分分支都是 `getExternalFilesDir("sr")`。
 * 改目录要改三处（那两个 when + 这里的 [SR_DIR]）。
 *
 * ⚠️ 文件名**不在这里硬编码**，一律从 `downloadinfo.json`（经 Repository）取 ——
 * 清单是文件的单一事实来源，这里再写一份就会在换文件名时静默失配。
 */
object SrModelManager {

    /** prefs 键：当前启用的超分模型（存 [ModelKey.name]） */
    const val PREF_ACTIVE_KEY = "sr_active_model_key"

    /** 超分模型目录名（getExternalFilesDir 下） */
    private const val SR_DIR = "sr"

    /**
     * 所有可选的超分模型，顺序 = 模型管理页「超分」Tab 的展示顺序。
     * 新增模型时**同时**要加：`ModelKey`、`downloadinfo.json`、以及本列表。
     */
    val allKeys: List<ModelKey> = listOf(
        ModelKey.SR_W2X_UP7_ANIME_M1,
        ModelKey.SR_W2X_UP7_ANIME_N2,
        ModelKey.SR_W2X_CUNET_M1,
        ModelKey.SR_W2X_CUNET_N1,
        ModelKey.SR_W2X_CUNET_N2,
        ModelKey.SR_SRMD_X2,
        ModelKey.SR_SRMD_NF_X2,
        ModelKey.SR_REALCUGAN_NODENOISE,
        ModelKey.SR_REALCUGAN_CONSERVATIVE,
        ModelKey.SR_REALCUGAN_DENOISE3X,
        ModelKey.SR_REALESRGAN_ANIME6B,
    )

    /**
     * 老版本存过的档位名 → 现行档位（sr_active_model_key 里存的是 [ModelKey.name]）。
     *
     * upconv_7 在 2026-10 精简为「不降噪 + 强力降噪（N2）」：旧的极强降噪 N3 与照片族
     * 都已下架。用户存的是被删掉的档位名时不能直接算「未选择」（超分开着却没有模型 =
     * 功能看起来坏了），按语义就近映射到现行档位。
     */
    private val LEGACY_KEY_MAP = mapOf(
        "SR_W2X_UP7_ANIME_N3" to ModelKey.SR_W2X_UP7_ANIME_N2,
        "SR_W2X_UP7_PHOTO_M1" to ModelKey.SR_W2X_UP7_ANIME_M1,
        "SR_W2X_UP7_PHOTO_N3" to ModelKey.SR_W2X_UP7_ANIME_N2,
    )

    /** 当前启用的模型；未选 / 存了非法值 → null（旧档位名先按 [LEGACY_KEY_MAP] 迁移并回写）。 */
    fun getActiveKey(prefs: SharedPreferences): ModelKey? {
        val raw = prefs.getString(PREF_ACTIVE_KEY, null) ?: return null
        runCatching { ModelKey.valueOf(raw) }.getOrNull()?.takeIf { it in allKeys }?.let { return it }
        return LEGACY_KEY_MAP[raw]?.takeIf { it in allKeys }?.also { setActive(prefs, it) }
    }

    /**
     * 模型展示名（字符串资源）。
     *
     * ⚠️ **这是唯一一份「ModelKey → 显示名」映射**：模型管理页与个性化设置的摘要都调它。
     * 两处各写一套必然漂移（改了一个忘了另一个 → 同一个模型两个名字）。
     */
    fun nameResOf(key: ModelKey): Int = when (key) {
        ModelKey.SR_W2X_UP7_ANIME_M1 -> R.string.sr_model_w2x_up7_anime_m1
        ModelKey.SR_W2X_UP7_ANIME_N2 -> R.string.sr_model_w2x_up7_anime_n2
        ModelKey.SR_W2X_CUNET_M1 -> R.string.sr_model_w2x_cunet_m1
        ModelKey.SR_W2X_CUNET_N1 -> R.string.sr_model_w2x_cunet_n1
        ModelKey.SR_W2X_CUNET_N2 -> R.string.sr_model_w2x_cunet_n2
        ModelKey.SR_SRMD_X2 -> R.string.sr_model_srmd_x2
        ModelKey.SR_SRMD_NF_X2 -> R.string.sr_model_srmd_nf_x2
        ModelKey.SR_REALCUGAN_NODENOISE -> R.string.sr_model_cugan_dn
        ModelKey.SR_REALCUGAN_CONSERVATIVE -> R.string.sr_model_cugan_cons
        ModelKey.SR_REALCUGAN_DENOISE3X -> R.string.sr_model_cugan_d3
        ModelKey.SR_REALESRGAN_ANIME6B -> R.string.sr_model_rsrgan_a6b
        else -> 0
    }

    fun setActive(prefs: SharedPreferences, key: ModelKey) {
        prefs.edit().putString(PREF_ACTIVE_KEY, key.name).apply()
    }

    /** 超分模型目录 */
    fun modelDir(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, SR_DIR) }

    /**
     * 某模型的**全部**目标文件（顺序与 downloadinfo.json 一致）。
     *
     * ⚠️ ncnn 模型是**两个文件**（`.param` + `.bin`），这里不能再只取第一个 ——
     * 只取第一个会让「下载好了」和「能加载」两件事脱节。
     */
    fun modelFiles(context: Context, key: ModelKey): List<File> {
        val info = ModelDownloadRepository.getInstance(context).getModelInfo(key) ?: return emptyList()
        val dir = modelDir(context) ?: return emptyList()
        return info.files.map { File(dir, it.fileName) }
    }

    /** 某模型的第一个文件（AnimeJaNai ONNX 单文件模型用；保留旧语义） */
    fun modelFile(context: Context, key: ModelKey): File? = modelFiles(context, key).firstOrNull()

    /**
     * ncnn 模型的 (param, bin) 对；不是 ncnn 模型、或任一文件缺失 → null。
     *
     * 按**扩展名**配对而不是按下标：清单里 param 在前 bin 在后是约定，
     * 但按下标配对一旦清单顺序变了就会静默换错文件。
     */
    fun ncnnPair(context: Context, key: ModelKey): Pair<File, File>? {
        val files = modelFiles(context, key)
        val param = files.firstOrNull { it.name.endsWith(".param") } ?: return null
        val bin = files.firstOrNull { it.name.endsWith(".bin") } ?: return null
        if (!param.isFile || param.length() == 0L || !bin.isFile || bin.length() == 0L) return null
        return param to bin
    }

    /** 该模型是否已下载（**所有**文件都存在且非空） */
    fun isDownloaded(context: Context, key: ModelKey): Boolean =
        modelFiles(context, key).let { it.isNotEmpty() && it.all { f -> f.isFile && f.length() > 0L } }
}
