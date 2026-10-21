package com.moe.starflow.me.settings

import android.content.Intent
import android.os.Looper
import androidx.preference.Preference
import com.google.android.material.tabs.TabLayout
import com.moe.starflow.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PersonalizationSectionsTest {
    private fun launch(key: String? = null) = Robolectric.buildActivity(
        SettingPageActivity::class.java,
        Intent(RuntimeEnvironment.getApplication(), SettingPageActivity::class.java).apply {
            putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_PERSONALIZATION)
            key?.let { putExtra(SettingPageActivity.EXTRA_PREFERENCE_KEY, it) }
        }
    ).setup().also { shadowOf(Looper.getMainLooper()).idle() }

    private fun screen(activity: SettingPageActivity): PersonalizationConfig =
        activity.supportFragmentManager.fragments.filterIsInstance<PersonalizationConfig>().single()

    @Test
    fun defaultsToGeneralAndSwitchesToMangaControls() {
        val activity = launch().get()
        val fragment = screen(activity)
        val tabs = fragment.requireView().findViewById<TabLayout>(R.id.personalization_tabs)
        assertEquals(1, tabs.selectedTabPosition)
        assertTrue(fragment.findPreference<Preference>("personalization_floating")!!.isVisible)
        assertFalse(fragment.findPreference<Preference>("personalization_manga_result")!!.isVisible)
        tabs.getTabAt(2)!!.select()
        assertTrue(fragment.findPreference<Preference>("personalization_manga_result")!!.isVisible)
        assertFalse(fragment.findPreference<Preference>("personalization_floating")!!.isVisible)
        assertFalse(fragment.findPreference<Preference>("personalization_game_result")!!.isVisible)
        assertNotNull(fragment.findPreference<Preference>(PpRegionSizeFilterPreferences.KEY_THRESHOLDS))
    }

    @Test
    fun preferenceDeepLinkSelectsItsMangaSection() {
        val activity = launch(PpRegionSizeFilterPreferences.KEY_THRESHOLDS).get()
        val fragment = screen(activity)
        assertEquals(2, fragment.requireView().findViewById<TabLayout>(R.id.personalization_tabs).selectedTabPosition)
        assertTrue(fragment.findPreference<Preference>("personalization_manga_result")!!.isVisible)
    }

    @Test
    fun activityRecreationPreservesSelectedTabAndSingleSettingsFragment() {
        val controller = launch()
        screen(controller.get()).requireView().findViewById<TabLayout>(R.id.personalization_tabs)
            .getTabAt(0)!!.select()
        controller.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        val restored = screen(controller.get())
        assertEquals(0, restored.requireView().findViewById<TabLayout>(R.id.personalization_tabs).selectedTabPosition)
        assertTrue(restored.findPreference<Preference>("personalization_game_result")!!.isVisible)
        assertFalse(restored.findPreference<Preference>("personalization_floating")!!.isVisible)
    }
}
