package com.moe.starflow.utils

import java.util.concurrent.atomic.AtomicInteger

/** Tracks screenshot/reader OCR or translation work for execution-mode switching guards. */
object TranslationBusyRegistry {
    private val active = AtomicInteger(0)

    val isBusy: Boolean
        get() = active.get() > 0

    fun enter() { active.incrementAndGet() }

    fun exit() {
        active.updateAndGet { (it - 1).coerceAtLeast(0) }
    }
}
