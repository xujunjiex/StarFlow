package com.moe.starflow.translate.batch

/** 一次服务会话的队列账本；保留已结束章节，使总进度不会随着上一章出队而归零。 */
internal class ChapterNotificationQueue {
    data class Snapshot(val current: ActiveChapterJob?, val count: Int, val finished: Int, val progress: Int) {
        val maximum: Int get() = count * 1_000
        val multiple: Boolean get() = count > 1
    }

    private class Entry(var job: ActiveChapterJob, var finished: Boolean = false)
    private val entries = mutableListOf<Entry>()
    private val activeEntries = mutableMapOf<Int, Entry>()

    fun update(jobs: List<ActiveChapterJob>): Snapshot {
        val active = jobs.filter { it.isActive }
        val live = active.map { it.notificationId }.toSet()
        activeEntries.keys.filter { it !in live }.forEach { key ->
            activeEntries.remove(key)?.finished = true
        }
        active.forEach { job ->
            val entry = activeEntries.getOrPut(job.notificationId) { Entry(job).also { entries += it } }
            entry.job = job
        }
        // 原生清理尚未结束的章优先，其次才是仍在等待调度的章。
        val current = active.firstOrNull { it.hasInFlight && it.state != ChapterJobState.QUEUED }
            ?: active.firstOrNull { it.state == ChapterJobState.RUNNING }
            ?: active.firstOrNull { it.hasInFlight }
            ?: active.firstOrNull { it.state == ChapterJobState.QUEUED }
            ?: active.firstOrNull()
        val progress = entries.sumOf { entry ->
            if (entry.finished) 1_000 else {
                val job = entry.job
                if (job.total <= 0) 0 else (job.done.toLong() * 1_000 / job.total).coerceIn(0, 1_000).toInt()
            }
        }
        return Snapshot(current, entries.size, entries.count { it.finished }, progress)
    }
}
