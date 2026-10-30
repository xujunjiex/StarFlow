package com.moe.starflow.translate
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import android.graphics.Bitmap

/**
 * Cloud image translation provider (no local OCR).
 *
 * ### Bitmap ownership
 * The caller owns [pic]. Implementations **must not** recycle it; they may create and
 * recycle their own upload copy. This keeps the caller free to use the same bitmap for
 * rendering after the callback.
 */
interface TranslationPicAPI {

    /**
     * Asynchronously translate an image.
     *
     * On success the callback receives [TranslationResult.Success] with an
     * [ImageTranslationPayload] (possibly empty `blocks`). On failure it receives
     * [TranslationResult.Error]. Callbacks should arrive on the main thread, matching the
     * existing text engines.
     */
    fun getTranslation(
        pic: Bitmap,
        sourceLanguage: String,
        targetLanguage: String,
        callback: (TranslationResult) -> Unit
    )

    /** Cancel all in-flight requests owned by this instance. */
    fun cancelTranslation()

    /** Cancel and release the provider's coroutine scope. */
    fun release()
}
