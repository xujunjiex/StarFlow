package com.moe.starflow.utils

import android.content.Context
import androidx.preference.PreferenceManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationExecutionModeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    @Test
    fun `legacy image flag migrates to image mode`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putInt(TranslationExecutionMode.LEGACY_KEY, 1)
            .commit()

        assertEquals(TranslationExecutionMode.IMAGE, TranslationExecutionMode.get(context))
        assertEquals(true, TranslationExecutionMode.isImage(context))
    }

    @Test
    fun `new key wins over legacy mirror and normalizes`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putInt(TranslationExecutionMode.LEGACY_KEY, 0)
            .putInt(TranslationExecutionMode.KEY, 99)
            .commit()

        assertEquals(TranslationExecutionMode.IMAGE, TranslationExecutionMode.get(context))
    }

    @Test
    fun `set writes new key and legacy mirror`() {
        TranslationExecutionMode.set(context, TranslationExecutionMode.OCR_ONLY)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        assertEquals(TranslationExecutionMode.OCR_ONLY, prefs.getInt(TranslationExecutionMode.KEY, -1))
        assertEquals(0, prefs.getInt(TranslationExecutionMode.LEGACY_KEY, -1))

        TranslationExecutionMode.set(context, TranslationExecutionMode.IMAGE)
        assertEquals(1, prefs.getInt(TranslationExecutionMode.LEGACY_KEY, -1))
    }

    @Test
    fun `default is text translation`() {
        assertEquals(TranslationExecutionMode.TRANSLATE, TranslationExecutionMode.get(context))
    }
}
