package com.moe.starflow.chat
import com.moe.starflow.translate.widget.*

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.moe.starflow.R

/**
 * 问号按钮弹层：7 套翻译模板列表 → 点开详情（完整模板 + 变量说明）→ 复制全文。
 * 模板仅作参考，用户复制后自行组织提问。
 */
class ChatTemplateSheet : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 48)
        }
        root.addView(TextView(requireContext()).apply {
            text = requireContext().getString(R.string.chat_template_sheet_title)
            textSize = 18f
            setPadding(0, 0, 0, 32)
        })
        ChatTemplates.all.forEach { tpl ->
            root.addView(TextView(requireContext()).apply {
                text = "📋 " + requireContext().getString(tpl.labelRes)
                textSize = 16f
                setPadding(0, 24, 0, 24)
                setOnClickListener { showDetail(tpl) }
            })
        }
        return root
    }

    private fun showDetail(tpl: ChatTemplate) {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_template_detail, null, false)
        // 三部分卡片（中文/英文/变量说明），textIsSelectable 支持长按手动复制
        view.findViewById<TextView>(R.id.templateContentZh).apply {
            text = tpl.zh
            setTextIsSelectable(true)
        }
        view.findViewById<TextView>(R.id.templateContentEn).apply {
            text = tpl.en
            setTextIsSelectable(true)
        }
        view.findViewById<TextView>(R.id.templateHints).text =
            tpl.variableHints.joinToString("\n") { "${it.first} = ${getString(it.second)}" }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(tpl.labelRes))
            .setView(view)
            .setNegativeButton(getString(R.string.close), null)
            .create().also { it.show(); it.window?.setBackgroundDrawableResource(R.drawable.dialog_background) }
    }

    companion object {
        fun show(manager: androidx.fragment.app.FragmentManager) {
            ChatTemplateSheet().show(manager, "ChatTemplateSheet")
        }
    }
}
