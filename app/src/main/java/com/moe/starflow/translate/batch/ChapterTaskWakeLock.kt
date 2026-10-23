package com.moe.starflow.translate.batch

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.moe.starflow.utils.LogCollector

/** 只保持 CPU 运行，屏幕可以关闭；租约超时兜底，服务定期续租并随任务状态释放。 */
internal class ChapterTaskWakeLock(context: Context) {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StarFlow:ChapterTasks")
        .apply { setReferenceCounted(false) }
    private var renewAt = 0L

    fun update(jobs: List<ActiveChapterJob>) {
        if (jobs.none { it.needsCpu }) {
            release()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lock.isHeld && now < renewAt) return
        try {
            if (lock.isHeld) lock.release()
            lock.acquire(10 * 60_000L)
            renewAt = now + 5 * 60_000L
        } catch (e: Exception) {
            LogCollector.e("ChapterTasks", "保持章节任务 CPU 运行失败", e)
        }
    }

    fun release() {
        if (lock.isHeld) lock.release()
        renewAt = 0L
    }
}
