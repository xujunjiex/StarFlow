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

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import com.moe.starflow.R
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

class LanguageSelectionDialog(
    private val context: Context,
    private val type: Int,
    private val locales: List<CustomLocale>,
    private val enabled: List<Boolean>? = null,
    private val onDisabledClick: ((CustomLocale) -> Unit)? = null,
    private val dark: Boolean = false,
    private val lightBg: Int = R.drawable.dialog_background,
    private val fixedLightText: Boolean = false,
    private val onLanguageSelected: (CustomLocale) -> Unit
) {
    private data class LanguageEntry(val locale: CustomLocale, val enabled: Boolean)

    fun show() {
        val builder = AlertDialog.Builder(context)
        val inflater = LayoutInflater.from(context)
        val dialogView = inflater.inflate(R.layout.dialog_languages, null)
        val searchInput = dialogView.findViewById<EditText>(R.id.language_search_input)
        val listView = dialogView.findViewById<ListView>(R.id.languages_list)
        val emptyHint = dialogView.findViewById<TextView>(R.id.languages_empty)
        val density = context.resources.displayMetrics.density

        val entries = orderedEntries()
        val adapter = object : ArrayAdapter<LanguageEntry>(
            context,
            android.R.layout.simple_list_item_1,
            entries.toMutableList()
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: inflater.inflate(android.R.layout.simple_list_item_1, parent, false)
                val textView = view.findViewById<TextView>(android.R.id.text1)
                val entry = getItem(position) ?: return view
                textView.text = entry.locale.getDisplayName(context)
                if (dark) textView.setTextColor(0xFFE2E2E4.toInt())
                else if (fixedLightText) textView.setTextColor(0xFF333333.toInt())
                textView.isEnabled = entry.enabled
                view.alpha = if (entry.enabled) 1f else 0.4f
                return view
            }
        }

        listView.adapter = adapter

        fun applyFilter(keyword: CharSequence?) {
            val query = keyword?.toString().orEmpty()
            val filtered = entries.filter { matchesSearch(it.locale, query) }
            adapter.setNotifyOnChange(false)
            adapter.clear()
            adapter.addAll(filtered)
            adapter.setNotifyOnChange(true)
            adapter.notifyDataSetChanged()
            emptyHint.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE

            val rowHeightPx = (48 * density).toInt()
            listView.layoutParams = listView.layoutParams.apply {
                height = filtered.size.coerceAtMost(MAX_VISIBLE_ROWS) * rowHeightPx
            }
        }
        applyFilter("")

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter(s)
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        builder.setView(dialogView)

        // 深色/固定浅色：自定义标题色（避免默认标题随 app 主题在固定背景上不可见）
        if (dark || fixedLightText) {
            builder.setCustomTitle(TextView(context).apply {
                text = context.getString(
                    if (type == 1) R.string.select_source_language else R.string.select_target_language
                )
                setTextColor(if (dark) 0xFFE2E2E4.toInt() else 0xFF333333.toInt())
                textSize = 18f
                setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (4 * density).toInt())
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            })
        } else {
            builder.setTitle(if (type == 1) R.string.select_source_language else R.string.select_target_language)
        }

        val dialog = builder.create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = adapter.getItem(position) ?: return@setOnItemClickListener
            if (entry.enabled) {
                onLanguageSelected(entry.locale)
                dialog.dismiss()
            } else {
                // 点击置灰语言 → 弹提示（不关闭对话框）
                onDisabledClick?.invoke(entry.locale)
            }
        }

        applySearchTheme(searchInput, emptyHint)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(if (dark) R.drawable.bg_dialog_dark else lightBg)
    }

    private fun orderedEntries(): List<LanguageEntry> {
        val collator = Collator.getInstance(uiLocale()).apply { strength = Collator.PRIMARY }
        return locales.mapIndexed { index, locale ->
            LanguageEntry(locale, enabled?.getOrNull(index) ?: true)
        }.sortedWith(
            compareBy<LanguageEntry> { commonLanguageRank(it.locale.getOriCode()) }
                .thenComparator { left, right ->
                    collator.compare(
                        left.locale.getDisplayName(context),
                        right.locale.getDisplayName(context)
                    )
                }
        )
    }

    private fun matchesSearch(locale: CustomLocale, query: String): Boolean {
        if (query.isBlank()) return true
        val needle = normalizeSearchText(query)
        val localeObject = locale.locale
        return sequenceOf(
            locale.getOriCode(),
            locale.getDisplayName(context),
            localeObject.getDisplayLanguage(localeObject),
            localeObject.getDisplayLanguage(Locale.ENGLISH),
            localeObject.getDisplayLanguage(Locale.getDefault())
        ).any { normalizeSearchText(it).contains(needle) }
    }

    private fun applySearchTheme(searchInput: EditText, emptyHint: TextView) {
        when {
            dark -> {
                searchInput.setTextColor(0xFFE2E2E4.toInt())
                searchInput.setHintTextColor(0xFF888888.toInt())
                searchInput.backgroundTintList = ColorStateList.valueOf(0xFF2A2A2C.toInt())
                emptyHint.setTextColor(0xFF9A9A9F.toInt())
            }
            fixedLightText -> {
                searchInput.setTextColor(0xFF333333.toInt())
                searchInput.setHintTextColor(0xFF888888.toInt())
                searchInput.backgroundTintList = ColorStateList.valueOf(0xFFEFF7FB.toInt())
                emptyHint.setTextColor(0xFF888888.toInt())
            }
        }
    }

    private fun uiLocale(): Locale {
        val configured = context.resources.configuration.locales
        return if (configured.isEmpty) Locale.getDefault() else configured[0]
    }

    private fun normalizeSearchText(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(DIACRITICS_REGEX, "")
            .lowercase(Locale.ROOT)

    private fun commonLanguageRank(code: String): Int {
        val normalized = code.lowercase(Locale.ROOT).replace('_', '-')
        return COMMON_LANGUAGE_RANKS[normalized]
            ?: COMMON_LANGUAGE_RANKS[normalized.substringBefore('-')]
            ?: Int.MAX_VALUE
    }

    private companion object {
        const val MAX_VISIBLE_ROWS = 10

        val DIACRITICS_REGEX = "\\p{M}+".toRegex()

        // 常用语言按产品习惯固定顺序；其余语言交给 Collator 按当前界面语言名称排序。
        val COMMON_LANGUAGE_RANKS = linkedMapOf(
            "zh" to 0,
            "zh-cn" to 0,
            "zh-hans" to 0,
            "yue" to 0,
            "zh-tw" to 1,
            "zh-hant" to 1,
            "zh-hk" to 1,
            "zh-hk-hant" to 1,
            "zh-tw-hant" to 1,
            "en" to 2,
            "ja" to 3,
            "ko" to 4,
            "fr" to 5,
            "de" to 6,
            "es" to 7,
            "pt" to 8,
            "ru" to 9,
            "it" to 10
        )
    }
}
