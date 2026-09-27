package com.moe.starflow.translate.batch

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.moe.starflow.R
import com.moe.starflow.mangaimport.reader.MangaReaderActivity
import com.moe.starflow.novel.reader.NovelReaderActivity
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.launch

/**
 * 批量翻译的**前台服务**：在通知栏给每一章一条进度通知（可暂停/继续/取消）。
 *
 * 用户口径：
 * - 「翻译本章要能后台进行…除非直接清除后台，否则会继续翻译」→ 任务本体在各自的宿主里
 *   （漫画 `ReaderTranslationHub` / 小说 `NovelTranslationHub`，都是应用级 scope），
 *   本服务只负责**前台保活 + 通知栏**：不带前台服务的话系统随时可能回收进程，任务会中途消失
 * - 「通知栏的进度条上面也能暂停」→ 通知动作 [JobAction]
 * - 「每章一张通知」→ 一章一条（id 由 [ActiveChapterJob.notificationId] 稳定派生），
 *   点通知回到那本书的对应阅读器（漫画带该章第一页）
 *
 * ⚠️ **任务状态不在这里**：这里只镜像 [TranslationJobRegistry.activeJobs]。
 * 服务被杀 → 任务照跑（直到进程死）；任务跑完 → registry 里没有活动项 → 服务自己 `stopSelf`。
 */
class TranslationJobService : LifecycleService() {

    companion object {
        private const val TAG = "TranslationJobService"

        const val ACTION_PAUSE = "com.moe.starflow.action.JOB_PAUSE"
        const val ACTION_RESUME = "com.moe.starflow.action.JOB_RESUME"
        const val ACTION_CANCEL = "com.moe.starflow.action.JOB_CANCEL"
        const val EXTRA_KIND = "job_kind"
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_CHAPTER_INDEX = "chapter_index"

        const val CHANNEL_ID = "chapter_translation"
        const val NOTIFICATION_ID_SERVICE = 9998

        /** 启动（或唤醒）前台服务：任务已经在跑，这里只是把通知栏挂上去。 */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TranslationJobService::class.java))
            } catch (e: Exception) {
                LogCollector.e(TAG, "启动批量翻译前台服务失败", e)
            }
        }

        /** 当前有没有活动任务（宿主用来决定要不要拉起服务）。 */
        fun hasActiveJobs(): Boolean = TranslationJobRegistry.hasActiveJobs.value
    }

    private var isForegroundStarted = false
    private val shown = mutableSetOf<Int>()

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        if (isForegroundStarted) return
        isForegroundStarted = true
        try {
            startForeground(NOTIFICATION_ID_SERVICE, buildSummaryNotification(1))
        } catch (e: Exception) {
            LogCollector.e(TAG, "startForeground 失败", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        intent?.action?.let { handleAction(it, intent) }
        observeJobs()
        return START_NOT_STICKY
    }

    /** 通知栏动作：暂停/继续/取消某一章（转发给对应宿主）。 */
    private fun handleAction(action: String, intent: Intent) {
        val kindName = intent.getStringExtra(EXTRA_KIND) ?: return
        val kind = runCatching { TranslationJobKind.valueOf(kindName) }.getOrNull() ?: return
        val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
        val chapterIndex = intent.getIntExtra(EXTRA_CHAPTER_INDEX, -1)
        if (bookId < 0 || chapterIndex < 0) return
        val job = TranslationJobRegistry.activeJobs.value
            .firstOrNull { it.kind == kind && it.bookId == bookId && it.chapterIndex == chapterIndex }
            ?: return
        val act = when (action) {
            ACTION_PAUSE -> JobAction.PAUSE
            ACTION_RESUME -> JobAction.RESUME
            ACTION_CANCEL -> JobAction.CANCEL
            else -> return
        }
        TranslationJobRegistry.dispatch(act, job)
        LogCollector.d(TAG, "通知动作 $act kind=$kind book=$bookId chapter=$chapterIndex")
    }

    private var observing = false

    /** 镜像 registry 的任务快照 → 每章一条通知；没有活动任务了就停服务。 */
    // 通知权限已在 `canPostNotifications()` 里查过（lint 不会跟进 helper，所以显式声明已处理）
    @SuppressLint("MissingPermission")
    private fun observeJobs() {
        if (observing) return
        observing = true
        lifecycleScope.launch {
            TranslationJobRegistry.activeJobs.collect { jobs ->
                // ⚠️ 只给**还在跑**的章发通知：`jobs` 里会长期保留 DONE/CANCELLED 的章
                // （面板要靠它显示「已完成 x/y」），照单全发的话完成的章会**永远挂着一条
                // 「正在翻译 20/20 页」的通知**，而且服务因为 jobs 非空永不停 —— 用户看得到的脏状态。
                val active = jobs.filter {
                    it.state == ChapterJobState.RUNNING || it.state == ChapterJobState.QUEUED ||
                        it.state == ChapterJobState.PAUSED
                }
                if (active.isEmpty()) {
                    clearAll()
                    stopSelf()
                    return@collect
                }
                active.forEach { job -> notify(job) }
                // 已经不在活动列表里的通知要撤掉（跑完/取消的章）
                val alive = active.map { it.notificationId }.toSet()
                (shown - alive).forEach { id ->
                    runCatching { NotificationManagerCompat.from(this@TranslationJobService).cancel(id) }
                    shown -= id
                }
                runCatching {
                    if (canPostNotifications()) {
                        NotificationManagerCompat.from(this@TranslationJobService)
                            .notify(NOTIFICATION_ID_SERVICE, buildSummaryNotification(active.size))
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun notify(job: ActiveChapterJob) {
        if (!canPostNotifications()) return
        val label = job.chapterLabel.ifBlank { getString(R.string.manga_chapter_label, job.chapterIndex + 1) }
        val title = getString(R.string.chapter_translate_notify_title, job.bookTitle, label)
        val text = when (job.state) {
            ChapterJobState.PAUSED -> getString(R.string.chapter_translate_notify_paused, job.done, job.total)
            ChapterJobState.CANCELLED -> getString(R.string.chapter_translate_notify_cancelled)
            else -> getString(R.string.chapter_translate_notify_progress, job.done, job.total)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reader_translate)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(job.state == ChapterJobState.RUNNING || job.state == ChapterJobState.QUEUED)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(job.total.coerceAtLeast(1), job.done, false)
            .setContentIntent(openReaderIntent(job))
            .addAction(
                0, getString(R.string.cancel),
                actionIntent(JobAction.CANCEL, job, job.notificationId + 1)
            )
        if (job.state == ChapterJobState.PAUSED) {
            builder.addAction(
                0, getString(R.string.reader_translate_chapter_resume),
                actionIntent(JobAction.RESUME, job, job.notificationId + 2)
            )
        } else if (job.state != ChapterJobState.CANCELLED) {
            builder.addAction(
                0, getString(R.string.reader_translate_chapter_pause),
                actionIntent(JobAction.PAUSE, job, job.notificationId + 2)
            )
        }
        try {
            NotificationManagerCompat.from(this).notify(job.notificationId, builder.build())
            shown += job.notificationId
        } catch (e: SecurityException) {
            LogCollector.w(TAG, "通知权限缺失，无法显示翻译进度: ${e.message}")
        }
    }

    private fun actionIntent(action: JobAction, job: ActiveChapterJob, requestCode: Int): PendingIntent {
        val name = when (action) {
            JobAction.PAUSE -> ACTION_PAUSE
            JobAction.RESUME -> ACTION_RESUME
            JobAction.CANCEL -> ACTION_CANCEL
        }
        val intent = Intent(this, TranslationJobService::class.java).apply {
            this.action = name
            putExtra(EXTRA_KIND, job.kind.name)
            putExtra(EXTRA_BOOK_ID, job.bookId)
            putExtra(EXTRA_CHAPTER_INDEX, job.chapterIndex)
        }
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 点通知 → 打开对应阅读器（漫画带该章第一页，阅读器自己会落到那一章）。 */
    private fun openReaderIntent(job: ActiveChapterJob): PendingIntent {
        val intent = when (job.kind) {
            TranslationJobKind.MANGA -> Intent(this, MangaReaderActivity::class.java).apply {
                putExtra(MangaReaderActivity.EXTRA_MANGA_ID, job.bookId)
                putExtra(MangaReaderActivity.EXTRA_START_PAGE, job.startPage)
            }
            TranslationJobKind.NOVEL -> Intent(this, NovelReaderActivity::class.java).apply {
                putExtra(NovelReaderActivity.EXTRA_NOVEL_ID, job.bookId)
                putExtra(NovelReaderActivity.EXTRA_CHAPTER_INDEX, job.chapterIndex)
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, job.notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildSummaryNotification(running: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reader_translate)
            .setContentTitle(getString(R.string.chapter_translate_notify_summary_title))
            .setContentText(getString(R.string.chapter_translate_notify_summary, running))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /**
     * 有没有通知权限（Android 13+ 需要用户授权）。
     *
     * ⚠️ 必须先查再发：没有权限时 `notify()` 在部分 ROM 上直接抛 `SecurityException`（我们虽然在
     * `runCatching` 里兜住了，但每次都白构造一遍通知、还会刷日志）。`MainActivity` 启动时已经申请过
     * 这个权限，用户拒绝的话这里静默跳过 —— **任务照跑**，只是没有通知栏进度。
     */
    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun clearAll() {
        val nm = NotificationManagerCompat.from(this)
        shown.forEach { runCatching { nm.cancel(it) } }
        shown.clear()
        runCatching { nm.cancel(NOTIFICATION_ID_SERVICE) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.chapter_translate_notify_summary_title),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        // ⚠️ 任务本体在各宿主里，服务停掉**不代表任务停止**（用户要求除非清后台否则继续翻）
        LogCollector.d(TAG, "批量翻译前台服务已停止（任务仍在后台跑）")
        super.onDestroy()
    }
}
