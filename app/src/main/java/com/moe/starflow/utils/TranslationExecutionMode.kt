package com.moe.starflow.utils

import android.content.Context
import androidx.preference.PreferenceManager

/** Global OCR/text execution mode. Image translation remains outside this switch. */
object TranslationExecutionMode {
    const val KEY = "translation_execution_mode"
    const val TRANSLATE = 0
    const val OCR_ONLY = 1

    fun get(context: Context): Int =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(KEY, TRANSLATE)

    fun isOcrOnly(context: Context): Boolean = get(context) == OCR_ONLY

    fun set(context: Context, mode: Int) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putInt(KEY, mode.coerceIn(TRANSLATE, OCR_ONLY)).apply()
    }
}
