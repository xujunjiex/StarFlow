package com.moe.starflow.novel.reader

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.moe.starflow.R

/**
 * 小说弹窗的统一主题：跟随**阅读背景**深浅，**不跟系统主题**。
 *
 * ⚠️ `AlertDialog` 默认跟系统主题走，而小说阅读器的深浅是跟阅读背景走的 —— 浅色系统主题下切到
 * 深色背景，弹窗就是白底白字/白底黑字的突兀块（「清空本章」和「下载」以前就是这样）。
 * ⚠️ 面板与阅读器**共用这一份**：各写一份迟早分叉（一处修了另一处还是白底）。
 * 新增弹窗一律过这里。
 */
internal fun applyNovelDialogTheme(dlg: AlertDialog, dark: Boolean) {
    dlg.window?.setBackgroundDrawableResource(
        if (dark) R.drawable.bg_dialog_dark else R.drawable.bg_dialog_white,
    )
    if (dark) recolorDialogDark(dlg.window?.decorView)
}

/** 深色弹窗里把所有文字刷成浅色（背景换了、文字不换 = 白底白字）。 */
internal fun recolorDialogDark(v: View?) {
    if (v == null) return
    if (v is TextView) v.setTextColor(0xFFE2E2E4.toInt())
    if (v is ViewGroup) for (i in 0 until v.childCount) recolorDialogDark(v.getChildAt(i))
}
