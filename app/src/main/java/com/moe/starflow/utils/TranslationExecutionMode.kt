package com.moe.starflow.utils

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Global execution mode: local OCR/text translation, OCR-only, or direct image translation.
 *
 * `Translate_Mode` is kept only as a legacy compatibility mirror written by [set].
 * Do not read it for business decisions; use [isOcrOnly] / [isImage].
 */
object TranslationExecutionMode {
    const val KEY = "translation_execution_mode"

    const val TRANSLATE = 0
    const val OCR_ONLY = 1

    /** Direct image translation: the image is sent to a cloud image translation API without local OCR. */
    const val IMAGE = 2

    /** Legacy key written for older settings code / downgrade compatibility. */
    const val LEGACY_KEY = "Translate_Mode"

    fun get(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        // 升级迁移：旧版只有 Translate_Mode（1 = 图片直传），新键缺失时沿用旧选择。
        val raw = if (prefs.contains(KEY)) {
            prefs.getInt(KEY, TRANSLATE)
        } else if (prefs.getInt(LEGACY_KEY, 0) == 1) {
            IMAGE
        } else {
            TRANSLATE
        }
        return raw.coerceIn(TRANSLATE, IMAGE)
    }

    fun isOcrOnly(context: Context): Boolean = get(context) == OCR_ONLY

    fun isImage(context: Context): Boolean = get(context) == IMAGE

    fun set(context: Context, mode: Int) {
        val normalized = mode.coerceIn(TRANSLATE, IMAGE)
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putInt(KEY, normalized)
            .putInt(LEGACY_KEY, if (normalized == IMAGE) 1 else 0)
            .apply()
    }
}
