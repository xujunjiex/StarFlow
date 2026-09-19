package com.moe.starflow.me.settings

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.moe.starflow.R
import com.moe.starflow.manga.config.ReplacementRule
import com.moe.starflow.manga.config.TranslationTextRules
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.UiUtils

/**
 * 「译文替换表」二级面板（漫画翻译结果设置 → 译文替换表）。
 *
 * 用途：渲染译文 overlay 时把文本过一遍用户配置的规则，例如识别到的省略号 `...` 换成英文句号 `.`、
 * 去掉模型爱加的「~」等。
 *
 * ⚠️ 生效时机是**渲染时**（`OverlayRenderer` 里的 `TranslationTextRules.process`），
 * 不是翻译完成时：overlay 是后期画上去的，所以改完规则**回阅读器重新渲染即生效、不用重翻**。
 *
 * 实现：**动态 Preference 屏**（不是 XML 里写死的几行）—— 规则条数由用户决定，每条规则一行：
 * 点行 = 编辑（弹窗里同时提供「删除」），底部固定一行「添加规则」。
 * 规则存默认 SharedPreferences 的 JSON（见 [TranslationTextRules]），与其它设置同一份。
 */
class TranslationReplacementFragment : PreferenceFragmentCompat() {

    private val prefs get() = CustomPreference.getInstance(requireContext()).getSharedPreferences()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        render()
    }

    /** 按当前规则重建整个偏好屏（规则是用户数据，改动后整体重排最省心）。 */
    private fun render() {
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        val rules = TranslationTextRules.load(prefs)

        if (rules.isEmpty()) {
            screen.addPreference(disabledPreference(
                getString(R.string.manga_replacement_empty_title),
                getString(R.string.manga_replacement_empty_hint)
            ))
        }

        rules.forEachIndexed { index, rule ->
            screen.addPreference(Preference(requireContext()).apply {
                title = rule.from
                summary = if (rule.to.isEmpty()) {
                    getString(R.string.manga_replacement_row_summary_delete)
                } else {
                    getString(R.string.manga_replacement_row_summary, rule.to)
                }
                setOnPreferenceClickListener {
                    showRuleDialog(index)
                    true
                }
            })
        }

        screen.addPreference(Preference(requireContext()).apply {
            title = getString(R.string.manga_replacement_add)
            summary = getString(
                R.string.manga_replacement_add_summary,
                rules.size,
                TranslationTextRules.MAX_RULES
            )
            setOnPreferenceClickListener {
                showRuleDialog(null)
                true
            }
        })

        screen.addPreference(disabledPreference(
            getString(R.string.manga_replacement_note_title),
            getString(R.string.manga_replacement_note_summary)
        ))

        preferenceScreen = screen
    }

    private fun disabledPreference(title: String, summary: String) =
        Preference(requireContext()).apply {
            this.title = title
            this.summary = summary
            isEnabled = false
        }

    /**
     * 新建（[index] = null）或编辑（[index] = 规则下标）一条规则。
     *
     * 「替换为」允许留空（= 删掉这段内容），所以只有「查找内容」做非空校验 ——
     * 空 from 的规则会在 `String.replace("")` 里往每个字符之间插内容，必须挡住。
     */
    private fun showRuleDialog(index: Int?) {
        val rules = TranslationTextRules.load(prefs).toMutableList()
        val editing = index != null && index in rules.indices
        if (!editing && rules.size >= TranslationTextRules.MAX_RULES) {
            UiUtils.showToast(
                requireContext(),
                getString(R.string.manga_replacement_limit, TranslationTextRules.MAX_RULES)
            )
            return
        }
        val current = if (editing) rules[index!!] else ReplacementRule("", "")

        val ctx = requireContext()
        val pad = (20 * ctx.resources.displayMetrics.density).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 3, pad, 0)
        }

        fun label(textRes: Int): TextView = TextView(ctx).apply {
            setText(textRes)
            textSize = 13f
            setPadding(0, pad / 2, 0, 0)
        }

        val fromField = EditText(ctx).apply {
            hint = getString(R.string.manga_replacement_from_hint)
            setText(current.from)
            setSelection(current.from.length)
            setSingleLine(true)
            gravity = Gravity.START
        }
        val toField = EditText(ctx).apply {
            hint = getString(R.string.manga_replacement_to_hint)
            setText(current.to)
            setSelection(current.to.length)
            setSingleLine(true)
            gravity = Gravity.START
        }
        container.addView(label(R.string.manga_replacement_from_label))
        container.addView(fromField)
        container.addView(label(R.string.manga_replacement_to_label))
        container.addView(toField)

        val builder = AlertDialog.Builder(ctx)
            .setTitle(
                if (editing) R.string.manga_replacement_edit_title
                else R.string.manga_replacement_add_title
            )
            .setView(container)
            .setPositiveButton(R.string.save, null)   // show 后再绑，避免校验失败时被自动关闭
            .setNegativeButton(R.string.cancel, null)
        if (editing) {
            // 删除不需要校验 → 直接用真监听（而不是 null + onShow 里再绑：某些实现下
            // getButton(BUTTON_NEUTRAL) 拿不到按钮，监听就静默失效了）
            builder.setNeutralButton(R.string.manga_replacement_delete) { _, _ ->
                rules.removeAt(index!!)
                TranslationTextRules.save(prefs, rules)
                render()
                UiUtils.showToast(requireContext(), getString(R.string.manga_replacement_deleted))
            }
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // ⚠️ 用 isBlank 而不是 isEmpty：纯空格规则（" "）会把每段译文里的空格成片替换/删除，
                // 效果和「空 from」一样是灾难（空 from 已经被 apply 挡掉），只是更隐蔽
                val from = fromField.text.toString()
                if (from.isBlank()) {
                    UiUtils.showToast(requireContext(), getString(R.string.manga_replacement_from_empty))
                    return@setOnClickListener
                }
                val rule = ReplacementRule(from, toField.text.toString())
                if (editing) rules[index!!] = rule else rules.add(rule)
                TranslationTextRules.save(prefs, rules)
                dialog.dismiss()
                render()
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }
}
