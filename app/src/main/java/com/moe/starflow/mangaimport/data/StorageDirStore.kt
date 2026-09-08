package com.moe.starflow.mangaimport.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 漫画存储目录（= 导入的漫画实际存放的位置）。
 *
 * 语义与 Kototoro 一致：
 * - **默认**为 app 专属目录（getExternalFilesDir/manga_import，无需任何权限）
 * - 用户获得「所有文件访问」权限（MANAGE_EXTERNAL_STORAGE）后，
 *   可通过 SAF 选择**任意已有目录**设为自定义存储位置，之后导入的漫画存到这里
 *
 * 支持两个存储位置：默认（File 路径）或自定义（SAF tree uri）。
 */
object StorageDirStore {

    private const val PREFS_NAME = "manga_import"
    private const val KEY_URI = "storage_dir_uri"
    private const val KEY_NAME = "storage_dir_name"

    // ---------- 位置存取 ----------

    /** 自定义存储目录的 tree uri；null = 使用默认 app 目录。 */
    fun currentUri(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_URI, null)

    /** 自定义存储目录的显示名（文件夹名）。 */
    fun currentName(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_NAME, null)

    /** 是否使用了自定义存储目录（非默认）。 */
    fun isCustom(context: Context): Boolean = currentUri(context) != null

    /** 设自定义存储目录（tree uri + 显示名）。 */
    fun setCustom(context: Context, uri: String, name: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URI, uri)
            .putString(KEY_NAME, name)
            .apply()
    }

    /** 恢复默认 app 目录。 */
    fun resetToDefault(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_URI)
            .remove(KEY_NAME)
            .apply()
    }

    // ---------- 目录解析 ----------

    /** 默认 app 专属存储根目录（无需权限，可自由读写）。 */
    fun defaultRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "manga_import")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 当前存储位置对应的「目标目录」：
     * @return 默认 → File（导入写到这里）；自定义 → DocumentFile（导入写入 SAF 树）
     */
    fun currentRoot(context: Context): PickedRoot? {
        val uri = currentUri(context)
        return if (uri == null) {
            PickedRoot.FileRoot(defaultRoot(context))
        } else {
            val doc = DocumentFile.fromTreeUri(context, Uri.parse(uri))
            if (doc == null) null else PickedRoot.DocRoot(doc)
        }
    }

    /** 存储目录入口的显示文本。 */
    fun describe(context: Context): String {
        val name = currentName(context)
        return if (name != null) {
            name  // 自定义：显示文件夹名
        } else {
            // 默认：显示 app 专属目录名
            defaultRoot(context).absolutePath
        }
    }

    // ---------- 「所有文件访问」权限（MANAGE_EXTERNAL_STORAGE） ----------

    /**
     * 是否已获得「所有文件访问」权限。
     * Android 11+ 用 MANAGE_EXTERNAL_STORAGE；Android 10 用 WRITE_EXTERNAL_STORAGE。
     */
    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /** 跳转系统「所有文件访问」授权页。 */
    fun allFilesAccessIntent(context: Context): Intent {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
        // 带上包名，直接定位到本应用
        intent.data = Uri.parse("package:${context.packageName}")
        return intent
    }
}

/** 当前存储位置目标：默认（File）或自定义 SAF（DocumentFile）。 */
sealed class PickedRoot {
    class FileRoot(val file: File) : PickedRoot()
    class DocRoot(val doc: DocumentFile) : PickedRoot()
}