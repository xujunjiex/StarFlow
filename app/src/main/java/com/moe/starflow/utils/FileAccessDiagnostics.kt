package com.moe.starflow.utils

import android.os.Process
import android.system.Os
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

/** Exact File API state for paths that exist but cannot be enumerated by this app process. */
object FileAccessDiagnostics {

    fun describe(file: File): String {
        val exists = runCatching { file.exists() }.getOrNull()
        val isDirectory = runCatching { file.isDirectory }.getOrNull()
        val canRead = runCatching { file.canRead() }.getOrNull()
        val canWrite = runCatching { file.canWrite() }.getOrNull()
        val canTraverse = runCatching { file.canExecute() }.getOrNull()
        val checked = runCatching { Os.stat(file.absolutePath) }
        val stat = checked.getOrNull()
        val unix = stat?.let {
            "appUid=${Process.myUid()}, ownerUid=${it.st_uid}, groupGid=${it.st_gid}, " +
                "mode=${"%04o".format(Locale.ROOT, it.st_mode and 0x0fff)}"
        }
        return listOfNotNull(
            "exists=$exists",
            "isDirectory=$isDirectory",
            "canRead=$canRead",
            "canWrite=$canWrite",
            "canTraverse=$canTraverse",
            unix,
            checked.exceptionOrNull()?.message,
        ).joinToString(", ")
    }

    /** Unlike File.mkdirs(), NIO retains the filesystem failure instead of returning false.
     * Never chmod, rename or replace an existing directory to work around an access error. */
    fun ensureDirectory(directory: File) {
        if (directory.isDirectory) return
        try {
            Files.createDirectories(directory.toPath())
        } catch (e: IOException) {
            if (directory.isDirectory) return // Another import may have created the parent.
            throw IOException(directoryFailure(directory, e), e)
        } catch (e: SecurityException) {
            throw IOException(directoryFailure(directory, e), e)
        }
        if (!directory.isDirectory) throw IOException(directoryFailure(directory, null))
    }

    private fun directoryFailure(directory: File, cause: Exception?): String = buildString {
        append("无法创建目录：${directory.absolutePath}")
        cause?.message?.let { append("\n系统原因：$it") }
        append("\n目标：${describe(directory)}")
        directory.parentFile?.let { parent ->
            append("\n上级目录：${parent.absolutePath}\n${describe(parent)}")
            if (parent.isDirectory && !parent.canWrite()) {
                append("\n当前应用没有上级目录的写入权限，无法新建作品目录。")
                append("请修复该目录的写权限，或在存储位置选择可写目录；覆盖重建书库记录不会修复文件系统权限。")
            }
        }
    }
}
