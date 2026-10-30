package translationapi.tencentcloud

import com.moe.starflow.translate.ImageApiException
import com.moe.starflow.translate.ImageTextBlock
import com.moe.starflow.translate.ImageTranslationPayload
import translationapi.TranslationResponseDiagnostics
import org.json.JSONObject

/**
 * Parses Tencent Cloud's `ImageTranslate` response.
 *
 * The response always has `Response.ImageRecord.Value[*].SourceText/TargetText`.
 * Some responses also carry per-line geometry (`Line[].Points[].X/Y`); we use it when
 * present and fall back to plain text otherwise. Coordinates are best-effort because the
 * exact geometry block is not guaranteed by every API version.
 *
 * 失败一律抛 [ImageApiException]（带 `stage` / `Error.Code`），便于日志按字段检索。
 */
object TencentImageResponseParser {

    fun parse(body: String, imageWidth: Int, imageHeight: Int): ImageTranslationPayload {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw TranslationResponseDiagnostics.invalidResponse("Tencent image translation", body, e)
        }

        val response = json.optJSONObject("Response")
            ?: throw TranslationResponseDiagnostics.invalidResponse(
                "Tencent image translation",
                body,
                IllegalStateException("response is missing 'Response'"),
            )

        response.optJSONObject("Error")?.let { error ->
            val code = error.optString("Code")
            throw ImageApiException(
                "Tencent Cloud image translation API error $code: " +
                    TranslationResponseDiagnostics.safeMessage(error.optString("Message")),
                stage = "provider",
                providerCode = code.takeIf { it.isNotBlank() },
            )
        }

        val values = response.optJSONObject("ImageRecord")?.optJSONArray("Value")
        if (values == null || values.length() == 0) {
            throw ImageApiException(
                "Tencent Cloud image translation returned no 'ImageRecord.Value'; " +
                    "response=${TranslationResponseDiagnostics.responsePreview(body)}",
                stage = "parse",
            )
        }

        val blocks = buildList {
            for (i in 0 until values.length()) {
                val item = values.optJSONObject(i) ?: continue
                val source = item.optString("SourceText")
                val translated = item.optString("TargetText")
                if (source.isBlank() && translated.isBlank()) continue

                val bounds = boundsOf(item, imageWidth, imageHeight) ?: continue
                val lineCount = countLines(item).coerceAtLeast(1)
                val vertical = bounds.width < bounds.height && translated.length > 1
                val glyph = if (vertical) {
                    bounds.width.toFloat() / lineCount
                } else {
                    bounds.height.toFloat() / lineCount
                }
                add(
                    ImageTextBlock(
                        left = bounds.left,
                        top = bounds.top,
                        right = bounds.right,
                        bottom = bounds.bottom,
                        sourceText = source,
                        translatedText = translated,
                        vertical = vertical,
                        lineCount = lineCount,
                        fontSize = glyph.coerceIn(1f, 512f),
                    )
                )
            }
        }

        val text = buildString {
            for (i in 0 until values.length()) {
                val item = values.optJSONObject(i) ?: continue
                val translated = item.optString("TargetText")
                if (translated.isBlank()) continue
                if (isNotEmpty()) append('\n')
                append(translated)
            }
        }
        if (text.isBlank() && blocks.isEmpty()) {
            throw ImageApiException(
                "Tencent Cloud image translation returned no TargetText; " +
                    "response=${TranslationResponseDiagnostics.responsePreview(body)}",
                stage = "empty",
            )
        }
        return ImageTranslationPayload(blocks = blocks, text = text)
    }

    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    private fun boundsOf(item: JSONObject, imageWidth: Int, imageHeight: Int): Bounds? {
        val lines = item.optJSONArray("Line") ?: return null
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var points = 0
        for (i in 0 until lines.length()) {
            val line = lines.optJSONObject(i) ?: continue
            val linePoints = line.optJSONArray("Points") ?: continue
            for (j in 0 until linePoints.length()) {
                val point = linePoints.optJSONObject(j) ?: continue
                val x = point.optDouble("X", Double.NaN)
                val y = point.optDouble("Y", Double.NaN)
                if (x.isNaN() || y.isNaN()) continue
                points++
                minX = minOf(minX, x.toInt())
                minY = minOf(minY, y.toInt())
                maxX = maxOf(maxX, x.toInt())
                maxY = maxOf(maxY, y.toInt())
            }
        }
        if (points == 0) return null
        if (maxX <= minX || maxY <= minY) return null
        // 腾讯文档在不同版本里给过像素坐标，也可能没有 Line。这里只接受“像像素”的框：
        // 归一化坐标（0..1）会得到 1px 框，宁可退回文本面板也不要画到错误位置。
        if (maxX - minX < 4 || maxY - minY < 4) return null
        if (maxX > imageWidth * 1.05f || maxY > imageHeight * 1.05f) return null
        val left = minX.coerceIn(0, (imageWidth - 1).coerceAtLeast(0))
        val top = minY.coerceIn(0, (imageHeight - 1).coerceAtLeast(0))
        val right = maxX.coerceIn(left + 1, imageWidth.coerceAtLeast(left + 1))
        val bottom = maxY.coerceIn(top + 1, imageHeight.coerceAtLeast(top + 1))
        return Bounds(left, top, right, bottom)
    }

    private fun countLines(item: JSONObject): Int {
        val lines = item.optJSONArray("Line") ?: return 1
        return lines.length().coerceAtLeast(1)
    }
}
