package com.moe.starflow.translate.screenshot

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ShooterGeometryTest {
    private var physical = DisplayGeometry(1880, 3008, 0, 160)

    @Before
    fun physicalDisplay() {
        val app = RuntimeEnvironment.getApplication()
        physical = physical.copy(densityDpi = app.resources.displayMetrics.densityDpi)
        val wm = mock(android.view.WindowManager::class.java)
        val display = mock(android.view.Display::class.java)
        `when`(display.rotation).thenAnswer { physical.rotation }
        `when`(wm.defaultDisplay).thenReturn(display)
        `when`(wm.maximumWindowMetrics).thenAnswer {
            android.view.WindowMetrics(android.graphics.Rect(0, 0, physical.width, physical.height), android.view.WindowInsets.Builder().build())
        }
        val context = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? = if (name == Context.WINDOW_SERVICE) wm else super.getSystemService(name)
        }
        org.robolectric.util.ReflectionHelpers.setStaticField(OverlayWindow::class.java, "displayContext", context)
    }

    @After
    fun releasePhysicalDisplay() {
        org.robolectric.util.ReflectionHelpers.setStaticField(OverlayWindow::class.java, "displayContext", null)
    }

    @Test
    fun capturedContentResizesBufferAndOldProjectionCannotStopNewSession() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val manager = mock(MediaProjectionManager::class.java)
        val projection = mock(MediaProjection::class.java)
        val display = mock(VirtualDisplay::class.java)
        val intent = Intent()
        `when`(manager.getMediaProjection(-1, intent)).thenReturn(projection)
        `when`(projection.createVirtualDisplay(anyString(), anyInt(), anyInt(), anyInt(), anyInt(), any(), any(), any()))
            .thenReturn(display)
        val context = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.MEDIA_PROJECTION_SERVICE) manager else super.getSystemService(name)
        }
        val shooter = Shooter(context)
        try {
            assertTrue(shooter.init(intent))
            val callback = ArgumentCaptor.forClass(MediaProjection.Callback::class.java)
            verify(projection).registerCallback(callback.capture(), any(Handler::class.java))
            physical = physical.copy(width = 3008, height = 1880, rotation = 1)
            callback.value.onCapturedContentResize(3008, 1880)
            shooter.shotNoWait()
            verify(display).resize(3008, 1880, app.resources.displayMetrics.densityDpi)

            // 缓冲区调整失败时，不能把上个方向的缓存帧送给 OCR。
            org.robolectric.util.ReflectionHelpers.setField(shooter, "lastBitmap",
                android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888))
            doThrow(IllegalStateException("resize failed")).`when`(display)
                .resize(eq(1880), eq(3008), anyInt())
            physical = physical.copy(width = 1880, height = 3008, rotation = 2)
            callback.value.onCapturedContentResize(1880, 3008)
            assertNull(shooter.shotNoWait())

            val nextProjection = mock(MediaProjection::class.java)
            `when`(manager.getMediaProjection(-1, intent)).thenReturn(nextProjection)
            val nextDisplay = mock(VirtualDisplay::class.java)
            `when`(nextProjection.createVirtualDisplay(anyString(), anyInt(), anyInt(), anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(nextDisplay)
            assertTrue(shooter.init(intent))
            callback.value.onStop()
            callback.value.onCapturedContentResize(1, 1)
            assertTrue(shooter.ready)
            shooter.shotNoWait()
            verify(nextDisplay, never()).resize(eq(1), eq(1), anyInt())
        } finally {
            shooter.release()
        }
    }

    @Test
    fun rotation180FlushesSameSizedOldPixelsBeforeReturningAnotherFrame() = runTest {
        physical = physical.copy(width = 4, height = 6)
        val app = RuntimeEnvironment.getApplication()
        val manager = mock(MediaProjectionManager::class.java)
        val projection = mock(MediaProjection::class.java)
        val display = mock(VirtualDisplay::class.java)
        val intent = Intent()
        `when`(manager.getMediaProjection(-1, intent)).thenReturn(projection)
        `when`(projection.createVirtualDisplay(anyString(), anyInt(), anyInt(), anyInt(), anyInt(), any(), any(), any())).thenReturn(display)
        val context = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? = if (name == Context.MEDIA_PROJECTION_SERVICE) manager else super.getSystemService(name)
        }
        val shooter = Shooter(context)
        try {
            assertTrue(shooter.init(intent))
            val stale = android.graphics.Bitmap.createBitmap(4, 6, android.graphics.Bitmap.Config.ARGB_8888)
            org.robolectric.util.ReflectionHelpers.setField(shooter, "lastBitmap", stale)
            org.robolectric.util.ReflectionHelpers.setField(shooter, "lastBitmapGeometry", physical)
            physical = physical.copy(rotation = 2)
            assertNull(shooter.shotNoWait())
            verify(display).resize(4, 6, app.resources.displayMetrics.densityDpi)
            assertTrue(stale.isRecycled)
        } finally {
            shooter.release()
        }
    }

    @Test
    fun displayMonitorDetects180AndDensityChangesAndStopsAfterRelease() {
        var invalidations = 0
        val monitor = DisplayGeometryMonitor(RuntimeEnvironment.getApplication()) { invalidations++ }
        try {
            physical = physical.copy(rotation = 2)
            monitor.check()
            assertTrue(invalidations == 1)
            monitor.check()
            assertTrue(invalidations == 1)
            RuntimeEnvironment.setQualifiers("hdpi")
            monitor.check()
            assertTrue(invalidations == 2)
        } finally {
            monitor.close()
        }
        physical = physical.copy(rotation = 0)
        monitor.check()
        assertTrue(invalidations == 2)
    }
}
