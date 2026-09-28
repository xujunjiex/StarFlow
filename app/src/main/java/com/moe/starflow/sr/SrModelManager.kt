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
 * 模型管理页「超分」Tab 读写选择、超分引擎按 [activeModelFile] 取文件，
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
        ModelKey.SR_WAIFU2X_SWIN_N1,
    )

    /** 当前启用的模型；未选 / 存了非法值 → null */
    fun getActiveKey(prefs: SharedPreferences): ModelKey? {
        val raw = prefs.getString(PREF_ACTIVE_KEY, null) ?: return null
        return runCatching { ModelKey.valueOf(raw) }.getOrNull()?.takeIf { it in allKeys }
    }

    /**
     * 模型展示名（字符串资源）。
     *
     * ⚠️ **这是唯一一份「ModelKey → 显示名」映射**：模型管理页与个性化设置的摘要都调它。
     * 两处各写一套必然漂移（改了一个忘了另一个 → 同一个模型两个名字）。
     */
    fun nameResOf(key: ModelKey): Int = when (key) {
        ModelKey.SR_ANIMEJANAI_HD_BALANCED -> R.string.sr_model_animejanai_balanced
        ModelKey.SR_ANIMEJANAI_HD_PERFORMANCE -> R.string.sr_model_animejanai_performance
        ModelKey.SR_ANIMEJANAI_HD_SHARP1_BALANCED -> R.string.sr_model_animejanai_sharp1_balanced
        ModelKey.SR_ANIMEJANAI_HD_SHARP1_PERFORMANCE -> R.string.sr_model_animejanai_sharp1_performance
        ModelKey.SR_ANIMEJANAI_SD_COMPACT -> R.string.sr_model_animejanai_sd_compact
        ModelKey.SR_WAIFU2X_CUNET_N0 -> R.string.sr_model_w2x_cunet_n0
        ModelKey.SR_WAIFU2X_CUNET_N1 -> R.string.sr_model_w2x_cunet_n1
        ModelKey.SR_WAIFU2X_CUNET_N2 -> R.string.sr_model_w2x_cunet_n2
        ModelKey.SR_WAIFU2X_CUNET_N3 -> R.string.sr_model_w2x_cunet_n3
        ModelKey.SR_WAIFU2X_SWIN_N0 -> R.string.sr_model_w2x_swin_n0
        ModelKey.SR_WAIFU2X_SWIN_N1 -> R.string.sr_model_w2x_swin_n1
        else -> 0
    }

    fun setActive(prefs: SharedPreferences, key: ModelKey) {
        prefs.edit().putString(PREF_ACTIVE_KEY, key.name).apply()
    }

    /** 超分模型目录 */
    fun modelDir(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, SR_DIR) }

    /** 某模型的目标文件（未下载时是期望路径；文件名取自 downloadinfo.json） */
    fun modelFile(context: Context, key: ModelKey): File? {
        val info = ModelDownloadRepository.getInstance(context).getModelInfo(key) ?: return null
        val name = info.files.firstOrNull()?.fileName ?: return null
        val dir = modelDir(context) ?: return null
        return File(dir, name)
    }

    /** 该模型是否已下载（文件存在且非空） */
    fun isDownloaded(context: Context, key: ModelKey): Boolean =
        modelFile(context, key)?.let { it.isFile && it.length() > 0L } ?: false

    /**
     * 当前启用且**确实已下载**的模型文件。
     * 未选模型或文件缺失 → null（调用方据此回退到不超分，绝不抛异常）。
     */
    fun activeModelFile(context: Context, prefs: SharedPreferences): File? {
        val key = getActiveKey(prefs) ?: return null
        return modelFile(context, key)?.takeIf { it.isFile && it.length() > 0L }
    }
}
