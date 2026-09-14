package com.moe.starflow.manga.engine
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.state.*
import com.moe.starflow.manga.render.*
import com.moe.starflow.manga.merge.*
import com.moe.starflow.manga.*

import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import android.content.Context
import com.moe.starflow.R
import com.moe.starflow.utils.LogCollector
import java.io.File

/**
 * Manga-Ocr 模型管理器（仅查询 API，下载走 ModelDownloadService）
 *
 * 从 HuggingFace l0wgear/manga-ocr-2025-onnx 下载 ONNX 模型。
 *
 * 下载地址:
 * - Encoder: https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/main/encoder_model.onnx
 * - Decoder: https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/main/decoder_model.onnx
 * - Vocab:   https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/main/vocab.txt
 */
object MangaOcrModelFiles {

    private const val TAG = "MangaOcrModelFiles"

    // 模型配置（文件名常量，OCR 引擎依赖）
    private const val ENCODER_FILE = "encoder_model.onnx"
    private const val DECODER_FILE = "decoder_model.onnx"
    private const val VOCAB_FILE = "vocab.txt"
    private const val MODEL_DIR_NAME = "manga_ocr_download"

    // 旧版本子目录名（用于兼容老用户已下载的模型）
    private const val LEGACY_V2025_DIR = "V2025"

    /**
     * 获取下载的模型目录（外部存储）
     */
    fun getModelDir(context: Context): File {
        return File(context.getExternalFilesDir(null), MODEL_DIR_NAME)
    }

    /**
     * 获取 encoder 文件路径
     */
    fun getEncoderFile(context: Context): File {
        return File(getModelDir(context), ENCODER_FILE)
    }

    /**
     * 获取 decoder 文件路径
     */
    fun getDecoderFile(context: Context): File {
        return File(getModelDir(context), DECODER_FILE)
    }

    /**
     * 获取 vocab 文件路径
     */
    fun getVocabFile(context: Context): File {
        return File(getModelDir(context), VOCAB_FILE)
    }

    /**
     * 检查模型是否已下载
     *
     * 如果根目录找不到，会尝试从旧版子目录（V2025/）迁移文件到根目录，
     * 避免老用户升级后被迫重新下载。
     */
    fun isModelDownloaded(context: Context): Boolean {
        val encoder = getEncoderFile(context)
        val decoder = getDecoderFile(context)
        val vocab = getVocabFile(context)
        if (encoder.exists() && decoder.exists() && vocab.exists() &&
                encoder.length() > 1000 && decoder.length() > 1000) {
            return true
        }

        // 兼容老版本：尝试从 V2025/ 子目录迁移
        if (migrateLegacyV2025(context)) {
            return encoder.exists() && decoder.exists() && vocab.exists() &&
                    encoder.length() > 1000 && decoder.length() > 1000
        }
        return false
    }

    /**
     * 从旧版 V2025/ 子目录迁移模型文件到根目录。
     * 成功迁移返回 true。
     */
    private fun migrateLegacyV2025(context: Context): Boolean {
        val legacyDir = File(getModelDir(context), LEGACY_V2025_DIR)
        if (!legacyDir.exists() || !legacyDir.isDirectory) return false

        val legacyEncoder = File(legacyDir, ENCODER_FILE)
        val legacyDecoder = File(legacyDir, DECODER_FILE)
        val legacyVocab = File(legacyDir, VOCAB_FILE)
        if (!legacyEncoder.exists() || !legacyDecoder.exists() || !legacyVocab.exists()) return false

        val modelDir = getModelDir(context)
        if (!modelDir.exists()) modelDir.mkdirs()

        LogCollector.d(TAG, "检测到旧版 V2025/ 子目录，迁移模型文件到根目录")
        val encoderOk = runCatching { legacyEncoder.copyTo(getEncoderFile(context), overwrite = true) }.isSuccess
        val decoderOk = runCatching { legacyDecoder.copyTo(getDecoderFile(context), overwrite = true) }.isSuccess
        val vocabOk = runCatching { legacyVocab.copyTo(getVocabFile(context), overwrite = true) }.isSuccess
        if (encoderOk && decoderOk && vocabOk) {
            legacyDir.deleteRecursively()
            LogCollector.d(TAG, "旧版模型文件迁移完成，已删除 V2025/ 子目录")
            return true
        } else {
            LogCollector.e(TAG, "旧版模型文件迁移失败 (encoder=$encoderOk, decoder=$decoderOk, vocab=$vocabOk)")
            return false
        }
    }

    /**
     * 获取模型大小描述
     */
    fun getModelSizeString(context: Context): String {
        val encoder = getEncoderFile(context)
        val decoder = getDecoderFile(context)
        if (!encoder.exists() || !decoder.exists()) return context.getString(R.string.model_not_downloaded)

        val totalSize = encoder.length() + decoder.length()
        return formatSize(totalSize)
    }

    private fun formatSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
            size < 1024 * 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024.0))
            else -> String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /**
     * 删除已下载的模型
     */
    fun deleteModel(context: Context): Result<Unit> {
        return try {
            val modelDir = getModelDir(context)
            if (modelDir.exists()) {
                val deleted = modelDir.deleteRecursively()
                if (!deleted) {
                    LogCollector.e(TAG, "删除模型失败: ${modelDir.absolutePath}")
                    return Result.failure(Exception("Failed to delete model directory"))
                }
            }
            LogCollector.d(TAG, "模型已删除")
            Result.success(Unit)
        } catch (e: Exception) {
            LogCollector.e(TAG, "删除模型失败", e)
            Result.failure(e)
        }
    }
}
