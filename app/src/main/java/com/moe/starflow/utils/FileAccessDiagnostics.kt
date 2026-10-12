package com.moe.starflow.utils

import android.os.Process
import android.system.Os
import java.io.File
import java.util.Locale

/** Exact File API state for paths that exist but cannot be enumerated by this app process. */
object FileAccessDiagnostics {

    fun describe(file: File): String {
        val exists = runCatching { file.exists() }.getOrNull()
        val isDirectory = runCatching { file.isDirectory }.getOrNull()
        val canRead = runCatching { file.canRead() }.getOrNull()
        val stat = runCatching { Os.stat(file.absolutePath) }.getOrNull()
        val unix = stat?.let {
            "appUid=${Process.myUid()}, ownerUid=${it.st_uid}, groupGid=${it.st_gid}, " +
                "mode=${"%04o".format(Locale.ROOT, it.st_mode and 0x0fff)}"
        }
        return listOfNotNull(
            "exists=$exists",
            "isDirectory=$isDirectory",
            "canRead=$canRead",
            unix,
        ).joinToString(", ")
    }
}
