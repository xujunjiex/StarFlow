package com.moe.starflow.mangaimport.data

import android.content.Context
import com.moe.starflow.library.LibrarySessions
import java.io.File
import java.io.IOException

/** Source and artifact directories are always resolved through the captured library session. */
object StorageDirStore {
    fun root(context: Context): File = File(LibrarySessions.current(context).workingRoot, "manga_import")
        .apply { if (!isDirectory && !mkdirs() && !isDirectory) throw IOException("无法创建漫画目录：$path") }
    fun bookDir(context: Context, id: Long): File = File(root(context), LibrarySessions.current(context).uuid("manga", id))
    fun coversDir(context: Context): File = File(LibrarySessions.current(context).workingRoot, ".starflow/staging/covers")
        .apply { if (!isDirectory && !mkdirs() && !isDirectory) throw IOException("无法创建封面暂存目录：$path") }
}
