package com.moe.starflow.mangaimport.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.SeekBar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.moe.starflow.R

/**
 * 显示选项底部弹窗：4 种列表模式 + 网格尺寸 + 排序。
 * 各选项切换即生效（无需点「应用」），通过回调实时持久化并刷新书架。
 */
class DisplayOptionsSheet(
    private val currentMode: DisplayMode,
    private val currentGridSize: Int,
    private val sortByAdded: Boolean,
    private val onApply: (DisplayMode, Int, Boolean) -> Unit
) : BottomSheetDialogFragment() {

    override fun onStart() {
        super.onStart()
        // 圆角对齐 app 弹窗主题（dialog_background 样式）
        (dialog as? BottomSheetDialog)
            ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(R.drawable.bg_bottom_sheet)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.sheet_display_options, container, false)

        val modeView = view.findViewById<RadioGroup>(R.id.rg_mode)
        val sizeView = view.findViewById<SeekBar>(R.id.seek_grid_size)
        val sortView = view.findViewById<RadioGroup>(R.id.rg_sort)

        fun modeToId(mode: DisplayMode): Int = when (mode) {
            DisplayMode.DETAILED_LIST -> R.id.rb_detailed
            DisplayMode.GRID -> R.id.rb_grid
        }

        modeView.check(modeToId(currentMode))
        sizeView.progress = currentGridSize
        sortView.check(
            if (sortByAdded) R.id.rb_sort_added else R.id.rb_sort_title
        )

        // 各选项切换即生效
        var selectedMode = currentMode
        modeView.setOnCheckedChangeListener { _, checkedId ->
            selectedMode = when (checkedId) {
                R.id.rb_detailed -> DisplayMode.DETAILED_LIST
                else -> DisplayMode.GRID
            }
            onApply(selectedMode, sizeView.progress, sortView.checkedRadioButtonId == R.id.rb_sort_added)
        }

        var gridSize = currentGridSize
        sizeView.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    gridSize = progress
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                // 拖动结束时自动应用（避免拖动中反复刷新）
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    onApply(selectedMode, gridSize, sortView.checkedRadioButtonId == R.id.rb_sort_added)
                }
            }
        )

        var sortAdded = sortByAdded
        sortView.setOnCheckedChangeListener { _, checkedId ->
            sortAdded = checkedId == R.id.rb_sort_added
            onApply(selectedMode, sizeView.progress, sortAdded)
        }

        return view
    }

    companion object {
        const val TAG = "DisplayOptionsSheet"
    }
}
