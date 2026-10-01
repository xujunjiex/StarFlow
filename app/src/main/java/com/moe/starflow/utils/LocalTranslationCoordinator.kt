package com.moe.starflow.utils

import kotlinx.coroutines.sync.Mutex

/** Serializes local NLLB/LlamaCpp translation across reader controllers. */
object LocalTranslationCoordinator {
    val mutex = Mutex()
}
