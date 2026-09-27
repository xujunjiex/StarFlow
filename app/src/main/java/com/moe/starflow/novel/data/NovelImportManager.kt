package com.moe.starflow.novel.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.moe.starflow.data.TranslationHistoryDatabase
import com.moe.starflow.mangaimport.data.ImportFormat
import com.moe.starflow.mangaimport.data.ImportPhase
import com.moe.starflow.mangaimport.data.ImportProgress
import com.moe.starflow.mangaimport.data.ImportTask
import com.moe.starflow.novel.parser.NovelReadLimits
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

    /**
     * 异常 → 失败原因。**只按 [NovelImporter] 里那几个常量比对**，不要在这里再写字符串字面量
     * （两处各写一份，改一处就静默地全都落进 UNKNOWN）。
     *
     * ⚠️ 之所以还是按 message 比：抛出方是解析器（`EpubParser` / `FolderNovelParser`），
     * 它们直接抛字面量 `"NO_TEXT_CHAPTER"` / `"ENCRYPTED"`（与常量取值一致，见这两个文件）。
     */
    internal fun classify(e: Throwable): NovelImportFailureReason = when {
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
        val app = context.applicationContext
        // 清单先同步删：调用方紧接着的 refresh 必然看到「已删除」，不会闪回一张已经删掉的卡片
        novels.forEach { NovelStore.remove(app, it.id) }
        scope.launch {
            try {
                val dao = TranslationHistoryDatabase.getInstance(app).novelParagraphTranslationDao()
                novels.forEach { n ->
                    // ⚠️ 逐部兜异常：一部失败（外置存储被卸载、provider 抛 SecurityException）
                    // 不该让后面的书连**文件**都不删。真删不掉的译文行还有 purgeOrphanTranslations 兜底。
                    runCatching { NovelImporter.deleteBookFiles(app, n.id) }
                        .onFailure { LogCollector.w(TAG, "删除书籍文件失败 id=${n.id}", it) }
                    runCatching { dao.deleteForNovelScoped(n.id, n.translationKey) }
                        .onFailure { LogCollector.w(TAG, "删除译文记录失败 id=${n.id}", it) }
                }
                LogCollector.i(TAG, "已删除 ${novels.size} 部小说: ${novels.map { it.id }}")
            } catch (e: Exception) {
                // 连库都没拿到（getInstance 失败）：清单已经同步删了，孤儿行等下次进书架清理
                LogCollector.e(TAG, "删除译文记录失败: ${novels.map { it.id }}", e)
            }
        }
    }

    /**
     * 清理**孤儿译文行**：库里出现过、但书架清单里已经没有的 novelId（进程级作用域，幂等）。
     *
     * 必要性：删除是异步的（见 [deleteNovels]），进程被杀 / 崩溃 / 中途异常都会让那几条 DELETE
     * 永远不执行；孤儿行虽然不会串到新书（读取按 (id, 指纹) 过滤），但每行都带着原文与译文，
     * 只会白占空间。与漫画侧 `ImportMangaFragment.purgeOrphanTranslations` 同构。
     */
    fun purgeOrphanTranslations(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                val db = TranslationHistoryDatabase.getInstance(app)
                val dao = db.novelParagraphTranslationDao()
                val validIds = NovelStore.load(app).map { it.id }.toSet()
                dao.allNovelIds().filter { it !in validIds }.forEach { id ->
                    try {
                        // ⚠️ 逐个二次确认：本方法也是异步的，期间用户完全可能刚好导入了一本复用同 id 的
                        // 新书（id = 清单最大 id + 1）。不再确认就按 id 删，会删掉那本新书的翻译行。
                        if (NovelStore.load(app).any { it.id == id }) return@forEach
                        dao.deleteForNovelId(id)
                        LogCollector.i(TAG, "已清理孤儿译文 id=$id")
                    } catch (e: Exception) {
                        LogCollector.w(TAG, "清理孤儿译文失败 id=$id", e)
                    }
                }
            } catch (e: Exception) {
                LogCollector.w(TAG, "孤儿译文清理失败", e)
            }
        }
    }
}
