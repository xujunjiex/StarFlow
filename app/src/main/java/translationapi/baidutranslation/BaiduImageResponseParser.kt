package translationapi.baidutranslation

import com.moe.starflow.translate.ImageTextBlock
import com.moe.starflow.translate.ImageTranslationPayload
import translationapi.TranslationResponseDiagnostics
import java.io.IOException
import org.json.JSONObject

/**
 * Parses Baidu's picture-translation response.
 *
 * `data.content[]` carries one entry per text region:
 * `rect` = "x y w h", `src`, `dst`, `lineCount`.
 * `data.sumDst` is the concatenated translation fallback.
 */
object BaiduImageResponseParser {

    fun parse(body: String, imageWidth: Int, imageHeight: Int): ImageTranslationPayload {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw TranslationResponseDiagnostics.invalidResponse("Baidu image translation", body, e)
        }

        val errorCode = json.optString("error_code")
        if (errorCode.isNotEmpty() && errorCode != "0") {
            throw IOException(
                "Baidu image translation API error $errorCode: " +
                    TranslationResponseDiagnostics.safeMessage(json.optString("error_msg"))
            )
        }

        val data = json.optJSONObject("data")
            ?: throw TranslationResponseDiagnostics.invalidResponse(
                "Baidu image translation",
                body,
                IOException("response is missing 'data'"),
            )

        val blocks = buildList {
            val content = data.optJSONArray("content") ?: return@buildList
            for (i in 0 until content.length()) {
                val item = content.optJSONObject(i) ?: continue
                val source = item.optString("src")
                val translated = item.optString("dst")
                if (source.isBlank() && translated.isBlank()) continue
                val rect = parseRect(item.optString("rect"), imageWidth, imageHeight) ?: continue
                val lineCount = item.optInt("lineCount", 1).coerceAtLeast(1)
                val vertical = rect.width < rect.height && translated.length > 1
                val glyph = if (vertical) {
                    rect.width.toFloat() / lineCount
                } else {
                    rect.height.toFloat() / lineCount
                }
                add(
                    ImageTextBlock(
                        left = rect.left,
                        top = rect.top,
                        right = rect.right,
                        bottom = rect.bottom,
                        sourceText = source,
                        translatedText = translated,
                        vertical = vertical,
                        lineCount = lineCount,
                        fontSize = glyph.coerceIn(1f, 512f),
                    )
                )
            }
        }

        val merged = data.optString("sumDst").ifBlank {
            blocks.joinToString("\n") { it.translatedText }
        }
        if (merged.isBlank() && blocks.isEmpty()) {
            throw IOException(
                "Baidu image translation returned no text; " +
                    "response=${TranslationResponseDiagnostics.responsePreview(body)}"
            )
        }
        return ImageTranslationPayload(blocks = blocks, text = merged)
    }

    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    private fun parseRect(raw: String, imageWidth: Int, imageHeight: Int): Bounds? {
        if (raw.isBlank()) return null
        val parts = raw.trim().split(Regex("[\\s,]+"))
        if (parts.size != 4) return null
        val values = parts.map { it.toIntOrNull() ?: return null }
        val x = values[0]
        val y = values[1]
        val w = values[2]
        val h = values[3]
        if (w <= 0 || h <= 0) return null
        val left = x.coerceIn(0, (imageWidth - 1).coerceAtLeast(0))
        val top = y.coerceIn(0, (imageHeight - 1).coerceAtLeast(0))
        val right = (x + w).coerceIn(left + 1, imageWidth.coerceAtLeast(left + 1))
        val bottom = (y + h).coerceIn(top + 1, imageHeight.coerceAtLeast(top + 1))
        if (right <= left || bottom <= top) return null
        return Bounds(left, top, right, bottom)
    }
}
