package com.moe.starflow.llamacpp

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import com.moe.starflow.R

/**
 * 模型行布局的**交互/观感**回归守卫（Robolectric inflate 真实布局）。
 *
 * 锁死 2026-09 用户明确的要求（改回去就会红）：
 *  1. **不要「设为当前使用」按钮** —— 点卡片即设为当前（按钮集合必须恰好是那 6 个主/次操作）；
 *  2. 按钮**右对齐、按内容宽度**（wrap_content，不占满整行、不用 weight）；
 *  3. 删除按钮**不是深红实心块** —— 中性胶囊底 + `@color/red` 文字；
 *  4. 选中态是 RadioButton 且不可点（只能通过整行点击切换，避免出现"多个都选中"的错觉）。
 */
@RunWith(RobolectricTestRunner::class)
class LlamaCppRowLayoutTest {

    private fun inflateRow(): View {
        val ctx = RuntimeEnvironment.getApplication()
        return LayoutInflater.from(ctx).inflate(R.layout.item_llamacpp_model_row, null)
    }

    @Test
    fun `按钮集合恰好是六个主次操作_没有设为当前按钮`() {
        val row = inflateRow()
        val actions = row.findViewById<LinearLayout>(R.id.rowActions)
        val ids = (0 until actions.childCount).map { actions.getChildAt(it).id }
        assertEquals(
            "新增/删除操作按钮都要同步更新这个断言；「设为当前使用」不允许作为按钮存在",
            listOf(
                R.id.rowDownload, R.id.rowResume, R.id.rowPause,
                R.id.rowCancel, R.id.rowParams, R.id.rowDelete,
            ),
            ids,
        )
    }

    @Test
    fun `按钮右对齐且按内容宽度不占满整行`() {
        val row = inflateRow()
        val actions = row.findViewById<LinearLayout>(R.id.rowActions)
        // 用绝对重力比较（`end` 会带 RELATIVE_LAYOUT_DIRECTION 位，直接比常量不可靠）
        val absolute = Gravity.getAbsoluteGravity(actions.gravity, actions.layoutDirection)
        assertEquals(
            "按钮行必须右对齐",
            Gravity.RIGHT,
            absolute and Gravity.HORIZONTAL_GRAVITY_MASK,
        )
        for (i in 0 until actions.childCount) {
            val child = actions.getChildAt(i)
            val lp = child.layoutParams as LinearLayout.LayoutParams
            assertEquals("${child.id} 应是 wrap_content", ViewGroup.LayoutParams.WRAP_CONTENT, lp.width)
            assertEquals("${child.id} 不应使用 weight 撑满", 0f, lp.weight, 0.0001f)
        }
    }

    @Test
    fun `删除按钮用中性底加红字而不是深红实心块`() {
        val ctx = RuntimeEnvironment.getApplication()
        val row = inflateRow()
        val del = row.findViewById<TextView>(R.id.rowDelete)
        assertEquals(
            "删除文字色必须是项目调色板的红（浅色/夜间都有定义），不是 btn_delete 的深红实心块",
            ContextCompat.getColor(ctx, R.color.red),
            del.currentTextColor,
        )
        // 底图必须是中性胶囊（@drawable/btn_neutral）：形状 drawable，且不是纯色块
        assertTrue("删除按钮底图应是胶囊形状", del.background is android.graphics.drawable.GradientDrawable)
    }

    @Test
    fun `选中态用不可点的 RadioButton`() {
        val row = inflateRow()
        val radio = row.findViewById<RadioButton>(R.id.rowActive)
        assertFalse("RadioButton 自身不可点：切换只走整行点击，避免多点选中", radio.isClickable)
        assertFalse(radio.isFocusable)
    }
}
