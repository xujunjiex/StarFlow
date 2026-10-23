package com.moe.starflow.translate.batch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/** 后台批量翻译任务属于哪个阅读器（通知点击要回到对应界面）。 */
enum class TranslationJobKind { MANGA, NOVEL }

/** 阅读器身份与任务类型分离，超分通知仍返回同一个漫画阅读器。 */
enum class ChapterJobOperation { TRANSLATE, UPSCALE }

/** 一章后台翻译任务的状态。 */
enum class ChapterJobState {
    /** 已提交、等待调度。 */
    QUEUED,
    RUNNING,
    PAUSED,
    DONE,
    CANCELLED
}

/** 通知栏动作。 */
enum class JobAction { PAUSE, RESUME, CANCEL }

/**
 * 通知栏/前台服务用的**任务快照**（跨书、跨模块汇总）。纯数据，不带 Android 引用。
 *
 * @param bookId 书 id（漫画=ImportedManga.id，小说=ImportedNovel.id）
 * @param chapterIndex 章在书里的下标（点通知回到这一章要用）
 * @param startPage 漫画用：该章第一页（点通知直接跳过去）；小说恒 0
 * @param stageText **比"第 x/y"更细的一行状态**（可空）。目前只有小说插图用：
 *   一整批里三张图要一张张 OCR，通知栏要显示得出「正在识别插图 2/3…」——
 *   只报 `done/total` 的话，阅读器已经关掉时用户在通知栏看到的是"进度不动"。
 *   ⚠️ 有值就**优先于**默认的进度文案（[TranslationJobService] 里那条 when）。
 */
data class ActiveChapterJob(
    val kind: TranslationJobKind,
    val bookId: Long,
    val bookTitle: String,
    val chapterIndex: Int,
    val chapterLabel: String,
    val total: Int,
    val done: Int,
    val state: ChapterJobState,
    val startPage: Int = 0,
    val stageText: String? = null,
    val libraryLocationId: String = "",
    val bookUuid: String = "",
    val operation: ChapterJobOperation = ChapterJobOperation.TRANSLATE,
    val stopping: Boolean = false,
    /** 暂停或取消后仍在收尾的请求，也需要保持 CPU 运行到资源释放。 */
    val hasInFlight: Boolean = false,
) {
    val isActive: Boolean
        get() = state == ChapterJobState.RUNNING || state == ChapterJobState.PAUSED ||
            state == ChapterJobState.QUEUED || hasInFlight

    val needsCpu: Boolean
        get() = state == ChapterJobState.RUNNING || state == ChapterJobState.QUEUED || hasInFlight

    /**
     * 通知 id 的稳定来源（同一本书同一章恒等）。
     *
     * ⚠️ 两点硬要求（2026-09-28 审查实测）：
     * ① **不能落在前台服务汇总通知那一段**（`NOTIFICATION_ID_SERVICE = 9998`）：旧公式下
     *    bookId=322/ch=16 正好是 9998 → 章通知会把汇总通知顶掉；
     * ② **跨书不能撞**：旧公式 `bookId*31 + chapterIndex` 让 book1/ch31 与 book2/ch0 都得 62
     *    → 两条章通知互相覆盖 / 服务的 `shown - alive` 差集把别人的通知 cancel 掉。
     * 现在用 64 位混合 + 高位掩码，并把结果整体挪出汇总 id 附近的小数值区。
     */
    val runtimeKey get() = "$libraryLocationId:${kind.name.lowercase()}:$bookUuid"

    val notificationId: Int
        get() {
            if (bookUuid.isNotEmpty()) {
                val scoped = "$runtimeKey:${kind.name}:$chapterIndex:${operation.name}".hashCode().toLong() and 0x7fffffffL
                return (10_000 + scoped % (Int.MAX_VALUE - 20_000)).toInt()
            }
            // 兼容没有 UUID 的旧快照：按书、章、阅读器和任务类型分配，再避开汇总通知区。
            val base = bookId.coerceIn(0L, 99_999L) * 10_000L + chapterIndex.coerceIn(0, 9_999)
            return (10_000L + (base * 4L + kind.ordinal * 2L + operation.ordinal) % (Int.MAX_VALUE - 20_000)).toInt()
        }
}

/**
 * 「谁在跑批量翻译」的提供者。漫画侧是 `ReaderTranslationHub`，小说侧是小说自己的宿主。
 *
 * ⚠️ 收口成一个接口的原因：通知栏与前台服务**只认这一份快照**，
 * 不然每加一个阅读器就要再抄一遍通知/暂停/取消的代码（`ModelDownloadService` 那套已经够长了）。
 */
interface ChapterJobSource {
    /** 稳定 key（一个模块一个）。 */
    val key: String

    /** 当前任务快照（调用方在主线程外读）。 */
    fun snapshot(): List<ActiveChapterJob>

    /** 处理通知栏动作；返回 true = 已处理（用于前台服务判断要不要清通知）。 */
    fun onAction(action: JobAction, job: ActiveChapterJob): Boolean
}

/**
 * 批量翻译任务的**全局注册表**：前台服务与通知栏的唯一数据源。
 *
 * 生命周期：各宿主在自己的任务状态 flow 变化时调 [notifyChanged]；
 * 服务 collect [activeJobs]，没有任何活动任务时自己 `stopSelf`。
 */
object TranslationJobRegistry {

    private val sources = CopyOnWriteArrayList<ChapterJobSource>()

    private val _activeJobs = MutableStateFlow<List<ActiveChapterJob>>(emptyList())
    val activeJobs: StateFlow<List<ActiveChapterJob>> = _activeJobs.asStateFlow()

    private val _hasActiveJobs = MutableStateFlow(false)
    val hasActiveJobs: StateFlow<Boolean> = _hasActiveJobs.asStateFlow()

    fun register(source: ChapterJobSource) {
        if (sources.none { it.key == source.key }) sources += source
        notifyChanged()
    }

    fun unregister(key: String) {
        sources.removeAll { it.key == key }
        notifyChanged()
    }

    /** 任务状态变了 → 重新聚合（宿主在 collect 到自己的 flow 变化时调用）。 */
    @Synchronized
    fun notifyChanged() {
        val snapshot = sources.flatMap { runCatching { it.snapshot() }.getOrDefault(emptyList()) }
        _activeJobs.value = snapshot
        _hasActiveJobs.value = snapshot.any { it.isActive }
    }

    /** 转发通知栏动作给对应宿主。 */
    fun dispatch(action: JobAction, job: ActiveChapterJob): Boolean {
        var handled = false
        sources.forEach { source ->
            if (runCatching { source.onAction(action, job) }.getOrDefault(false)) handled = true
        }
        notifyChanged()
        return handled
    }
}
