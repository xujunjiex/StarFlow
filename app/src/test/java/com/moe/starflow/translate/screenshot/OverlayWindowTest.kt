package com.moe.starflow.translate.screenshot

import android.app.Application
import android.content.Context
import android.graphics.Point
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.moe.starflow.utils.DisplaySize

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OverlayWindowTest {
    @Test
    fun fullScreenLayoutIsResolvedByWindowManagerAndIncludesSystemBars() {
        val params = OverlayWindow.fullDisplayParams()
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params.width)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params.height)
        assertEquals(0, params.fitInsetsTypes)
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, params.layoutInDisplayCutoutMode)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0)
    }

    @Test
    @Config(sdk = [29])
    fun android10UsesSupportedCutoutMode() {
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
            OverlayWindow.fullDisplayParams().layoutInDisplayCutoutMode)
    }

    @Test
    fun displayMetricsIgnorePortraitAppWindowOnLandscapeTablet() {
        val app = RuntimeEnvironment.getApplication()
        val wm = org.mockito.Mockito.mock(WindowManager::class.java)
        val expected = android.graphics.Rect(0, 0, 3008, 1880)
        org.mockito.Mockito.`when`(wm.maximumWindowMetrics).thenReturn(android.view.WindowMetrics(
            expected, android.view.WindowInsets.Builder().build()))
        val physical = object : android.content.ContextWrapper(app) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.WINDOW_SERVICE) wm else super.getSystemService(name)
        }
        val portrait = app.createConfigurationContext(android.content.res.Configuration().apply {
            orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT
            screenWidthDp = 600
            screenHeightDp = 960
        })
        org.robolectric.util.ReflectionHelpers.setStaticField(OverlayWindow::class.java, "displayContext", physical)
        try {
            assertEquals(Point(expected.width(), expected.height()), DisplaySize.legacySize(portrait))
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(OverlayWindow::class.java, "displayContext", null)
        }
    }
}
