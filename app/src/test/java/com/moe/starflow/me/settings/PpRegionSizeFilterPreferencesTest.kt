package com.moe.starflow.me.settings

import android.app.AlertDialog
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.moe.starflow.R
import com.moe.starflow.manga.engine.PpRegionSizeFilter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
class PpRegionSizeFilterPreferencesTest {
    class FilterScreen : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.personalization, rootKey)
            PpRegionSizeFilterPreferences.bind(preferenceScreen)
        }
    }

    private fun launch(): FilterScreen {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).create().start().resume()
        val fragment = FilterScreen()
        controller.get().supportFragmentManager.beginTransaction().add(fragment, "filter-settings").commitNow()
        val prefs = fragment.preferenceManager.sharedPreferences!!
        prefs.edit().remove(PpRegionSizeFilter.KEY_SMALL).remove(PpRegionSizeFilter.KEY_LARGE).commit()
        fragment.findPreference<SwitchPreference>(PpRegionSizeFilter.KEY_ENABLED)!!.isChecked = false
        return fragment
    }

    @Test
    fun controlsAreLastInMangaCategoryAndThresholdsRequireEnabledSwitch() {
        val fragment = launch()
        val toggle = fragment.findPreference<SwitchPreference>(PpRegionSizeFilter.KEY_ENABLED)!!
        val thresholds = fragment.findPreference<Preference>(PpRegionSizeFilterPreferences.KEY_THRESHOLDS)!!
        val category = toggle.parent!!
        assertEquals(fragment.getString(R.string.manga_result_setting_title), category.title.toString())
        assertEquals(PpRegionSizeFilter.KEY_ENABLED, category.getPreference(category.preferenceCount - 2).key)
        assertEquals(PpRegionSizeFilterPreferences.KEY_THRESHOLDS, category.getPreference(category.preferenceCount - 1).key)
        assertFalse(thresholds.isEnabled)
        toggle.isChecked = true
        assertTrue(thresholds.isEnabled)
        assertTrue(PpRegionSizeFilter.read(fragment.preferenceManager.sharedPreferences!!).enabled)
    }

    private fun openDialog(fragment: FilterScreen): Pair<AlertDialog, List<SeekBar>> {
        fragment.findPreference<SwitchPreference>(PpRegionSizeFilter.KEY_ENABLED)!!.isChecked = true
        fragment.findPreference<Preference>(PpRegionSizeFilterPreferences.KEY_THRESHOLDS)!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog() ?: error("No threshold dialog")
        val sliders = mutableListOf<SeekBar>()
        fun walk(view: View) {
            if (view is SeekBar) sliders += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(dialog.window!!.decorView)
        assertEquals(2, sliders.size)
        return dialog to sliders
    }

    @Test
    fun savingIndependentThresholdsUpdatesPersistedRecognizerConfiguration() {
        val fragment = launch()
        val (dialog, sliders) = openDialog(fragment)
        assertEquals(PpRegionSizeFilter.DEFAULT_TENTHS - PpRegionSizeFilter.MIN_TENTHS, sliders[0].progress)
        sliders[0].progress = 15 - PpRegionSizeFilter.MIN_TENTHS
        sliders[1].progress = 62 - PpRegionSizeFilter.MIN_TENTHS
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val config = PpRegionSizeFilter.read(fragment.preferenceManager.sharedPreferences!!)
        assertEquals(1.5, config.smallTolerance, 0.0)
        assertEquals(6.2, config.largeTolerance, 0.0)
        assertTrue(config.enabled)
        val summary = fragment.findPreference<Preference>(PpRegionSizeFilterPreferences.KEY_THRESHOLDS)!!.summary.toString()
        assertEquals(fragment.getString(R.string.pp_size_filter_thresholds_summary, 1.5, 6.2), summary)
    }

    @Test
    fun cancellingDialogDoesNotPersistSliderChanges() {
        val fragment = launch()
        val (dialog, sliders) = openDialog(fragment)
        sliders[0].progress = 0
        sliders[1].progress = sliders[1].max
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val config = PpRegionSizeFilter.read(fragment.preferenceManager.sharedPreferences!!)
        assertEquals(3.5, config.smallTolerance, 0.0)
        assertEquals(3.5, config.largeTolerance, 0.0)
    }

    @Test
    fun outOfRangeStoredThresholdsAreClampedToSliderRange() {
        val fragment = launch()
        val prefs = fragment.preferenceManager.sharedPreferences!!
        prefs.edit().putInt(PpRegionSizeFilter.KEY_SMALL, -200).putInt(PpRegionSizeFilter.KEY_LARGE, 900).commit()
        val config = PpRegionSizeFilter.read(prefs)
        assertEquals(0.5, config.smallTolerance, 0.0)
        assertEquals(8.0, config.largeTolerance, 0.0)
    }
}
