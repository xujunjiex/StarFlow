package com.moe.starflow.translate.screenshot

import android.app.Application
import android.graphics.Bitmap
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import com.moe.starflow.utils.DisplaySize
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AlignedResultGuardTest {
    @Test
    fun refusesToDrawBeforeScalingOrOffsetCanHideAnInvalidWindow() {
        val app = RuntimeEnvironment.getApplication()
        val display = DisplaySize.geometry(app)
        val mapping = requireNotNull(ScreenshotMapping.create(display, null))
        for ((width, x, rotation) in listOf(Triple(display.width, 0, display.rotation),
            Triple(display.width - 1, 0, display.rotation), Triple(display.width, 1, display.rotation),
            Triple(display.width, 0, (display.rotation + 2) % 4))) {
            val view = mock(View::class.java)
            `when`(view.context).thenReturn(app)
            `when`(view.width).thenReturn(width)
            `when`(view.height).thenReturn(display.height)
            `when`(view.viewTreeObserver).thenReturn(mock(ViewTreeObserver::class.java))
            doAnswer { invocation -> invocation.getArgument<IntArray>(0).apply { this[0] = x }; null }
                .`when`(view).getLocationOnScreen(any(IntArray::class.java))
            doAnswer { invocation -> invocation.getArgument<Runnable>(0).run(); true }.`when`(view).post(any(Runnable::class.java))
            var invalidations = 0
            val expected = mapping.copy(display = display.copy(rotation = rotation))
            val guard = AlignedResultGuard(view, expected) { invalidations++ }
            val valid = width == display.width && x == 0 && rotation == display.rotation
            assertEquals(valid, guard.onPreDraw())
            assertEquals(if (valid) 0 else 1, invalidations)
            assertEquals(valid, guard.onPreDraw())
            assertEquals(if (valid) 0 else 1, invalidations)
        }
    }

    @Test
    fun screenshotBitmapIsDisplayedInPhysicalPixelsRegardlessOfItsDensity() {
        val view = ImageView(RuntimeEnvironment.getApplication())
        val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply { density = 640 }
        OverlayWindow.setBitmapInPixels(view, bitmap)
        view.layout(0, 0, 40, 60)
        assertEquals(Bitmap.DENSITY_NONE, bitmap.density)
        assertEquals(40, view.drawable.intrinsicWidth)
        assertEquals(60, view.drawable.intrinsicHeight)
        assertEquals(ImageView.ScaleType.CENTER, view.scaleType)
        bitmap.recycle()
    }
}
