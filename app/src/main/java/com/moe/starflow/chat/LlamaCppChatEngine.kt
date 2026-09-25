package com.moe.starflow.chat

import android.content.Context
import com.moe.starflow.R
import com.moe.starflow.llamacpp.LlamaCppModelStore
import com.moe.starflow.llamacpp.LlamaCppSharedHolder
import com.moe.starflow.llamacpp.LlamaCppTranslation
import com.moe.starflow.translate.TranslationResult
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector

/**
 * 本地 GGUF 对话引擎（替代只认内置 Hy-MT2 的 `HyMt2ChatEngine`）。
 *
 * 支持**任意**已激活的模型：
 *  - 内置 Hy-MT2（含 hy 角色标记）→ 桥接侧 `nativeTranslateChat`（多轮角色标记由 native 拼）
 *  - 用户导入的任意 instruct GGUF → 用模型自带 Jinja 模板渲染 system + 多轮消息后再推理
 *
 * 走进程级共享热实例（keepAlive）：对话与翻译复用同一个模型，切页面不重载。
 */
class LlamaCppChatEngine(context: Context, prefs: CustomPreference) : ChatEngine {

    private val appContext = context.applicationContext
    private val ctx = appContext
    private val engine: LlamaCppTranslation? = run {
        LlamaCppModelStore.init(appContext)
        val model = LlamaCppModelStore.active()
        if (model == null) {
            LogCollector.e(TAG, "对话引擎创建失败：没有激活的 LlamaCpp 模型")
            null
        } else {
            LogCollector.d(TAG, "对话引擎：${model.displayName}")
            LlamaCppSharedHolder.get(appContext, model)
        }
    }

    override fun chat(
        history: List<ChatMessage>,
        input: String,
        onPhase: (String) -> Unit,
        onPartial: (String) -> Unit,
        callback: (ChatResult) -> Unit
    ) {
        val e = engine
        if (e == null) {
            callback(ChatResult.Error(Exception(ctx.getString(R.string.llamacpp_no_active_model))))
            return
        }
        val (roles, contents) = ChatPromptBuilder.buildMessages(history, input)
        LogCollector.d(TAG, "chat 消息[${contents.size}]（system='${ChatTemplates.DEFAULT_SYSTEM.take(40)}'）")
        e.chatNative(
            systemPrompt = ChatTemplates.DEFAULT_SYSTEM,
            roles = roles,
            contents = contents,
            onPhase = onPhase,
            onPartial = onPartial,
        ) { result ->
            when (result) {
                is TranslationResult.Success -> callback(ChatResult.Success(result.translatedText))
                is TranslationResult.Error -> callback(ChatResult.Error(result.error))
            }
        }
    }

    override fun cancel() {
        engine?.cancelTranslation()
    }

    override fun release() {
        // 共享实例 keepAlive 常驻，全 app 复用：不释放模型
    }

    companion object {
        private const val TAG = "LlamaCppChatEngine"
    }
}
