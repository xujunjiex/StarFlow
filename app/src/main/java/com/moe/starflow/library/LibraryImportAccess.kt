package com.moe.starflow.library

import com.moe.starflow.utils.FileAccessDiagnostics
import java.io.File
import java.io.IOException
import java.util.UUID

class LibraryImportWriteException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Import-only checks: an unwritable source directory must remain readable on the shelf. */
internal object LibraryImportAccess {
    fun blockedDirectories(session: LibrarySession): List<File> =
        if (session.storage !is FileLibraryStorage) emptyList() else listOf("manga_import", "novel_import")
            .map(session::local).filter { it.isDirectory && (!it.canWrite() || !it.canExecute()) }

    fun check(session: LibrarySession, kind: String) {
        require(kind == "manga" || kind == "novel")
        val root = "${kind}_import"
        val directory = session.local(root)
        if (session.storage is FileLibraryStorage && directory.isDirectory &&
            (!directory.canWrite() || !directory.canExecute())) {
            throw LibraryImportWriteException("导入目录可读取，但当前应用不能写入或遍历：${directory.path}\n" +
                FileAccessDiagnostics.describe(directory) +
                "\n请在书库存储目录选择可写位置，或修复此目录权限；现有书目和记录保留，覆盖重建记录不能修复写权限。")
        }
        // Check the actual destination, including SAF, before reserving an identity or copying originals.
        // The probe is hidden from scanners and never replaces an existing file.
        val probe = "$root/.starflow-write-${UUID.randomUUID()}"
        var failure: Exception? = null
        try {
            session.storage.writeNew(probe).use { it.write(83) }
        } catch (e: Exception) { failure = e }
        finally {
            try { if (session.storage.exists(probe)) session.storage.delete(probe) }
            catch (e: Exception) { if (failure == null) failure = e else failure.addSuppressed(e) }
        }
        failure?.let { error ->
            val diagnostic = if (session.storage is FileLibraryStorage) "\n${FileAccessDiagnostics.describe(directory)}" else ""
            throw LibraryImportWriteException("导入目标无法写入或清理临时文件：${session.storage.displayPath}/$root\n" +
                "${error.message}$diagnostic\n请在书库存储目录检查授权或选择可写位置；现有书目和记录保留。", error)
        }
    }
}
