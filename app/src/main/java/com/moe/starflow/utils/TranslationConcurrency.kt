package com.moe.starflow.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * 「同时请求数」（并发度）的唯一来源。
 *
 * 用户口径：
 * - **漫画**批量翻译：受**本地 OCR 速度**制约，2-5 个并发就够，默认 3
 * - **文本**翻译：可以激进些，1-10，默认 5；**越高越快烧 API 额度**（设置页要写明）
 * - **本地翻译引擎**（LlamaCpp / NLLB）**强制 1**：本地推理是 CPU 单例，并发只会互相拖慢
 *   （LlamaCpp 那边还有一把 native `g_mutex`，并发请求等于排队 + 白占内存）
 *
 * ⚠️ 两处设置**刻意不共用同一个值**（用户明确要求）：漫画受 OCR 限制、文本受 API 限流限制，
 * 最优值本来就不同。
 */
object TranslationConcurrency {

    /** 漫画批量翻译的并发数（设置项：同时请求数 2-5）。 */
    const val KEY_MANGA = "manga_concurrent_requests"
    const val MANGA_MIN = 2
    const val MANGA_MAX = 5
    const val MANGA_DEFAULT = 3

    /** 文本翻译的并发数（设置项：同时 API 请求数 1-10）。 */
    const val KEY_NOVEL = "novel_concurrent_requests"
    const val NOVEL_MIN = 1
    const val NOVEL_MAX = 10
    const val NOVEL_DEFAULT = 5

    fun mangaConcurrency(context: Context, prefs: SharedPreferences): Int =
        resolve(context, prefs, KEY_MANGA, MANGA_MIN, MANGA_MAX, MANGA_DEFAULT)

    fun novelConcurrency(context: Context, prefs: SharedPreferences): Int =
        resolve(context, prefs, KEY_NOVEL, NOVEL_MIN, NOVEL_MAX, NOVEL_DEFAULT)

    /**
     * 本地引擎并发恒为 1。判据与 `TranslatorFactory.create` 的分支**同源**
     * （`Text_API == AI` 且 `Text_AI` 是 NLLB(0/旧值1) 或预制/导入的 LlamaCpp(2)）。
     */
    fun isLocalEngine(prefs: SharedPreferences): Boolean {
        val api = prefs.getInt("Text_API", Constants.TextApi.BING.id)
        if (api != Constants.TextApi.AI.id) return false
        val ai = prefs.getInt("Text_AI", Constants.TextAI.NLLB.id)
        // 1 = 升级前 NLLB 的旧值（见 TranslatorFactory 的兼容分支）
        return ai == Constants.TextAI.NLLB.id || ai == 1 || ai == Constants.TextAI.HYMT2.id
    }

    private fun resolve(
        context: Context,
        prefs: SharedPreferences,
        key: String,
        min: Int,
        max: Int,
        default: Int,
    ): Int {
        if (isLocalEngine(prefs)) return 1
        val stored = try {
            prefs.getInt(key, default)
        } catch (e: Exception) {
            // 老版本可能把它存成 String（ListPreference 的默认行为），兜一手
            prefs.getString(key, null)?.toIntOrNull() ?: default
        }
        return stored.coerceIn(min, max)
    }
}
