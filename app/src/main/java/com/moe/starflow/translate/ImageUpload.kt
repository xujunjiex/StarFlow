package com.moe.starflow.translate

import android.graphics.Bitmap

/**
 * Builds the bitmap actually uploaded to a cloud image API.
 *
 * The API returns coordinates in the uploaded image's space. Callers must use
 * [scaleToOriginal] to map those coordinates back to the bitmap they passed in.
 *
 * [owned] is true only when a downscaled copy was created; the caller is responsible for
 * recycling it. When no scaling is needed the source bitmap is returned untouched and
 * [owned] is false.
 */
object ImageUpload {

    /** Long-edge cap shared by Baidu/Tencent/custom image translation. */
    const val MAX_SIDE = 2048

    /**
     * JPEG 质量：三个接口**共用这一份**编码（以前百度/腾讯/自定义各自 `compress(95)`，
     * 质量一改就得改三处，日志里的 `bytes=` 也对不上号）。
     */
    const val JPEG_QUALITY = 95

    data class Prepared(
        val bitmap: Bitmap,
        val owned: Boolean,
        val scaleToOriginal: Float,
    )

    fun prepare(source: Bitmap, maxSide: Int = MAX_SIDE): Prepared {
        val max = maxOf(source.width, source.height)
        if (max <= maxSide) return Prepared(source, owned = false, scaleToOriginal = 1f)

        val ratio = maxSide.toFloat() / max.toFloat()
        val width = (source.width * ratio).toInt().coerceAtLeast(1)
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        return Prepared(
            bitmap = scaled,
            owned = true,
            scaleToOriginal = source.width.toFloat() / width.toFloat(),
        )
    }

    /** 编码上传副本；三个接口共用（字节数由 [ImageTranslationLog.Session.uploaded] 记账）。 */
    fun encodeJpeg(bitmap: Bitmap, quality: Int = JPEG_QUALITY): ByteArray =
        java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }

}

/** Map parser coordinates from the uploaded bitmap back to the caller's original bitmap. */
fun ImageTranslationPayload.scaledToOriginal(scale: Float): ImageTranslationPayload {
    if (scale == 1f || blocks.isEmpty()) return this
    return copy(
        blocks = blocks.map { block ->
            block.copy(
                left = (block.left * scale).toInt(),
                top = (block.top * scale).toInt(),
                right = (block.right * scale).toInt(),
                bottom = (block.bottom * scale).toInt(),
                fontSize = block.fontSize * scale,
            )
        },
    )
}
