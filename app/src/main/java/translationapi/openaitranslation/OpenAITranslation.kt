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

package translationapi.openaitranslation

import com.moe.starflow.me.apiconfig.OpenAIProviderConfig
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.translate.CustomLocale
import com.moe.starflow.translate.TranslationResult
import com.moe.starflow.translate.TranslationTextAPI
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI规范的AI翻译
 * 请求方法：POST
 * URL: {baseUrl}/chat/completions
 * 请求头设置Content-Type为application/json和Authorization
 * 请求参数放在请求体中，以json形式发送
 * 支持各种兼容OpenAI API的服务商
 */

class OpenAITranslation(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-3.5-turbo",
    private val systemPrompt: String,
    private val userPrompt: String,
    private val maxTokens: Int = 1000,
    private val temperature: Float = 0.3f,
    private val continuationType: String = "none",
    private val prefillContent: String = "",
    private val autoAppendPath: Boolean = true,
    /** 思考模式：0=跟随模型默认（不发送 thinking 参数）/ 1=强制关闭 / 2=强制开启（见 OpenAIProviderConfig.THINKING_*） */
    private val thinkingMode: Int = OpenAIProviderConfig.THINKING_DEFAULT
) : TranslationTextAPI {

    override val modelName: String get() = model

    companion object {
        private const val TAG = "OpenAITranslation"
        private const val SOCKET_TIMEOUT = 90L // 90秒，AI接口（尤其第三方代理）冷启动/长文本响应偶尔超过 30s
        /** 列表接口超时：只查元数据，够不到基本就是网络/key 不通，早失败早反馈 */
        private const val MODELS_TIMEOUT = 15L
    }

    // 上下文相关（动态更新，每次翻译前通过 updateContext 设置）
    @Volatile private var currentContextHistory: List<Pair<String, String>> = emptyList()
    @Volatile private var currentContextEnabled: Boolean = false

    /**
     * 更新上下文。每次翻译前调用，传入最新的历史对话。
     */
    fun updateContext(history: List<Pair<String, String>>, enabled: Boolean) {
        this.currentContextHistory = history
        this.currentContextEnabled = enabled
    }

    // 创建协程作用域
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentJob: Job? = null

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // 创建OkHttpClient实例
    private val client = OkHttpClient.Builder()
        .connectTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
        .build()

    /**
     * 「获取模型列表」专用客户端：共享 [client] 的连接池与线程池，只是超时短得多。
     * 用 90s 的话网络不通时按钮会卡在「获取中…」一分半，用户以为死机了。
     */
    private val modelsClient = client.newBuilder()
        .connectTimeout(MODELS_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(MODELS_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(MODELS_TIMEOUT, TimeUnit.SECONDS)
        .build()

    override fun getTranslation(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        callback: (TranslationResult) -> Unit
    ) {
        // 取消之前的任务（如果存在）
        currentJob?.cancel()

        currentJob = coroutineScope.launch {
            try {
                val result = translate(text, sourceLanguage, targetLanguage)
                withContext(Dispatchers.Main) {
                    callback(TranslationResult.Success(result))
                }
            } catch (e: CancellationException) {
                // 协程被取消，不调用callback
                throw e
            } catch (e: Exception) {
                LogCollector.e(TAG, "Translation error", e)
                withContext(Dispatchers.Main) {
                    callback(TranslationResult.Error(e))
                }
            }
        }
    }

    private suspend fun translate(text: String, from: String, to: String): String = withContext(Dispatchers.IO) {
        ensureActive()

        // 构建翻译提示词
        val toLang = CustomLocale.getInstance(to).getDisplayName()
        val systemPrompt = buildSystemPrompt(toLang)
        val userPrompt = buildUserPrompt(text, from, to)

        // 构建请求体：按思考模式决定是否发送 thinking 参数（0=不发送，1=强制关闭，2=强制开启）
        val requestBody = buildRequestBody(systemPrompt, userPrompt, thinkingMode)
        LogCollector.d(TAG, "Request: $requestBody")

        val endpoint = if (continuationType == "prefix") {
            if (autoAppendPath) {
                if (baseUrl.endsWith("/")) {
                    "${baseUrl}beta/chat/completions"
                } else {
                    "$baseUrl/beta/chat/completions"
                }
            } else {
                if (baseUrl.endsWith("/")) {
                    "${baseUrl}beta"
                } else {
                    baseUrl
                }
            }
        } else {
            if (autoAppendPath) {
                if (baseUrl.endsWith("/")) {
                    "${baseUrl}chat/completions"
                } else {
                    "$baseUrl/chat/completions"
                }
            } else {
                baseUrl
            }
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody.toRequestBody(JSON))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        ensureActive()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            response.close()
            throw IOException("Request failed ${response.code}: $errorBody")
        }

        // 解析响应
        val responseBody = response.body?.string()
            ?: throw IOException("Empty response body")
        response.close()

        LogCollector.d(TAG, "Response: $responseBody")
        parseResponse(responseBody)
    }

    private fun buildSystemPrompt(toLang: String): String {
        val prompt = systemPrompt.replace("usetolang", toLang)
        return if (currentContextEnabled && currentContextHistory.isNotEmpty()) {
            "根据上下文剧情进行翻译，保持角色语气和用词一致。\n\n$prompt"
        } else {
            prompt
        }
    }

    private fun buildUserPrompt(text: String, from: String, to: String): String {
        val fromLang = CustomLocale.getInstance(from).getDisplayName()
        val toLang = CustomLocale.getInstance(to).getDisplayName()

        LogCollector.d(TAG, "翻译配置: model=$model, from=$from($fromLang), to=$to($toLang), continuationType=$continuationType, context=${if (currentContextEnabled) "${currentContextHistory.size}轮" else "关闭"}")
        LogCollector.d(TAG, "SystemPrompt: $systemPrompt")
        LogCollector.d(TAG, "UserPrompt模板: $userPrompt")

        val fullUserPrompt = userPrompt
            .replace("usefromlang", fromLang)
            .replace("usetolang", toLang)
            .replace("usesourcetext", text)

        LogCollector.d(TAG, "UserPrompt: $fullUserPrompt")

        return fullUserPrompt
    }

    private fun buildRequestBody(
        systemPrompt: String,
        userPrompt: String,
        thinkingMode: Int = OpenAIProviderConfig.THINKING_DEFAULT
    ): String {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            // 上下文历史：插入历史 user/assistant 对
            if (currentContextEnabled && currentContextHistory.isNotEmpty()) {
                for ((src, tgt) in currentContextHistory) {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "翻译：$src")
                    })
                    put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", tgt)
                    })
                }
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", userPrompt)
            })
            // 续写模式：添加 assistant prefill（JSON模式不加，靠 response_format 控制）
            // 仅对已知支持的续写类型启用，避免未知类型（如用户自定义API空字符串）发送不兼容的 prefill
            if (prefillContent.isNotEmpty() && continuationType in setOf("standard", "partial", "prefix")) {
                put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", prefillContent)
                    when (continuationType) {
                        "partial" -> put("partial", true)   // 千问
                        "prefix" -> put("prefix", true)     // DeepSeek
                    }
                })
            }
        }

        return JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            put("stream", false)
            // 思考模式三态：0=跟随模型默认（不发送参数，兼容不支持的模型如硅基流动 Hunyuan）；
            // 1=强制关闭（发送 disabled，针对"默认会思考"的推理模型）；2=强制开启（发送 enabled）
            when (thinkingMode) {
                OpenAIProviderConfig.THINKING_FORCE_DISABLED ->
                    put("thinking", JSONObject().apply { put("type", "disabled") })
                OpenAIProviderConfig.THINKING_FORCE_ENABLED ->
                    put("thinking", JSONObject().apply { put("type", "enabled") })
            }
            // 智谱AI结构化输出：强制JSON格式
            if (continuationType == "json") {
                put("response_format", JSONObject().apply {
                    put("type", "json_object")
                })
            }
        }.toString()
    }

    private fun parseResponse(responseBody: String): String {
        try {
            val jsonObject = JSONObject(responseBody)

            // 检查是否有错误
            if (jsonObject.has("error")) {
                val error = jsonObject.getJSONObject("error")
                val message = error.optString("message", "Unknown error")
                val type = error.optString("type", "unknown")
                throw IOException("OpenAI API error ($type): $message")
            }

            // 获取翻译结果
            val choices = jsonObject.getJSONArray("choices")
            if (choices.length() == 0) {
                throw IOException("No translation result in response")
            }

            val firstChoice = choices.getJSONObject(0)
            val message = firstChoice.getJSONObject("message")
            val content = message.getString("content").trim()

            LogCollector.d(TAG, "翻译结果: $content")

            if (content.isEmpty()) {
                throw IOException("Empty translation result")
            }

            return content
        } catch (e: Exception) {
            throw IOException("Failed to parse response: ${e.message}")
        }
    }

    override fun cancelTranslation() {
        currentJob?.cancel()
        currentJob = null
    }

    override fun release() {
        cancelTranslation()
        coroutineScope.cancel() // 取消整个作用域
    }

    /**
     * 获取支持的模型列表（需要API支持）
     * @param callback 模型列表回调
     */
    fun getSupportedModels(callback: (List<String>?, String?) -> Unit) {
        coroutineScope.launch {
            val url = "$baseUrl/models"
            try {
                // 打 apiKey 长度而不是内容：401 排查时"key 是不是空的/是不是被截断"是最常见原因
                LogCollector.d(TAG, "获取模型列表: GET $url（apiKey 长度=${apiKey.length}）")
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Authorization", "Bearer $apiKey")
                    .build()

                modelsClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val detail = extractServerError(responseBody, response.code)
                        LogCollector.e(TAG, "获取模型列表失败 $detail")
                        throw IOException(detail)
                    }

                    val jsonObject = JSONObject(responseBody)
                    val data = jsonObject.getJSONArray("data")
                    val models = mutableListOf<String>()

                    for (i in 0 until data.length()) {
                        val model = data.getJSONObject(i)
                        models.add(model.getString("id"))
                    }

                    LogCollector.d(TAG, "获取模型列表成功: ${models.size} 个")
                    withContext(Dispatchers.Main) {
                        callback(models, null)
                    }
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "获取模型列表异常: $url", e)
                withContext(Dispatchers.Main) {
                    callback(null, e.message)
                }
            }
        }
    }

    /**
     * 从错误响应里取一句能直接展示给用户的说明。
     *
     * OpenAI 兼容格式是 `{"error":{"message":"..."}}` —— 服务端那句话本身最有用
     * （如 DeepSeek 的 "Authentication Fails, Your api key: xxx is invalid"），
     * 直接把原始 JSON 丢给用户既难看又难读。取不到才退回状态码 + 响应体片段。
     */
    private fun extractServerError(body: String, code: Int): String {
        val message = try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        } catch (e: Exception) {
            ""   // 响应体不是 JSON（网关/代理返回的 HTML 等）
        }
        return when {
            message.isNotBlank() && (code == 401 || code == 403) ->
                "HTTP $code 未授权（API Key 无效）：$message"
            message.isNotBlank() -> "HTTP $code：$message"
            else -> "HTTP $code：${body.take(200)}"
        }
    }
}