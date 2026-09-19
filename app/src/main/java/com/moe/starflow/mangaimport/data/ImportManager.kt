package com.moe.starflow.mangaimport.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.moe.starflow.R
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
 * 导入编排器（进程级单例）。
 *
 * 为什么不让 Fragment 自己导入：`viewLifecycleOwner.lifecycleScope` 里跑的导入会在**旋转/切页**
 * 时随 View 一起被取消 —— 文件复制了一半、条目还没入库，留下孤儿目录。这里用**自己的进程级作用域**
 * 跑，View 只做观察者，导入过程与界面生命周期解耦：
 *
 * - [tasks]：进行中的导入（纯内存态），书架用它渲染**占位卡片**（图片位置显示进度）
 * - [events]：需要弹窗告知的结果（夹里没图 / 导入失败）；攒着等 UI 取走（[drainEvents]），
 *   旋转期间产生的事件不丢、也不会重复弹
 * - [cancel]：取消某次导入（删半成品目录，不产生条目）
 *
 * id 由 [reserveId] 预留（清单最大 id + 进行中预留集合去重），并发导入不撞 id；占位与最终条目
 * id 相同 → 书架 DiffUtil 原地替换，不闪。
 */
object ImportManager {

    private const val TAG = "ImportManager"

    /**
     * ⚠️ 必须挂 [CoroutineExceptionHandler]：`launch` 里未捕获的异常会走线程默认处理器 →
     * **整个应用崩**。导入路径上有大量 SAF/磁盘调用（权限被撤销、存储卸载、provider 抛
     * SecurityException…），漏一个 catch 就是崩溃，而有 handler 至少留下日志。
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            LogCollector.e(TAG, "导入协程未捕获异常（已兜住，未崩溃）", e)
        }
    )

    /** 进行中的任务 id → Job（取消用）。 */
    private val jobs = mutableMapOf<Long, Job>()

    /** 已预留但尚未入库的 id（并发导入防撞）。 */
    private val reservedIds = mutableSetOf<Long>()

    private val _tasks = MutableStateFlow<List<ImportTask>>(emptyList())
    val tasks: StateFlow<List<ImportTask>> = _tasks.asStateFlow()

    private val _events = MutableStateFlow<List<ImportEvent>>(emptyList())
    val events: StateFlow<List<ImportEvent>> = _events.asStateFlow()

    // ===== 对外入口 =====

    /** 导入若干压缩包（可多选）。每个文件一个独立任务：各自占位卡片、各自进度、各自可取消。 */
    fun importArchives(context: Context, uris: List<Uri>) {
        val app = context.applicationContext
        uris.forEach { uri -> launchArchiveTask(app, uri) }
    }

    /** 导入一个图片文件夹（整个夹 = 一部）。 */
    fun importDirectory(context: Context, treeUri: Uri) {
        val app = context.applicationContext
        scope.launch {
            val id = reserveId(app)
            // ⚠️ 名字查询走 ContentResolver（SAF provider）：权限被撤销 / URI 失效 / 存储卸载都会抛。
            // 必须在 try 之外也不能让它冒泡（冒泡 = 未捕获异常 = 崩溃）+ 连提示都没有，
            // 所以这里单独兜住并退化成默认标题，后续导入失败会走正常的 Failed 弹窗。
            val title = runCatching { DocumentFile.fromTreeUri(app, treeUri)?.name }.getOrNull()
                ?: app.getString(R.string.default_manga_title, id.toString())
            registerTask(id, title, isArchive = false, phase = ImportPhase.SCANNING, job = coroutineContext[Job])
            try {
                val manga = MangaImporter.importDirectory(app, treeUri, id) { p -> updateProgress(id, p) }
                ensureActive()
                ImportedMangaStore.add(app, manga)
                LogCollector.i(TAG, "导入目录完成: ${manga.title} (${manga.pageCount} 页)")
                if (manga.pageCount == 0) emit(ImportEvent.NoImages(manga.title, isArchive = false))
            } catch (e: CancellationException) {
                cleanup(app, id)
                LogCollector.i(TAG, "导入目录已取消: $title")
                throw e
            } catch (e: Exception) {
                cleanup(app, id)
                LogCollector.e(TAG, "导入目录失败: $treeUri", e)
                emit(ImportEvent.Failed(title, classifyDirectory(e)))
            } finally {
                unregisterTask(id)
                releaseId(id)
            }
        }
    }

    /**
     * 取消一次导入。返回 false = 该任务已不在进行中（已完成/已失败/已被取消），
     * 此时调用方应提示「已完成」而不是假装取消成功。
     */
    @Synchronized
    fun cancel(id: Long): Boolean {
        val job = jobs[id] ?: return false
        job.cancel()
        return true
    }

    /** 取走并清空待弹窗事件（没被取走的事件会留着等下次）。 */
    @Synchronized
    fun drainEvents(): List<ImportEvent> {
        val pending = _events.value
        if (pending.isNotEmpty()) _events.value = emptyList()
        return pending
    }

    // ===== 内部：任务生命周期 =====

    private fun launchArchiveTask(app: Context, uri: Uri) {
        scope.launch {
            val id = reserveId(app)
            // 同 importDirectory：名字查询在 try 外，单独兜住，别让 SAF 异常崩掉应用
            val name = runCatching { DocumentFile.fromSingleUri(app, uri)?.name }.getOrNull()
                ?: uri.lastPathSegment.orEmpty()
            val title = name.substringBeforeLast('.', name).ifEmpty {
                app.getString(R.string.default_manga_title, id.toString())
            }
            registerTask(id, title, isArchive = true, phase = ImportPhase.COPYING, job = coroutineContext[Job])
            try {
                val manga = MangaImporter.importArchive(app, uri, id) { p -> updateProgress(id, p) }
                ensureActive()
                ImportedMangaStore.add(app, manga)
                LogCollector.i(TAG, "导入压缩包完成: ${manga.title} (${manga.pageCount} 页)")
                if (manga.pageCount == 0) emit(ImportEvent.NoImages(manga.title, isArchive = true))
            } catch (e: CancellationException) {
                cleanup(app, id)
                LogCollector.i(TAG, "导入压缩包已取消: $title")
                throw e
            } catch (e: Exception) {
                cleanup(app, id)
                LogCollector.e(TAG, "导入压缩包失败: $uri", e)
                emit(ImportEvent.Failed(title, classifyArchive(e)))
            } finally {
                unregisterTask(id)
                releaseId(id)
            }
        }
    }

    /** Job 先登记、任务后登记：两者都在同一协程里、无挂起点，UI 不可能挤进中间。 */
    private fun registerTask(id: Long, title: String, isArchive: Boolean, phase: ImportPhase, job: Job?) {
        synchronized(this) { if (job != null) jobs[id] = job }
        val task = ImportTask(
            id = id,
            title = title,
            isArchive = isArchive,
            addedAt = System.currentTimeMillis(),
            progress = ImportProgress(phase)
        )
        _tasks.update { it + task }
    }

    private fun updateProgress(id: Long, progress: ImportProgress) {
        _tasks.update { list ->
            val idx = list.indexOfFirst { it.id == id }
            if (idx < 0) list
            else list.toMutableList().also { it[idx] = it[idx].copy(progress = progress) }
        }
    }

    /** ⚠️ 必须先摘 Job/占位再 [releaseId]：否则同 id 的新任务可能被这次 remove 误删。 */
    private fun unregisterTask(id: Long) {
        _tasks.update { list -> list.filterNot { it.id == id } }
        synchronized(this) { jobs.remove(id) }
    }

    @Synchronized
    private fun reserveId(context: Context): Long {
        var id = MangaImporter.nextId(context)
        while (id in reservedIds) id++
        reservedIds += id
        return id
    }

    @Synchronized
    private fun releaseId(id: Long) {
        reservedIds -= id
    }

    private fun emit(event: ImportEvent) {
        _events.update { it + event }
    }

    /** 删掉本次导入的半成品（取消/失败时；成功路径不会调）。同时清掉可能已生成的封面缩略图。 */
    private fun cleanup(context: Context, id: Long) {
        try {
            File(StorageDirStore.root(context), id.toString()).deleteRecursively()
            // ⚠️ 封面也要清：目录导入是「复制 → 生成封面 → 返回」，取消可能落在生成封面之后、
            // 入库之前 → 只删目录会留下孤儿图（covers/<id>_<ts>.jpg，同 id 只会有一张）
            StorageDirStore.coversDir(context).listFiles()
                ?.filter { it.name.startsWith("${id}_") }
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            LogCollector.w(TAG, "清理导入半成品失败 id=$id", e)
        }
    }

    private fun classifyArchive(e: Throwable): ImportFailureReason = when (e) {
        // ZipFile 对非 zip（rar/7z/改名的文件）与损坏包抛 ZipException；空包也走这里
        is ZipException, is IllegalArgumentException -> ImportFailureReason.NOT_ARCHIVE
        is java.io.FileNotFoundException, is SecurityException -> ImportFailureReason.UNREADABLE
        else -> ImportFailureReason.UNKNOWN
    }

    private fun classifyDirectory(e: Throwable): ImportFailureReason = when (e) {
        is SecurityException, is java.io.FileNotFoundException -> ImportFailureReason.UNREADABLE
        is IllegalStateException -> ImportFailureReason.DIRECTORY
        else -> ImportFailureReason.UNKNOWN
    }
}
