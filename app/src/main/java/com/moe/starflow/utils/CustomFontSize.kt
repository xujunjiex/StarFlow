package com.moe.starflow.utils

import android.content.Context

/**
 * **游戏/视频翻译结果**字号设置。
 *
 * 与 [MangaFontSize] 是**两套独立设置**：游戏的结果浮层是普通 TextView，字号的语义是 sp；
 * 漫画是在气泡里排版，字号参与「自适应 / 紧凑矩形 / 合并块」一整套布局计算。混用会让
 * 一边的改动莫名其妙地影响另一边（历史上这两套就分别存在）。
 *
 * 本对象只做一件事：把「写」收拢到一处。此前 `Dialogs.fontSizeDialog` 自己写 prefs，
 * 调用方拿不到落盘点，也就没法在改完之后刷新界面或通知在跑的服务。
 */
object CustomFontSize {

    const val KEY = "Custom_Result_Font_Size"
    const val DEFAULT = 16f
    const val MIN = 8f
    const val MAX = 72f

    fun get(context: Context): Float =
        CustomPreference.getInstance(context).getFloat(KEY, DEFAULT)

    /** 写入并夹到合法区间（越界值会让浮层文字看不见或直接溢出屏幕）。 */
    fun setSize(context: Context, sp: Float) {
        CustomPreference.getInstance(context).setFloat(KEY, sp.coerceIn(MIN, MAX))
    }
}
