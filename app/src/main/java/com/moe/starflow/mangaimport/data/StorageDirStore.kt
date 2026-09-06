package com.moe.starflow.mangaimport.data

import android.content.Context

/**
 * 存储目录记录：用户 SAF 选的漫画扫描源目录（Kototoro 的 mangaStorageUri 同款）。
 * 阶段一只存 uri + 显示，「扫描入架」属后续后端。
 */
object StorageDirStore {

    private const val PREFS_NAME = "manga_import"
    private const val KEY_URI = "storage_dir_uri"
    private const val KEY_NAME = "storage_dir_name"

    fun load(context: Context): Pair<String?, String?> {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return p.getString(KEY_URI, null) to p.getString(KEY_NAME, null)
    }

    fun save(context: Context, uri: String, name: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URI, uri)
            .putString(KEY_NAME, name)
            .apply()
    }
}
