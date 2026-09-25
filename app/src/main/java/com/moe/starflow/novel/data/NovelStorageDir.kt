package com.moe.starflow.novel.data

import android.content.Context
import com.moe.starflow.mangaimport.data.StorageDirStore
import java.io.File

/**
 * 小说的固定存放位置（与漫画同层：`getExternalFilesDir()/novel_import/`）。
 *
 * 与漫画同层而不是同目录的理由：都在 `Android/data/<pkg>/files/` 下，用户用文件管理器 /
 * 数据线都能访问，无需任何存储权限。
 *
 * ⚠️ **必须与漫画分目录**：两套 id 空间独立（各自从 1 开始），共用目录会让漫画 id=1 与
 * 小说 id=1 抢同一个 `1/` 目录，互相覆盖。
 */
object NovelStorageDir {

    fun root(context: Context): File {
        // getExternalFilesDir 极罕见为 null 时退回 filesDir 保底（与漫画侧一致）
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "novel_import")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 某部小说的本地目录（也是取消/失败时的清理对象）。 */
    fun bookDir(context: Context, id: Long): File = File(root(context), id.toString())

    /** 封面目录与漫画共用（`covers/`），靠文件名前缀 `novel_` 区分，不另开目录。 */
    fun coversDir(context: Context): File = StorageDirStore.coversDir(context)
}
