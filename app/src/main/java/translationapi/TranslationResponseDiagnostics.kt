package translationapi

import java.io.IOException

/** Bounded, credential-redacted details for translation API responses. */
internal object TranslationResponseDiagnostics {
    private const val MAX_PREVIEW_CHARS = 240
    private val sensitiveJsonValue = Regex(
        "(?i)(\"(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret|password|authorization)\"\\s*:\\s*\")[^\"]*(\")"
    )
    private val sensitiveTextValue = Regex(
        "(?i)\\b(api[_ -]?key|access[_ -]?token|refresh[_ -]?token|token|secret|password|authorization)\\b(\\s*[:=]\\s*)[^\\s,;\"'&]+"
    )
    private val bearerToken = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*")

    fun responsePreview(body: String): String {
        if (body.isBlank()) return "<empty>"

        return "\"${safeMessage(body)}\""
    }

    /** Sanitizes provider prose too, e.g. an error message that echoes an invalid API key. */
    fun safeMessage(value: String): String {
        val compact = value.replace(Regex("\\s+"), " ").trim()
        val redacted = bearerToken.replace(
            sensitiveTextValue.replace(
                sensitiveJsonValue.replace(compact, "${'$'}1[redacted]${'$'}2"),
                "${'$'}1${'$'}2[redacted]"
            ),
            "Bearer [redacted]"
        )
        val suffix = if (redacted.length > MAX_PREVIEW_CHARS) "…" else ""
        return redacted.take(MAX_PREVIEW_CHARS) + suffix
    }

    fun emptyBody(provider: String, statusCode: Int, contentType: String?): IOException {
        val type = contentType?.takeIf(String::isNotBlank) ?: "unknown"
        return IOException("$provider returned an empty response body (HTTP $statusCode, Content-Type: $type)")
    }

    fun httpError(
        provider: String,
        statusCode: Int,
        body: String,
        contentType: String? = null
    ): IOException {
        val type = contentType?.takeIf(String::isNotBlank)?.let { ", Content-Type: $it" }.orEmpty()
        return IOException(
            "$provider request failed (HTTP $statusCode$type); response=${responsePreview(body)}"
        )
    }

    fun invalidResponse(
        provider: String,
        body: String,
        cause: Exception,
        statusCode: Int? = null,
        contentType: String? = null
    ): IOException {
        val detail = safeMessage(cause.message?.takeIf(String::isNotBlank) ?: cause.javaClass.simpleName)
        val status = statusCode?.let { "HTTP $it" } ?: "HTTP success response"
        val type = contentType?.takeIf(String::isNotBlank)?.let { ", Content-Type: $it" }.orEmpty()
        return IOException(
            "$provider returned an unreadable response ($status$type, $detail); " +
                "response=${responsePreview(body)}",
            cause
        )
    }
}
