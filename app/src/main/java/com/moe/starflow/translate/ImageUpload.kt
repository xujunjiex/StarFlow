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
