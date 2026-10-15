package com.moe.starflow.mangaimport.data

import android.content.Context
import com.moe.starflow.library.LibrarySessions
import java.io.File

/** Source and artifact directories are always resolved through the captured library session. */
object StorageDirStore {
    fun root(context: Context): File = File(LibrarySessions.current(context).workingRoot, "manga_import")
        .apply { com.moe.starflow.utils.FileAccessDiagnostics.ensureDirectory(this) }
    fun bookDir(context: Context, id: Long): File = File(root(context), LibrarySessions.current(context).uuid("manga", id))
    fun coversDir(context: Context): File = File(LibrarySessions.current(context).workingRoot, ".starflow/staging/covers")
        .apply { com.moe.starflow.utils.FileAccessDiagnostics.ensureDirectory(this) }
}
