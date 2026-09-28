package com.moe.starflow.sr

import android.content.SharedPreferences

/**
 * 超分（SR）的**开关状态唯一来源**。
 *
 * ⚠️ **超分只服务阅读器**（2026-10 用户口径）：截图 / 录屏的翻译链路**不做超分** ——
 * 那两条链路（`MangaFloatingService` / `FloatingBallService`）已恢复成「裁剪后直接喂 OCR」，
 * 个性化里也不再有任何超分开关。所以这里**只剩一个开关**。
 *
 * 曾经有过「总开关 + 游戏模式 + 漫画模式」（AND 关系），已整体删除；对应的 prefs 键
 * （`sr_enabled` / `sr_enabled_game` / `sr_enabled_comic`）不再读取，老用户残留的值无害。
 *
 * | prefs 键 | 作用范围 |
 * |---|---|
 * | [KEY_READER_ENABLED] | **阅读器专用**：只影响漫画阅读器（先超分再识别 + 每页手动超分） |
 *
 * 模型是否"选好了/下载了"由 [SrModelManager] 负责 —— 这里只管开关。
 */
object SrSettings {

    /** 阅读器超分开关 */
    const val KEY_READER_ENABLED = "sr_reader_enabled"

    /**
     * **「翻译时自动超分」**子开关（2026-10 用户口径）。
     *
     * - **开**（默认）：点翻译 → **先原图 OCR** → OCR 完成后启动超分（与翻译请求并行；
     *   本地引擎则串行）
     * - **关**：翻译**不自动超分**，用户点每页按钮手动超分（该按钮同时充当"原图⇄超分底图"二态切换）
     *
     * ⚠️ 名字刻意不叫「OCR 同时超分」：超分发生在 OCR **之后**，叫"同时"会让人以为它参与识别
     * （v2 起 OCR 永远吃原图，超分纯粹是**显示增强**）。
     */
    const val KEY_AUTO = "sr_reader_auto"

    /** 默认 **关**：超分只对真正低分辨率的图源有意义，且更耗电更慢，必须用户显式开启 */
    const val DEFAULT_READER_ENABLED = false

    /** 「翻译时自动超分」默认 **开**（用户开启了超分总开关，就是想要它生效） */
    const val DEFAULT_AUTO = true

    fun isEnabledForReader(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_READER_ENABLED, DEFAULT_READER_ENABLED)

    /** 阅读器开关的写入端（阅读器面板的 Switch 调它）。写入后记得让引擎释放缓存 */
    fun setReaderEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_READER_ENABLED, enabled).apply()
    }

    /** 「翻译时自动超分」是否开启（**必须先满足 [isEnabledForReader]**） */
    fun isAutoEnabledForReader(prefs: SharedPreferences): Boolean =
        isEnabledForReader(prefs) && prefs.getBoolean(KEY_AUTO, DEFAULT_AUTO)

    fun setAutoEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO, enabled).apply()
    }
}
