package com.moe.starflow.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 将点击时的统一日志快照发布到下载目录；失败保留完整异常并删除未完成的文件。 */
object LogExporter {
    private const val TAG = "LogExporter"

    data class ExportedLog(val uri: Uri, val location: String)

    fun export(context: Context): ExportedLog {
        val resolver = context.contentResolver
        val name = "starflow_log_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date())}.txt"
        val location = "${Environment.DIRECTORY_DOWNLOADS}/StarFlow/$name"
        var stage = "snapshot"
        var uri: Uri? = null
        try {
            val content = snapshot()
            stage = "create"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/StarFlow")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert returned null")
            uri = target
            stage = "write"
            val output = resolver.openOutputStream(target, "w")
                ?: throw IOException("MediaStore openOutputStream returned null")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
            stage = "publish"
            val published = resolver.update(target, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            if (published != 1) throw IOException("MediaStore publish updated $published rows")
            LogCollector.i(TAG, "Log export succeeded: destination=$location uri=$target")
            return ExportedLog(target, location)
        } catch (e: Exception) {
            LogCollector.e(TAG, "Log export failed: stage=$stage destination=$location uri=$uri", e)
            uri?.let { target ->
                try {
                    resolver.delete(target, null, null)
                } catch (cleanup: Exception) {
                    LogCollector.e(TAG, "Failed to delete unfinished log export: uri=$target", cleanup)
                }
            }
            throw e
        }
    }

    private fun snapshot(): String {
        val file = LogCollector.crashLogFile
        if (file != null) {
            try {
                synchronized(file) {
                    if (file.exists() && file.length() > 0L) return file.readText()
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Cannot read persisted log; exporting current memory buffer", e)
            }
        }
        return LogCollector.getFormattedLogs()
    }
}
