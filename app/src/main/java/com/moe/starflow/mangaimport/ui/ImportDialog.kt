package com.moe.starflow.mangaimport.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import com.moe.starflow.R
import com.moe.starflow.mangaimport.data.StorageDirStore

/**
 * 二选一导入弹窗（纯 UI）。SAF launcher 由调用方（Fragment）持有并在 onCreate 时注册，
 * 弹窗只负责渲染两个入口、把选中回调传回调用方。
 *
 * ⚠️ 曾有的第三项「导入多个漫画文件夹」已删除：SAF 的 ACTION_OPEN_DOCUMENT_TREE 只返回**一个**
 * tree uri（系统选择器不给多选目录），原先那一项接的是与「导入文件夹」完全相同的
 * launcher，行为一模一样，纯属误导。要恢复多夹语义必须新做遍历后端，不要只把入口加回来。
 */
object ImportDialog {

    fun show(
        context: Context,
        onPickFiles: () -> Unit,
        onPickSingleDir: () -> Unit
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

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }
}
