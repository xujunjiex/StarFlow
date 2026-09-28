package com.moe.starflow.utils

import android.content.ContextWrapper
import androidx.appcompat.app.AppCompatActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * `ReaderDialogs` 的**真机行为**守卫（Robolectric：真建弹窗、真 show）。
 *
 * 这两条对应线上崩过的两次（都是"为了配色把上下文换了"造成的）：
 *
 * | 崩法 | 原因 | 本测试怎么拦 |
 * |---|---|---|
 * | `IllegalStateException: You need to use a Theme.AppCompat theme` | 用 `createConfigurationContext()` 的裸 Context 建弹窗 → 丢掉 AppCompat 主题 | [深色浅色都能真的把弹窗显示出来] |
 * | `WindowManager$BadTokenException: token null is not valid` | 配置上下文**不挂在 Activity 窗口上** → 弹窗没有 window token | [主题化上下文必须以原 Activity 为 base] |
 *
 * ⚠️ Robolectric 的 WindowManager 比较宽松（不一定复现 token 崩溃），所以 token 那条用
 * **"baseContext 必须是原上下文"** 来断言 —— 这正是 token 的来源，且无法绕过。
 */
@RunWith(RobolectricTestRunner::class)
class ReaderDialogsRoboTest {

    private fun activity(): AppCompatActivity =
        Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()

    @Test
    fun `深色浅色都能真的把弹窗显示出来`() {
        val a = activity()
        listOf(true, false).forEach { dark ->
            val dlg = ReaderDialogs.show(a, dark) { setMessage("color check") }
            assertNotNull("弹窗必须建出来（$dark）", dlg)
            assertTrue("弹窗必须真的显示（$dark）—— 抛异常说明主题/上下文被换坏了", dlg.isShowing)
            dlg.dismiss()
        }
    }

    @Test
    fun `主题化上下文必须以原 Activity 为 base`() {
        val a = activity()
        listOf(true, false).forEach { dark ->
            val themed = ReaderDialogs.context(a, dark)
            assertTrue("应当是包装上下文（ContextWrapper）", themed is ContextWrapper)
            assertSame(
                "base 必须是**原上下文**：用 createConfigurationContext() 的结果当 base 会丢 window token " +
                    "→ 弹窗 show 时 BadTokenException（线上崩过）",
                a,
                (themed as ContextWrapper).baseContext,
            )
        }
    }
}
