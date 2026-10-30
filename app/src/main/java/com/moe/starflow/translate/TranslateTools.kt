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

import android.content.Context
import android.util.Log
import com.moe.starflow.R
import com.moe.starflow.manga.config.OcrEngineGroup
import com.moe.starflow.utils.Constants
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.TranslationExecutionMode
import org.w3c.dom.NodeList
import javax.xml.parsers.DocumentBuilderFactory

object TranslateTools {
    /**
     * @param ocrGroup 当前 OCR 引擎组（仅源语言 type=1 生效）：30 种语言池按组排序，支持的前、不支持的自动下移；null=全量（文本翻译不经 OCR，源语言不受影响）
     * @param forImage true = 按当前图片翻译接口的能力返回语言表；false = 文本翻译/小说正文语言表，
     *   不受图片模式影响。默认跟随全局执行模式，文本翻译页/小说面板必须显式传 false。
     */
    fun getLanguagesList(
        context: Context,
        type: Int,
        ocrGroup: OcrEngineGroup? = null,
        forImage: Boolean = TranslationExecutionMode.isImage(context),
    ): List<CustomLocale>? = runCatching {
        if (forImage) {
            val resourceId = imageLanguageResource(context, type) ?: run {
                Log.w("TranslateTools", "当前图片翻译接口没有语言表（自定义接口）")
                return@runCatching null
            }
            return@runCatching loadLanguages(context, resourceId)
        }

        // 源语言动态化：不再读固定 ocr_support_languages.xml（6 种），改为 30 种池按 OCR 组排序
        if (type == 1) {
            val langs = if (ocrGroup != null) {
                OcrEngineGroup.ALL_LANGS.filter { ocrGroup.sourceLangs.contains(it) } +
                    OcrEngineGroup.ALL_LANGS.filterNot { ocrGroup.sourceLangs.contains(it) }
            } else {
                OcrEngineGroup.ALL_LANGS
            }
            return@runCatching langs.map { CustomLocale.getInstance(it) }
        }

        val prefs = CustomPreference.getInstance(context)
        val textApi = prefs.getInt("Text_API", Constants.TextApi.BING.id)
        val resourceId = when (textApi) {
            Constants.TextApi.AI.id -> if (com.moe.starflow.llamacpp.LlamaCppModelStore.isHyMt2ActiveFromPrefs(prefs)) {
                R.raw.hy_mt2_text_support_languages
            } else {
                R.raw.nllb_text_support_languages
            }
            Constants.TextApi.BING.id -> R.raw.bing_text_support_languages
            Constants.TextApi.NIUTRANS.id -> R.raw.niutrans_text_support_languages
            Constants.TextApi.OPENAI.id -> R.raw.niutrans_text_support_languages
            Constants.TextApi.VOLC.id -> R.raw.volc_text_support_languages
            Constants.TextApi.AZURE.id -> R.raw.azure_text_support_languages
            Constants.TextApi.DEEPL.id -> R.raw.deepl_text_tar_support_languages
            Constants.TextApi.BAIDU.id -> R.raw.baidu_text_support_languages
            Constants.TextApi.TENCENT.id -> R.raw.tencent_text_support_languages
            else -> {
                Log.w("TranslateTools", "Custom OCR API selected")
                return@runCatching null
            }
        }
        loadLanguages(context, resourceId)
    }.onFailure { exception ->
        exception.printStackTrace()
    }.getOrNull()

    private fun imageLanguageResource(context: Context, type: Int): Int? {
        val prefs = CustomPreference.getInstance(context)
        return when (prefs.getInt("Pic_API", Constants.PicApi.BAIDU.id)) {
            Constants.PicApi.BAIDU.id -> if (type == 1) {
                R.raw.baidu_pic_src_support_languages
            } else {
                R.raw.baidu_pic_tar_support_languages
            }
            Constants.PicApi.TENCENT.id -> if (type == 1) {
                R.raw.tencent_pic_src_support_languages
            } else {
                R.raw.tencent_pic_tar_support_languages
            }
            else -> null
        }
    }

    private fun loadLanguages(context: Context, resourceId: Int): List<CustomLocale> {
        context.resources.openRawResource(resourceId).use { inputStream ->
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(inputStream)
            val nodeList = document.getElementsByTagName("code")
            return nodeList.toCustomLocaleList()
        }
    }

    private fun NodeList.toCustomLocaleList(): List<CustomLocale> =
        (0 until length).mapNotNull { i ->
            item(i)?.textContent?.let {
                Log.d("TEXTCONTENT", it)
                CustomLocale.getInstance(it)
            }
        }

    /**
     * 当前翻译模型不支持的目标语言集合（30 种池内）。
     * 只有**内置 Hy-MT2** 有 38 种限制（30 种池内缺 sv/da/no/fi/hu/ro/ne/ca/af）；
     * 导入的任意 GGUF 与各 API 视为全支持。
     *
     * 图片模式不要调用它：图片接口的目标语言只由 Pic API 语言表决定。
     */
    fun getDisabledTargetLangs(prefs: com.moe.starflow.utils.CustomPreference): Set<String> {
        return if (com.moe.starflow.llamacpp.LlamaCppModelStore.isHyMt2ActiveFromPrefs(prefs)) {
            setOf("sv", "da", "no", "fi", "hu", "ro", "ne", "ca", "af")
        } else {
            emptySet()
        }
    }
}
