package com.moe.starflow.mangaimport.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import com.moe.starflow.R

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
    }
}
