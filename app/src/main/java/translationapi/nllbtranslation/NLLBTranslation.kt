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

package translationapi.nllbtranslation

import android.content.Context
import com.moe.starflow.R
import com.moe.starflow.translate.CustomLocale
import com.moe.starflow.translate.TranslationResult
import com.moe.starflow.translate.TranslationTextAPI
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.translate.TranslationStatusOverlay

/**
 * NLLB 本地机器翻译引擎。
 *
 * ⚠️ **刻意不接历史上下文**（不实现 `ContextAwareTranslation`）：NLLB 是纯 seq2seq 机器翻译，
 * 接口只有「一句原文 → 一句译文」，没有 system/user 提示词通道，硬塞历史只会被当成待翻译正文
 * 一起编码，把译文带跑偏。要上下文请用聚合 AI（OpenAI 兼容）或本地 LlamaCpp 模型。
 */
class NLLBTranslation(context: Context) : TranslationTextAPI {
    private val ctx = context.applicationContext
    private var currentTask: Thread? = null
    private var isInitialized = false
    private val statusOverlay = TranslationStatusOverlay.getInstance(ctx)

    companion object {
        private const val TAG = "NLLBTranslation"
    }

    private var nllbTranslator: TranslationCore = TranslationCore(ctx, object :InitializationListener{
        override fun onInitializationComplete() {
            isInitialized = true
            showToast(R.string.initialization_complete)
        }
        override fun onInitializationError(e: Exception) {
            e.printStackTrace()
            showToast(R.string.initialization_failed)
        }
    })

    init {
        showToast(R.string.initialization_start)
    }

    override fun getTranslation(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        callback: (TranslationResult) -> Unit
    ) {
        if (isInitialized){
            LogCollector.d(TAG, "NLLB 翻译开始: $sourceLanguage→$targetLanguage, text=$text")
            currentTask = Thread {
                try {
                    nllbTranslator.translate(text, CustomLocale(sourceLanguage), CustomLocale(targetLanguage), object: TranslationListener{
                        override fun onTranslationComplete(result: String) {
                            LogCollector.d(TAG, "NLLB 翻译完成: result=$result")
                            callback(TranslationResult.Success(result))
                        }

                        override fun onTranslationError(e: java.lang.Exception) {
                            LogCollector.e(TAG, "NLLB 翻译失败: ${e.message}", e)
                            callback(TranslationResult.Error(e))
                        }

                    })
                } catch (e: Exception) {
                    LogCollector.e(TAG, "NLLB 翻译异常: ${e.message}", e)
                    callback(TranslationResult.Error(e))
                }

            }.apply { start() }

        }else{
            LogCollector.d(TAG, "NLLB 未初始化完成，跳过翻译")
            showToast(R.string.initialization_not_complete)
        }
    }

    override fun cancelTranslation() {
        currentTask?.let {
            if (it.isAlive) {
                it.interrupt()
            }
        }
        currentTask = null
    }

    override fun release() {
        cancelTranslation()
        statusOverlay.release()
    }

    private fun showToast(@androidx.annotation.StringRes messageId: Int) {
        statusOverlay.show(ctx.getString(messageId))
    }
}