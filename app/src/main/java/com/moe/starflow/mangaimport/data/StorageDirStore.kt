package com.moe.starflow.mangaimport.data

import android.content.Context
import java.io.File

/**
 * 导入漫画的固定存放位置（app 内部存储）。
 *
 * 移除自定义 SAF 目录功能后只保留单一位置：`filesDir/manga_import`
 * （与下载的 PP-OCR 模型同为 app 内部存储层级，无需任何权限）。
 * 所有导入的漫画复制件都存放在这里，子目录按漫画 id 命名：
 *   filesDir/manga_import/<id>/   图片夹：直接放图
 *   filesDir/manga_import/<id>/   zip：放原始 zip 文件
 */
object StorageDirStore {

    /** 导入漫画根目录（app 内部存储，自动创建）。 */
    fun root(context: Context): File {
        val dir = File(context.filesDir, "manga_import")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}