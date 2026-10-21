package com.moe.starflow.me.settings

import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.moe.starflow.R
import com.moe.starflow.manga.engine.PpRegionSizeFilter
import kotlin.math.roundToInt

/** 个性化页的 PP 尺寸过滤阈值面板。 */
object PpRegionSizeFilterPreferences {
    const val KEY_THRESHOLDS = "Manga_PP_Size_Filter_Thresholds"

    fun bind(screen: PreferenceScreen) {
        val pref = screen.findPreference<Preference>(KEY_THRESHOLDS) ?: return
        val prefs = screen.preferenceManager.sharedPreferences ?: return
        val context = screen.context
        fun updateSummary() {
            val config = PpRegionSizeFilter.read(prefs)
            pref.summary = context.getString(
                R.string.pp_size_filter_thresholds_summary, config.smallTolerance, config.largeTolerance
            )
        }
        updateSummary()
        pref.setOnPreferenceClickListener {
            val config = PpRegionSizeFilter.read(prefs)
            val density = context.resources.displayMetrics.density
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (20 * density).toInt()
                setPadding(pad, 0, pad, pad)
            }
            container.addView(TextView(context).apply {
                text = context.getString(R.string.pp_size_filter_thresholds_hint)
                textSize = 14f
            })
            fun row(title: Int, value: Double): SeekBar {
                val label = TextView(context).apply {
                    textSize = 15f
                    setPadding(0, (12 * density).toInt(), 0, 0)
                }
                fun show(tenths: Int) {
                    label.text = context.getString(title) + "  " +
                        context.getString(R.string.pp_size_filter_tolerance_value, tenths / 10.0)
                }
                show((value * 10).roundToInt())
                val seek = SeekBar(context).apply {
                    max = PpRegionSizeFilter.MAX_TENTHS - PpRegionSizeFilter.MIN_TENTHS
                    progress = (value * 10).roundToInt() - PpRegionSizeFilter.MIN_TENTHS
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                            show(progress + PpRegionSizeFilter.MIN_TENTHS)
                        }
                        override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                        override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                    })
                }
                container.addView(label)
                container.addView(seek)
                return seek
            }
            val small = row(R.string.pp_size_filter_small_title, config.smallTolerance)
            val large = row(R.string.pp_size_filter_large_title, config.largeTolerance)
            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.pp_size_filter_thresholds_title)
                .setView(ScrollView(context).apply { addView(container) })
                .setPositiveButton(R.string.save) { _, _ ->
                    prefs.edit()
                        .putInt(PpRegionSizeFilter.KEY_SMALL, small.progress + PpRegionSizeFilter.MIN_TENTHS)
                        .putInt(PpRegionSizeFilter.KEY_LARGE, large.progress + PpRegionSizeFilter.MIN_TENTHS)
                        .apply()
                    updateSummary()
                }
                .setNegativeButton(R.string.user_cancel, null)
                .create()
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
            true
        }
    }
}
