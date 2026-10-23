package com.moe.starflow.translate.batch

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import com.moe.starflow.R
import com.moe.starflow.mangaimport.reader.MangaReaderActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ChapterTaskServiceTest {
    private class Source : ChapterJobSource {
        override val key = "chapter-service-test"
        var jobs: List<ActiveChapterJob> = emptyList()
        val actions = mutableListOf<Pair<JobAction, ChapterJobOperation>>()
        override fun snapshot() = jobs
        override fun onAction(action: JobAction, job: ActiveChapterJob): Boolean {
            if (jobs.none { it.notificationId == job.notificationId }) return false
            actions += action to job.operation
            if (action == JobAction.CANCEL) jobs = jobs.filterNot { it.notificationId == job.notificationId }
            return true
        }
    }

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val source = Source()
    private var controller: ServiceController<TranslationJobService>? = null
    private val service get() = controller!!.get()
    private val notifications get() = shadowOf(app.getSystemService(NotificationManager::class.java))

    private fun job(operation: ChapterJobOperation = ChapterJobOperation.TRANSLATE) = ActiveChapterJob(
        kind = TranslationJobKind.MANGA, bookId = 42, bookTitle = "Book", chapterIndex = 2,
        chapterLabel = "Chapter 3", total = 3, done = 1, state = ChapterJobState.RUNNING,
        startPage = 12, libraryLocationId = "location", bookUuid = "00000000-0000-0000-0000-000000000001",
        operation = operation,
    )

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        TranslationJobRegistry.register(source)
    }

    @After
    fun tearDown() {
        controller?.destroy()
        TranslationJobRegistry.unregister(source.key)
        ShadowPowerManager.clearWakeLocks()
    }

    private fun publish(jobs: List<ActiveChapterJob>) {
        source.jobs = jobs
        TranslationJobRegistry.notifyChanged()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun start(jobs: List<ActiveChapterJob>) {
        source.jobs = jobs
        TranslationJobRegistry.notifyChanged()
        controller = Robolectric.buildService(TranslationJobService::class.java).create()
        service.onStartCommand(Intent(app, TranslationJobService::class.java), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun upscalingPublishesProgressAndCancelAction_andTapReturnsToChapter() {
        val upscale = job(ChapterJobOperation.UPSCALE).copy(stageText = "P14")
        start(listOf(upscale))
        assertEquals(TranslationJobService.NOTIFICATION_ID_SERVICE, shadowOf(service).lastForegroundNotificationId)
        val notification = notifications.getNotification(upscale.notificationId)
        assertNotNull(notification)
        assertEquals(app.getString(R.string.chapter_sr_notify_title, "Book", "Chapter 3"), notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(1, notification.actions.size)
        assertEquals(app.getString(R.string.cancel), notification.actions.single().title.toString())
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("P14"))
        val open = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MangaReaderActivity::class.java.name, open.component!!.className)
        assertEquals(12, open.getIntExtra(MangaReaderActivity.EXTRA_START_PAGE, -1))
        publish(listOf(upscale.copy(done = 2)))
        assertEquals(2, notifications.getNotification(upscale.notificationId).extras.getInt(Notification.EXTRA_PROGRESS))
        val cancel = shadowOf(notification.actions.single().actionIntent).savedIntent
        service.onStartCommand(cancel, 0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(JobAction.CANCEL to ChapterJobOperation.UPSCALE), source.actions)
        assertNull(notifications.getNotification(upscale.notificationId))
    }

    @Test
    fun translationAndUpscalingNotificationsAndPendingIntentsStayIndependent() {
        val translate = job()
        val upscale = job(ChapterJobOperation.UPSCALE)
        start(listOf(translate, upscale))
        val translationNotification = notifications.getNotification(translate.notificationId)
        val srNotification = notifications.getNotification(upscale.notificationId)
        assertEquals(2, translationNotification.actions.size)
        assertEquals(1, srNotification.actions.size)
        assertNotEquals(translationNotification.contentIntent, srNotification.contentIntent)
        service.onStartCommand(shadowOf(srNotification.actions.single().actionIntent).savedIntent, 0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(translate), source.jobs)
        assertNotNull(notifications.getNotification(translate.notificationId))
    }

    @Test
    fun screenOffKeepsCpuLease_andPauseCleanupResumeAndFinishControlIt() {
        val translate = job()
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        start(listOf(translate))
        val wakeLock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(wakeLock.isHeld)
        publish(listOf(translate.copy(state = ChapterJobState.PAUSED, hasInFlight = true)))
        assertTrue(wakeLock.isHeld)
        publish(listOf(translate.copy(state = ChapterJobState.PAUSED)))
        assertFalse(wakeLock.isHeld)
        publish(listOf(translate))
        assertTrue(wakeLock.isHeld)
        publish(listOf(translate.copy(state = ChapterJobState.CANCELLED, hasInFlight = true, stopping = true)))
        assertTrue(wakeLock.isHeld)
        publish(emptyList())
        assertFalse(wakeLock.isHeld)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun denyingNotificationPermissionDoesNotStopWork_andServiceDestroyReleasesCpuLease() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val upscale = job(ChapterJobOperation.UPSCALE)
        start(listOf(upscale))
        assertNull(notifications.getNotification(upscale.notificationId))
        val wakeLock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(wakeLock.isHeld)
        controller!!.destroy()
        controller = null
        assertFalse(wakeLock.isHeld)
        assertEquals(listOf(upscale), source.jobs)
    }

    @Test
    fun cpuLeaseIsRenewedForLongTasks_andReleasedAfterCompletion() {
        start(listOf(job(ChapterJobOperation.UPSCALE)))
        val wakeLock = ShadowPowerManager.getLatestWakeLock()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(11))
        assertTrue(wakeLock.isHeld)
        publish(emptyList())
        assertFalse(wakeLock.isHeld)
    }

    @Test
    fun systemTimeoutCancelsBothKindsAndReleasesForegroundAndCpuLease() {
        start(listOf(job(), job(ChapterJobOperation.UPSCALE)))
        val wakeLock = ShadowPowerManager.getLatestWakeLock()
        service.onTimeout(1, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf(JobAction.CANCEL to ChapterJobOperation.TRANSLATE, JobAction.CANCEL to ChapterJobOperation.UPSCALE), source.actions.toSet())
        assertFalse(wakeLock.isHeld)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    @Test
    fun lockedTranslationAndUpscalingExposeProgressInBothChapterAndForegroundNotifications() {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        for (operation in ChapterJobOperation.values()) {
            val task = job(operation)
            if (controller == null) start(listOf(task)) else publish(listOf(task))
            for (id in listOf(task.notificationId, TranslationJobService.NOTIFICATION_ID_SERVICE)) {
                val notification = notifications.getNotification(id)
                assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
                assertEquals(Notification.CATEGORY_PROGRESS, notification.category)
                assertEquals(1, notification.extras.getInt(Notification.EXTRA_PROGRESS))
                assertNotNull(notification.contentIntent)
            }
            publish(listOf(task.copy(done = 2)))
            assertEquals(2, notifications.getNotification(TranslationJobService.NOTIFICATION_ID_SERVICE).extras.getInt(Notification.EXTRA_PROGRESS))
        }
    }

    @Test
    fun restoringPermissionRepostsProgressEvenWhenTaskSnapshotHasNotChanged() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val task = job()
        start(listOf(task))
        assertNull(notifications.getNotification(task.notificationId))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        service.onStartCommand(Intent(app, TranslationJobService::class.java), 0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(notifications.getNotification(task.notificationId))
    }

    @Test
    fun channelIsPublicOnFirstCreationAndRecreatedIfItWasDeleted() {
        TranslationNotifyChannel.ensure(app)
        val manager = app.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(TranslationNotifyChannel.ID)
        assertEquals(Notification.VISIBILITY_PUBLIC, channel.lockscreenVisibility)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        manager.deleteNotificationChannel(TranslationNotifyChannel.ID)
        TranslationNotifyChannel.ensure(app)
        assertNotNull(manager.getNotificationChannel(TranslationNotifyChannel.ID))
    }

    @Test
    fun blockedChannelIsDetectedAndUserSettingsArePreserved() {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel(TranslationNotifyChannel.ID, "Progress", NotificationManager.IMPORTANCE_NONE))
        TranslationNotifyChannel.ensure(app)
        assertFalse(ChapterTaskNotificationAccess.notificationsEnabled(app))
        val settings = ChapterTaskNotificationAccess.notificationSettingsIntent(app)
        assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, settings.action)
        assertEquals(app.packageName, settings.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    @Config(sdk = [29])
    fun android10ShowsPublicProgressWithoutRuntimeNotificationPermission() {
        val task = job()
        start(listOf(task))
        assertEquals(Notification.VISIBILITY_PUBLIC, notifications.getNotification(task.notificationId).visibility)
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test
    fun slowNovelChapterReadKeepsRealServiceAndCpuAlivePastStartupGrace() = kotlinx.coroutines.runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val host = com.moe.starflow.novel.translate.NovelChapterJobHost(
            app,
            com.moe.starflow.novel.data.ImportedNovel(77, "Slow book", localRoot = "unused", format = com.moe.starflow.novel.model.NovelFormat.TXT, chapterCount = 1, addedAt = 1),
            scope, { release.await(); emptyList() },
        )
        TranslationJobRegistry.register(host)
        try {
            host.startChapter(0, "Slow chapter", retranslateAll = true)
            start(emptyList())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
            assertFalse(shadowOf(service).isStoppedBySelf)
            val preparing = host.snapshot().single()
            assertTrue(notifications.getNotification(preparing.notificationId).extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
            assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
            release.complete(Unit)
            kotlinx.coroutines.withTimeout(4_000) {
                while (host.isRunning()) kotlinx.coroutines.delay(10)
            }
            TranslationJobRegistry.notifyChanged()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        } finally {
            release.complete(Unit)
            host.shutdownAndJoin()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            TranslationJobRegistry.unregister(host.key)
        }
    }

    @Test
    fun chapterPreparationFailureRemainsVisibleAfterProgressServiceStops() {
        val task = job(ChapterJobOperation.UPSCALE)
        start(listOf(task))
        ChapterTaskNotificationAccess.reportFailure(app, task, "Model file is missing")
        publish(emptyList())
        assertNull(notifications.getNotification(task.notificationId))
        val failure = notifications.allNotifications.single {
            it.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Model file is missing")
        }
        assertEquals(Notification.VISIBILITY_PUBLIC, failure.visibility)
        assertTrue(failure.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertNotNull(failure.contentIntent)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }
}
