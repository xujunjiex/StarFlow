package com.moe.starflow.mangaimport.data

import android.content.Context
import java.io.File

/**
 * 漫画存储位置（= 导入的漫画实际存放目录）。
 *
 * 采用 app 可自由写入的位置，**不使用系统 SAF 目录选择器**——
 * MIUI/HyperOS 的 SAF 选择器不允许选已有目录（"保护隐私..."），
 * app 专属 + 内部位置无任何权限限制。切换在 app 内进行。
 */
object StorageDirStore {

    private const val PREFS_NAME = "manga_import"
    private const val KEY_LOCATION = "storage_location"
    private const val SUB_DIR = "manga_import"

    /** 可选存储位置。 */
    enum class Location(val key: String) {
        /** 应用专属外部目录（外部存储，容量大，卸载 app 时清除） */
        APP_EXTERNAL("app_external"),

        /** 应用内部存储（更安全，但容量受 app 单进程限制） */
        APP_INTERNAL("app_internal")
    }

    /** 当前存储位置。 */
    fun current(context: Context): Location {
        val key = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LOCATION, null)
        return runCatching { Location.valueOf(key ?: "") }.getOrDefault(Location.APP_EXTERNAL)
    }

    /** 切换存储位置。 */
    fun set(context: Context, location: Location) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOCATION, location.name)
            .apply()
    }

    /** 存储位置对应的根目录（不存在则创建）。 */
    fun rootDir(context: Context, location: Location = current(context)): File {
        val base = when (location) {
            Location.APP_EXTERNAL ->
                context.getExternalFilesDir(null) ?: context.filesDir
            Location.APP_INTERNAL -> context.filesDir
        }
        val dir = File(base, SUB_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 存储位置的用户可读描述（路径 + 特点）。 */
    fun describe(context: Context, location: Location): String = when (location) {
        Location.APP_EXTERNAL ->
            "应用外部存储\n${rootDir(context, location).absolutePath}"
        Location.APP_INTERNAL ->
            "应用内部存储\n${rootDir(context, location).absolutePath}"
    }
}