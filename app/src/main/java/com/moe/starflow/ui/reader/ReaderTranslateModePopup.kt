package com.moe.starflow.ui.reader

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.RadioButton
import android.widget.RadioGroup
import com.moe.starflow.R

/** 翻译按钮长按入口；选中实际模式后立即关闭，点击空白处也可关闭。 */
object ReaderTranslateModePopup {
    const val MANUAL = 0
    const val AUTO = 1
    const val AHEAD = 2

    fun show(
        anchor: View,
        selectedMode: Int,
        ocrOnly: Boolean,
        onModeSelected: (Int) -> Unit,
    ): PopupWindow {
        val content = LayoutInflater.from(anchor.context)
            .inflate(R.layout.popup_reader_translate_mode, null) as RadioGroup
        val buttons = listOf(
            content.findViewById<RadioButton>(R.id.reader_quick_mode_manual),
            content.findViewById<RadioButton>(R.id.reader_quick_mode_auto),
            content.findViewById<RadioButton>(R.id.reader_quick_mode_ahead),
        )
        if (ocrOnly) {
            buttons[MANUAL].setText(R.string.reader_ocr_mode_manual)
            buttons[AUTO].setText(R.string.reader_ocr_mode_auto)
            buttons[AHEAD].setText(R.string.reader_ocr_mode_incremental)
        }
        content.check(buttons[selectedMode.coerceIn(MANUAL, AHEAD)].id)

        val popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 8f * anchor.resources.displayMetrics.density
        }
        buttons.forEachIndexed { mode, button ->
            button.setOnClickListener {
                popup.dismiss()
                onModeSelected(mode)
            }
        }
        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val gap = (8f * anchor.resources.displayMetrics.density).toInt()
        popup.showAsDropDown(
            anchor,
            anchor.width - content.measuredWidth,
            -anchor.height - content.measuredHeight - gap,
        )
        return popup
    }
}
