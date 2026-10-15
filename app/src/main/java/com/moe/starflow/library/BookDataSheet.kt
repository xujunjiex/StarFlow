package com.moe.starflow.library

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.moe.starflow.R

/** One selectable document lets the native selection span labels, values and text blocks. */
internal object BookDataSheet {
    fun create(activity: BookDataActivity, title: String, content: CharSequence, raw: String? = null,
        monospace: Boolean = false): BottomSheetDialog {
        val context = activity
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        fun color(id: Int) = ContextCompat.getColor(context, id)
        val dialog = BottomSheetDialog(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), 0)
            layoutParams = android.view.ViewGroup.LayoutParams(-1, (context.resources.displayMetrics.heightPixels * .88f).toInt())
        }
        root.addView(View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(color(R.color.divider)); cornerRadius = dp(2).toFloat()
            }
        }, LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) })
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply {
            text = title; textSize = 19f; setTextColor(color(R.color.text_primary)); setTypeface(typeface, Typeface.BOLD)
            setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val body = TextView(context).apply {
            text = content; textSize = 15f; setLineSpacing(dp(4).toFloat(), 1f)
            setTextColor(color(R.color.text_primary)); setTextIsSelectable(true)
            if (monospace) typeface = Typeface.MONOSPACE
            setPadding(0, dp(16), 0, dp(32))
        }
        fun icon(drawable: Int, description: Int, action: (View) -> Unit) = AppCompatImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)); setPadding(dp(12), dp(12), dp(12), dp(12))
            setImageResource(drawable); imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.text_primary))
            contentDescription = context.getString(description)
            val background = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true)
            setBackgroundResource(background.resourceId); setOnClickListener(action)
        }
        if (raw != null) {
            var showingRaw = false
            header.addView(icon(R.drawable.ic_reader_more, R.string.book_data_actions) { anchor ->
                PopupMenu(context, anchor).apply {
                    menu.add(if (showingRaw) R.string.book_record_formatted else R.string.book_data_raw)
                    setOnMenuItemClickListener {
                        showingRaw = !showingRaw
                        body.text = if (showingRaw) raw else content
                        body.typeface = if (showingRaw) Typeface.MONOSPACE else Typeface.DEFAULT
                        true
                    }
                    show()
                }
            })
        }
        header.addView(icon(R.drawable.ic_close, R.string.close) { dialog.dismiss() })
        root.addView(header)
        root.addView(TextView(context).apply {
            setText(R.string.book_record_select_hint); textSize = 12f; setTextColor(color(R.color.text_secondary))
            setTextIsSelectable(true)
        })
        root.addView(NestedScrollView(context).apply { isFillViewport = true; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(root)
        dialog.setOnShowListener {
            val dark = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundResource(if (dark) R.drawable.bg_bottom_sheet_dark else R.drawable.bg_bottom_sheet_light)
            dialog.behavior.apply { skipCollapsed = true; state = BottomSheetBehavior.STATE_EXPANDED }
        }
        return dialog
    }
}
