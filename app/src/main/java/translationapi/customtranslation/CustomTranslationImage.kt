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

import android.graphics.Bitmap
import android.util.Base64
import com.moe.starflow.me.apiconfig.CustomPicAPIConfig
import com.moe.starflow.translate.ImageApiException
import com.moe.starflow.translate.ImageTranslationLog
import com.moe.starflow.translate.ImageTranslationPayload
import com.moe.starflow.translate.ImageUpload
import com.moe.starflow.translate.TranslationPicAPI
import com.moe.starflow.translate.TranslationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 自定义图片翻译接口。
 *
 * 自定义配置只提供一个 JSON 文本响应路径，没有坐标字段 —— 因此结果永远是
 * `blocks = emptyList()`，导入/插图侧会降级为文本面板。
 * 本类**不回收调用方传入的 Bitmap**；只回收自己按长边上限生成的缩略上传副本。
 */
class CustomTranslationImage(
    private val config: CustomPicAPIConfig,
    /** 稳定 provider id（`custom#<序号>`），只用于日志。 */
    private val providerId: String = PROVIDER_PREFIX,
): TranslationPicAPI {
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inFlight = InFlightRequests()

    companion object {
        /** 稳定 provider id 前缀（日志里带自定义接口序号：`custom#1`）。 */
        const val PROVIDER_PREFIX = "custom"
    }

    private val SOCKET_TIMEOUT = 30L
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val MULTIPART_FORM_DATA = "multipart/form-data".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
        .build()

    override fun getTranslation(
        pic: Bitmap,
        sourceLanguage: String,
        targetLanguage: String,
        requestContext: String,
        callback: (TranslationResult) -> Unit
    ) {
        inFlight.track(coroutineScope) {
            val prepared = try {
                ImageUpload.prepare(pic)
            } catch (e: Exception) {
                ImageTranslationLog.prepareFailed(providerId, requestContext, pic.width, pic.height, e)
                withContext(Dispatchers.Main) { callback(TranslationResult.Error(e)) }
                return@track
            }
            val session = ImageTranslationLog.start(
                provider = providerId,
                requestContext = requestContext,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                sourceWidth = pic.width,
                sourceHeight = pic.height,
                upload = prepared,
            )
            var stage = "encode"
            var tempFile: File? = null
            try {
                val uploadBytes = withContext(Dispatchers.IO) { ImageUpload.encodeJpeg(prepared.bitmap) }
                session.uploaded(uploadBytes.size)
                val base64Image = if (config.method == "POST" && config.contentType == "multipart/form-data") {
                    tempFile = File.createTempFile("translate_", ".jpg")
                    withContext(Dispatchers.IO) {
                        FileOutputStream(tempFile!!).use { it.write(uploadBytes) }
                    }
                    ""
                } else {
                    Base64.encodeToString(uploadBytes, Base64.NO_WRAP)
                }
                stage = "request"

                val result = when {
                    config.method == "GET" -> executeGetRequest(base64Image, session)
                    config.contentType == "application/json" -> executePostJsonRequest(base64Image, session)
                    config.contentType == "multipart/form-data" -> executePostMultipartRequest(tempFile!!, session)
                    else -> throw ImageApiException(
                        "Unsupported request type (method=${config.method}, contentType=${config.contentType})",
                        stage = "config",
                    )
                }

                val payload = ImageTranslationPayload(blocks = emptyList(), text = result)
                session.ok(blocks = 0, textOnly = true, chars = result.length)
                withContext(Dispatchers.Main) {
                    callback(TranslationResult.Success(result, payload))
                }
            } catch (e: CancellationException) {
                session.cancelled()
                throw e
            } catch (e: Exception) {
                session.fail(e, fallbackStage = stage)
                withContext(Dispatchers.Main) {
                    callback(TranslationResult.Error(e))
                }
            } finally {
                // 取消路径上 withContext 会立刻抛 CancellationException，临时文件/缩放副本会泄漏。
                tempFile?.delete()
                if (prepared.owned && !prepared.bitmap.isRecycled) prepared.bitmap.recycle()
            }
        }
    }

    private fun executeGetRequest(
        base64Image: String,
        session: ImageTranslationLog.Session,
    ): String {
        // 构建URL，添加查询参数
        val urlBuilder = config.baseUrl.toHttpUrl().newBuilder()

        // 添加查询参数，替换base64占位符
        config.queryParams.forEach { param ->
            val value = if (param.value == "useimgbase64") {
                base64Image
            } else param.value
            urlBuilder.addQueryParameter(param.key, value)
        }

        // 构建请求
        val request = Request.Builder()
            .url(urlBuilder.build())
            .apply {
                config.headers.forEach { header ->
                    addHeader(header.key, header.value)
                }
            }
            .get()
            .build()

        // 执行请求
        return executeRequest(request, session)
    }

    private fun executePostJsonRequest(
        base64Image: String,
        session: ImageTranslationLog.Session,
    ): String {
        // 构建JSON请求体
        val jsonBody = JSONObject().apply {
            config.body.forEach { field ->
                val value = if (field.value == "useimgbase64") {
                    base64Image
                } else field.value
                put(field.key, value)
            }
        }.toString()

        // 构建请求
        val request = Request.Builder()
            .url(config.baseUrl)
            .post(jsonBody.toRequestBody(JSON_TYPE))
            .apply {
                config.headers.forEach { header ->
                    addHeader(header.key, header.value)
                }
            }
            .build()

        return executeRequest(request, session)
    }

    private fun executePostMultipartRequest(
        imageFile: File,
        session: ImageTranslationLog.Session,
    ): String {
        // 构建MultipartBody
        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)

        // 添加form字段
        config.body.forEach { field ->
            if (field.value == "useimgfile") {
                // 添加文件部分
                multipartBuilder.addFormDataPart(
                    field.key,
                    "image.jpg",
                    imageFile.asRequestBody(MULTIPART_FORM_DATA)
                )
            } else {
                // 添加普通字段
                multipartBuilder.addFormDataPart(field.key, field.value)
            }
        }

        // 构建请求
        val request = Request.Builder()
            .url(config.baseUrl)
            .post(multipartBuilder.build())
            .apply {
                config.headers.forEach { header ->
                    addHeader(header.key, header.value)
                }
            }
            .build()

        return executeRequest(request, session)
    }

    private fun executeRequest(
        request: Request,
        session: ImageTranslationLog.Session,
    ): String {
        // 执行请求
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            session.http(response.code)
            if (!response.isSuccessful) {
                throw ImageApiException(
                    TranslationResponseDiagnostics.httpError(
                        "Custom image API", response.code, responseBody, response.header("Content-Type")
                    ).message.orEmpty(),
                    stage = "http",
                    httpStatus = response.code,
                )
            }

            if (responseBody.isBlank()) {
                throw ImageApiException(
                    TranslationResponseDiagnostics.emptyBody(
                        "Custom image API", response.code, response.header("Content-Type")
                    ).message.orEmpty(),
                    stage = "empty",
                    httpStatus = response.code,
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
                throw ImageApiException(
                    "Custom image API returned an empty value at response path '${config.jsonResponsePath}'; " +
                        "response=${TranslationResponseDiagnostics.responsePreview(responseBody)}",
                    stage = "empty",
                )
            }
            translated
        } catch (e: ImageApiException) {
            throw e
        } catch (e: Exception) {
            throw ImageApiException(
                TranslationResponseDiagnostics.invalidResponse("Custom image API", responseBody, e).message.orEmpty(),
                stage = "parse",
                cause = e,
            )
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
