package translationapi.doubaotranslation

import translationapi.InFlightRequests
import translationapi.TranslationResponseDiagnostics

import android.util.Log
import com.moe.starflow.me.apiconfig.OpenAIProviderConfig
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
 * 豆包 Responses API 翻译
 * 请求方法：POST
 * URL: {baseUrl}/responses
 * 格式：{ "model": "...", "input": "...", "instructions": "..." }
 * 响应：output[0].content[0].text
 */
class DoubaoTranslation(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
    private val systemPrompt: String,
    private val userPrompt: String,
    private val maxTokens: Int = 1000,
    /** 思考模式：0=跟随模型默认（不发送 thinking 参数）/ 1=强制关闭 / 2=强制开启 */
    private val thinkingMode: Int = OpenAIProviderConfig.THINKING_DEFAULT
) : TranslationTextAPI {

    companion object {
        private const val TAG = "DoubaoTranslation"
        private const val SOCKET_TIMEOUT = 30L
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inFlight = InFlightRequests()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
        .build()

    override fun getTranslation(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        callback: (TranslationResult) -> Unit
    ) {
        inFlight.track(coroutineScope) {
            try {
                val result = translate(text, sourceLanguage, targetLanguage)
                withContext(Dispatchers.Main) {
                    callback(TranslationResult.Success(result))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Translation error", e)
                withContext(Dispatchers.Main) {
                    callback(TranslationResult.Error(e))
                }
            }
        }
    }

    private suspend fun translate(text: String, from: String, to: String): String = withContext(Dispatchers.IO) {
        ensureActive()

        val fromLang = CustomLocale.getInstance(from).getDisplayName()
        val toLang = CustomLocale.getInstance(to).getDisplayName()

        val instructions = systemPrompt
        val input = userPrompt
            .replace("usefromlang", fromLang)
            .replace("usetolang", toLang)
            .replace("usesourcetext", text)

        val requestBody = JSONObject().apply {
            put("model", model)
            put("input", input)
            put("instructions", instructions)
            put("max_tokens", maxTokens)
            put("stream", false)
            // 思考模式三态：0=不发送（兼容不支持的模型）/ 1=强制关闭 / 2=强制开启
            when (thinkingMode) {
                OpenAIProviderConfig.THINKING_FORCE_DISABLED ->
                    put("thinking", JSONObject().apply { put("type", "disabled") })
                OpenAIProviderConfig.THINKING_FORCE_ENABLED ->
                    put("thinking", JSONObject().apply { put("type", "enabled") })
            }
        }.toString()

        Log.d(TAG, "Request: $requestBody")

        val url = if (baseUrl.endsWith("/")) "${baseUrl}responses" else "$baseUrl/responses"
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(JSON))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        ensureActive()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            response.close()
            throw TranslationResponseDiagnostics.httpError(
                "Doubao Responses API", response.code, errorBody, response.header("Content-Type")
            )
        }

        val responseBody = response.body?.string().orEmpty()
        val contentType = response.header("Content-Type")
        val statusCode = response.code
        response.close()

        if (responseBody.isBlank()) {
            throw TranslationResponseDiagnostics.emptyBody("Doubao Responses API", statusCode, contentType)
        }

        Log.d(TAG, "Response: $responseBody")
        parseResponse(responseBody)
    }

    private fun parseResponse(responseBody: String): String {
        try {
            val jsonObject = JSONObject(responseBody)

            if (jsonObject.has("error")) {
                val error = jsonObject.getJSONObject("error")
                val message = TranslationResponseDiagnostics.safeMessage(
                    error.optString("message", "Unknown error")
                )
                throw IOException("Doubao API error: $message")
            }

            val output = jsonObject.optJSONArray("output")
                ?: throw TranslationResponseDiagnostics.invalidResponse(
                    "Doubao Responses API", responseBody, IllegalStateException("output is missing")
                )
            if (output.length() == 0) {
                throw IOException(
                    "Doubao Responses API returned an empty 'output' array " +
                        "(status=${jsonObject.optString("status", "unknown")}); " +
                        "response=${TranslationResponseDiagnostics.responsePreview(responseBody)}"
                )
            }

            val contentBlocks = mutableListOf<JSONObject>()
            val blockTypes = mutableListOf<String>()
            for (outputIndex in 0 until output.length()) {
                val outputItem = output.optJSONObject(outputIndex) ?: continue
                val content = outputItem.optJSONArray("content") ?: continue
                for (contentIndex in 0 until content.length()) {
                    val block = content.optJSONObject(contentIndex) ?: continue
                    contentBlocks += block
                    block.optString("type").takeIf(String::isNotBlank)?.let(blockTypes::add)
                }
            }

            val text = contentBlocks.mapNotNull { block ->
                val type = block.optString("type")
                if (type.isNotBlank() && type != "output_text") return@mapNotNull null
                block.optString("text").takeIf(String::isNotBlank)
            }.joinToString("\n").trim()
            if (text.isEmpty()) {
                throw IOException(
                    "Doubao Responses API returned no translation text in output content " +
                        "(block_types=${blockTypes.distinct()}, status=${jsonObject.optString("status", "unknown")}); " +
                        "response=${TranslationResponseDiagnostics.responsePreview(responseBody)}"
                )
            }

            return text
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw TranslationResponseDiagnostics.invalidResponse("Doubao Responses API", responseBody, e)
        }
    }

    override fun cancelTranslation() {
        inFlight.cancelAll()
    }

    override fun release() {
        cancelTranslation()
        coroutineScope.cancel()
    }
}
