package com.moe.starflow.translate.screenshot

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ScreenshotGeometryTest {
    @Test
    fun allFourRotationsMapCropCornersBackToTheirExactDisplayPixels() {
        for (rotation in 0..3) {
            val landscape = rotation % 2 == 1
            val display = DisplayGeometry(if (landscape) 3008 else 1880, if (landscape) 1880 else 3008, rotation, 320)
            val mapping = requireNotNull(ScreenshotMapping.create(display, RectF(100f, 200f, 700f, 900f)))
            assertEquals(Rect(100, 200, 700, 900), mapping.toDisplayRect(Rect(0, 0, 600, 700)))
            assertEquals(Rect(699, 899, 700, 900), mapping.toDisplayRect(Rect(599, 699, 600, 700)))
            assertTrue(mapping.matchesWindow(600, 700, 100, 200))
            assertFalse(mapping.matchesWindow(601, 700, 100, 200))
            assertFalse(mapping.matchesWindow(600, 700, 101, 200))
        }
    }

    @Test
    fun rotation180DensityAndResolutionChangesRejectAnOtherwiseSameSizedFrame() {
        val display = DisplayGeometry(40, 60, 0, 320)
        val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
        val data = ScreenshotManager.createData(CapturedScreen(bitmap, display), null, Point())
        assertTrue(data.isCurrent(display))
        assertFalse(data.isCurrent(display.copy(rotation = 2)))
        assertFalse(data.isCurrent(display.copy(densityDpi = 400)))
        assertFalse(data.isCurrent(display.copy(width = 60, height = 40, rotation = 1)))
        data.recycle()
    }

    @Test
    fun outOfBoundsCropIsRejectedInsteadOfClampedOrConvertedToFullScreen() {
        val display = DisplayGeometry(40, 60, 0, 320)
        for (crop in listOf(RectF(-1f, 0f, 10f, 20f), RectF(30f, 0f, 50f, 20f),
            RectF(0f, 50f, 20f, 70f), RectF(10f, 10f, 10f, 20f), RectF(Float.NaN, 0f, 10f, 20f))) {
            val full = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
            val data = ScreenshotManager.createData(CapturedScreen(full, display), crop, Point())
            assertNull(data.mapping)
            assertFalse(data.isCurrent(display))
            data.recycle()
        }
    }

    @Test
    fun fractionalCropUsesTheSameIntegerEdgesForBitmapAndWindow() {
        val display = DisplayGeometry(4, 6, 0, 160)
        val full = Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888)
        val crop = RectF(0.9f, 1.9f, 2.1f, 4.1f)
        val data = ScreenshotManager.createData(CapturedScreen(full, display), crop, Point())
        assertEquals(Rect(0, 1, 2, 4), data.mapping?.rect())
        assertEquals(2, data.croppedBitmap?.width)
        assertEquals(3, data.croppedBitmap?.height)
        crop.set(1f, 1f, 3f, 3f)
        assertEquals(Rect(0, 1, 2, 4), data.mapping?.rect())
        assertTrue(data.isCurrent(display))
        data.recycle()
    }

    @Test
    fun cropCoveringWholeImmutableBitmapHasIndependentOwnership() {
        val original = Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888)
        val full = original.copy(Bitmap.Config.ARGB_8888, false)
        original.recycle()
        val display = DisplayGeometry(4, 6, 0, 160)
        val data = ScreenshotManager.createData(CapturedScreen(full, display), RectF(0f, 0f, 4f, 6f), Point())
        assertNotSame(full, data.croppedBitmap)
        full.recycle()
        assertFalse(requireNotNull(data.croppedBitmap).isRecycled)
        data.recycle()
    }

    @Test
    fun unknownOrWrongSizeFrameCannotBeReinterpretedAsTheCurrentDisplay() {
        val display = DisplayGeometry(4, 6, 0, 160)
        val full = Bitmap.createBitmap(6, 4, Bitmap.Config.ARGB_8888)
        val data = ScreenshotManager.createData(CapturedScreen(full, display), null, Point())
        assertFalse(data.isCurrent(display))
        assertNull(data.mapping)
        data.recycle()
    }

    @Test
    fun cachedPositionsRequireIdenticalSourcePixelsEvenWhenSizeAndRotationMatch() {
        val source = Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888)
        val cached = source.copy(Bitmap.Config.ARGB_8888, true)
        val crop = Rect(0, 0, 4, 6)
        assertTrue(ScreenshotMapping.canReuseCoordinates(source, cached, crop, crop))
        assertFalse(ScreenshotMapping.canReuseCoordinates(source, cached, Rect(1, 0, 5, 6), crop))
        assertFalse(ScreenshotMapping.canReuseCoordinates(source, cached, null, crop))
        cached.setPixel(1, 1, android.graphics.Color.BLACK)
        assertFalse(ScreenshotMapping.canReuseCoordinates(source, cached, crop, crop))
        source.recycle()
        cached.recycle()
    }

    @Test
    fun displayPixelOriginRemainsOnTheLeftInRtlLayouts() {
        val gravity = OverlayWindow.fullDisplayParams().gravity
        for (direction in listOf(android.view.View.LAYOUT_DIRECTION_LTR, android.view.View.LAYOUT_DIRECTION_RTL)) {
            val absolute = android.view.Gravity.getAbsoluteGravity(gravity, direction)
            assertEquals(android.view.Gravity.LEFT, absolute and android.view.Gravity.HORIZONTAL_GRAVITY_MASK)
        }
    }

    @Test
    fun copyHitRegionsClipInBitmapSpaceBeforeTranslationToTheDisplay() {
        val mapping = requireNotNull(ScreenshotMapping.create(DisplayGeometry(40, 60, 0, 160), RectF(10f, 20f, 30f, 50f)))
        assertEquals(Rect(10, 20, 15, 25), mapping.toDisplayRect(Rect(-5, -5, 5, 5)))
        assertNull(mapping.toDisplayRect(Rect(30, 40, 35, 45)))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun parallelModesOwnDifferentFramesAndOldCallbacksCannotJoinANewRequest() = runTest {
        val game = mutableListOf<ScreenshotData>()
        val manga = mutableListOf<ScreenshotData>()
        val gameJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ScreenshotManager.screenshotsFor(ScreenshotTarget.GAME).collect { game.add(it) }
        }
        val mangaJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ScreenshotManager.screenshotsFor(ScreenshotTarget.MANGA).collect { manga.add(it) }
        }
        val oldId = ScreenshotManager.newRequestId()
        val newId = ScreenshotManager.newRequestId()
        val gameData = ScreenshotData(Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888), null, requestId = oldId)
        val mangaData = ScreenshotData(Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888), null, requestId = newId)
        assertTrue(ScreenshotManager.emitScreenshot(gameData, ScreenshotTarget.GAME))
        assertTrue(ScreenshotManager.emitScreenshot(mangaData, ScreenshotTarget.MANGA))
        assertEquals(listOf(gameData), game)
        assertEquals(listOf(mangaData), manga)
        assertTrue(gameData.belongsToRequest(oldId))
        assertFalse(gameData.belongsToRequest(newId))
        assertFalse(gameData.belongsToRequest(-1))
        gameData.recycle()
        assertFalse(mangaData.fullBitmap.isRecycled)
        mangaData.recycle()
        gameJob.cancel()
        mangaJob.cancel()
        val dropped = ScreenshotData(Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888), null)
        assertFalse(ScreenshotManager.emitScreenshot(dropped, ScreenshotTarget.GAME))
        assertTrue(dropped.fullBitmap.isRecycled)
    }
}
