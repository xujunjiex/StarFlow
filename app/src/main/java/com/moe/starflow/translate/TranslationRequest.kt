package com.moe.starflow.translate

/** Cancellation belongs to one request, including when the translator is a shared model. */
fun interface TranslationRequest {
    fun cancel()
}
