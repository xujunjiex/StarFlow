package com.moe.starflow.manga.config

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MangaTextSpacingTest {
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("manga-spacing-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun newPreferencesDefaultToTwentyPercent() {
        val spacing = MangaTextSpacing.read(prefs)
        assertEquals(20, spacing.trackingPercent)
        assertEquals(20, spacing.leadingPercent)
        assertEquals(0.2f, spacing.trackingRatio, 0f)
        assertEquals(0.2f, spacing.leadingRatio, 0f)
    }

    @Test
    fun legacyZeroDefaultsMigrateTogether() {
        prefs.edit().putInt(MangaTextSpacing.KEY_TRACKING, 0)
            .putInt(MangaTextSpacing.KEY_LEADING, 0).commit()
        assertEquals(MangaTextSpacing.Spacing(20, 20), MangaTextSpacing.read(prefs))
        assertEquals(20, prefs.getInt(MangaTextSpacing.KEY_TRACKING, -1))
        assertEquals(20, prefs.getInt(MangaTextSpacing.KEY_LEADING, -1))
    }

    @Test
    fun migrationRetainsNonzeroChoices() {
        prefs.edit().putInt(MangaTextSpacing.KEY_TRACKING, 37)
            .putInt(MangaTextSpacing.KEY_LEADING, 60).commit()
        assertEquals(MangaTextSpacing.Spacing(37, 60), MangaTextSpacing.read(prefs))
    }

    @Test
    fun zeroChosenAfterMigrationIsPreserved() {
        MangaTextSpacing.read(prefs)
        prefs.edit().putInt(MangaTextSpacing.KEY_TRACKING, 0)
            .putInt(MangaTextSpacing.KEY_LEADING, 0).commit()
        assertEquals(MangaTextSpacing.Spacing(0, 0), MangaTextSpacing.read(prefs))
        assertEquals(0, MangaTextSpacing.percent(prefs, MangaTextSpacing.KEY_TRACKING))
        assertEquals(0, MangaTextSpacing.percent(prefs, MangaTextSpacing.KEY_LEADING))
    }
}
