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

package translationapi.tencentcloud

import translationapi.InFlightRequests
import translationapi.TranslationResponseDiagnostics

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.moe.starflow.translate.ImageTranslationPayload
import com.moe.starflow.translate.ImageUpload
import com.moe.starflow.translate.TranslationPicAPI
import com.moe.starflow.translate.TranslationResult
import com.moe.starflow.translate.scaledToOriginal
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 腾讯云的图片翻译
 * 请求方法：POST
 * URL没有查询参数
 * 腾讯云API需要特殊的认证头信息，通过TencentSign.getSignature生成，显式设置Content-Type为application/json
 * 请求参数放在请求体中，图片数据转换为Base64格式，以json形式发送
 *
 * 响应主路径是 `ImageRecord.Value[*].TargetText`；若接口带行坐标则解析成 blocks，
 * 否则只返回整段文本（导入侧会降级成文本面板）。
 * 本类**不回收调用方传入的 Bitmap**；只回收自己按长边上限生成的缩略上传副本。
 */

class TencentTranslationImage(
    private val secretId: String,
    private val secretKey: String
) : TranslationPicAPI {
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inFlight = InFlightRequests()

    private val SOCKET_TIMEOUT = 30L
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(SOCKET_TIMEOUT, TimeUnit.SECONDS)
        .build()

    override fun getTranslation(
        pic: Bitmap,
        sourceLanguage: String,
        targetLanguage: String,
        callback: (TranslationResult) -> Unit
    ) {
        inFlight.track(coroutineScope) {
            val prepared = ImageUpload.prepare(pic)
            try {
                // 转换图片为Base64
                val imageBase64 = withContext(Dispatchers.IO) {
                    convertBitmapToBase64(prepared.bitmap)
                }
                ensureActive()

                val payload = translate(
                    imageBase64 = imageBase64,
                    imageWidth = prepared.bitmap.width,
                    imageHeight = prepared.bitmap.height,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                ).scaledToOriginal(prepared.scaleToOriginal)

                withContext(Dispatchers.Main) {
                    callback(TranslationResult.Success(payload.text, payload))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("TENCENT_PIC", "Translation error", e)
                withContext(Dispatchers.Main) {
                    callback(TranslationResult.Error(e))
                }
            } finally {
                if (prepared.owned && !prepared.bitmap.isRecycled) prepared.bitmap.recycle()
            }
        }
    }

    private suspend fun translate(
        imageBase64: String,
        imageWidth: Int,
        imageHeight: Int,
        sourceLanguage: String,
        targetLanguage: String,
    ): ImageTranslationPayload = withContext(Dispatchers.IO) {
        ensureActive()

        // 构建请求体
        // 生成 1 到 100 之间的随机整数
        val randomInt = Random.nextInt(1, 100000)
        // 将整数转换为三位字符串格式
        val formattedString = randomInt.toString().padStart(5, '0')
        val sessionUuid = "session-$formattedString"
        val requestBody = JSONObject().apply {
            put("SessionUuid", sessionUuid)
            put("Scene", "doc")
            put("Data", imageBase64)
            put("Source", sourceLanguage)
            put("Target", targetLanguage)
            put("ProjectId", 0)
        }.toString()

        ensureActive()

        // 获取签名头
        val headers = TencentSign.getSignature(secretId, secretKey, "ImageTranslate", requestBody)

        // 构建请求
        val request = Request.Builder()
            .url("https://tmt.tencentcloudapi.com")
            .post(requestBody.toRequestBody(JSON_TYPE))
            .apply {
                headers.forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()

        ensureActive()

        // 执行请求
        client.newCall(request).execute().use { response ->
            ensureActive()
            val responseBody = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw TranslationResponseDiagnostics.httpError(
                    "Tencent Cloud image translation", response.code, responseBody,
                    response.header("Content-Type")
                )
            }

            if (responseBody.isBlank()) {
                throw TranslationResponseDiagnostics.emptyBody(
                    "Tencent Cloud image translation", response.code, response.header("Content-Type")
                )
            }

            Log.d("TENCENT_PIC", "Response: $responseBody")
            TencentImageResponseParser.parse(responseBody, imageWidth, imageHeight)
        }
    }

    private fun convertBitmapToBase64(bitmap: Bitmap): String {
        return ByteArrayOutputStream().use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
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
