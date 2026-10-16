package com.moe.starflow.novel.reader

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelAppearanceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("novel_appearance_test", Context.MODE_PRIVATE)

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun `透明完成色保存后重读仍隐藏文本与插图且恢复默认还原各自透明度`() {
        NovelAppearance.setStatusColor(prefs, NovelHighlight.COMPLETED, 0)
        val reopened = context.getSharedPreferences("novel_appearance_test", Context.MODE_PRIVATE)
        val hidden = NovelAppearance.statusColors(reopened)
        assertEquals(0, hidden.color(NovelHighlight.COMPLETED))
        assertEquals(0, hidden.border(NovelHighlight.COMPLETED))
        NovelAppearance.setStatusColor(prefs, NovelHighlight.COMPLETED, null)
        val restored = NovelAppearance.statusColors(prefs)
        assertEquals(0x3D43A047, restored.color(NovelHighlight.COMPLETED))
        assertEquals(0xFF43A047.toInt(), restored.border(NovelHighlight.COMPLETED))
    }

    @Test fun `透明选中色也隐藏插图填充而全部恢复不会改自定义背景`() {
        NovelAppearance.saveCustom(prefs, Color.BLACK, Color.WHITE)
        NovelAppearance.setStatusColor(prefs, NovelHighlight.SELECTION, 0)
        assertEquals(0, NovelAppearance.statusColors(prefs).selectionFill)
        NovelAppearance.resetStatusColors(prefs)
        assertEquals(NovelStatusColors(), NovelAppearance.statusColors(prefs))
        assertEquals(NovelAppearance.CUSTOM_MODE, NovelPanelStyle.background(prefs))
        assertEquals(Color.BLACK, NovelAppearance.customBackground(prefs))
    }

    @Test fun `小说自定义配色不向漫画背景键写入新值且切回预设保留自定义颜色`() {
        prefs.edit().putInt("reader_background", 2).commit()
        NovelAppearance.saveCustom(prefs, 0x00112233, 0x00EEEEEE)
        assertEquals(2, prefs.getInt("reader_background", -1))
        assertEquals(6, NovelPanelStyle.background(prefs))
        assertEquals(0xFF112233.toInt(), NovelPanelStyle.backgroundColor(6, prefs))
        assertEquals(0xFFEEEEEE.toInt(), NovelPanelStyle.textColor(6, prefs))
        assertTrue(NovelPanelStyle.isDarkBackground(6, prefs))

        NovelPanelStyle.setBackground(prefs, 3)
        assertEquals(3, NovelPanelStyle.background(prefs))
        assertEquals(Color.WHITE, NovelPanelStyle.backgroundColor(3, prefs))
        assertEquals(0xFF1A1A1A.toInt(), NovelPanelStyle.textColor(3, prefs))
        assertEquals(0xFF112233.toInt(), NovelAppearance.customBackground(prefs))
        NovelPanelStyle.setBackground(prefs, 6)
        assertEquals(3, prefs.getInt("reader_background", -1))
        assertEquals(0xFF112233.toInt(), NovelPanelStyle.backgroundColor(6, prefs))
    }

    @Test fun `旧自动背景仍兼容且排版重置不清除独立配色`() {
        prefs.edit().putInt("reader_background", 5).commit()
        assertEquals(5, NovelPanelStyle.background(prefs))
        assertEquals(NovelPanelStyle.backgroundColor(0), NovelPanelStyle.backgroundColor(5))
        NovelAppearance.saveCustom(prefs, Color.WHITE, Color.BLUE)
        NovelAppearance.setStatusColor(prefs, NovelHighlight.ACTIVE, Color.MAGENTA)
        NovelPanelStyle.resetTypography(prefs)
        assertEquals(Color.BLUE, NovelAppearance.customText(prefs))
        assertEquals(Color.MAGENTA, NovelAppearance.statusColors(prefs).color(NovelHighlight.ACTIVE))
    }

    @Test fun `透明的高优先级状态不透出失败或翻译中的颜色且同状态换色立即生效`() {
        val view = NovelScrollAdapter.ParagraphView(context)
        val palette = NovelStatusColors(colors = listOf(0, Color.YELLOW, Color.RED, Color.GREEN))
        fun bind(selected: Boolean = false, failed: Boolean = false, active: Boolean = false,
                 colors: NovelStatusColors = palette) {
            view.bind("状态优先级正文", NovelPageView.DEFAULT_STYLE, Color.BLACK, Color.WHITE,
                selected = selected, failed = failed, active = active, recent = true, statusColors = colors)
        }
        fun color() = (view.background as GradientDrawable).color!!.defaultColor
        bind(selected = true, failed = true, active = true)
        assertEquals(0, color())
        bind(failed = true, active = true)
        assertEquals(Color.RED, color())
        bind(active = true)
        assertEquals(Color.YELLOW, color())
        bind()
        assertEquals(Color.GREEN, color())
        bind(colors = palette.copy(colors = listOf(0, Color.YELLOW, Color.RED, 0)))
        assertEquals(0, color())
    }
}
