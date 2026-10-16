package com.moe.starflow.novel.reader

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import com.jaredrummler.android.colorpicker.ColorPickerView
import com.moe.starflow.R
import com.moe.starflow.utils.ReaderDialogs
import kotlin.math.roundToInt

/** Personalization's HSV picker inside compact dialogs themed by the active reader. */
object NovelColorDialogs {
    private const val ACCENT = 0xFF55AEEA.toInt()
    private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun label(dark: Boolean) = if (dark) ReaderDialogs.LABEL_DARK else ReaderDialogs.LABEL_LIGHT
    private fun secondary(dark: Boolean) = if (dark) ReaderDialogs.SUB_DARK else ReaderDialogs.SUB_LIGHT

    fun hex(color: Int, alpha: Boolean = true): String =
        if (alpha) String.format("#%08X", color) else String.format("#%06X", color and 0xFFFFFF)

    private fun dialogTitle(context: Context, title: Int, dark: Boolean) = TextView(context).apply {
        setText(title)
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(label(dark))
        setPadding(context.dp(20), context.dp(18), context.dp(20), context.dp(8))
    }

    private fun styleActions(dialog: AlertDialog, dark: Boolean) {
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach {
            dialog.getButton(it)?.apply {
                setTextColor(if (dark) ACCENT else 0xFF247BAA.toInt())
                textSize = 14f
                isAllCaps = false
            }
        }
    }

    fun picker(
        context: Context, dark: Boolean, title: Int, initial: Int, default: Int,
        alpha: Boolean = true, onSave: (Int?) -> Unit,
    ) {
        val ctx = ReaderDialogs.context(context, dark)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), ctx.dp(8))
        }
        val picker = ColorPickerView(ctx).apply {
            setAlphaSliderVisible(alpha)
            setAlphaSliderText(ctx.getString(R.string.novel_color_opacity))
            setColor(initial, false)
            borderColor = secondary(dark)
        }
        picker.setOnTouchListener { view, _ -> view.parent?.requestDisallowInterceptTouchEvent(true); false }
        val pickerHeight = (ctx.resources.configuration.screenHeightDp * 0.38f).toInt().coerceIn(120, 200)
        content.addView(picker, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(pickerHeight)))
        val valueRow = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ctx.dp(8), 0, 0)
        }
        val preview = NovelColorSwatch(ctx).apply { color = initial; this.dark = dark }
        valueRow.addView(preview, LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)).apply { marginEnd = ctx.dp(12) })
        val input = EditText(ctx).apply {
            setSingleLine()
            textSize = 14f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(label(dark))
            setHintTextColor(secondary(dark))
            setSelectAllOnFocus(true)
            hint = if (alpha) "#AARRGGBB" else "#RRGGBB"
            backgroundTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(ACCENT, secondary(dark)),
            )
            textCursorDrawable = textCursorDrawable?.mutate()?.apply { setTint(ACCENT) }
            setText(hex(initial, alpha))
        }
        valueRow.addView(input, LinearLayout.LayoutParams(0, ctx.dp(44), 1f))
        content.addView(valueRow)
        if (alpha) content.addView(TextView(ctx).apply {
            setText(R.string.novel_status_color_hint)
            setTextColor(secondary(dark))
            textSize = 11f
            setPadding(0, ctx.dp(4), 0, 0)
        })
        ReaderDialogs.tintCustomView(content, dark)
        var syncing = false
        var reset = false
        fun showValue(color: Int) {
            preview.color = color
            syncing = true
            input.setText(hex(color, alpha))
            input.error = null
            syncing = false
        }
        picker.setOnColorChangedListener { color -> reset = false; showValue(color) }
        fun parsed(): Int? = runCatching {
            val text = input.text.toString().trim().removePrefix("#")
            require(text.length == 6 || (alpha && text.length == 8))
            Color.parseColor("#$text").let { if (alpha) it else it or 0xFF000000.toInt() }
        }.getOrNull()
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!syncing) {
                    reset = false
                    parsed()?.let { picker.setColor(it, false); preview.color = it }
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        val scroll = ScrollView(ctx).apply { addView(content) }
        val dialog = ReaderDialogs.show(context, dark) {
            setCustomTitle(dialogTitle(ctx, title, dark))
            setView(scroll)
            setPositiveButton(R.string.save, null)
            setNegativeButton(R.string.user_cancel, null)
            setNeutralButton(R.string.reset_default_color, null)
        }
        styleActions(dialog, dark)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            picker.setColor(default, false)
            showValue(default)
            reset = true
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val color = parsed()
            if (color == null) input.error = ctx.getString(R.string.novel_color_invalid)
            else { onSave(if (reset) null else color); dialog.dismiss() }
        }
    }

    private data class ColorRow(val root: LinearLayout, val value: TextView, val sample: NovelColorSwatch)

    private fun colorRow(context: Context, title: Int, dark: Boolean): ColorRow {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(48)
            setPadding(0, context.dp(8), 0, context.dp(8))
            isClickable = true
        }
        row.addView(TextView(context).apply {
            setText(title)
            textSize = 14f
            setTextColor(label(dark))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val value = TextView(context).apply { textSize = 12f; setTextColor(secondary(dark)) }
        row.addView(value, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = context.dp(12) })
        val sample = NovelColorSwatch(context).apply { this.dark = dark }
        row.addView(sample, LinearLayout.LayoutParams(context.dp(20), context.dp(20)))
        return ColorRow(row, value, sample)
    }

    fun customAppearance(
        context: Context, background: Int, text: Int,
        dark: Boolean = ColorUtils.calculateLuminance(background) < 0.5,
        onSave: (Int, Int) -> Unit,
    ) {
        // Editor chrome follows the active reader. Draft colors affect only the reading sample.
        val ctx = ReaderDialogs.context(context, dark)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), 0, ctx.dp(20), ctx.dp(12))
        }
        var draftBackground = background
        var draftText = text
        val bgRow = colorRow(ctx, R.string.novel_custom_background_color, dark)
        val textRow = colorRow(ctx, R.string.novel_custom_text_color, dark)
        content.addView(bgRow.root)
        content.addView(textRow.root)
        ReaderDialogs.tintCustomView(content, dark)
        val sample = TextView(ctx).apply {
            setText(R.string.novel_color_preview)
            textSize = 14f
            typeface = NovelReaderFont.resolve(context).typeface
            setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
        }
        content.addView(sample, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })
        fun refresh() {
            bgRow.sample.color = draftBackground
            bgRow.value.text = hex(draftBackground, false)
            textRow.sample.color = draftText
            textRow.value.text = hex(draftText, false)
            sample.background = GradientDrawable().apply {
                cornerRadius = ctx.dp(6).toFloat()
                setColor(draftBackground)
                setStroke(ctx.dp(1), if (dark) 0xFF4A4E55.toInt() else 0xFFDEE2E6.toInt())
            }
            sample.setTextColor(draftText)
        }
        refresh()
        val dialog = ReaderDialogs.show(context, dark) {
            setCustomTitle(dialogTitle(ctx, R.string.novel_custom_appearance, dark))
            setView(content)
            setPositiveButton(R.string.save) { _, _ -> onSave(draftBackground, draftText) }
            setNegativeButton(R.string.user_cancel, null)
            setNeutralButton(R.string.reset_default_color, null)
        }
        styleActions(dialog, dark)
        bgRow.root.setOnClickListener {
            picker(context, dark, R.string.novel_custom_background_color, draftBackground, NovelAppearance.DEFAULT_BACKGROUND, false) {
                draftBackground = it ?: NovelAppearance.DEFAULT_BACKGROUND
                refresh()
            }
        }
        textRow.root.setOnClickListener {
            picker(context, dark, R.string.novel_custom_text_color, draftText, NovelAppearance.DEFAULT_TEXT, false) {
                draftText = it ?: NovelAppearance.DEFAULT_TEXT
                refresh()
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            draftBackground = NovelAppearance.DEFAULT_BACKGROUND
            draftText = NovelAppearance.DEFAULT_TEXT
            refresh()
        }
    }
}
