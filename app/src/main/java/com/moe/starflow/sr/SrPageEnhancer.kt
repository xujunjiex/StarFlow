package com.moe.starflow.sr

import android.content.Context
import androidx.preference.PreferenceManager
import com.moe.starflow.sr.anime4k.Anime4kMode

/**
 * 超分相关**设置指纹**的持有者（应用级 Context）+ 设置指纹的**唯一来源**。
 *
 * ## v2 架构（2026-10，改动前必读）
 * 超分与 OCR **彻底解耦**：
 * ```
 * 点翻译 → ① 原图 OCR（持 OcrLock）→ bubbleRects（源图坐标）
 *        → ② OCR 完成后才启动：超分 ∥ 翻译请求
 *        → ③ 默认渲染在超分底图上，可二态切回原图
 * ```
 * 因此这里**不再有任何"喂给 OCR 的增强"**：
 * - 旧的 `enhanceForReader(src)`（超分 → 缩回原尺寸喂 OCR）**已删除** ——
 *   OCR 永远吃原图，所以那些坐标/阈值顾虑全部不存在了
 * - 旧的 `enhanceForCapture(src, forGame)` 也已删除（截图/录屏链路**不做超分**，用户口径）
 *
 * ## 为什么还要 Context
 * 影响超分的设置存在 `SharedPreferences` 里，而调用点（渲染 key、显示底图缓存）
 * 不一定有 Context。这里持一个 **applicationContext**，不泄漏 Activity。
 */
object SrPageEnhancer {

    @Volatile
    private var appContext: Context? = null

    /** 由 `StarFlowApplication.onCreate` 调一次（幂等） */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 是否可用（没初始化上下文时一律不可用 → 调用方按"未超分"处理） */
    val isAvailable: Boolean get() = appContext != null

    /**
     * 影响超分结果的**全部**输入的指纹（没初始化上下文 → null）。
     *
     * 开关 / 选的模型 / Anime4K 档位 任一变化都会让它变 → 调用方据此作废缓存。
     * 用字符串而不是散着比几个字段：以后新增影响输出的设置，只要往这里加一项，
     * 所有调用点自动跟着失效，不会出现"改了设置但缓存还是旧图"。
     *
     * ⚠️ **渲染缓存的 key 必须混入它**（`renderLru` 的 `page:idx:MODE` 曾漏了它 →
     * 换了模型画面不变，且**静默**）。
     */
    fun signature(): String? {
        val ctx = appContext ?: return null
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        return buildString {
            append(if (SrSettings.isEnabledForReader(prefs)) '1' else '0')
            append('|').append(SrModelManager.getActiveKey(prefs)?.name ?: "-")
            append('|').append(if (Anime4kMode.isEnabled(prefs)) Anime4kMode.fromPrefs(prefs).id else "-")
        }
    }
}
