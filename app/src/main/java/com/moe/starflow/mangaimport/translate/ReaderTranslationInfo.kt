package com.moe.starflow.mangaimport.translate

import android.content.Context
import androidx.preference.PreferenceManager
import com.moe.starflow.translate.TranslatorNames
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.OcrEngineManager

/** 阅读器翻译面板的模型名 + 语言支持判定（纯函数可单测）。 */
object ReaderTranslationInfo {

    /** 当前 OCR 引擎组标签。 */
    fun ocrModelLabel(context: Context): String {
        val group = OcrEngineManager.getOcrEngineGroup(PreferenceManager.getDefaultSharedPreferences(context))
        return context.getString(group.labelRes)
    }

    /** 当前翻译模型显示名。 */
    fun translatorModelLabel(context: Context): String =
        TranslatorNames.of(context, CustomPreference.getInstance(context))

    /** 源语言是否被当前 OCR 组支持。 */
    fun isSourceSupported(code: String, sourceLangs: Set<String>): Boolean = code in sourceLangs

    /** 目标语言是否被当前翻译模型支持（Hy-MT2=白名单；其余=非禁用）。 */
    fun isTargetSupported(code: String, isHyMt2: Boolean, supportedCodes: Set<String>, disabledTargets: Set<String>): Boolean =
        if (isHyMt2) code in supportedCodes else code !in disabledTargets
}