package com.moe.starflow.mangaimport.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.moe.starflow.R

/**
 * 显示选项底部弹窗：4 种列表模式 + 网格尺寸 + 排序。
 * 选择结果通过回调返回，由 Fragment 持久化到 SharedPreferences。
 */
class DisplayOptionsSheet(
    private val currentMode: DisplayMode,
    private val currentGridSize: Int,
    private val sortByAdded: Boolean,
    private val onApply: (DisplayMode, Int, Boolean) -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.sheet_display_options, container, false)

        fun modeToId(mode: DisplayMode): Int = when (mode) {
            DisplayMode.LIST -> R.id.rb_list
            DisplayMode.DETAILED_LIST -> R.id.rb_detailed
            DisplayMode.GRID -> R.id.rb_grid
            DisplayMode.COMPACT_GRID -> R.id.rb_compact
        }

        view.findViewById<RadioGroup>(R.id.rg_mode).check(modeToId(currentMode))
        view.findViewById<SeekBar>(R.id.seek_grid_size).progress = currentGridSize
        view.findViewById<RadioGroup>(R.id.rg_sort).check(
            if (sortByAdded) R.id.rb_sort_added else R.id.rb_sort_title
        )

        var selectedMode = currentMode
        view.findViewById<RadioGroup>(R.id.rg_mode).setOnCheckedChangeListener { _, checkedId ->
            selectedMode = when (checkedId) {
                R.id.rb_list -> DisplayMode.LIST
                R.id.rb_detailed -> DisplayMode.DETAILED_LIST
                R.id.rb_grid -> DisplayMode.GRID
                else -> DisplayMode.COMPACT_GRID
            }
        }

        var gridSize = currentGridSize
        view.findViewById<SeekBar>(R.id.seek_grid_size).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    gridSize = progress
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }
        )

        var sortAdded = sortByAdded
        view.findViewById<RadioGroup>(R.id.rg_sort).setOnCheckedChangeListener { _, checkedId ->
            sortAdded = checkedId == R.id.rb_sort_added
        }

        view.findViewById<Button>(R.id.sheet_apply).setOnClickListener {
            onApply(selectedMode, gridSize, sortAdded)
            dismiss()
        }

        return view
    }

    companion object {
        const val TAG = "DisplayOptionsSheet"
    }
}
