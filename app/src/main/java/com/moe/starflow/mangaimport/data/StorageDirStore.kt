package com.moe.starflow.mangaimport.data

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * 导入漫画的固定存放位置（app 外部存储专属目录，即 `Android/data/<pkg>/files/`）。
 *
 * 漫画原文件与模型放在 `getExternalFilesDir`，供用户直接放入或管理；生成的封面缩略图
 * 放在应用私有 `filesDir/covers`。封面是可重建缓存，不应依赖 Android/data 下目录的
 * 所有权或可写权限。
 * 漫画复制件的子目录按漫画 id 命名：
 *   <externalFiles>/manga_import/<id>/   图片夹：直接放图
 *   <externalFiles>/manga_import/<id>/   zip：放原始 zip 文件
 */
object StorageDirStore {

    /** 导入漫画根目录（外部专属目录，自动创建）。 */
    fun root(context: Context): File {
        val dir = File(externalBase(context), "manga_import")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 应用私有封面缓存目录，避免 Android/data 下被 MTP/ADB 创建的目录不可写。 */
    fun coversDir(context: Context): File {
        val dir = File(context.filesDir, "covers")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("无法创建应用私有封面目录：${dir.absolutePath}")
        }
        return dir
    }

    private fun externalBase(context: Context): File {
        // getExternalFilesDir 返回 Android/data/<pkg>/files；极罕见为 null 时退回 filesDir 保底
        return context.getExternalFilesDir(null) ?: context.filesDir
    }

    /**
     * 一次性迁移：把旧版存放在 `filesDir`（/data/data/<pkg>/files，用户不可访问）的
     * 漫画原文件搬移到外部专属目录（Android/data/<pkg>/files），并同步更新
     * [ImportedMangaStore] 里所有条目的 localRoot。封面缓存继续留在应用私有目录。
     *
     * 幂等：旧目录不存在或已搬完时直接返回，可安全重复调用。应在后台线程调用。
     */
    fun migrate(context: Context) {
        val oldRoot = File(context.filesDir, "manga_import")
        val newRoot = root(context)
        if (oldRoot.isDirectory) {
            oldRoot.listFiles()?.forEach { child ->
                val target = File(newRoot, child.name)
                if (!target.exists()) moveQuietly(child, target)
            }
            oldRoot.listFiles()?.takeIf { it.isEmpty() }?.let { oldRoot.delete() }
        }

        // 同步清单里指向旧 filesDir 路径的漫画原文件。
        rewriteStorePaths(context, oldRoot.absolutePath, newRoot.absolutePath)
    }

    /** 把 store 里命中旧目录前缀的 localRoot 改写为新目录。 */
    private fun rewriteStorePaths(context: Context, oldBase: String, newBase: String) {
        if (oldBase == newBase) return
        val list = ImportedMangaStore.load(context)
        var changed = false
        val migrated = list.map { m ->
            var out = m
            if (m.localRoot.startsWith(oldBase)) {
                out = out.copy(localRoot = newBase + m.localRoot.removePrefix(oldBase))
                changed = true
            }
            out
        }
        if (changed) ImportedMangaStore.save(context, migrated)
    }

    /** 优先 rename（/data 与 /storage/emulated 同文件系统），失败退化为复制+删除。 */
    private fun moveQuietly(from: File, to: File) {
        if (from.renameTo(to)) return
        try {
            if (from.isDirectory) from.copyRecursively(to, overwrite = true) else from.copyTo(to, overwrite = true)
            from.deleteRecursively()
        } catch (e: Exception) {
            // 搬移失败不阻断主流程，旧副本留在原处
        }
    }
}
