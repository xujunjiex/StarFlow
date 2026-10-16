package com.moe.starflow.novel.reader

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.moe.starflow.R
import com.moe.starflow.utils.ReaderDialogs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
class NovelColorDialogsTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test
    fun `配色窗口标签跟随活动阅读主题而不是草稿背景且色样保持小尺寸`() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        for (dark in listOf(false, true)) {
            NovelColorDialogs.customAppearance(activity, if (dark) Color.WHITE else Color.BLACK,
                Color.RED, dark = dark) { _, _ -> }
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val views = descendants(dialog.window!!.decorView)
            val label = views.filterIsInstance<TextView>().single {
                it.text.toString() == activity.getString(R.string.novel_custom_background_color)
            }
            assertEquals(if (dark) ReaderDialogs.LABEL_DARK else ReaderDialogs.LABEL_LIGHT, label.currentTextColor)
            val actionColor = dialog.getButton(AlertDialog.BUTTON_POSITIVE).currentTextColor
            assertEquals(if (dark) 0xFF55AEEA.toInt() else 0xFF247BAA.toInt(), actionColor)
            assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(actionColor,
                if (dark) 0xFF1C1C1E.toInt() else Color.WHITE) >= 4.5)
            val samples = views.filterIsInstance<NovelColorSwatch>()
            assertEquals(2, samples.size)
            val expected = (20 * activity.resources.displayMetrics.density + 0.5f).toInt()
            assertTrue(samples.all { it.layoutParams.width == expected && it.layoutParams.height == expected })
            dialog.dismiss()
        }
    }

    @Test
    fun `调色器只有小色样且十六进制输入文字始终不透明`() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        for (dark in listOf(false, true)) {
            NovelColorDialogs.picker(activity, dark, R.string.novel_status_completed, Color.WHITE,
                NovelTextRenderer.COLOR_LAST_TRANSLATED) { }
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val views = descendants(dialog.window!!.decorView)
            val input = views.filterIsInstance<EditText>().single()
            assertEquals(255, Color.alpha(input.currentTextColor))
            assertEquals(if (dark) ReaderDialogs.LABEL_DARK else ReaderDialogs.LABEL_LIGHT, input.currentTextColor)
            assertEquals(1, views.filterIsInstance<NovelColorSwatch>().size)
            dialog.dismiss()
        }
    }

    @Test
    fun `色样测量遵守宽高限制不会把预览条撑成大圆`() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val sample = NovelColorSwatch(activity)
        sample.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(24, View.MeasureSpec.EXACTLY))
        assertEquals(320, sample.measuredWidth)
        assertEquals(24, sample.measuredHeight)
    }
}
