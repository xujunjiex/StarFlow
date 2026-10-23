package com.moe.starflow.mangaimport.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 章节及章内页面均串行；等待章不解码图片，取消后等当前任务实际退出再交接。 */
class SrChapterQueue<R>(
    private val scope: CoroutineScope,
    private val prepare: suspend (request: R) -> Boolean = { true },
    private val processPage: suspend (page: Int, request: R) -> Unit,
    private val onFailure: (chapterIndex: Int, page: Int?, error: Exception) -> Unit = { _, _, _ -> },
    private val onChanged: (List<Progress>) -> Unit = {},
) {
    data class Progress(
        val chapterIndex: Int,
        val done: Int,
        val total: Int,
        val page: Int = 0,
        val queued: Boolean = true,
        val stopping: Boolean = false,
        val currentPage: Int? = null,
    )

    private class Runtime<R>(val pages: List<Int>, val request: R, var progress: Progress) {
        var task: Job? = null
    }

    private val lock = Any()
    private val chapters = linkedMapOf<Int, Runtime<R>>()
    private var current: Runtime<R>? = null
    private val _jobs = MutableStateFlow<List<Progress>>(emptyList())
    val jobs = _jobs.asStateFlow()

    fun submit(chapterIndex: Int, pages: List<Int>, request: R): Boolean {
        val targets = pages.filter { it >= 0 }.distinct()
        if (targets.isEmpty()) return false
        val next = synchronized(lock) {
            if (!scope.isActive || chapterIndex in chapters) return false
            chapters[chapterIndex] = Runtime(targets, request, Progress(chapterIndex, 0, targets.size))
            startNextLocked().also { publishLocked() }
        }
        next?.start()
        return true
    }

    /** 指定章仅取消该章；null 清掉全部等待章并停止当前章。 */
    fun cancel(chapterIndex: Int? = null): Job? {
        val task = synchronized(lock) {
            if (chapterIndex == null) {
                chapters.entries.removeAll { it.value !== current }
            } else {
                val target = chapters[chapterIndex] ?: return null
                if (target !== current) {
                    chapters.remove(chapterIndex)
                    publishLocked()
                    return null
                }
            }
            current?.let { it.progress = it.progress.copy(stopping = true) }
            publishLocked()
            current?.task
        }
        task?.cancel()
        return task
    }

    suspend fun cancelAllAndJoin() {
        cancel()?.join()
    }

    /** 必须持锁；先登记任务再启动，避免提交与取消之间出现无主任务。 */
    private fun startNextLocked(): Job? {
        if (current != null || !scope.isActive) return null
        val runtime = chapters.values.firstOrNull() ?: return null
        current = runtime
        runtime.progress = runtime.progress.copy(queued = false)
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!prepare(runtime.request)) return@launch
                for (page in runtime.pages) {
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        runtime.progress = runtime.progress.copy(currentPage = page)
                        publishLocked()
                    }
                    try {
                        processPage(page, runtime.request)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onFailure(runtime.progress.chapterIndex, page, e)
                    }
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        runtime.progress = runtime.progress.copy(done = runtime.progress.done + 1, page = page + 1, currentPage = null)
                        publishLocked()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure(runtime.progress.chapterIndex, null, e)
            }
        }
        runtime.task = task
        // Job 完成包含 finally 与原生取消收尾，不能在发出 cancel 时就放行下一章。
        task.invokeOnCompletion {
            val next = synchronized(lock) {
                if (current !== runtime) return@invokeOnCompletion
                chapters.remove(runtime.progress.chapterIndex)
                current = null
                if (!scope.isActive) chapters.clear()
                startNextLocked().also { publishLocked() }
            }
            next?.start()
        }
        return task
    }

    private fun publishLocked() {
        val snapshot = chapters.values.map { it.progress }
        _jobs.value = snapshot
        onChanged(snapshot)
    }
}
