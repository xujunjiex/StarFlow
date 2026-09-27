package com.moe.starflow.novel.reader

import android.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 滚动模式 item 的**重绑代价**：纯绘制输入（文字色/选中/翻译高亮）变了不该重新测量。
 *
 * ### 为什么这条测试值得写
 * item 是 `WRAP_CONTENT`，`requestLayout()` 必然带回一遍 `onMeasure`，而 `onMeasure` 要拿
 * `StaticLayout` 的高度。原先 [NovelScrollAdapter.ParagraphView.bind] **无条件** `requestLayout()`，
 * 于是选择模式每点一下、切一次阅读背景，所有可见项都要重新量一遍（每项 = 一次文本排版）。
 *
 * ⚠️ Robolectric 的文本引擎是桩（量不出真机行高，几何错误盖不住），但「有没有请求布局」是
 * `View` 自己的记账、与文本引擎无关 —— 所以这条钉得住：
 * [ShadowView.didRequestLayout] 只有在**真的调了** `requestLayout()` 时才为 true
 * （`didRequestLayout()` 是 Robolectric 专门给这种断言用的，`setDidRequestLayout(false)` 可复位）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelScrollRebindTest {

    private val style = NovelPageView.DEFAULT_STYLE
    private val backgroundColor = Color.WHITE

    private fun paragraphView() =
        NovelScrollAdapter.ParagraphView(RuntimeEnvironment.getApplication())

    /** 只换文字色（文本/排版参数/底色都不变）→ 该重画，不该重新测量。 */
    @Test
    fun `只换文字色的重绑不请求布局`() {
        val v = paragraphView()
        v.bind("第一段正文", style, NovelPageAdapter.DEFAULT_TEXT_COLOR, backgroundColor)
        val shadow = shadowOf(v)
        shadow.setDidRequestLayout(false)

        v.bind("第一段正文", style, 0xFF3366CC.toInt(), backgroundColor)

        assertFalse("文字色不参与测量，纯换色不该 requestLayout", shadow.didRequestLayout())
    }

    /** 换文本 → **必须**重新测量（item 高度就是按文本量的）。防的是「为了省事干脆不 requestLayout」。 */
    @Test
    fun `换文本的重绑必须请求布局`() {
        val v = paragraphView()
        v.bind("第一段正文", style, NovelPageAdapter.DEFAULT_TEXT_COLOR, backgroundColor)
        val shadow = shadowOf(v)
        shadow.setDidRequestLayout(false)

        v.bind("换成了另一段更长的正文", style, NovelPageAdapter.DEFAULT_TEXT_COLOR, backgroundColor)

        assertTrue("文本变了，条目高度要重算", shadow.didRequestLayout())
    }

    /** 排印参数（字号）变了 → 同样必须重排重测，高度公式里就有它。 */
    @Test
    fun `改字号的重绑必须请求布局`() {
        val v = paragraphView()
        v.bind("第一段正文", style, NovelPageAdapter.DEFAULT_TEXT_COLOR, backgroundColor)
        val shadow = shadowOf(v)
        shadow.setDidRequestLayout(false)

        val bigger = style.copy(fontSizePx = style.fontSizePx + 4f)
        v.bind("第一段正文", bigger, NovelPageAdapter.DEFAULT_TEXT_COLOR, backgroundColor)

        assertTrue("字号变了，条目高度要重算", shadow.didRequestLayout())
    }
}
