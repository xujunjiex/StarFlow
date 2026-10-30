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

package translationapi.baidutranslation

import translationapi.InFlightRequests
import translationapi.TranslationResponseDiagnostics

import android.graphics.Bitmap
import com.moe.starflow.translate.ImageApiException
import com.moe.starflow.translate.ImageTranslationLog
import com.moe.starflow.translate.ImageTranslationPayload
import com.moe.starflow.translate.ImageUpload
import com.moe.starflow.translate.TranslationPicAPI
import com.moe.starflow.translate.TranslationResult
import com.moe.starflow.translate.scaledToOriginal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.concurrent.TimeUnit

/**
 * 百度翻译的图片翻译
 * 请求方法：POST
 * URL没有查询参数
 * 请求头设置Content-Type为multipart/form-data
 * 请求参数放在请求体中，包含图片文件数据，包含多个表单字段（有签名）
 *
 * 响应里的 `data.content[]` 带 `rect`（"x y w h"）、`src`、`dst`，用于把译文画回原图。
 * 本类**不回收调用方传入的 Bitmap**；只回收自己按长边上限生成的缩略上传副本。
 */

class BaiduTranslationImage(
    private val appId: String,
    private val secretKey: String
) : TranslationPicAPI {
    // 创建协程作用域
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inFlight = InFlightRequests()

    companion object {
        /** 稳定 provider id（日志 `provider=` 字段，不随语言本地化变化）。 */
        const val PROVIDER = "baidu"
    }

    private val TRANS_API_HOST = "https://fanyi-api.baidu.com/api/trans/sdk/picture"
    private val SOCKET_TIMEOUT = 30L // 图片上传比文本慢，留足时间
    private val MULTIPART_FORM_DATA = "multipart/form-data".toMediaType()

    // 创建OkHttpClient实例
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
                // 连上传副本都生成不了（超大图分配失败等）：没有请求可发，但也必须留一行日志
                ImageTranslationLog.prepareFailed(PROVIDER, requestContext, pic.width, pic.height, e)
                withContext(Dispatchers.Main) { callback(TranslationResult.Error(e)) }
                return@track
            }
            val session = ImageTranslationLog.start(
                provider = PROVIDER,
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

                tempFile = File.createTempFile("translate_", ".jpg")
                withContext(Dispatchers.IO) {
                    FileOutputStream(tempFile!!).use { it.write(uploadBytes) }
                }
                stage = "request"

                val payload = translate(
                    imageFile = tempFile,
                    imageWidth = prepared.bitmap.width,
                    imageHeight = prepared.bitmap.height,
                    from = sourceLanguage,
                    to = targetLanguage,
                    session = session,
                ).scaledToOriginal(prepared.scaleToOriginal)

                session.ok(
                    blocks = payload.blocks.size,
                    textOnly = payload.blocks.isEmpty(),
                    chars = payload.text.length,
                )
                withContext(Dispatchers.Main) {
                    callback(TranslationResult.Success(payload.text, payload))
                }
            } catch (e: CancellationException) {
                // 协程被取消，不调用callback
                session.cancelled()
                throw e
            } catch (e: Exception) {
                session.fail(e, fallbackStage = stage)
                withContext(Dispatchers.Main) {
                    callback(TranslationResult.Error(e))
                }
            } finally {
                // 取消路径上不能再 suspend（withContext 会立刻抛 CancellationException），
                // 否则临时文件和缩放副本都会泄漏。delete/recycle 都是非挂起操作，直接做。
                tempFile?.delete()
                if (prepared.owned && !prepared.bitmap.isRecycled) prepared.bitmap.recycle()
            }
        }
    }

    private suspend fun translate(
        imageFile: File,
        imageWidth: Int,
        imageHeight: Int,
        from: String,
        to: String,
        session: ImageTranslationLog.Session,
    ): ImageTranslationPayload = withContext(Dispatchers.IO) {
        ensureActive()
        // 计算图片MD5
        val imageMd5 = getMD5File(imageFile)

        // 构建签名参数
        val salt = System.currentTimeMillis().toString()
        val cuid = "APICUID"
        val mac = "mac"

        // 计算签名：md5(appid+md5(image)+salt+cuid+mac+密钥)
        val sign = getMD5(appId + imageMd5 + salt + cuid + mac + secretKey).lowercase()

        // 构建MultipartBody
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", "image.jpg",
                imageFile.asRequestBody(MULTIPART_FORM_DATA))
            .addFormDataPart("from", modifyTargetLanguage(from))
            .addFormDataPart("to", modifyTargetLanguage(to))
            .addFormDataPart("appid", appId)
            .addFormDataPart("salt", salt)
            .addFormDataPart("cuid", cuid)
            .addFormDataPart("mac", mac)
            .addFormDataPart("version", "3")
            .addFormDataPart("paste", "0")
            .addFormDataPart("erase", "0")
            .addFormDataPart("sign", sign)
            .build()

        // 构建请求
        val request = Request.Builder()
            .url(TRANS_API_HOST)
            .post(requestBody)
            .build()

        // 执行请求
        client.newCall(request).execute().use { response ->
            ensureActive()
            val responseBody = response.body?.string().orEmpty()
            session.http(response.code)

            if (!response.isSuccessful) {
                throw ImageApiException(
                    TranslationResponseDiagnostics.httpError(
                        "Baidu image translation", response.code, responseBody, response.header("Content-Type")
                    ).message.orEmpty(),
                    stage = "http",
                    httpStatus = response.code,
                )
            }

            if (responseBody.isBlank()) {
                throw ImageApiException(
                    TranslationResponseDiagnostics.emptyBody(
                        "Baidu image translation", response.code, response.header("Content-Type")
                    ).message.orEmpty(),
                    stage = "empty",
                    httpStatus = response.code,
                )
            }

            try {
                BaiduImageResponseParser.parse(responseBody, imageWidth, imageHeight)
            } catch (e: ImageApiException) {
                throw e
            } catch (e: Exception) {
                throw ImageApiException(
                    TranslationResponseDiagnostics.invalidResponse(
                        "Baidu image translation", responseBody, e, response.code,
                        response.header("Content-Type"),
                    ).message.orEmpty(),
                    stage = "parse",
                    httpStatus = response.code,
                    cause = e,
                )
            }
        }
    }

    private suspend fun getMD5File(file: File): String = withContext(Dispatchers.IO) {
        try {
            val md = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    md.update(buffer, 0, bytesRead)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException("MD5 algorithm not found", e)
        }
    }

    private fun getMD5(input: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val messageDigest = md.digest(input.toByteArray())
            messageDigest.joinToString("") { "%02x".format(it) }
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException("MD5 algorithm not found", e)
        }
    }

    // 因为百度翻译的API没有使用BCP-47代码作为他们的语言代码，因此需要转换
    private fun modifyTargetLanguage(to: String): String = when (to) {
        "ko" -> "kor"
        "bg" -> "bul"
        "fi" -> "fin"
        "sl" -> "slo"
        "zh-TW" -> "cht"
        "fr" -> "fra"
        "ar" -> "ara"
        "et" -> "est"
        "sv" -> "swe"
        "vi" -> "vie"
        "ja" -> "jp"
        "es" -> "spa"
        "da" -> "dan"
        "ro" -> "rom"
        else -> to
    }

    override fun cancelTranslation() {
        inFlight.cancelAll()
    }

    override fun release() {
        cancelTranslation()
        coroutineScope.cancel() // 取消整个作用域
    }
}
