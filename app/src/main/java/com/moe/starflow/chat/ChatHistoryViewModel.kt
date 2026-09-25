package com.moe.starflow.chat
import com.moe.starflow.translate.widget.*

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.moe.starflow.data.ChatMessageDao
import com.moe.starflow.data.ChatMessageEntity
import com.moe.starflow.data.TranslationHistoryDatabase
import com.moe.starflow.utils.Constants
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.launch

/** 对话状态：消息列表 + 流式 + Room 持久化（Activity 级，跨 fragment 重建保留） */
class ChatHistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = CustomPreference.getInstance(app)
    private val dao: ChatMessageDao = TranslationHistoryDatabase.getInstance(app).chatMessageDao()

    private val _messages = MutableLiveData<List<ChatMessage>>(emptyList())
    val messages: LiveData<List<ChatMessage>> = _messages

    private val _isSending = MutableLiveData(false)
    val isSending: LiveData<Boolean> = _isSending

    private var engine: ChatEngine? = null
    private var engineKey: String? = null

    /** 获取当前引擎；引擎 key 变化（切换模型）时重建，避免继续用旧模型 */
    private fun getEngine(): ChatEngine? {
        val key = currentEngineKey()
        if (engine == null || engineKey != key) {
            engine?.release()
            engine = null
            engineKey = key
            engine = createEngine()
        }
        return engine
    }

    /** 当前引擎是否支持对话（Hy-MT2 / OpenAI 兼容） */
    val engineSupported: Boolean
        get() {
            val api = prefs.getInt("Text_API", Constants.TextApi.BING.id)
            val ai = prefs.getInt("Text_AI", Constants.TextAI.NLLB.id)
            return (api == Constants.TextApi.AI.id && ai == Constants.TextAI.HYMT2.id) ||
                api == Constants.TextApi.OPENAI.id
        }

    private fun createEngine(): ChatEngine? {
        val api = prefs.getInt("Text_API", Constants.TextApi.BING.id)
        val ai = prefs.getInt("Text_AI", Constants.TextAI.NLLB.id)
        return when {
            api == Constants.TextApi.AI.id && ai == Constants.TextAI.HYMT2.id ->
                LlamaCppChatEngine(getApplication(), prefs)
            api == Constants.TextApi.OPENAI.id ->
                OpenAIChatEngine(getApplication(), prefs)
            else -> null
        }
    }

    /** 进入对话页时从 DB 加载历史 */
    fun loadHistory() {
        viewModelScope.launch {
            val entities = dao.queryAll()
            _messages.value = entities.map { it.toDomain() }
        }
    }

    // ===== 引擎变更检测（切换模型后旧历史不适用） =====

    private val KEY_LAST_ENGINE = "chat_last_engine_key"

    fun currentEngineKey(): String {
        val api = prefs.getInt("Text_API", Constants.TextApi.BING.id)
        val ai = prefs.getInt("Text_AI", Constants.TextAI.NLLB.id)
        // 本地模型也进 key：换模型后旧历史不适用，要重建引擎并提示
        val modelId = if (api == Constants.TextApi.AI.id && ai == Constants.TextAI.HYMT2.id) {
            com.moe.starflow.llamacpp.LlamaCppModelStore.init(getApplication())
            com.moe.starflow.llamacpp.LlamaCppModelStore.active()?.id ?: ""
        } else ""
        return "api=$api|ai=$ai|model=$modelId"
    }

    /** 上次对话使用的引擎与当前不同（模型已切换，历史是旧模型的） */
    fun hasEngineChanged(): Boolean {
        val last = prefs.getString(KEY_LAST_ENGINE, "")
        return last.isNotEmpty() && last != currentEngineKey()
    }

    /** 标记当前引擎已使用（清空/保留后调用，避免每次进入重复提示） */
    fun markEngineCurrent() {
        prefs.setString(KEY_LAST_ENGINE, currentEngineKey())
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _isSending.value == true) return
        val e = getEngine() ?: return

        // 1. 追加 user 消息并持久化
        val userMsg = ChatMessage(role = ChatRole.USER, content = trimmed)
        val listWithUser = _messages.value.orEmpty() + userMsg
        _messages.value = listWithUser
        persist(userMsg)

        // 2. 追加空 assistant 占位（流式填充）
        val assistantMsg = ChatMessage(role = ChatRole.ASSISTANT, content = "")
        _messages.value = listWithUser + assistantMsg
        _isSending.value = true

        // 3. 引擎 chat：history = 去掉占位 assistant + 当前 user 的剩余消息
        val historyForEngine = _messages.value.orEmpty().dropLast(2)
        LogCollector.d(TAG, "chat sendMessage: 引擎key=${currentEngineKey()} system='${ChatTemplates.DEFAULT_SYSTEM.take(60)}'")
        LogCollector.d(TAG, "chat sendMessage: 历史${historyForEngine.size}条 → [${historyForEngine.joinToString(" | ") { "${it.role}:${it.content.take(60)}" }}]")
        LogCollector.d(TAG, "chat sendMessage: 当前输入='$trimmed'")
        e.chat(
            history = historyForEngine,
            input = trimmed,
            onPhase = {},
            onPartial = { partial ->
                _messages.postValue(_messages.value.orEmpty().dropLast(1) + assistantMsg.copy(content = partial))
            },
            callback = { result ->
                _isSending.postValue(false)
                when (result) {
                    is ChatResult.Success -> {
                        val finalMsg = assistantMsg.copy(content = result.text)
                        _messages.postValue(_messages.value.orEmpty().dropLast(1) + finalMsg)
                        persist(finalMsg)
                    }
                    is ChatResult.Error -> {
                        val failed = assistantMsg.copy(content = "⚠️ ${result.error.message ?: "生成失败"}")
                        _messages.postValue(_messages.value.orEmpty().dropLast(1) + failed)
                    }
                }
            }
        )
    }

    fun clearAll() {
        engine?.cancel()
        viewModelScope.launch { dao.clearAll() }
        _messages.value = emptyList()
    }

    private fun persist(msg: ChatMessage) {
        viewModelScope.launch {
            dao.insert(ChatMessageEntity(
                role = if (msg.role == ChatRole.USER) 0 else 1,
                content = msg.content,
                createdAt = msg.createdAt
            ))
        }
    }

    override fun onCleared() {
        engine?.release()
        engine = null
        super.onCleared()
    }

    companion object {
        private const val TAG = "ChatHistoryViewModel"
    }
}

private fun ChatMessageEntity.toDomain() =
    ChatMessage(id = id, role = if (role == 0) ChatRole.USER else ChatRole.ASSISTANT, content = content, createdAt = createdAt)
