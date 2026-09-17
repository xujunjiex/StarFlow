/*
 * Copyright 2016 Luca Martino.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copyFile of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Modified by murangogo in 2024
 * This file is derived from nie.translator.rtranslator project.
 * Modifications:
 * - Simplified the original implementation by removing unused features
 * - Retained only core translation functionality
 * Original source: https://github.com/niedev/RTranslator
 */

package com.moe.starflow.translate
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import android.content.Context
import android.text.TextUtils
import java.util.Locale

class CustomLocale {
    val locale: Locale
    val localCode: String

    // 包含 language, country, variant
    constructor(originCode: String, language: String, country: String, variant: String) {
        locale = Locale(language, country, variant)
        localCode = originCode
    }

    // 包含 languageCode 和 countryCode
    constructor(originCode: String, languageCode: String, countryCode: String) {
        locale = Locale(languageCode, countryCode)
        localCode = originCode
    }

    // 只包含 languageCode
    constructor(languageCode: String) {
        locale = Locale(languageCode)
        localCode = languageCode
    }

    // 包含 Locale 对象
    constructor(locale: Locale) {
        this.locale = locale
        localCode = locale.language
    }

    companion object {
        fun getInstance(code: String): CustomLocale {
            val languageCode = code.split("-")
            return when (languageCode.size) {
                1 -> CustomLocale(languageCode[0])
                2 -> CustomLocale(code, languageCode[0], languageCode[1])
                else -> CustomLocale(code, languageCode[0], languageCode[1], languageCode[2])
            }
        }
    }

    fun getDisplayName(): String = getDisplayName(null)

    /**
     * 语言显示名，按**传入上下文**的界面语言渲染（null = 进程默认语言）。
     *
     * ⚠️ `Locale.displayName` 用的是 **JVM 默认 locale**（`LanguageManager.updateResources` 里
     * `Locale.setDefault` 定的那份），不是调用方 Context 的语言。Service 场景下这个默认值不可靠
     * ——悬浮窗弹窗必须传 `ThemeManager.dialogContext(service)`，否则 App_Language=en 时菜单里会
     * 冒出中文（"日语" 而不是 "Japanese"）。
     */
    fun getDisplayName(context: Context?): String {
        val res = context?.resources
        val name = if (res == null) locale.displayName else locale.getDisplayName(res.configuration.locales[0])
        return name.replaceFirstChar { it.uppercase(locale) }
    }

    fun getCode(): String {
        val language = StringBuilder(locale.language)
        val country = locale.country
        if (!TextUtils.isEmpty(country)) {
            language.append("-")
            language.append(country)
        }
        return language.toString()
    }

    fun getOriCode(): String {
        return localCode
    }

    override fun toString(): String {
        return locale.displayName
    }

}