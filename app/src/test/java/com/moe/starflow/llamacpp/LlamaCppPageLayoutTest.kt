package com.moe.starflow.llamacpp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import com.moe.starflow.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 模型管理页**页面骨架**的回归守卫（Robolectric inflate 真实布局）。
 *
 * 锁死 2026-09 用户的要求：
 *  1. 「添加模型」按钮**始终在页面最底部** —— 放在 ScrollView 之外、页面最后一个子 View 里，
 *     而且必须是那个底部区里的**最后一个**元素（内容少时不会飘在半空、内容多时不用滚到底去找）；
 *  2. 「功能说明」入口在**滚动区内的页首标题行**（原来它在按钮下方，会把按钮顶离底部）。
 */
@RunWith(RobolectricTestRunner::class)
class LlamaCppPageLayoutTest {

    private fun inflatePage(): ViewGroup =
        LayoutInflater.from(RuntimeEnvironment.getApplication())
            .inflate(R.layout.fragment_llamacpp_model, null) as ViewGroup

    private fun isDescendantOf(child: View, ancestor: ViewGroup): Boolean {
        var p: Any? = child.parent
        while (p is View) {
            if (p === ancestor) return true
            p = p.parent
        }
        return false
    }

    @Test
    fun `添加模型按钮在底部固定区且是页面最后一个元素`() {
        val root = inflatePage()
        val bar = root.findViewById<ViewGroup>(R.id.bottomBar)
        assertNotNull("页面必须有底部操作区", bar)
        assertEquals(
            "底部操作区必须是页面的最后一个子 View（它下面的内容会把按钮顶离底部）",
            R.id.bottomBar,
            root.getChildAt(root.childCount - 1).id,
        )
        assertEquals(
            "「添加模型」必须是底部区最后一个元素（说明文字放它上面）",
            R.id.btnAddModel,
            bar.getChildAt(bar.childCount - 1).id,
        )
    }

    @Test
    fun `添加模型按钮不在滚动区内`() {
        val root = inflatePage()
        val scroll = root.findViewById<ScrollView>(R.id.contentScroll)
        assertNotNull("pages 必须有滚动区", scroll)
        assertFalse(
            "按钮不能在 ScrollView 里，否则内容一长就被滚走了",
            isDescendantOf(root.findViewById(R.id.btnAddModel), scroll),
        )
        assertFalse(
            "导入进度也要跟着按钮待在底部区（导入时始终看得见）",
            isDescendantOf(root.findViewById(R.id.importProgressBox), scroll),
        )
    }

    @Test
    fun `功能说明入口在滚动区的页首标题行`() {
        val root = inflatePage()
        val scroll = root.findViewById<ScrollView>(R.id.contentScroll)
        assertTrue(
            "功能说明入口应在滚动区内（页首），不能压在按钮下面",
            isDescendantOf(root.findViewById(R.id.btnIntroLink), scroll),
        )
    }

    /** 真的量一遍布局：底部区贴住页面底边，按钮落在页面最下面那一截里。 */
    private fun layoutPage(root: View, w: Int = 1080, h: Int = 2000) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, w, h)
    }

    /** ⚠️ `getBottom()` 是**相对父容器**的，必须累加各级 top 才是页面坐标。 */
    private fun bottomInPage(v: View, root: View): Int {
        var y = v.bottom
        var p: Any? = v.parent
        while (p is View && p !== root) {
            y += p.top
            p = p.parent
        }
        return y
    }

    private fun assertButtonPinnedToBottom(root: View, h: Int = 2000) {
        val bar = root.findViewById<ViewGroup>(R.id.bottomBar)
        val btn = root.findViewById<View>(R.id.btnAddModel)
        assertEquals("底部操作区必须贴住页面底边", h, bar.bottom)
        val btnBottom = bottomInPage(btn, root)
        assertTrue(
            "「添加模型」必须落在页面最底部（页面坐标 bottom=$btnBottom，页面高=$h）",
            btnBottom > h - 80,
        )
    }

    @Test
    fun `内容很少时按钮也在页面最底部`() {
        val root = inflatePage()
        layoutPage(root)
        assertButtonPinnedToBottom(root)
    }

    @Test
    fun `内容很长时按钮仍钉在页面最底部`() {
        val root = inflatePage()
        val container = root.findViewById<ViewGroup>(R.id.importedContainer)
        repeat(30) { i ->
            val row = android.widget.TextView(RuntimeEnvironment.getApplication()).apply {
                text = "row $i"
            }
            container.addView(
                row,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    200,
                ),
            )
        }
        layoutPage(root)
        assertButtonPinnedToBottom(root)
    }
}
