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
 * ⚠️ **落盘目录必须与下载流水线一致**：Repository 与 Service 的超分分支、
 * 以及这里的模型目录都指向 [SrStoragePaths.ROOT_DIR_NAME]。
 *
 * ⚠️ 文件名**不在这里硬编码**，一律从 `downloadinfo.json`（经 Repository）取 ——
 * 清单是文件的单一事实来源，这里再写一份就会在换文件名时静默失配。
 */
object SrModelManager {

    /** prefs 键：当前启用的超分模型（存 [ModelKey.name]） */
    const val PREF_ACTIVE_KEY = "sr_active_model_key"

    /**
     * 所有可选的超分模型，顺序 = 模型管理页「超分」Tab 的展示顺序。
     * 新增模型时**同时**要加：`ModelKey`、`downloadinfo.json`、以及本列表。
     */
    val allKeys: List<ModelKey> = listOf(
        ModelKey.SR_REALCUGAN_SE,
        ModelKey.SR_REALCUGAN_PRO,
        ModelKey.SR_REALCUGAN_NOSE,
        ModelKey.SR_WAIFU2X_CUNET,
        ModelKey.SR_WAIFU2X_FAST,
        ModelKey.SR_REALESRGAN,
        ModelKey.SR_W2XEX_UNIVERSAL_FAST,
        ModelKey.SR_W2XEX_OMNI_MINI_V2,
        ModelKey.SR_W2XEX_PHOTO_SMALL,
        ModelKey.SR_SUDO_ULTRA_COMPACT,
        ModelKey.SR_SPAN_NOMOSUNI
    )

    /** Only the current, unpublished catalog is accepted. */
    fun getActiveKey(prefs: SharedPreferences): ModelKey? =
        prefs.getString(PREF_ACTIVE_KEY, null)?.let { raw ->
            runCatching { ModelKey.valueOf(raw) }.getOrNull()?.takeIf { it in allKeys }
        }

    /**
     * 模型展示名（字符串资源）。
     *
     * ⚠️ **这是唯一一份「ModelKey → 显示名」映射**：模型管理页与个性化设置的摘要都调它。
     * 两处各写一套必然漂移（改了一个忘了另一个 → 同一个模型两个名字）。
     */
    fun nameResOf(key: ModelKey): Int = when (key) {
        ModelKey.SR_REALCUGAN_SE -> R.string.sr_model_realcugan_se
        ModelKey.SR_REALCUGAN_PRO -> R.string.sr_model_realcugan_pro
        ModelKey.SR_REALCUGAN_NOSE -> R.string.sr_model_realcugan_nose
        ModelKey.SR_WAIFU2X_CUNET -> R.string.sr_model_waifu2x_cunet
        ModelKey.SR_WAIFU2X_FAST -> R.string.sr_model_waifu2x_fast
        ModelKey.SR_REALESRGAN -> R.string.sr_model_realesrgan_av3
        ModelKey.SR_W2XEX_UNIVERSAL_FAST -> R.string.sr_model_w2xex_uf
        ModelKey.SR_W2XEX_OMNI_MINI_V2 -> R.string.sr_model_w2xex_omni
        ModelKey.SR_W2XEX_PHOTO_SMALL -> R.string.sr_model_w2xex_photo
        ModelKey.SR_SUDO_ULTRA_COMPACT -> R.string.sr_model_sudo_uc
        ModelKey.SR_SPAN_NOMOSUNI -> R.string.sr_model_span_nomosuni
        // 非超分档位（OCR/翻译模型）不在本表里：调用方只用它对超分档位取文案
        else -> 0
    }

    fun setActive(prefs: SharedPreferences, key: ModelKey) {
        prefs.edit().putString(PREF_ACTIVE_KEY, key.name).apply()
    }

    /** 超分模型目录；同时迁移旧版放在 files/ 下的 SR 辅助目录。 */
    fun modelDir(context: Context): File? = SrStoragePaths.modelDir(context)

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

    /** 某模型的第一个文件（仅用于文件展示；推理须使用 ncnnPair） */
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

    private data class Validation(val signature: String, val error: String?)
    private val validations = java.util.concurrent.ConcurrentHashMap<ModelKey, Validation>()
    private fun fileSignature(context: Context, key: ModelKey): String =
        modelFiles(context, key).joinToString("|") { "${it.absolutePath}:${it.length()}:${it.lastModified()}" }

    /** Hashing is performed on IO at startup, model-page refresh and before inference. */
    fun validate(context: Context, key: ModelKey): String? {
        val signature = fileSignature(context, key)
        validations[key]?.takeIf { it.signature == signature }?.let { return it.error }
        val info = ModelDownloadRepository.getInstance(context).getModelInfo(key)
        val files = modelFiles(context, key)
        val error = if(info == null || files.isEmpty()) "model manifest is missing" else
            info.files.zip(files).firstNotNullOfOrNull { (entry, file) ->
                when {
                    !file.isFile || file.length() == 0L -> "model file missing: ${file.name}"
                    entry.fileSize > 0 && file.length() != entry.fileSize -> "model size mismatch: ${file.name}"
                    !com.moe.starflow.download.ChecksumHelper.verifyChecksum(file, entry.checksum) -> "model checksum mismatch: ${file.name}"
                    else -> null
                }
            }
        validations[key] = Validation(signature, error)
        return error
    }
    fun validationError(context: Context, key: ModelKey): String? =
        validations[key]?.takeIf { it.signature == fileSignature(context, key) }?.error

    /** Cheap UI predicate; no hashes on the main thread. */
    fun isDownloaded(context: Context, key: ModelKey): Boolean {
        val files = modelFiles(context, key)
        if(files.isEmpty() || files.any { !it.isFile || it.length()==0L }) return false
        val known = validations[key]?.takeIf { it.signature == fileSignature(context, key) }
        return known?.error == null && known != null
    }
}
