package com.moe.starflow.library

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore

/** Bound original-file copies and decoders across both shelves, including directory imports. */
internal object LibraryWork {
    val imports = Semaphore(2)

    // A scan must not recreate a directory or catalogue a stale candidate during deletion.
    val sourceMutations = Mutex()
}
