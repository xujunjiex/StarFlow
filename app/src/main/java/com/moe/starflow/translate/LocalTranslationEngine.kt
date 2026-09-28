package com.moe.starflow.translate

import com.moe.starflow.llamacpp.LlamaCppTranslation
import translationapi.nllbtranslation.NLLBTranslation

/**
 * 「本地重计算翻译引擎」的**唯一判据**（超分要与它们串行）。
 *
 * ## 为什么要单独一个文件
 * 超分（ONNX 卷积 / GLES）和本地翻译引擎都是**纯 CPU 重活**，同时跑只会互相抢核、
 * 两边都变慢，内存峰值还翻倍。所以「超分 ∥ 翻译请求」这条并行只对**网络 API**成立，
 * 本地引擎必须串行。
 *
 * 之前这个判断散在 `TranslateUtils` 里、而且**只判了 LlamaCpp**：
 * ```kotlin
 * val isLocalEngine = translator is LlamaCppTranslation     // ← 漏了 NLLB
 * ```
 * 超分要用同一条判据，所以收敛到这一处 —— **不要在调用点自己写 `when(引擎名)` 或
 * 另抄一份类型判断**（漏一处就是"本地引擎下超分和翻译抢 CPU"的静默性能问题）。
 *
 * ⚠️ 这里是**按实现类型**判断，不是按显示名/枚举名 —— 后者会随改名/本地化失效。
 */
fun Any?.isLocalHeavyEngine(): Boolean = this is LlamaCppTranslation || this is NLLBTranslation
