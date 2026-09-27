package com.moe.starflow.novel.reader

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.moe.starflow.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **面板主题重喷的守卫测试**：切阅读背景（浅 ↔ 深）时，面板上每一处随主题着色的控件
 * 都必须**就地**跟着变 —— 不允许「关掉面板再打开才正常」。
 *
 * ### 这条测试存在的意义（它是会失败的测试，不是装饰）
 * `NovelPanelSheet.applyPanelTheme` 是一份**手写控件清单**，清单之外的控件永远不会被重喷。
 * 踩过的坑：底部 4 个 Tab 图标只在 `setTab()`（创建 / 点 Tab）里着色、翻译页筛选 chip 只在
 * 新建 / 点 chip 时着色 —— 切背景改了 `darkPanel` 之后没人重放它们，用户看到的就是
 * 「面板上有几处颜色不跟着变，必须关掉面板再打开才正常」。
 *
 * 所以：**以后给面板新增任何一组随深浅变色的控件，都必须接进 `applyPanelTheme`
 * （或它调用的重喷出口），否则这里会红。** 加控件的同时在这里补一条断言。
 *
 * ⚠️ 可读性前提：`ImageView.setColorFilter` 的颜色**读不回来**，所以 Tab 图标的着色走
 * `imageTintList`（视觉等价）；chip 的底色同理走 `backgroundTintList` 而不是程序化
 * `GradientDrawable.setColor`。别为了"省事"改回去，改回去这条测试立刻失效。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelPanelThemeTest {

    /** 挂上面板（走真实 onCreateView + 真实布局），返回它的根视图。 */
    private fun attach(): Pair<NovelPanelSheet, View> {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val state = NovelPanelState(chapters = emptyList())
        val callbacks = NovelPanelCallbacks(
            onReaderMode = {}, onAnimation = {}, onBackground = {},
            onLineSpacing = {}, onParagraphSpacing = {}, onPadding = {},
            onAutoTurn = { _, _ -> }, onRotate = {}, onSettings = {},
        )
        val sheet = NovelPanelSheet(state, callbacks)
        activity.supportFragmentManager.beginTransaction().add(sheet, "panel").commitNow()
        val view = sheet.view ?: error("面板视图没建出来")
        return sheet to view
    }

    /** Tab 格子里：第 0 个是选中态圆/方块，第 1 个才是图标。 */
    private fun tabIcon(v: View, tabId: Int): ImageView =
        (v.findViewById<ViewGroup>(tabId)).getChildAt(1) as ImageView

    private fun iconTint(v: View, tabId: Int): Int =
        tabIcon(v, tabId).imageTintList?.defaultColor
            ?: error("tab $tabId 没走 imageTintList（setColorFilter 的颜色读不回来，测试断言不了）")

    private fun chip(v: View, index: Int): TextView =
        v.findViewById<ViewGroup>(R.id.translate_filter_row).getChildAt(index) as TextView

    private fun chipBg(v: View, index: Int): Int =
        chip(v, index).backgroundTintList?.defaultColor
            ?: error("筛选 chip $index 没走 backgroundTintList（程序化 GradientDrawable 的颜色读不回来）")

    private fun label(v: View, id: Int): Int = v.findViewById<TextView>(id).currentTextColor

    /** 可选中的就是「翻页」Tab（面板默认停在它上面），其余三个是未选中态。 */
    private val tabs = listOf(R.id.tab_paging, R.id.tab_translate, R.id.tab_style, R.id.tab_more)

    @Test
    fun `切深浅时 tab 图标 筛选 chip 与文字色都必须就地重喷`() {
        val (sheet, v) = attach()

        // ---- 浅色基线 ----
        sheet.renderHostState(NovelPanelHostState(isDarkPanel = false))
        val lightTint = tabs.associateWith { iconTint(v, it) }
        assertEquals("浅色：未选中 Tab 是灰的", 0xFF777777.toInt(), lightTint[R.id.tab_translate])
        assertEquals("浅色：选中的 Tab 是蓝的", 0xFF55AEEA.toInt(), lightTint[R.id.tab_paging])
        assertEquals("浅色：选中 chip 底色", 0xFFE4E4E6.toInt(), chipBg(v, 0))
        assertEquals("浅色：label 文字色", 0xFF333333.toInt(), label(v, R.id.tv_mode_label))

        // ---- 切到深色背景（宿主推 isDarkPanel=true）----
        sheet.renderHostState(NovelPanelHostState(isDarkPanel = true))

        // ① 底部 4 个 Tab 图标：未选中的必须从旧灰阶换到深色灰，选中的那个也要重放（保持蓝）
        //    ⚠️ Tab 图标用的是 0xFFB8BCC2（`setTab`），**与面板文字的 subColor（0xFF9A9A9F）不同**
        for (id in listOf(R.id.tab_translate, R.id.tab_style, R.id.tab_more)) {
            assertEquals("深色：未选中 Tab 应换成深色灰", 0xFFB8BCC2.toInt(), iconTint(v, id))
        }
        assertEquals("深色：选中的 Tab 不能被重放成未选中色", 0xFF55AEEA.toInt(), iconTint(v, R.id.tab_paging))

        // ② 翻译页筛选 chip 底色
        assertEquals("深色：选中 chip 底色", 0xFF2E3A45.toInt(), chipBg(v, 0))

        // ③ 文字色
        assertEquals("深色：label 文字色", 0xFFE2E2E4.toInt(), label(v, R.id.tv_mode_label))

        // ④ 新增控件也必须登记（「同时 API 请求数」/「单批预警阈值」的标签与数值 + 提示行）：
        //    `applyPanelTheme` 是手写清单，漏登记的控件在深色面板下保持布局默认的深色字 → 看不见
        assertEquals("深色：并发数标签", 0xFFE2E2E4.toInt(), label(v, R.id.tv_concurrency_label))
        assertEquals("深色：并发数值", 0xFF9A9A9F.toInt(), label(v, R.id.tv_concurrency_value))
        assertEquals("深色：预警阈值标签", 0xFFE2E2E4.toInt(), label(v, R.id.tv_batch_warn_label))
        assertEquals("深色：预警阈值数值", 0xFF9A9A9F.toInt(), label(v, R.id.tv_batch_warn_value))
        assertEquals("深色：并发提示行", 0xFF9A9A9F.toInt(), label(v, R.id.tv_concurrency_hint))
        assertEquals("深色：预警提示行", 0xFF9A9A9F.toInt(), label(v, R.id.tv_batch_warn_hint))

        // ---- 再切回浅色：必须原路还原（用户视角就是「切回来又不对了」）----
        sheet.renderHostState(NovelPanelHostState(isDarkPanel = false))
        assertEquals(0xFF777777.toInt(), iconTint(v, R.id.tab_translate))
        assertEquals(0xFFE4E4E6.toInt(), chipBg(v, 0))
        assertEquals(0xFF333333.toInt(), label(v, R.id.tv_mode_label))
        assertEquals("浅色：并发数标签", 0xFF333333.toInt(), label(v, R.id.tv_concurrency_label))
        assertEquals("浅色：并发数值", 0xFF888888.toInt(), label(v, R.id.tv_concurrency_value))
        assertEquals("浅色：预警阈值数值", 0xFF888888.toInt(), label(v, R.id.tv_batch_warn_value))
        assertEquals("浅色：并发提示行", 0xFF888888.toInt(), label(v, R.id.tv_concurrency_hint))
    }
}
