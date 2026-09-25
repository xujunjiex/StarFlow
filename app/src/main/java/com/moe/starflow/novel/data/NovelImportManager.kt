package com.moe.starflow.novel.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.moe.starflow.mangaimport.data.ImportFormat
import com.moe.starflow.mangaimport.data.ImportPhase
import com.moe.starflow.mangaimport.data.ImportProgress
import com.moe.starflow.mangaimport.data.ImportTask
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.zip.ZipException
import kotlin.coroutines.coroutineContext

/**
 * 小说导入编排器（进程级单例）。与 `mangaimport.data.ImportManager` 同构，但用小说自己的
 * 存储、事件与 id 空间。
 *
 * ⚠️ 为什么不让 Fragment 自己导入：`viewLifecycleOwner.lifecycleScope` 里跑的导入会在
 * **旋转/切页**时随 View 一起被取消 —— 文件复制了一半、条目还没入库，留下孤儿目录。
 * 这里用**自己的进程级作用域**跑，View 只做观察者。
 *
 * - [tasks]：进行中的导入（纯内存态），书架用它渲染**占位卡片**
 * - [events]：需要弹窗告知的结果；攒着等 UI 取走（[drainEvents]），旋转期间产生的不丢
 * - [cancel]：取消某次导入（删半成品目录，不产生条目）
 */
object NovelImportManager {

    private const val TAG = "NovelImportManager"

    /**
     * ⚠️ 必须挂 [CoroutineExceptionHandler]：`launch` 里未捕获的异常会走线程默认处理器 →
     * **整个应用崩**。这条路径上全是 SAF / 磁盘调用（权限被撤销、存储卸载、provider 抛
     * SecurityException），漏一个 catch 就是崩溃，而有 handler 至少留下日志。
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            LogCollector.e(TAG, "小说导入协程未捕获异常（已兜住，未崩溃）", e)
        }
    )

    private val jobs = mutableMapOf<Long, Job>()
    private val reservedIds = mutableSetOf<Long>()

    private val _tasks = MutableStateFlow<List<ImportTask>>(emptyList())
    val tasks: StateFlow<List<ImportTask>> = _tasks.asStateFlow()

    private val _events = MutableStateFlow<List<NovelImportEvent>>(emptyList())
    val events: StateFlow<List<NovelImportEvent>> = _events.asStateFlow()

    // ===== 对外入口 =====

    /** 导入若干文件（可多选）。每个文件一个独立任务：各自占位卡片、各自进度、各自可取消。 */
    fun importFiles(context: Context, uris: List<Uri>) {
        val app = context.applicationContext
        uris.forEach { uri -> launchFileTask(app, uri) }
    }

    /**
     * 导入一个文件夹：**整个夹 = 一部小说**，夹内每个 txt 是一章（与漫画的「整个夹 = 一部」同一套心智）。
     */
    fun importDirectory(context: Context, treeUri: Uri) {
        val app = context.applicationContext
        scope.launch {
            val id = reserveId(app)
            // ⚠️ 名字查询走 ContentResolver（SAF provider）：权限被撤销 / URI 失效 / 存储卸载
            // 都会抛。必须在 try 外也兜住（冒泡 = 未捕获异常 = 崩溃），退化成默认标题。
            val title = runCatching { DocumentFile.fromTreeUri(app, treeUri)?.name }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "folder_$id"
            registerTask(id, title, ImportPhase.SCANNING, coroutineContext[Job])
            try {
                val novel = NovelImporter.importDirectory(app, treeUri, id) { p -> updateProgress(id, p) }
                ensureActive()
                NovelStore.add(app, novel)
                LogCollector.i(TAG, "文件夹导入完成: ${novel.title} (${novel.chapterCount} 章)")
                if (novel.chapterCount == 0) emit(NovelImportEvent.NoChapters(novel.title))
            } catch (e: CancellationException) {
                cleanup(app, id)
                throw e
            } catch (e: Exception) {
                cleanup(app, id)
                LogCollector.e(TAG, "文件夹导入失败: $treeUri", e)
                emit(NovelImportEvent.Failed(title, classify(e)))
            } finally {
                unregisterTask(id)
                releaseId(id)
            }
        }
    }

    /**
     * 取消一次导入。返回 false = 该任务已不在进行中（已完成/已失败/已被取消），
     * 此时调用方应提示「已经导入完成了」，而不是假装取消成功。
     */
    @Synchronized
    fun cancel(id: Long): Boolean {
        val job = jobs[id] ?: return false
        job.cancel()
        return true
    }

    /** 取走并清空待弹窗事件（没被取走的事件会留着等下次）。 */
    @Synchronized
    fun drainEvents(): List<NovelImportEvent> {
        val pending = _events.value
        if (pending.isNotEmpty()) _events.value = emptyList()
        return pending
    }

    // ===== 内部 =====

    private fun launchFileTask(app: Context, uri: Uri) {
        scope.launch {
            val id = reserveId(app)
            val name = runCatching { DocumentFile.fromSingleUri(app, uri)?.name }.getOrNull()
                ?: uri.lastPathSegment.orEmpty()
            val title = name.substringBeforeLast('.', name).ifBlank { "book_$id" }
            registerTask(id, title, ImportPhase.COPYING, coroutineContext[Job])
            try {
                val novel = NovelImporter.importFile(app, uri, id) { p -> updateProgress(id, p) }
                ensureActive()
                NovelStore.add(app, novel)
                LogCollector.i(TAG, "导入完成: ${novel.title} (${novel.chapterCount} 章)")
                if (novel.chapterCount == 0) emit(NovelImportEvent.NoChapters(novel.title))
            } catch (e: CancellationException) {
                cleanup(app, id)
                throw e
            } catch (e: NovelWrongFormatException) {
                cleanup(app, id)
                emit(NovelImportEvent.WrongFormat(title, e.isManga))
            } catch (e: Exception) {
                cleanup(app, id)
                LogCollector.e(TAG, "导入失败: $uri", e)
                emit(NovelImportEvent.Failed(title, classify(e)))
            } finally {
                unregisterTask(id)
                releaseId(id)
            }
        }
    }

    /** Job 先登记、任务后登记：两者在同一协程里、无挂起点，UI 不可能挤进中间。 */
    private fun registerTask(id: Long, title: String, phase: ImportPhase, job: Job?) {
        synchronized(this) { if (job != null) jobs[id] = job }
        _tasks.update {
            it + ImportTask(
                id = id,
                title = title,
                isArchive = false,
                addedAt = System.currentTimeMillis(),
                progress = ImportProgress(phase),
                format = ImportFormat.NOVEL,
            )
        }
    }

    private fun updateProgress(id: Long, progress: ImportProgress) {
        _tasks.update { list ->
            val idx = list.indexOfFirst { it.id == id }
            if (idx < 0) list else list.toMutableList().also { it[idx] = it[idx].copy(progress = progress) }
        }
    }

    /** ⚠️ 必须先摘 Job/占位再 [releaseId]：否则同 id 的新任务可能被这次 remove 误删。 */
    private fun unregisterTask(id: Long) {
        _tasks.update { list -> list.filterNot { it.id == id } }
        synchronized(this) { jobs.remove(id) }
    }

    /** 预留一个不与清单、也不与进行中任务冲突的 id。 */
    @Synchronized
    private fun reserveId(context: Context): Long {
        var id = NovelImporter.nextId(context)
        while (id in reservedIds) id++
        reservedIds += id
        return id
    }

    @Synchronized
    private fun releaseId(id: Long) {
        reservedIds -= id
    }

    private fun emit(event: NovelImportEvent) {
        _events.update { it + event }
    }

    /** 删掉本次导入的半成品目录与封面（取消/失败时；成功路径不会调）。 */
    private fun cleanup(context: Context, id: Long) {
        NovelImporter.deleteBookFiles(context, id)
    }

    private fun classify(e: Throwable): NovelImportFailureReason = when {
        e is IllegalStateException && e.message == NovelImporter.ERROR_NO_TEXT_CHAPTER ->
            NovelImportFailureReason.NO_TEXT_CHAPTER
        e is IllegalStateException && e.message == NovelImporter.ERROR_ENCRYPTED ->
            NovelImportFailureReason.ENCRYPTED
        // 空文件：能复制成功但解析出空
        e is IllegalStateException && e.message == "EMPTY" -> NovelImportFailureReason.EMPTY
        e is ZipException -> NovelImportFailureReason.NOT_ARCHIVE
        e is java.io.FileNotFoundException || e is SecurityException -> NovelImportFailureReason.UNREADABLE
        else -> NovelImportFailureReason.UNKNOWN
    }
}
