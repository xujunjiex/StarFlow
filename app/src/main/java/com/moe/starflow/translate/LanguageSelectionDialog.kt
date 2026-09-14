/*
 * Copyright (C) 2024 murangogo
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with this library; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */

package com.moe.starflow.translate
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import com.moe.starflow.R

class LanguageSelectionDialog(
    private val context: Context,
    private val type: Int,
    private val locales: List<CustomLocale>,
    private val enabled: List<Boolean>? = null,
    private val onDisabledClick: ((CustomLocale) -> Unit)? = null,
    private val dark: Boolean = false,
    private val onLanguageSelected: (CustomLocale) -> Unit)
{
    fun show() {
        val builder = AlertDialog.Builder(context)
        val inflater = LayoutInflater.from(context)
        val dialogView = inflater.inflate(R.layout.dialog_languages, null)
        val listView = dialogView.findViewById<ListView>(R.id.languages_list)
        val density = context.resources.displayMetrics.density

        val adapter = object : ArrayAdapter<CustomLocale>(context, android.R.layout.simple_list_item_1, locales) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: inflater.inflate(android.R.layout.simple_list_item_1, parent, false)
                val textView = view.findViewById<TextView>(android.R.id.text1)
                val locale = locales[position]
                textView.text = locale.getDisplayName()
                if (dark) textView.setTextColor(0xFFE2E2E4.toInt())
                // 置灰：当前 OCR/翻译模型不支持的语言（enabled[position]=false）
                val isEnabled = enabled?.getOrNull(position) ?: true
                textView.isEnabled = isEnabled
                view.alpha = if (isEnabled) 1f else 0.4f
                return view
            }
        }

        listView.adapter = adapter

        builder.setView(dialogView)

        // 深色：自定义浅色标题（避免默认标题文字不可见）
        if (dark) {
            builder.setCustomTitle(TextView(context).apply {
                text = context.getString(
                    if (type == 1) R.string.select_source_language else R.string.select_target_language
                )
                setTextColor(0xFFE2E2E4.toInt())
                textSize = 18f
                setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (4 * density).toInt())
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            })
        } else {
            builder.setTitle(if (type == 1) R.string.select_source_language else R.string.select_target_language)
        }

        val dialog = builder.create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val locale = locales[position]
            val isEnabled = enabled?.getOrNull(position) ?: true
            if (isEnabled) {
                onLanguageSelected(locale)
                dialog.dismiss()
            } else {
                // 点击置灰语言 → 弹提示（不关闭对话框）
                onDisabledClick?.invoke(locale)
            }
        }

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(if (dark) R.drawable.bg_dialog_dark else R.drawable.dialog_background)
    }
}