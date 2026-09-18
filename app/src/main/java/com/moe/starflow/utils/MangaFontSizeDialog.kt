package com.moe.starflow.utils

import android.content.Context
import android.content.DialogInterface
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.moe.starflow.R

/**
 * 漫画「字体大小」选择弹窗 —— **三个入口共用的唯一实现**。
 *
 * 入口：个性化设置页、漫画悬浮窗菜单、阅读器翻译面板。它们的父容器主题各不相同
 * （设置页随全局 DayNight、悬浮窗是 `TYPE_APPLICATION_OVERLAY`、阅读器面板自绘深浅），
 * 各写一套的结果就是"同一个弹窗三个样子"（踩过：阅读器那版标题与列表项跟自绘背景同色，
 * 字完全看不见）。所以配色与尺寸都收在这里，调用方只传 [dark]。
 *
 * 尺寸上限（三处一致，用户要求）：宽不超过屏幕 80% 且不超过 280dp；
 * 列表高不超过屏幕 50% 且不超过 320dp（超出则列表内部滚动）。
 */
object MangaFontSizeDialog {

    private const val MAX_WIDTH_DP = 280
    private const val MAX_LIST_HEIGHT_DP = 320
    private const val ITEM_HEIGHT_DP = 48

    /**
     * @param dark 背景是否为深色（决定文字色与背景 drawable）
     * @param onPicked 选择完成回调（已落盘，调用方只需刷新自己的显示）
     * @return 已 `create()` 但**未 show** 的对话框，调用方按需设置窗口类型后再 show
     */
    fun create(context: Context, dark: Boolean, onPicked: () -> Unit = {}): AlertDialog {
        val density = context.resources.displayMetrics.density
        val dp: (Int) -> Int = { (it * density).toInt() }
        val textColor = if (dark) 0xFFE2E2E4.toInt() else 0xFF333333.toInt()
        val accent = 0xFF55AEEA.toInt()

        val labels = arrayOf(context.getString(R.string.manga_font_size_auto)) +
            MangaFontSize.PRESET_SIZES.map { "$it sp" }
        val checked = if (MangaFontSize.isAuto(context)) 0 else MangaFontSize.presetIndexOf(context) + 1

        val list = ListView(context).apply {
            adapter = object : ArrayAdapter<String>(
                context, android.R.layout.simple_list_item_1, labels
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = convertView ?: LayoutInflater.from(context)
                        .inflate(android.R.layout.simple_list_item_1, parent, false)
                    v.findViewById<TextView>(android.R.id.text1).apply {
                        text = labels[position]
                        setTextColor(if (position == checked) accent else textColor)
                        // 选中项加粗：深浅背景下都一眼看得出当前值
                        setTypeface(typeface, if (position == checked) Typeface.BOLD else Typeface.NORMAL)
                    }
                    return v
                }
            }
        }
        // 限高：条目不多时按内容高，多了就在列表内部滚（不要让弹窗顶到屏幕外）
        val maxListHeight = minOf(
            (context.resources.displayMetrics.heightPixels * 0.5f).toInt(),
            dp(MAX_LIST_HEIGHT_DP)
        )
        val container = FrameLayout(context).apply {
            addView(list, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                minOf(dp(ITEM_HEIGHT_DP) * labels.size, maxListHeight)
            ))
        }

        val dialog = AlertDialog.Builder(context)
            .setCustomTitle(TextView(context).apply {
                text = context.getString(R.string.manga_font_size_title)
                setTextColor(textColor)
                textSize = 18f
                setPadding(dp(16), dp(16), dp(16), dp(4))
                gravity = Gravity.CENTER_HORIZONTAL
            })
            .setView(container)
            .setNegativeButton(R.string.user_cancel, null)
            .create()

        list.setOnItemClickListener { _, _, position, _ ->
            if (position == 0) MangaFontSize.setAuto(context, true)
            else MangaFontSize.setSize(context, MangaFontSize.PRESET_SIZES[position - 1].toFloat())
            onPicked()
            dialog.dismiss()
        }

        // ⚠️ 窗口在 `create()` 时就已经存在 —— 尺寸/位置/背景必须**在这里**设好。
        // 放到 `setOnShowListener` 里会先按默认布局显示一帧、再被改成限制后的尺寸并重新居中，
        // 肉眼看到的就是「先在左边出现，然后瞬移到中间」（踩过）。
        dialog.window?.apply {
            setBackgroundDrawableResource(
                if (dark) R.drawable.bg_dialog_dark else R.drawable.bg_dialog_white
            )
            // 宽度限住（默认铺满屏宽，手机上很空）；高度由内容决定
            val maxWidth = minOf(
                (context.resources.displayMetrics.widthPixels * 0.8f).toInt(),
                dp(MAX_WIDTH_DP)
            )
            setLayout(maxWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            // 显式居中：不设重力时自定义 view 的弹窗可能先靠左排布
            setGravity(Gravity.CENTER)
        }
        // 负按钮的文字色来自 dialog 主题，与自绘背景未必搭 —— 按钮此时还没创建，只能等 show 后上色
        // （只改颜色的重绘不会引起位移，与上面的布局问题不是一回事）
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.setTextColor(accent)
        }
        return dialog
    }
}
