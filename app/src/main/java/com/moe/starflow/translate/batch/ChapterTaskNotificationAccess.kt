package com.moe.starflow.translate.batch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.moe.starflow.R
import com.moe.starflow.utils.ReaderDialogs

/** 只在系统通知被关闭时请求授权或提供系统入口，不额外弹锁屏引导。 */
class ChapterTaskNotificationAccess(
    private val activity: ComponentActivity,
    private val dark: () -> Boolean,
) {
    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            TranslationJobService.start(activity)
        } else showSettings(activity, dark())
    }

    fun ensureForTask() {
        TranslationNotifyChannel.ensure(activity)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!notificationsEnabled(activity)) {
            showSettings(activity, dark())
        }
    }

    companion object {
        /** 失败通知独立于服务进度，服务退出后仍保留具体原因。 */
        @android.annotation.SuppressLint("MissingPermission")
        fun reportFailure(context: Context, job: ActiveChapterJob, message: String) {
            TranslationNotifyChannel.ensure(context)
            if (!notificationsEnabled(context)) return
            val title = context.getString(
                if (job.operation == ChapterJobOperation.UPSCALE) R.string.chapter_sr_notify_title else R.string.chapter_translate_notify_title,
                job.bookTitle, job.chapterLabel,
            )
            val detail = context.getString(R.string.chapter_task_failed, message)
            val notification = NotificationCompat.Builder(context, TranslationNotifyChannel.ID)
                .setSmallIcon(R.drawable.ic_reader_translate)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(TranslationJobService.openReaderIntent(context, job))
                .build()
            runCatching {
                NotificationManagerCompat.from(context).notify("chapter_failure:${job.runtimeKey}", job.notificationId, notification)
            }.onFailure { com.moe.starflow.utils.LogCollector.w("ChapterTasks", "任务失败通知显示失败", it) }
        }

        fun notificationsEnabled(context: Context): Boolean {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
            val channel = context.getSystemService(android.app.NotificationManager::class.java)
                .getNotificationChannel(TranslationNotifyChannel.ID)
            return channel == null || channel.importance != android.app.NotificationManager.IMPORTANCE_NONE
        }

        fun showSettings(activity: ComponentActivity, dark: Boolean) {
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || activity.isFinishing) return
            ReaderDialogs.show(activity, dark) {
                setTitle(R.string.task_notifications_disabled_title)
                setMessage(R.string.task_notifications_disabled_message)
                setPositiveButton(R.string.chapter_task_notification_settings) { _, _ ->
                    openSettings(activity, notificationSettingsIntent(activity))
                }
                setNegativeButton(android.R.string.ok, null)
            }
        }

        internal fun notificationSettingsIntent(context: Context): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

        private fun openSettings(context: Context, intent: Intent) {
            // 部分系统没有标准设置页，回退应用详情，保持跨品牌可用。
            runCatching { context.startActivity(intent) }.recoverCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }
        }
    }
}
