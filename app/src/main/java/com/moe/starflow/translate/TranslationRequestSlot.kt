package com.moe.starflow.translate

/** Handles cancellation/completion even when a callback fires before startTranslation returns. */
class TranslationRequestSlot : TranslationRequest {
    private var handle: TranslationRequest? = null
    private var cancelled = false
    private var finished = false
    @Synchronized fun attach(request: TranslationRequest) {
        if (cancelled) request.cancel() else if (!finished) handle = request
    }
    @Synchronized fun finish() { finished = true; handle = null }
    @Synchronized override fun cancel() {
        if (finished) return
        cancelled = true
        handle?.cancel()
        handle = null
    }
}
