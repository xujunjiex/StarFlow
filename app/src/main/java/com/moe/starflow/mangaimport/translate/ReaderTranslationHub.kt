package com.moe.starflow.mangaimport.translate

import android.content.Context
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.translate.batch.ActiveChapterJob
import com.moe.starflow.translate.batch.ChapterJobSource
import com.moe.starflow.translate.batch.ChapterJobState
import com.moe.starflow.translate.batch.JobAction
import com.moe.starflow.translate.batch.TranslationJobKind
import com.moe.starflow.translate.batch.TranslationJobRegistry
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 章节批量翻译的**应用级宿主**：按漫画缓存 [ReaderTranslationController]，跑在应用级 scope 上。
 *
 * 为什么需要它（用户口径「翻译本章要能后台进行、除非清后台否则继续」）：
 * 控制器原来挂在阅读器的 `lifecycleScope` 上，退出阅读器就随 Activity 一起没了。
 * 这里把控制器提到**进程级**：
 * - 阅读器打开 → [controllerFor] 复用同一个实例（任务、记录、渲染缓存都在）
 * - 阅读器关掉 → 控制器**不销毁**，章节任务继续跑（进度走前台服务通知栏）
 * - 任务跑完且没有 UI 挂着 → [releaseIfIdle] 回收，避免控制器与页图数据源常驻内存
 *
 * ⚠️ 与 `ImportManager` 同一套思路：后台任务的编排不归 View 管。
 * 任务快照通过 [TranslationJobRegistry] 汇总给前台服务（小说侧是另一个 source，同一份通知栏）。
 */
object ReaderTranslationHub : ChapterJobSource {

    private const val TAG = "ReaderTranslationHub"

    override val key: String = "manga"

    /** 应用级 scope：进程活多久它活多久（后台任务靠的就是它）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val controllers = ConcurrentHashMap<Long, ReaderTranslationController>()

    /** 每个控制器一个「任务状态变化」收集协程；回收控制器时必须一并取消，否则收集协程会留着。 */
    private val collectors = ConcurrentHashMap<Long, Job>()

    init {
        TranslationJobRegistry.register(this)
    }

    /** 取（或创建）某本书的控制器。同一本书永远只有一份（任务/缓存不会分裂）。 */
    @Synchronized
    fun controllerFor(context: Context, manga: ImportedManga): ReaderTranslationController {
        // ⚠️ **必须比身份指纹**，不能只看 id：书架删除后新导入的书会**复用同一个 id**
        // （`书架最大id+1`），而控制器在构造时就捕获了 `mangaKey = manga.translationKey`
        // → 旧控制器若还活着，新书会按**旧指纹**读写译文（当场看着正常，重启后译文"消失"、
        // 书架删除也清不掉）。指纹不一致 → 丢掉旧实例、重建。
        controllers[manga.id]?.let { cached ->
            // 已关闭（被 releaseIfIdle 回收过）→ 不能再交出去
            if (!cached.closed && cached.translationKeyOf() == manga.translationKey) return cached
            LogCollector.i(TAG, "mangaId=${manga.id} 身份指纹变了（删除后复用 id）→ 重建控制器")
            controllers.remove(manga.id)
            collectors.remove(manga.id)?.cancel()
            cached.shutdownAll()
        }
        val app = context.applicationContext
        val controller = ReaderTranslationController(app, manga, scope)
        controllers[manga.id] = controller
        // 任务状态变化 → 重新聚合给通知栏（控制器是唯一真值，这里只做汇总）；
        // 全部任务收尾且阅读器没开着 → 顺手回收控制器
        collectors[manga.id] = scope.launch {
            controller.chapterJobs.collect {
                TranslationJobRegistry.notifyChanged()
                // ⚠️ `collect` 会**立刻收到一次当前值**，而那一刻阅读器还在 `bind()` 之前
                //（`uiAttached` 仍 false）→ 不加 `everAttached` 判断就会把刚建好的控制器当场回收，
                // Activity 手上留个已 shutdown 的实例、下次进来又新建（日志里的"创建→回收→再创建"）。
                if (!controller.everAttached) return@collect
                if (!controller.uiAttached && !controller.isChapterBatchRunning()) releaseIfIdle(manga.id)
            }
        }
        LogCollector.i(TAG, "创建阅读器翻译控制器 mangaId=${manga.id} title=${manga.title}")
        return controller
    }


    /**
     * 阅读器关闭后调用：这本书没有任务在跑就回收控制器。
     *
     * ⚠️ 没有它的话控制器**永远不会被回收**：唯一的回收触发点是 `chapterJobs` 的收集协程，
     * 而任务跑完就不会再有新值 —— 阅读器关掉（`uiAttached=false`）也等不到那次发射。
     * 结果每开一本书就常驻一份 `renderLru`（预算 100MB）+ 页图数据源，直到进程被杀。
     */
    fun onReaderClosed(mangaId: Long) {
        releaseIfIdle(mangaId)
    }

    /**
     * 没有 UI 挂着、也没有任务在跑 → 回收控制器（释放渲染缓存与页图数据源）。
     * ⚠️ 必须判 `uiAttached`：阅读器正开着时不能回收。
     */
    @Synchronized
    fun releaseIfIdle(mangaId: Long) {
        val c = controllers[mangaId] ?: return
        if (c.uiAttached || c.isChapterBatchRunning()) return
        controllers.remove(mangaId)
        collectors.remove(mangaId)?.cancel()
        c.shutdownAll()
        TranslationJobRegistry.notifyChanged()
        LogCollector.i(TAG, "回收阅读器翻译控制器 mangaId=$mangaId（无 UI 且无任务）")
    }

    // ===== ChapterJobSource =====

    override fun snapshot(): List<ActiveChapterJob> = controllers.values.flatMap { c ->
        c.chapterJobs.value.map { job ->
            ActiveChapterJob(
                kind = TranslationJobKind.MANGA,
                bookId = c.mangaId,
                bookTitle = c.mangaTitle,
                chapterIndex = job.chapterIndex,
                chapterLabel = job.label,
                total = job.total,
                done = job.done,
                state = job.state,
                startPage = job.startPage,
            )
        }
    }

    override fun onAction(action: JobAction, job: ActiveChapterJob): Boolean {
        if (job.kind != TranslationJobKind.MANGA) return false
        val c = controllers[job.bookId] ?: return false
        when (action) {
            JobAction.PAUSE -> c.pauseChapterJob(job.chapterIndex)
            JobAction.RESUME -> c.resumeChapterJob(job.chapterIndex)
            JobAction.CANCEL -> {
                c.cancelChapterJob(job.chapterIndex)
                releaseIfIdle(job.bookId)
            }
        }
        return true
    }

}
