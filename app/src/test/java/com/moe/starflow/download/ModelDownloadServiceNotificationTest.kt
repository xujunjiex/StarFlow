package com.moe.starflow.download

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import com.moe.starflow.translate.batch.TranslationNotifyChannel
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ModelDownloadServiceNotificationTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val repo get() = ModelDownloadRepository.getInstance(app)
    private var controller: ServiceController<ModelDownloadService>? = null
    private var server: ServerSocket? = null
    private var serverThread: Thread? = null
    private val release = CountDownLatch(1)
    private val notifications get() = shadowOf(app.getSystemService(NotificationManager::class.java))

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ModelDownloadRepository::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
    }

    @After fun tearDown() {
        release.countDown()
        server?.close()
        serverThread?.join(2_000)
        controller?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        ShadowPowerManager.clearWakeLocks()
        ModelDownloadRepository::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
    }

    /** 本地 HTTP 分两段返回真实字节；中间停住，用来模拟息屏时仍在下载。 */
    @Suppress("UNCHECKED_CAST")
    private fun startDownload(): CountDownLatch {
        val connected = CountDownLatch(1)
        val socket = ServerSocket(0).also { server = it }
        serverThread = Thread {
            socket.accept().use { client ->
                val reader = client.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) Unit
                val output = client.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nContent-Length: 131072\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(ByteArray(1_024) { 7 })
                output.flush()
                Thread.sleep(600)
                output.write(ByteArray(64_512) { 7 })
                output.flush()
                connected.countDown()
                check(release.await(8, TimeUnit.SECONDS))
                output.write(ByteArray(65_536) { 9 })
                output.flush()
            }
        }.apply { isDaemon = true; start() }
        val info = ModelInfo(ModelKey.PP_OCR_V5_DET, "", listOf(FileInfo("notification-test.onnx", "http://127.0.0.1:${socket.localPort}/model", 131_072, "")))
        val cache = ModelDownloadRepository::class.java.getDeclaredField("modelListCached").apply { isAccessible = true }
            .get(repo) as MutableStateFlow<List<ModelInfo>>
        cache.value = listOf(info)
        controller = Robolectric.buildService(ModelDownloadService::class.java).create()
        controller!!.get().onStartCommand(Intent(app, ModelDownloadService::class.java).apply {
            action = ModelDownloadService.ACTION_START_DOWNLOAD
            putExtra(ModelDownloadService.EXTRA_MODEL_KEY, ModelKey.PP_OCR_V5_DET.name)
        }, 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        return connected
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!condition() && System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Condition did not become true", condition())
    }

    @Test fun realHttpDownloadContinuesWithScreenOff_showsPublicByteProgress_andReleasesCpuWhenDone() {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        val connected = startDownload()
        assertTrue(connected.await(4, TimeUnit.SECONDS))
        awaitCondition { (notifications.getNotification(ModelDownloadService.NOTIFICATION_ID_SERVICE)?.extras?.getInt(Notification.EXTRA_PROGRESS) ?: 0) > 0 }
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        val notification = notifications.getNotification(ModelDownloadService.NOTIFICATION_ID_SERVICE)
        assertEquals(TranslationNotifyChannel.ID, notification.channelId)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertNotNull(notification.publicVersion)
        assertEquals(1_000, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertTrue(notification.extras.getInt(Notification.EXTRA_PROGRESS) in 1..999)
        assertEquals(1, notifications.allNotifications.size)
        release.countDown()
        awaitCondition { repo.getState(ModelKey.PP_OCR_V5_DET) is DownloadState.Done }
        awaitCondition { !ShadowPowerManager.getLatestWakeLock().isHeld }
        assertTrue(shadowOf(controller!!.get()).isStoppedBySelf)
        assertEquals(131_072, repo.targetFilePath(ModelKey.PP_OCR_V5_DET).length())
    }

    @Test fun systemTimeoutReleasesCpuAndKeepsPartialFileForResume() {
        val connected = startDownload()
        assertTrue(connected.await(4, TimeUnit.SECONDS))
        val part = java.io.File(repo.targetFilePath(ModelKey.PP_OCR_V5_DET).path + ".part")
        awaitCondition { part.length() > 0 }
        controller!!.get().onTimeout(1, 1)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertTrue(shadowOf(controller!!.get()).isStoppedBySelf)
        assertTrue(part.exists())
        release.countDown()
        awaitCondition { notifications.getNotification(ModelDownloadService.NOTIFICATION_ID_SERVICE) == null }
        awaitCondition { repo.getState(ModelKey.PP_OCR_V5_DET) is DownloadState.Partial }
    }

    @Test fun pausingWaitsForHttpCleanup_thenKeepsPartFileAndPausedProgressWithoutHoldingCpu() {
        val connected = startDownload()
        assertTrue(connected.await(4, TimeUnit.SECONDS))
        awaitCondition { (repo.getState(ModelKey.PP_OCR_V5_DET) as? DownloadState.Running)?.bytesDownloaded ?: 0L > 0L }
        controller!!.get().onStartCommand(Intent(app, ModelDownloadService::class.java).apply {
            action = ModelDownloadService.ACTION_PAUSE_DOWNLOAD
            putExtra(ModelDownloadService.EXTRA_MODEL_KEY, ModelKey.PP_OCR_V5_DET.name)
        }, 0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        release.countDown()
        awaitCondition { repo.getState(ModelKey.PP_OCR_V5_DET) is DownloadState.Paused }
        awaitCondition { !ShadowPowerManager.getLatestWakeLock().isHeld }
        val part = java.io.File(repo.targetFilePath(ModelKey.PP_OCR_V5_DET).path + ".part")
        assertTrue(part.exists())
        assertTrue(part.length() > 0)
        val paused = notifications.getNotification(ModelDownloadService.NOTIFICATION_ID_BASE + ModelKey.PP_OCR_V5_DET.stableId)
        assertEquals(Notification.VISIBILITY_PUBLIC, paused.visibility)
        assertTrue(paused.extras.getInt(Notification.EXTRA_PROGRESS) > 0)
    }
}
