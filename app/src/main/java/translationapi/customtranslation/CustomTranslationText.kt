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

package translationapi.customtranslation

import translationapi.InFlightRequests
import translationapi.TranslationResponseDiagnostics

import android.util.Log
import com.moe.starflow.me.apiconfig.CustomTextAPIConfig
import com.moe.starflow.translate.TranslationResult
import com.moe.starflow.translate.TranslationTextAPI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class CustomTranslationText(private val config: CustomTextAPIConfig): TranslationTextAPI {
    // 创建协程作用域
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inFlight = InFlightRequests()

    private val SOCKET_TIMEOUT = 10L // 10秒
    private val JSON = "application/json; charset=utf-8".toMediaType()

    // 创建OkHttpClient实例
    private val client = OkHttpClient.Builder()
        .connectTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
        .build()

    /**
     * 该函数的sourceLanguage和targetLanguage没有用处，仅为了保持兼容性才接收这两个参数
     * 自定义API的源语言和目标语言用户自己指定
     */
    override fun getTranslation(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        callback: (TranslationResult) -> Unit
    ) {
        inFlight.track(coroutineScope) {
            try {
                // 执行翻译请求
                val result = if (config.method == "GET") {
                    executeGetRequest(text)
                } else {
                    executePostRequest(text)
                }

                // 在主线程中回调结果
                withContext(Dispatchers.Main) {
                    callback(TranslationResult.Success(result))
                }
            } catch (e: CancellationException) {
                // 协程被取消，不调用callback
                throw e
            } catch (e: Exception) {
                Log.e("CUSTOM_TEXT", "Translation error", e)
                withContext(Dispatchers.Main) {
                    callback(TranslationResult.Error(e))
                }
            }
        }
    }

    private fun executeGetRequest(sourceText: String): String {
        // 构建URL，添加查询参数
        val urlBuilder = config.baseUrl.toHttpUrl().newBuilder()

        // 添加查询参数，替换占位符
        config.queryParams.forEach { param ->
            val value = if (param.value == "usesourcetext") {
                sourceText
            } else param.value
            urlBuilder.addQueryParameter(param.key, value)
        }

        // 构建请求
        val request = Request.Builder()
            .url(urlBuilder.build())
            .apply {
                // 添加请求头
                config.headers.forEach { header ->
                    addHeader(header.key, header.value)
                }
            }
            .get()
            .build()

        // 执行请求
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw TranslationResponseDiagnostics.httpError(
                    "Custom text API", response.code, responseBody, response.header("Content-Type")
                )
            }

            if (responseBody.isBlank()) {
                throw TranslationResponseDiagnostics.emptyBody(
                    "Custom text API", response.code, response.header("Content-Type")
                )
            }

            return parseResponse(responseBody)
        }
    }

    private fun executePostRequest(sourceText: String): String {
        // 构建JSON请求体
        val jsonBody = JSONObject().apply {
            config.jsonBody.forEach { field ->
                val value = if (field.value == "usesourcetext") {
                    sourceText
                } else field.value
                put(field.key, value)
            }
        }.toString()

        // 构建请求
        val request = Request.Builder()
            .url(config.baseUrl)
            .post(jsonBody.toRequestBody(JSON))
            .apply {
                // 添加请求头
                config.headers.forEach { header ->
                    addHeader(header.key, header.value)
                }
            }
            .build()

        // 执行请求
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw TranslationResponseDiagnostics.httpError(
                    "Custom text API", response.code, responseBody, response.header("Content-Type")
                )
            }

            if (responseBody.isBlank()) {
                throw TranslationResponseDiagnostics.emptyBody(
                    "Custom text API", response.code, response.header("Content-Type")
                )
            }

            return parseResponse(responseBody)
        }
    }

    private fun parseResponse(responseBody: String): String {
        return try {
            val jsonObject = JSONObject(responseBody)
            // 使用配置的 JSON 响应路径解析结果（支持对象属性 + 数组下标，见 JsonPathParser）
            val value = JsonPathParser.valueOf(jsonObject, config.jsonResponsePath)
            val translated = value?.toString()
            if (value == null || value === JSONObject.NULL || translated.isNullOrBlank()) {
                throw IOException(
                    "Custom text API returned an empty value at response path '${config.jsonResponsePath}'; " +
                        "response=${TranslationResponseDiagnostics.responsePreview(responseBody)}"
                )
            }
            translated
        } catch (e: Exception) {
            if (e is IOException) throw e
            throw TranslationResponseDiagnostics.invalidResponse("Custom text API", responseBody, e)
        }
    }

    override fun cancelTranslation() {
        inFlight.cancelAll()
    }

    override fun release() {
        cancelTranslation()
        coroutineScope.cancel() // 取消整个作用域
    }
}
