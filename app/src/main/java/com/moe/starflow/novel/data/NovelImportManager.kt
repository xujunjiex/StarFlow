package com.moe.starflow.novel.data

import android.content.Context
import android.net.Uri
import com.moe.starflow.mangaimport.data.ImportFormat
import com.moe.starflow.mangaimport.data.ImportPhase
import com.moe.starflow.mangaimport.data.ImportProgress
import com.moe.starflow.mangaimport.data.ImportTask
import com.moe.starflow.novel.parser.NovelReadLimits
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.SafDocumentMetadata
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
    private val deletingIds = mutableSetOf<Long>()
    private val deletingSourcePaths = mutableSetOf<String>()

    private val _tasks = MutableStateFlow<List<ImportTask>>(emptyList())
    val tasks: StateFlow<List<ImportTask>> = _tasks.asStateFlow()

    private val _events = MutableStateFlow<List<NovelImportEvent>>(emptyList())
    val events: StateFlow<List<NovelImportEvent>> = _events.asStateFlow()

    // ===== 对外入口 =====

    /** 导入若干文件（可多选）。每个文件一个独立任务：各自占位卡片、各自进度、各自可取消。 */
    fun importFiles(context: Context, uris: List<Uri>) {
        val app = com.moe.starflow.library.LibrarySessions.pin(context)
        uris.forEach { uri -> launchFileTask(app, uri) }
    }

    /**
     * 导入一个文件夹：**整个夹 = 一部小说**，夹内每个 txt 是一章（与漫画的「整个夹 = 一部」同一套心智）。
     */
    fun importDirectory(context: Context, treeUri: Uri) {
        val app = com.moe.starflow.library.LibrarySessions.pin(context)
        com.moe.starflow.library.LibrarySessions.launch(app, scope) {
            try { com.moe.starflow.library.LibrarySessions.prepare(app) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { emit(NovelImportEvent.Failed("书库存储", NovelImportFailureReason.UNKNOWN, failureDetail(e))); return@launch }
            val id = reserveId(app)
            // 占位标题只用 URI 本地回退；真实名称在 NovelImporter 中查询一次并记录诊断。
            val title = SafDocumentMetadata.fallbackName(treeUri) ?: "folder_$id"
            registerTask(id, title, ImportPhase.SCANNING, coroutineContext[Job])
            try {
                com.moe.starflow.library.LibrarySessions.prepare(app)
                com.moe.starflow.library.LibrarySessions.current(app).beginImport("novel", id)
                val novel = NovelImporter.importDirectory(app, treeUri, id) { p -> updateProgress(id, p) }
                ensureActive()
                NovelStore.add(app, novel)
                val savedSession = com.moe.starflow.library.LibrarySessions.current(app)
                savedSession.flush()
                savedSession.database.catalog().book("novel", id)?.let { savedSession.releaseBookCopies(it.uuid, it.source) }
                LogCollector.i(TAG, "文件夹导入完成: ${novel.title} (${novel.chapterCount} 章)")
                if (novel.chapterCount == 0) emit(NovelImportEvent.NoChapters(novel.title))
            } catch (e: CancellationException) {
                cleanup(app, id)
                throw e
            } catch (e: Exception) {
                cleanup(app, id)
                LogCollector.e(TAG, "文件夹导入失败: $treeUri", e)
                emit(NovelImportEvent.Failed(title, classify(e), failureDetail(e)))
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
        com.moe.starflow.library.LibrarySessions.launch(app, scope) {
            try { com.moe.starflow.library.LibrarySessions.prepare(app) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { emit(NovelImportEvent.Failed("书库存储", NovelImportFailureReason.UNKNOWN, failureDetail(e))); return@launch }
            val id = reserveId(app)
            // 占位标题使用 URI 本地回退，避免重复触发一次会被吞掉的 provider 元数据查询。
            val name = SafDocumentMetadata.fallbackName(uri).orEmpty()
            val title = name.substringBeforeLast('.', name).ifBlank { "book_$id" }
            registerTask(id, title, ImportPhase.COPYING, coroutineContext[Job])
            try {
                com.moe.starflow.library.LibrarySessions.prepare(app)
                com.moe.starflow.library.LibrarySessions.current(app).beginImport("novel", id)
                val novel = NovelImporter.importFile(app, uri, id) { p -> updateProgress(id, p) }
                ensureActive()
                NovelStore.add(app, novel)
                val savedSession = com.moe.starflow.library.LibrarySessions.current(app)
                savedSession.flush()
                savedSession.database.catalog().book("novel", id)?.let { savedSession.releaseBookCopies(it.uuid, it.source) }
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
                emit(NovelImportEvent.Failed(title, classify(e), failureDetail(e)))
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
    internal fun reserveScanId(context: Context, preferredId: Long?): Long? {
        if (preferredId != null && preferredId in reservedIds) return null
        val storedIds = NovelStore.load(context).mapTo(mutableSetOf()) { it.id }
        val id = preferredId?.takeIf { it > 0 && it !in storedIds && it !in reservedIds } ?: run {
            var candidate = NovelImporter.nextId(context)
            while (candidate in reservedIds || candidate in storedIds) candidate++
            candidate
        }
        reservedIds += id
        return id
    }

    @Synchronized
    internal fun releaseScanIds(ids: Collection<Long>) {
        ids.forEach { id ->
            if (id !in deletingIds) reservedIds -= id
        }
    }

    @Synchronized
    internal fun activeIdsForScanner(): Set<Long> =
        _tasks.value.mapTo(reservedIds.toMutableSet()) { it.id }

    @Synchronized
    internal fun isSourceBeingDeleted(file: File): Boolean {
        val path = runCatching { file.canonicalPath }.getOrNull() ?: return false
        return path in deletingSourcePaths
    }

    @Synchronized
    private fun markDeleting(novels: List<ImportedNovel>) {
        novels.forEach { novel ->
            reservedIds += novel.id
            deletingIds += novel.id
            runCatching { File(novel.localRoot).canonicalPath }
                .getOrNull()
                ?.let(deletingSourcePaths::add)
        }
    }

    @Synchronized
    private fun clearDeleting(novels: List<ImportedNovel>) {
        novels.forEach { novel ->
            deletingIds -= novel.id
            if (_tasks.value.none { it.id == novel.id }) reservedIds -= novel.id
            runCatching { File(novel.localRoot).canonicalPath }
                .getOrNull()
                ?.let(deletingSourcePaths::remove)
        }
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
        if (com.moe.starflow.library.LibrarySessions.current(context).database.catalog().book("novel", id) != null) return
        NovelImporter.deleteBookFiles(context, id)
        if (!NovelStorageDir.bookDir(context, id).exists()) com.moe.starflow.library.LibrarySessions.current(context).abandonImport("novel", id)
    }

    /**
     * 异常 → 失败原因。**只按 [NovelImporter] 里那几个常量比对**，不要在这里再写字符串字面量
     * （两处各写一份，改一处就静默地全都落进 UNKNOWN）。
     *
     * ⚠️ 之所以还是按 message 比：抛出方是解析器（`EpubParser` / `FolderNovelParser`），
     * 它们直接抛字面量 `"NO_TEXT_CHAPTER"` / `"ENCRYPTED"`（与常量取值一致，见这两个文件）。
     */
    internal fun classify(e: Throwable): NovelImportFailureReason = when {
        e is NovelImportIoException && e.kind == NovelImportIoException.Kind.SOURCE_OPEN ->
            NovelImportFailureReason.SOURCE_OPEN_FAILED
        e is NovelImportIoException && e.kind == NovelImportIoException.Kind.SOURCE_READ ->
            NovelImportFailureReason.SOURCE_READ_FAILED
        e is NovelImportIoException && e.kind == NovelImportIoException.Kind.DESTINATION_WRITE ->
            NovelImportFailureReason.DESTINATION_WRITE_FAILED
        e is NovelImportIoException && e.kind == NovelImportIoException.Kind.DIRECTORY_SCAN ->
            NovelImportFailureReason.DIRECTORY_SCAN_FAILED
        e is NovelImportIoException && e.kind == NovelImportIoException.Kind.DIRECTORY_ACCESS ->
            NovelImportFailureReason.DIRECTORY_ACCESS_DENIED
        e is IllegalStateException && e.message == NovelImporter.ERROR_NO_TEXT_CHAPTER ->
            NovelImportFailureReason.NO_TEXT_CHAPTER
        e is IllegalStateException && e.message == NovelImporter.ERROR_ENCRYPTED ->
            NovelImportFailureReason.ENCRYPTED
        // 空文件：能复制成功但字节数为 0（由 NovelImporter 在格式判定前判出，见 importOne）
        e is IllegalStateException && e.message == NovelImporter.ERROR_EMPTY ->
            NovelImportFailureReason.EMPTY
        // ⚠️ 必须排在 ZipException **之前**：TooLargeException 是它的子类，顺序反了就永远匹配不到
        e is NovelReadLimits.TooLargeException -> NovelImportFailureReason.TOO_LARGE
        e is ZipException -> NovelImportFailureReason.NOT_ARCHIVE
        e is java.io.FileNotFoundException || e is SecurityException -> NovelImportFailureReason.UNREADABLE
        else -> NovelImportFailureReason.UNKNOWN
    }

    /** Keep the operation and root provider/filesystem error visible instead of collapsing it to "unreadable". */
    private fun failureDetail(error: Throwable): String? {
        val chain = mutableListOf<String>()
        val seen = mutableSetOf<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current) && chain.size < 3) {
            val message = current.localizedMessage?.replace(Regex("\\s+"), " ")?.trim()
            chain += listOfNotNull(current.javaClass.simpleName.takeIf { it.isNotBlank() }, message)
                .joinToString(": ")
            current = current.cause
        }
        return chain.filter { it.isNotBlank() }.joinToString(" ← ").take(500).ifBlank { null }
    }

    // ===== 书架删除（进程级，不随 View 销毁被取消）=====

    /**
     * 删除若干部小说：**清单同步落地**（书架立刻不再显示），本地文件与译文记录交给进程级作用域清。
     *
     * ⚠️ 为什么删除不能跑在 `viewLifecycleOwner.lifecycleScope` 里（旧实现的问题）：
     * 确认删除后立刻切 tab / 旋转 / 退出书架，协程会随 View 一起被取消 —— 而清单是**同步**删的
     * （书架上看不出来），DB 里那些段的原文 / 译文 / 失败码就永久留下，成了孤儿行。
     * 与漫画侧 `mangaimport.data.ShelfCleanup` 是同一套做法（进程级作用域 + 兜异常）。
     *
     * ⚠️ 译文必须按 (id, 指纹) **成对**删：书籍 id 会被复用（`nextId` = 清单最大 id + 1），
     * 而删除是异步的 —— 只按 id 删的话，用户完全可能在它落地前就导入了一本复用同 id 的新书
     * 并翻了几章，把那本**新书**的译文删掉。
     */
    fun deleteNovels(context: Context, novels: List<ImportedNovel>) {
        if (novels.isEmpty()) return
        val pinned = com.moe.starflow.library.LibrarySessions.forLocation(context, novels.first().libraryLocationId).context()
        com.moe.starflow.library.LibraryDeletion.start(pinned, "novel", novels.map { it.id }, novels.associate { it.id to it.bookUuid })
    }

}
