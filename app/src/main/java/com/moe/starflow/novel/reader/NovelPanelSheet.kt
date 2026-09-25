package com.moe.starflow.novel.reader

import android.app.AlertDialog
import android.content.Context
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.moe.starflow.R
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.MangaFontSize

/**
 * 阅读器的三个面板：样式 / 翻译 / 更多。
 *
 * ### 配色约定（加控件必须遵守）
 * 面板**不随全局主题**，按阅读背景深浅翻转。所有可见文字都走 [label] / [value] 生成，
 * 它们会显式 `setTextColor` —— 漏掉这一步的控件在深色面板下会保持布局默认的深色字，
 * 压在同色背景上看不见。
 *
 * ### 尺寸约定
 * 章节标题行 14sp、说明行 12sp，与漫画阅读器的面板一致。
 */
object NovelPanelSheet {

    private const val DARK_BG = 0xFF1E1E1E.toInt()
    private const val LIGHT_BG = 0xFFF5F5F5.toInt()
    private const val DARK_LABEL = 0xFFECECEC.toInt()
    private const val LIGHT_LABEL = 0xFF222222.toInt()
    private const val DARK_SUB = 0xFFAAAAAA.toInt()
    private const val LIGHT_SUB = 0xFF666666.toInt()

    // ===== 对外 =====

    fun showStyle(context: Context, prefs: CustomPreference, onChanged: () -> Unit) {
        val dark = isDark(context, prefs)
        val root = container(context, dark)
        val dm = context.resources.displayMetrics

        root.addView(label(context, dark, context.getString(R.string.novel_style_font_size)))
        val sizeValue = value(context, dark)
        root.addView(sizeValue)
        sizeValue.text = "${MangaFontSize.size(context).toInt()} sp"
        root.addView(
            seek(
                context,
                (MangaFontSize.size(context) - MangaFontSize.MIN_SIZE).toInt(),
                (MangaFontSize.MAX_SIZE - MangaFontSize.MIN_SIZE).toInt(),
            ) { v ->
                MangaFontSize.setSize(context, (v + MangaFontSize.MIN_SIZE).toFloat())
                sizeValue.text = "${v + MangaFontSize.MIN_SIZE} sp"
                onChanged()
            }
        )

        root.addView(label(context, dark, context.getString(R.string.novel_style_line_spacing)))
        val lineValue = value(context, dark)
        lineValue.text = NovelPanelStyle.lineSpacingLabel(NovelPanelStyle.lineSpacingStep(prefs))
        root.addView(lineValue)
        root.addView(
            seek(
                context,
                NovelPanelStyle.lineSpacingStep(prefs) - NovelPanelStyle.LINE_SPACING_MIN,
                NovelPanelStyle.LINE_SPACING_MAX - NovelPanelStyle.LINE_SPACING_MIN,
            ) { v ->
                val step = v + NovelPanelStyle.LINE_SPACING_MIN
                NovelPanelStyle.setLineSpacingStep(prefs, step)
                lineValue.text = NovelPanelStyle.lineSpacingLabel(step)
                onChanged()
            }
        )

        root.addView(label(context, dark, context.getString(R.string.novel_style_paragraph_spacing)))
        val paraValue = value(context, dark)
        paraValue.text = "${NovelPanelStyle.paragraphSpacingDp(prefs)} dp"
        root.addView(paraValue)
        root.addView(
            seek(
                context,
                NovelPanelStyle.paragraphSpacingDp(prefs) - NovelPanelStyle.PARAGRAPH_SPACING_MIN,
                NovelPanelStyle.PARAGRAPH_SPACING_MAX - NovelPanelStyle.PARAGRAPH_SPACING_MIN,
            ) { v ->
                val dp = v + NovelPanelStyle.PARAGRAPH_SPACING_MIN
                NovelPanelStyle.setParagraphSpacingDp(prefs, dp)
                paraValue.text = "$dp dp"
                onChanged()
            }
        )

        root.addView(label(context, dark, context.getString(R.string.novel_style_padding)))
        val padValue = value(context, dark)
        padValue.text = "${NovelPanelStyle.paddingDp(prefs)} dp"
        root.addView(padValue)
        root.addView(
            seek(
                context,
                NovelPanelStyle.paddingDp(prefs) - NovelPanelStyle.PADDING_MIN,
                NovelPanelStyle.PADDING_MAX - NovelPanelStyle.PADDING_MIN,
            ) { v ->
                val dp = v + NovelPanelStyle.PADDING_MIN
                NovelPanelStyle.setPaddingDp(prefs, dp)
                padValue.text = "$dp dp"
                onChanged()
            }
        )

        dialog(context, prefs, root).show()
    }

    fun showTranslate(
        context: Context,
        prefs: CustomPreference,
        onTranslateNow: () -> Unit,
        onClearBook: () -> Unit,
        onChanged: () -> Unit,
    ) {
        val dark = isDark(context, prefs)
        val root = container(context, dark)

        root.addView(label(context, dark, context.getString(R.string.novel_translate_mode)))
        val modeValue = value(context, dark)
        modeValue.text = NovelPanelStyle.modeLabel(context, NovelPanelStyle.translateMode(prefs))
        root.addView(modeValue)
        root.addView(seek(context, NovelPanelStyle.translateMode(prefs), NovelPanelStyle.MODE_AUTO_AHEAD) { v ->
            NovelPanelStyle.setTranslateMode(prefs, v)
            modeValue.text = NovelPanelStyle.modeLabel(context, v)
            onChanged()
        })

        root.addView(label(context, dark, context.getString(R.string.novel_translate_debounce)))
        val dbValue = value(context, dark)
        dbValue.text = context.getString(R.string.novel_translate_debounce_value, NovelPanelStyle.debounceMs(prefs))
        root.addView(dbValue)
        root.addView(
            seek(
                context,
                NovelPanelStyle.debounceMs(prefs) - NovelPanelStyle.DEBOUNCE_MIN,
                NovelPanelStyle.DEBOUNCE_MAX - NovelPanelStyle.DEBOUNCE_MIN,
            ) { v ->
                val ms = v + NovelPanelStyle.DEBOUNCE_MIN
                NovelPanelStyle.setDebounceMs(prefs, ms)
                dbValue.text = context.getString(R.string.novel_translate_debounce_value, ms)
            }
        )

        root.addView(label(context, dark, context.getString(R.string.novel_translate_ahead)))
        val aheadValue = value(context, dark)
        aheadValue.text = context.getString(R.string.novel_translate_ahead_value, NovelPanelStyle.aheadChapters(prefs))
        root.addView(aheadValue)
        root.addView(
            seek(
                context,
                NovelPanelStyle.aheadChapters(prefs) - NovelPanelStyle.AHEAD_MIN,
                NovelPanelStyle.AHEAD_MAX - NovelPanelStyle.AHEAD_MIN,
            ) { v ->
                val n = v + NovelPanelStyle.AHEAD_MIN
                NovelPanelStyle.setAheadChapters(prefs, n)
                aheadValue.text = context.getString(R.string.novel_translate_ahead_value, n)
                onChanged()
            }
        )

        root.addView(action(context, dark, context.getString(R.string.novel_translate_action), onTranslateNow))
        root.addView(action(context, dark, context.getString(R.string.novel_translate_clear), onClearBook))

        dialog(context, prefs, root).show()
    }

    fun showMore(context: Context, prefs: CustomPreference, onChanged: () -> Unit) {
        val dark = isDark(context, prefs)
        val root = container(context, dark)

        val modeValue = value(context, dark)
        modeValue.text = context.getString(R.string.novel_style_reader_mode) + "：" +
            context.getString(
                if (NovelPanelStyle.readerMode(prefs) == NovelPanelStyle.READER_SCROLL) {
                    R.string.novel_reader_mode_scroll
                } else {
                    R.string.novel_reader_mode_paged
                }
            )
        root.addView(modeValue)
        root.addView(
            action(context, dark, context.getString(R.string.novel_style_reader_mode)) {
                val next = if (NovelPanelStyle.readerMode(prefs) == NovelPanelStyle.READER_SCROLL) {
                    NovelPanelStyle.READER_PAGED
                } else {
                    NovelPanelStyle.READER_SCROLL
                }
                NovelPanelStyle.setReaderMode(prefs, next)
                onChanged()
            }
        )

        val displayValue = value(context, dark)
        displayValue.text = context.getString(R.string.novel_display_translated) + "：" +
            NovelPanelStyle.displayModeLabel(context, NovelPanelStyle.displayMode(prefs))
        root.addView(displayValue)
        root.addView(action(context, dark, context.getString(R.string.novel_display_translated)) {
            NovelPanelStyle.setDisplayMode(prefs, NovelDisplayModeCodec.next(NovelPanelStyle.displayMode(prefs)))
            onChanged()
        })

        dialog(context, prefs, root).show()
    }

    // ===== 控件构造 =====

    private fun dialog(context: Context, prefs: CustomPreference, view: LinearLayout): AlertDialog {
        val d = AlertDialog.Builder(context)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .create()
        d.setOnShowListener { d.window?.setBackgroundDrawableResource(R.drawable.dialog_background) }
        return d
    }

    /** 阅读背景是否为深色（面板据此翻转配色）。 */
    private fun isDark(context: Context, prefs: CustomPreference): Boolean {
        val bg = prefs.getInt("reader_background", 0)
        if (bg != 5) return bg == 4
        val night = (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return night
    }

    private fun container(context: Context, dark: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(if (dark) DARK_BG else LIGHT_BG)
        val pad = dp(context, 16)
        setPadding(pad, pad, pad, pad)
    }

    private fun label(context: Context, dark: Boolean, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        setTextColor(if (dark) DARK_LABEL else LIGHT_LABEL)
        setPadding(0, dp(context, 12), 0, dp(context, 4))
    }

    private fun value(context: Context, dark: Boolean): TextView = TextView(context).apply {
        textSize = 14f
        setTextColor(if (dark) DARK_LABEL else LIGHT_LABEL)
    }

    private fun action(context: Context, dark: Boolean, text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(if (dark) DARK_LABEL else LIGHT_LABEL)
            setPadding(0, dp(context, 14), 0, dp(context, 10))
            isClickable = true
            setOnClickListener { onClick() }
        }

    /**
     * 滑块。`min`/`max` 在代码里显式设置，`progress` 用**绝对值** —— 设了 `min` 之后
     * `progress` 就是绝对刻度，再按 `value - MIN` 偏移会双重扣减。
     */
    private fun seek(
        context: Context,
        min: Int,
        max: Int,
        from: Int,
        onChange: (Int) -> Unit,
    ): SeekBar = SeekBar(context).apply {
        this.min = min
        this.max = max.coerceAtLeast(min + 1)
        progress = from.coerceIn(min, this.max)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) onChange(value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
    }

    /** 简化版：`0..max` 的滑块，用法同 [seek] 但起点恒为 0。 */
    private fun seek(context: Context, from: Int, max: Int, onChange: (Int) -> Unit): SeekBar =
        seek(context, 0, max, from, onChange)

    private fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
