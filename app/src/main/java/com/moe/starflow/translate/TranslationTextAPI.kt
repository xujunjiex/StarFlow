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

// 定义翻译结果的封装类
sealed class TranslationResult {
    data class Success(val translatedText: String) : TranslationResult()
    data class Error(val error: Exception) : TranslationResult()
}

interface TranslationTextAPI {

    // 模型名称（用于历史记录显示，如 "gpt-4o"、"deepseek-chat"）
    val modelName: String get() = ""

    // 异步翻译方法
    fun getTranslation(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        callback: (TranslationResult) -> Unit
    )

    /**
     * 流式翻译：支持边生成边回调部分译文的实现覆盖此方法；不支持的实现用默认实现（= 一次性翻译，onPhase/onPartial 不会被调用）。
     * onPhase / onPartial 在后台线程回调，调用方需自行切到主线程更新 UI。
     * @param onPhase 阶段回调："prefill"（读取原文）/ "generate"（生成译文）
     * @param onPartial 部分译文回调（累积到当前的完整译文）
     */
    fun getTranslationStreaming(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        onPhase: (String) -> Unit,
        onPartial: (String) -> Unit,
        callback: (TranslationResult) -> Unit
    ) {
        // 不流式的引擎（所有 API + NLLB）也上报"生成译文"阶段，
        // 让文本翻译页等调用方在结果返回前显示状态，与 Hy-MT2 流式引擎行为一致。
        onPhase("generate")
        getTranslation(text, sourceLanguage, targetLanguage, callback)
    }

    // 用于取消正在进行的翻译任务
    fun cancelTranslation()

    /** Local shared engines override this with cancellation scoped to the returned request. */
    fun startTranslation(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        onPhase: (String) -> Unit = {},
        onPartial: (String) -> Unit = {},
        callback: (TranslationResult) -> Unit,
    ): TranslationRequest {
        getTranslationStreaming(text, sourceLanguage, targetLanguage, onPhase, onPartial, callback)
        // Remote implementations may share concurrent requests. Discarding a consumer must not
        // call the engine-wide cancelAll and accidentally cancel somebody else's request.
        return TranslationRequest { }
    }

    // 释放资源
    fun release()
}
