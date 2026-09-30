package com.moe.starflow.mangaimport.reader

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.moe.starflow.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 翻译面板**真实布局**的排版守卫（inflate `sheet_reader_menu.xml`，不看合成视图）。
 *
 * 起因（用户口径 2026-09-28）：「字体自动的那个按钮放到字体大小的标题后面」——
 * 「自动」是**字号这个设置的开关**，必须与标题成组（`字体大小 [自动] …… 12sp`），
 * 夹在数值左边会被当成"又一个值"。这类"控件顺序"的问题编译不报错、
 * 行为测试也看不出来（点击照旧生效），只能对真实布局做机械断言。
 */
@RunWith(RobolectricTestRunner::class)
class ReaderPanelLayoutTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun inflatePanel(): View =
        LayoutInflater.from(ctx).inflate(R.layout.sheet_reader_menu, FrameLayout(ctx), false)

    /** 「自动」必须**紧跟**「字体大小」标题（同一行、相邻）。 */
    @Test
    fun fontAutoButton_sitsRightAfterFontSizeTitle() {
        val root = inflatePanel()
        val title = root.findViewById<View>(R.id.tv_font_size_row)
        val auto = root.findViewById<View>(R.id.btn_font_auto)
        val value = root.findViewById<View>(R.id.tv_font_size_value)

        val row = title.parent as ViewGroup
        assertEquals(
            "「自动」必须与标题同一行（用户口径）",
            row.id, (auto.parent as View).id
        )
        assertEquals(
            "「自动」必须紧跟标题：标题 #${row.indexOfChild(title)}、自动 #${row.indexOfChild(auto)}",
            row.indexOfChild(title) + 1, row.indexOfChild(auto)
        )
        assertTrue(
            "数值仍在最右（在「自动」之后）",
            row.indexOfChild(value) > row.indexOfChild(auto)
        )
    }
}
