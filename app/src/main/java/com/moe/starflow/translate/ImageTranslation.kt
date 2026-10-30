package com.moe.starflow.translate

/**
 * One text block returned by a cloud image translation API.
 *
 * Coordinates are in the **source bitmap space** (the bitmap passed to [TranslationPicAPI]),
 * not in the upload copy's space — implementations must map them back after downscaling.
 */
data class ImageTextBlock(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val sourceText: String,
    val translatedText: String,
    val vertical: Boolean,
    val lineCount: Int,
    val fontSize: Float,
) {
    val width: Int get() = (right - left).coerceAtLeast(1)
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/**
 * Optional payload carried by [TranslationResult.Success] for image translation.
 *
 * [blocks] may be empty when the provider only returns plain text (custom API, Tencent
 * without coordinates, etc.). In that case [text] is still the displayable translation.
 */
data class ImageTranslationPayload(
    val blocks: List<ImageTextBlock> = emptyList(),
    val text: String = blocks.joinToString("\n") { it.translatedText },
) {
    val hasBlocks: Boolean get() = blocks.isNotEmpty()
}
