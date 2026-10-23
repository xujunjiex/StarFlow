package com.moe.starflow.utils

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DisplayConfigurationTest {
    @Test
    fun localizedContextFollowsTabletRotationAndWindowResize() {
        RuntimeEnvironment.setQualifiers("sw600dp-w960dp-h600dp-land-xhdpi")
        val app = RuntimeEnvironment.getApplication()
        var override: Configuration? = null
        val context = object : ContextWrapper(app) {
            override fun createConfigurationContext(configuration: Configuration): Context {
                override = Configuration(configuration)
                return super.createConfigurationContext(configuration)
            }
        }
        val localized = LanguageManager.applyLanguage(context)
        val language = localized.resources.configuration.locales[0]
        // 执行 Android 合并 Configuration 覆盖项的规则。Robolectric 不向已有的
        // ConfigurationContext 广播 setQualifiers，故直接提供两次真实配置输入。
        for ((width, height, density) in listOf(Triple(600, 960, 320), Triple(400, 600, 240))) {
            val incoming = Configuration(app.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_PORTRAIT
                screenWidthDp = width
                screenHeightDp = height
                densityDpi = density
            }
            incoming.updateFrom(requireNotNull(override))
            assertEquals(Configuration.ORIENTATION_PORTRAIT, incoming.orientation)
            assertEquals(width, incoming.screenWidthDp)
            assertEquals(height, incoming.screenHeightDp)
            assertEquals(density, incoming.densityDpi)
            assertEquals(language, incoming.locales[0])
        }
    }

    @Test
    fun tabletStartupDoesNotRequestPortraitCompatibilityMode() {
        RuntimeEnvironment.setQualifiers("sw600dp-w960dp-h600dp-land")
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            StartupOrientation.apply(controller.get())
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, controller.get().requestedOrientation)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun phoneStartupRetainsPortrait() {
        RuntimeEnvironment.setQualifiers("sw360dp-w640dp-h360dp-land")
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            StartupOrientation.apply(controller.get())
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, controller.get().requestedOrientation)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun destroyingLandscapeStartupDuringDelayDoesNotShowInitializationError() {
        RuntimeEnvironment.setQualifiers("sw600dp-w960dp-h600dp-land")
        org.robolectric.shadows.ShadowToast.reset()
        val controller = Robolectric.buildActivity(com.moe.starflow.launch.LaunchActivity::class.java)
            .create().start().resume()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
    }
}
