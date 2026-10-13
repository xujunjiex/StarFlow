package com.moe.starflow.novel.data

import android.content.Context
import com.moe.starflow.mangaimport.data.StorageDirStore
import java.io.File

/** Fixed source and UUID artifact paths within the captured shared library root. */
object NovelStorageDir {

    fun root(context: Context): File = File(com.moe.starflow.library.LibrarySessions.current(context).workingRoot, "novel_import")
        .apply { if (!isDirectory && !mkdirs() && !isDirectory) throw java.io.IOException("无法创建小说目录：$path") }

    /** 某部小说的本地目录（也是取消/失败时的清理对象）。 */
    fun bookDir(context: Context, id: Long): File = File(root(context), com.moe.starflow.library.LibrarySessions.current(context).uuid("novel", id))

    /** Rebuildable reader images must never share a numeric directory with another book's source. */
    fun imageCacheDir(root: File, novel: ImportedNovel): File =
        File(root.parentFile, ".starflow/books/${novel.bookUuid.ifEmpty { "${novel.id}_${novel.addedAt}" }}/images")

    /** Shared durable staging for unpublished cover revisions. */
    fun coversDir(context: Context): File = StorageDirStore.coversDir(context)
}
