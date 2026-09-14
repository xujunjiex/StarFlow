package com.moe.starflow.download
import com.moe.starflow.translate.widget.*

import android.content.Context
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * 统一模型下载管理器
 * 支持断点续传、重试机制、进度回调
 */
object ModelDownloadManager {

    private const val TAG = "ModelDownloadManager"
    private const val SPEED_UPDATE_INTERVAL = 500L  // 每 500ms 更新一次速度
    private const val BUFFER_SIZE = 65536  // 64KB 缓冲区

    /**
     * 下载进度回调
     */
    interface ProgressCallback {
        fun onProgress(bytesRead: Long, totalBytes: Long, speed: Float)
    }

    /**
     * 下载模型文件
     */
    suspend fun downloadModel(
        @Suppress("UNUSED_PARAMETER") context: Context,
        url: String,
        checksum: String,  // MD5
        destFile: File,
        onProgress: ProgressCallback? = null,
        maxRetries: Int = 3
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val tempFile = File(destFile.parent, destFile.name + ".part")
        var lastException: Exception? = null
        var connection: HttpURLConnection? = null

        for (attempt in 1..maxRetries) {
            try {
                LogCollector.d(TAG, "开始下载 (attempt $attempt/$maxRetries): $url")

                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 60000
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept-Encoding", "identity")

                // 断点续传：任何时候 .part 文件存在且 size > 0 都尝试续传
                // 这样应用重启、跨页面、跨协程作用域都能恢复
                val startOffset = if (tempFile.exists() && tempFile.length() > 0) {
                    LogCollector.d(TAG, "断点续传：从 ${tempFile.length()} 字节继续 (attempt=$attempt)")
                    connection.setRequestProperty("Range", "bytes=${tempFile.length()}-")
                    tempFile.length()
                } else {
                    if (tempFile.exists()) tempFile.delete()
                    0L
                }

                val responseCode = connection.responseCode
                val totalBytes = connection.contentLengthLong
                LogCollector.d(TAG, "HTTP $responseCode, Content-Length: $totalBytes")

                if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                    // 404/403 等客户端错误不需要重试
                    val permanent = responseCode == 404 || responseCode == 403
                    return@withContext Result.failure(
                        Exception("HTTP $responseCode${if (permanent) " (文件不存在)" else ""}")
                    )
                }

                // 实际总大小
                val actualTotalBytes = if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    startOffset + totalBytes
                } else {
                    totalBytes
                }

                // 服务器不支持 Range（返回 200 而非 206）：即使有 .part 也要从头开始
                // 否则 append 模式会把完整文件追加到旧 .part 后面，得到损坏文件
                val appendMode = startOffset > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL
                if (startOffset > 0 && !appendMode) {
                    LogCollector.d(TAG, "服务器不支持 Range 续传，清除 .part 重新下载")
                    if (tempFile.exists()) tempFile.delete()
                }
                val conn = connection
                FileOutputStream(tempFile, appendMode).use { outputStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var lastUpdateTime = System.currentTimeMillis()
                    var lastBytesRead = startOffset
                    var speed: Float
                    speed = 0f

                    conn.inputStream.use { inputStream ->
                        while (true) {
                            // 检查协程取消：Job.cancel() 不会中断阻塞 IO，必须主动 disconnect
                            // 让下一次 read() 抛 SocketException 退出循环
                            if (currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == false) {
                                conn.disconnect()
                                throw kotlinx.coroutines.CancellationException(context.getString(R.string.error_download_cancelled))
                            }
                            val read = inputStream.read(buffer)
                            if (read == -1) break

                            outputStream.write(buffer, 0, read)

                            val currentTime = System.currentTimeMillis()
                            val elapsed = currentTime - lastUpdateTime
                            if (elapsed >= SPEED_UPDATE_INTERVAL) {
                                val bytesDelta = tempFile.length() - lastBytesRead
                                speed = (bytesDelta.toFloat() / elapsed) * 1000f / (1024f * 1024f)
                                lastUpdateTime = currentTime
                                lastBytesRead = tempFile.length()
                                onProgress?.onProgress(lastBytesRead, actualTotalBytes, speed)
                            }
                        }
                    }
                    outputStream.flush()
                }

                // 验证完整性
                val downloadedBytes = tempFile.length()
                if (actualTotalBytes > 0 && downloadedBytes != actualTotalBytes) {
                    throw Exception(context.getString(R.string.error_download_incomplete, actualTotalBytes.toString(), downloadedBytes.toString()))
                }

                // 移动到目标
                if (!tempFile.renameTo(destFile)) {
                    Files.move(tempFile.toPath(), destFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }

                // 校验
                if (checksum.isNotEmpty()) {
                    val fileHash = calculateMD5(destFile)
                    if (fileHash != checksum.lowercase()) {
                        destFile.delete()
                        throw Exception(context.getString(R.string.error_md5_failed))
                    }
                }

                LogCollector.d(TAG, "下载完成: ${destFile.absolutePath}")
                return@withContext Result.success(Unit)

            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消时直接抛出，不当作失败
                LogCollector.d(TAG, "下载已取消")
                throw e
            } catch (e: Exception) {
                lastException = e
                LogCollector.e(TAG, "attempt $attempt/$maxRetries 失败: ${e.message}")
                connection?.disconnect()
                connection = null
                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay((attempt * 2000L).coerceAtMost(10000L))
                }
            }
        }

        LogCollector.e(TAG, "下载失败，已重试 $maxRetries 次")
        // 保留 .part 文件，下次可继续断点续传（应用重启、跨页面都能恢复）
        // 只有删除已下载成功的目标文件时才会清理（由调用方 deleteModel() 处理）
        Result.failure(lastException ?: Exception(context.getString(R.string.toast_download_failed)))
    }

    private fun calculateMD5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}