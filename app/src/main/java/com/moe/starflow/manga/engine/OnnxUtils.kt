package com.moe.starflow.manga.engine
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.state.*
import com.moe.starflow.manga.render.*
import com.moe.starflow.manga.merge.*

import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import ai.onnxruntime.OnnxTensor
import android.content.Context
import com.moe.starflow.utils.LogCollector
import java.io.File

/**
 * ONNX Runtime 工具类
 * 提供模型文件管理和张量数据提取等通用功能
 */
object OnnxUtils {

    private const val TAG = "OnnxUtils"

    /**
     * 从 OnnxTensor 提取 FloatArray
     * 注意：会消费 buffer 的数据，调用后 buffer 位置会改变
     */
    fun extractFloatArray(tensor: OnnxTensor): FloatArray {
        val buffer = tensor.floatBuffer
        val arr = FloatArray(buffer.remaining())
        buffer.get(arr)
        buffer.rewind()
        return arr
    }

    /**
     * 从 OnnxTensor 提取 LongArray
     * 注意：会消费 buffer 的数据，调用后 buffer 位置会改变
     */
    fun extractLongArray(tensor: OnnxTensor): LongArray {
        val buffer = tensor.longBuffer
        val arr = LongArray(buffer.remaining())
        buffer.get(arr)
        buffer.rewind()
        return arr
    }

    /**
     * 将 assets 文件复制到缓存目录
     *
     * @param context 上下文
     * @param assetPath assets 中的文件路径
     * @param subDir 缓存子目录名称，用于隔离不同模型的同名文件（如 model.onnx）
     * @return 复制后的文件绝对路径
     */
    fun copyAssetToCache(context: Context, assetPath: String, subDir: String? = null): String {
        val fileName = assetPath.substringAfterLast("/")
        val cacheDir = if (subDir != null) {
            File(context.cacheDir, subDir).apply { mkdirs() }
        } else {
            context.cacheDir
        }
        val cacheFile = File(cacheDir, fileName)
        LogCollector.d(TAG, "复制 assets 文件: $assetPath -> ${cacheFile.absolutePath}")
        context.assets.open(assetPath).use { input ->
            cacheFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        LogCollector.d(TAG, "复制完成: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
        return cacheFile.absolutePath
    }
}
