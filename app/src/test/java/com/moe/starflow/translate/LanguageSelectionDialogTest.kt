package com.moe.starflow.translate

import android.content.Context
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import com.moe.starflow.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * LanguageSelectionDialog 置灰语言交互契约：
 * 点击 enabled=false 的置灰语言 → 触发 onDisabledClick 且对话框保持打开（不误选）；
 * 点击 enabled 语言 → 触发 onLanguageSelected 并关闭对话框。
 * 防止未来改动把置灰项当成可选项选中。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")  // Robolectric 4.11 最高支持 targetSdk 34（项目 targetSdk 35）
class LanguageSelectionDialogTest {

    private fun Context.showDialog(
        locales: List<CustomLocale> = listOf(CustomLocale.getInstance("ja"), CustomLocale.getInstance("ru")),
        enabled: List<Boolean>? = listOf(true, false),
        onDisabledClick: (CustomLocale) -> Unit,
        onLanguageSelected: (CustomLocale) -> Unit
    ): android.app.AlertDialog {
        LanguageSelectionDialog(
            this, 1,
            locales,
            enabled = enabled,
            onDisabledClick = onDisabledClick,
            onLanguageSelected = onLanguageSelected
        ).show()
        return ShadowDialog.getLatestDialog() as android.app.AlertDialog
    }

    private fun clickOn(adapterView: ListView, position: Int) {
        adapterView.performItemClick(
            adapterView.adapter.getView(position, null, adapterView),
            position,
            adapterView.adapter.getItemId(position)
        )
    }

    private fun visibleNames(dialog: android.app.AlertDialog): List<String> {
        val listView = dialog.findViewById<ListView>(R.id.languages_list)
        return (0 until listView.adapter.count).map { position ->
            listView.adapter.getView(position, null, listView)
                .findViewById<TextView>(android.R.id.text1).text.toString()
        }
    }


    @Test
    fun disabledItemClick_invokesOnDisabledClick_keepsDialogOpen() {
        val context = RuntimeEnvironment.getApplication()
        var disabledClicked: CustomLocale? = null
        var selected: CustomLocale? = null
        val dialog = context.showDialog(
            onDisabledClick = { disabledClicked = it },
            onLanguageSelected = { selected = it }
        )
        val listView = dialog.findViewById<ListView>(R.id.languages_list)
        clickOn(listView, 1)  // ru = 置灰项
        assertEquals("ru", disabledClicked?.getOriCode())
        assertNull("置灰语言不应触发 onLanguageSelected", selected)
        assertTrue("点击置灰语言后对话框不应关闭", dialog.isShowing)
    }

    @Test
    fun enabledItemClick_invokesOnLanguageSelected_dismissesDialog() {
        val context = RuntimeEnvironment.getApplication()
        var disabledClicked: CustomLocale? = null
        var selected: CustomLocale? = null
        val dialog = context.showDialog(
            onDisabledClick = { disabledClicked = it },
            onLanguageSelected = { selected = it }
        )
        val listView = dialog.findViewById<ListView>(R.id.languages_list)
        clickOn(listView, 0)  // ja = 可用项
        assertEquals("ja", selected?.getOriCode())
        assertNull("可用语言不应触发 onDisabledClick", disabledClicked)
        assertFalse("点击可用语言后对话框应关闭", dialog.isShowing)
    }

    @Test
    fun searchFiltersByCodeAndKeepsEnabledMapping() {
        val context = RuntimeEnvironment.getApplication()
        var disabledClicked: CustomLocale? = null
        var selected: CustomLocale? = null
        val dialog = context.showDialog(
            onDisabledClick = { disabledClicked = it },
            onLanguageSelected = { selected = it }
        )
        val searchInput = dialog.findViewById<EditText>(R.id.language_search_input)
        val listView = dialog.findViewById<ListView>(R.id.languages_list)

        searchInput.setText("ru")
        assertEquals(1, listView.adapter.count)
        clickOn(listView, 0)

        assertEquals("ru", disabledClicked?.getOriCode())
        assertNull("过滤后置灰项仍不应触发选择", selected)
        assertTrue("过滤后点击置灰语言不应关闭对话框", dialog.isShowing)
    }

    @Test
    fun commonLanguagesComeFirst_remainingSortedAlphabetically() {
        val context = RuntimeEnvironment.getApplication()
        val dialog = context.showDialog(
            locales = listOf("sv", "ar", "th", "ja", "en", "zh", "vi", "af").map { CustomLocale.getInstance(it) },
            enabled = null,
            onDisabledClick = {},
            onLanguageSelected = {}
        )

        assertEquals(
            listOf(
                "Chinese", "English", "Japanese",
                "Afrikaans", "Arabic", "Swedish", "Thai", "Vietnamese"
            ),
            visibleNames(dialog)
        )
    }

    @Test
    fun noMatchesShowsEmptyHint() {
        val context = RuntimeEnvironment.getApplication()
        val dialog = context.showDialog(
            onDisabledClick = {},
            onLanguageSelected = {}
        )
        val searchInput = dialog.findViewById<EditText>(R.id.language_search_input)
        val listView = dialog.findViewById<ListView>(R.id.languages_list)
        val emptyHint = dialog.findViewById<TextView>(R.id.languages_empty)

        searchInput.setText("not-a-language")
        assertEquals(0, listView.adapter.count)
        assertEquals(TextView.VISIBLE, emptyHint.visibility)
    }
}
