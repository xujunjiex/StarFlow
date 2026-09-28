package com.moe.starflow.sr

import android.content.SharedPreferences

/**
 * 超分（SR）的**开关状态唯一来源**。
 *
 * 四个开关，语义刻意分开（用户口径 2026-10）：
 * | prefs 键 | 作用范围 |
 * |---|---|
 * | [KEY_ENABLED] | **总开关**。关掉 = 任何链路都不超分（下面三个都失效） |
 * | [KEY_ENABLED_GAME] | 截屏/录屏的**游戏模式**是否超分 |
 * | [KEY_ENABLED_COMIC] | 截屏/录屏的**漫画模式**是否超分 |
 * | [KEY_READER_ENABLED] | **阅读器专用**：只影响漫画阅读器里的超分（先超分再翻译），
 *   与总开关/截图开关**各自独立生效** —— 关掉它不会影响悬浮窗截图翻译，反之亦然 |
 *
 * ⚠️ 判据一律走这里的 [isActiveFor*]，**不要在调用点直接读 prefs 键**：
 * 总开关与分项开关是 AND 关系，散读就会漏掉总开关（"关总开关还在超分"）。
 * 模型是否"选好了/下载了"由 [SrModelManager] 负责 —— 这里只管开关。
 */
object SrSettings {

    /** 总开关 */
    const val KEY_ENABLED = "sr_enabled"

    /** 截图/录屏 · 游戏模式 */
    const val KEY_ENABLED_GAME = "sr_enabled_game"

    /** 截图/录屏 · 漫画模式 */
    const val KEY_ENABLED_COMIC = "sr_enabled_comic"

    /** 阅读器内嵌翻译链路的独立开关（只对阅读器的漫画超分生效） */
    const val KEY_READER_ENABLED = "sr_reader_enabled"

    /** 总开关默认 **关**：超分只对真正低分辨率的图源有意义，默认别开（实测结论） */
    const val DEFAULT_ENABLED = false

    /** 分项默认值：跟随总开关打开后默认开启各项 */
    const val DEFAULT_MODE_ENABLED = true

    /** 阅读器开关默认 **关**（与总开关独立，用户要在阅读器里显式打开） */
    const val DEFAULT_READER_ENABLED = false

    fun isEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    /** 阅读器独立开关的写入端（阅读器面板的 Switch 调它）。写入后记得让引擎释放缓存 */
    fun setReaderEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_READER_ENABLED, enabled).apply()
    }

    fun isEnabledForGame(prefs: SharedPreferences): Boolean =
        isEnabled(prefs) && prefs.getBoolean(KEY_ENABLED_GAME, DEFAULT_MODE_ENABLED)

    fun isEnabledForComic(prefs: SharedPreferences): Boolean =
        isEnabled(prefs) && prefs.getBoolean(KEY_ENABLED_COMIC, DEFAULT_MODE_ENABLED)

    /**
     * 阅读器链路是否该超分。
     * ⚠️ 这里**刻意不 AND 总开关**：用户明确要求阅读器开关"和个性化里面的独立生效"。
     * 但为了不至于"总开关关了阅读器还在超分"造成困惑，UI 上总开关关闭时会提示阅读器开关独立。
     */
    fun isEnabledForReader(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_READER_ENABLED, DEFAULT_READER_ENABLED)

    /** 当前是否有任何链路可能用到超分（用于决定要不要预热/加载模型） */
    fun isUsedAnywhere(prefs: SharedPreferences): Boolean =
        isEnabledForGame(prefs) || isEnabledForComic(prefs) || isEnabledForReader(prefs)
}
