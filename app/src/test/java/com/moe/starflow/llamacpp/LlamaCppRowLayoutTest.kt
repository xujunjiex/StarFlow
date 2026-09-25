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
import org.junit.Assert.assertNull
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

    /**
     * 卡片间距（2026-09 用户反馈「相邻卡片重叠」）。
     *
     * 行间距来自行模板的 `layout_marginBottom`，而 XML 里的 `layout_*` 属性**只有带父容器 inflate
     * 才会被解析成 LayoutParams**；`inflate(inflater, null, false)` 之后 `addView` 会补一份
     * margin=0 的默认 params → 相邻卡片零间距、20dp 圆角贴在一起看着像重叠。
     * 之前内置只有 1 张卡（不挨着）所以看不出来，加到 2 张就暴露了。
     */
    @Test
    fun `相邻卡片必须保留间距`() {
        val ctx = RuntimeEnvironment.getApplication()
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // 正确姿势：走 Fragment 用的同一个入口
        val row = com.moe.starflow.me.model.inflateModelRow(LayoutInflater.from(ctx), container)
        container.addView(row.root)
        val lp = row.root.layoutParams as LinearLayout.LayoutParams
        assertTrue("行根必须保留 layout_marginBottom 作为卡片间距，实际=${lp.bottomMargin}", lp.bottomMargin > 0)

        // 反证：null-parent inflate 拿不到 XML 的 margin —— 这就是「卡片重叠」的成因
        val broken = LayoutInflater.from(ctx).inflate(R.layout.item_llamacpp_model_row, null)
        assertNull("null-parent inflate 不会有 LayoutParams", broken.layoutParams)
        container.addView(broken)
        assertEquals(
            "null-parent inflate 的卡片间距必然为 0（故禁止这么写）",
            0,
            (broken.layoutParams as LinearLayout.LayoutParams).bottomMargin,
        )
    }
}
