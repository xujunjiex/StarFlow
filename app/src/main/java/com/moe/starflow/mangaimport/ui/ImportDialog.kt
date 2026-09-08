package com.moe.starflow.mangaimport.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import com.moe.starflow.R
import com.moe.starflow.mangaimport.data.StorageDirStore

/**
 * 三选一导入弹窗（纯 UI）。SAF launcher 由调用方（Fragment）持有并在 onCreate 时注册，
 * 弹窗只负责渲染三个入口、把选中回调传回调用方。
 */
object ImportDialog {

    fun show(
        context: Context,
        onPickFiles: () -> Unit,
        onPickSingleDir: () -> Unit,
        onPickMultiDir: () -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_import_manga, null, false)
        // 存储位置提示：方便用户知道导入的漫画保存在哪
        view.findViewById<TextView>(R.id.tv_storage_hint).text =
            context.getString(R.string.import_storage_hint, StorageDirStore.root(context).absolutePath)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.import_manga_page)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .create()

        view.findViewById<TextView>(R.id.btn_import_file).setOnClickListener {
            onPickFiles()
            dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.btn_import_single_dir).setOnClickListener {
            onPickSingleDir()
            dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.btn_import_multi_dir).setOnClickListener {
            onPickMultiDir()
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }
}
