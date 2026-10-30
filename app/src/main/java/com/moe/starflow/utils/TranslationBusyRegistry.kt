package com.moe.starflow.utils

import java.util.concurrent.atomic.AtomicBoolean
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

    /**
     * 需要跨取消/回调两条路径释放的调用方用 Token：重复 release 只生效一次，
     * 避免手工 enter/exit 在“协程取消 + 迟到回调”竞态下把计数减穿。
     */
    fun begin(): Token {
        enter()
        return Token()
    }

    class Token internal constructor() {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) exit()
        }
    }
}
