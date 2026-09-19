package com.moe.starflow.mangaimport.reader

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.moe.starflow.R
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 调色（颜色矫正）面板的**控件归位**守卫。
 *
 * 真实反馈：点「重置」后滑块/开关/百分比一动不动，得关掉面板重进才更新。
 *
 * 根因是这条框架边界：`SeekBar` 的监听只在 `fromUser=true` 时触发（见
 * [ReaderMenuSheet] 里的 `slider{}`），而重置走的是**程序化**改值 —— 于是
 * ① 滑块与标签不刷新；② 右侧「处理后」预览也不刷新；③ 带着滤镜打开面板时预览同样是没处理的图
 * （原实现在填充控件**之前**就刷了一次预览，然后不再刷）。
 *
 * 这里用真实 `sheet_reader_menu.xml` 走完整 `onCreateView`（同 [ReaderMenuSheetTranslateTest]），
 * 断言的就是用户看得见的那几个控件。
 */
@RunWith(RobolectricTestRunner::class)
class ReaderMenuSheetColorResetTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private var resetCalls = 0

    /** 面板主动推给宿主的滤镜变更（重置**不该**推中间态，见 `reset_doesNotPushIntermediateStates`）。 */
    private val pushed = mutableListOf<ReaderColorFilter>()

    private fun sheet(filter: ReaderColorFilter): ReaderMenuSheet =
        ReaderMenuSheet(
            ReaderMenuState(colorFilter = filter),
            ReaderMenuCallbacks(
                onMode = {}, onAnimation = {}, onBackground = {}, onAutoTurn = { _, _ -> },
                onColorFilterChanged = { pushed += it },
                onResetColor = { resetCalls++ },
                onRotate = {}, onDownload = {}, onSettings = {}
            )
        ).apply {
            // 语言下拉要读真实 prefs，与本测试无关（同 ReaderMenuSheetTranslateTest 的处理）
            languagesList = { _, _ -> emptyList() }
        }

    /** 走真实 `onCreateView`：调色面板的控件与接线都是生产的那一份。 */
    private fun buildPanel(s: ReaderMenuSheet): View {
        // 必须真正 attached：onCreateView 里会读 resources（applyPanelTheme 算圆角）
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
            .create().start().resume()
        controller.get().supportFragmentManager
            .beginTransaction().add(s, "under-test").commitNow()
        return s.view!!
    }

    private val adjusted = ReaderColorFilter(
        brightness = 0.3f,
        contrast = -0.5f,
        isInverted = true,
        isGrayscale = false,
        isBookBackground = true
    )

    private fun seek(v: View, id: Int) = v.findViewById<SeekBar>(id)
    private fun text(v: View, id: Int) = v.findViewById<TextView>(id).text.toString()
    private fun switch(v: View, id: Int) = v.findViewById<Switch>(id).isChecked

    /** 「处理后」预览当前上的滤镜矩阵（null = 没上滤镜）。 */
    private fun previewMatrix(v: View): FloatArray? {
        val f = v.findViewById<ImageView>(R.id.iv_proc_preview).colorFilter as? ColorMatrixColorFilter
            ?: return null
        val out = ColorMatrix()
        f.getColorMatrix(out)
        return out.array
    }

    private fun matrixOf(f: ReaderColorFilter) = f.toColorMatrix().array

    // ===== 打开面板：把宿主当前滤镜灌进控件与预览 =====

    @Test
    fun openWithFilter_populatesSlidersSwitchesAndPreview() {
        val v = buildPanel(sheet(adjusted))

        assertEquals("亮度滑块必须反映当前滤镜", 30, seek(v, R.id.sb_brightness).progress)
        assertEquals("对比度滑块必须反映当前滤镜", -50, seek(v, R.id.sb_contrast).progress)
        assertEquals("30%", text(v, R.id.tv_brightness_value))
        assertEquals("-50%", text(v, R.id.tv_contrast_value))
        assertTrue(switch(v, R.id.sw_invert))
        assertFalse(switch(v, R.id.sw_grayscale))
        assertTrue(switch(v, R.id.sw_book))

        // 预览必须是**已处理**的图（带滤镜打开时刷成无滤镜 = 用户看到的对比是假的）
        assertNotNull("右侧预览必须上滤镜", previewMatrix(v))
        assertArrayEquals(
            "预览滤镜必须等于当前滤镜",
            matrixOf(adjusted),
            previewMatrix(v)!!,
            0.0001f
        )
        // 打开面板只是回显宿主状态，不该反向推一次
        assertTrue("打开面板不该 push 给宿主，实际 ${pushed.size} 次", pushed.isEmpty())
    }

    // ===== 重置：控件与预览必须**当场**归位 =====

    @Test
    fun reset_returnsSlidersSwitchesAndPreviewImmediately() {
        val v = buildPanel(sheet(adjusted))

        v.findViewById<View>(R.id.btn_reset_color).performClick()

        assertEquals("亮度滑块必须当场归零", 0, seek(v, R.id.sb_brightness).progress)
        assertEquals("对比度滑块必须当场归零", 0, seek(v, R.id.sb_contrast).progress)
        assertEquals("0%", text(v, R.id.tv_brightness_value))
        assertEquals("0%", text(v, R.id.tv_contrast_value))
        assertFalse("反色必须关掉", switch(v, R.id.sw_invert))
        assertFalse("灰度必须关掉", switch(v, R.id.sw_grayscale))
        assertFalse("护眼必须关掉", switch(v, R.id.sw_book))
        assertArrayEquals(
            "预览必须当场回到无滤镜",
            matrixOf(ReaderColorFilter.EMPTY),
            previewMatrix(v)!!,
            0.0001f
        )
        assertEquals("宿主侧的重置回调必须只调一次", 1, resetCalls)
    }

    /**
     * 归位过程中**不能**把「改了一半」的状态推给宿主（先归零亮度、对比度/开关还没归位的中间态）。
     * 那会写盘 + 对可见页上中间滤镜 —— 用户看到的是闪一下，库里留下的是错值。
     */
    @Test
    fun reset_doesNotPushIntermediateStates() {
        val v = buildPanel(sheet(adjusted))

        v.findViewById<View>(R.id.btn_reset_color).performClick()

        assertTrue(
            "重置只应走 onResetColor，不该经 onColorFilterChanged 推中间态，" +
                "实际推了 ${pushed.size} 次：${pushed.map { it.brightness to it.contrast }}",
            pushed.isEmpty()
        )
    }
}
