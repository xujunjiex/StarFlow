package com.moe.starflow.library.ui

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 标签 chips 的复制手势（用户口径 2026-11）：
 *
 * > 「把卡片点击直接复制标签换成长按复制，然后长按要有对应的点击长按动画」
 *
 * 卡片上的标签是"看一眼"的东西，短按就复制太容易误触（想点卡片却复制了标签）。现在的口径：
 * **长按才复制**；短按转发给这一块原本的动作（书架行 = 进作品数据页），作品数据页没有容器动作
 * 就什么都不做。长按的按下/命中动画只能真机看，这里钉住"哪种手势会真的复制"。
 */
@RunWith(RobolectricTestRunner::class)
class BookMetadataChipsTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** 卡片版 chips（书架行用的就是这一支：只有 chips，没有字段名）。 */
    private fun cardChips(
        onCopy: (String) -> Unit,
        onRowAction: (() -> Unit)? = null,
    ): Pair<LinearLayout, TextView> {
        val container = LinearLayout(context).apply {
            if (onRowAction != null) setOnClickListener { onRowAction() }
        }
        BookMetadataChips.fillFoldedChips(
            container = container,
            values = listOf("魔幻", "冒险"),
            expanded = false,
            limit = 3,
            onCopy = onCopy,
            onToggle = {},
        )
        return container to (container.getChildAt(0) as TextView)
    }

    @Test
    fun shortTapDoesNotCopyTheTag() {
        var copied: String? = null
        val (_, chip) = cardChips(onCopy = { copied = it })

        chip.performClick()

        assertNull("短按不该再复制标签", copied)
    }

    @Test
    fun shortTapStillRunsTheRowAction() {
        var opened = false
        val (_, chip) = cardChips(onCopy = {}, onRowAction = { opened = true })

        chip.performClick()

        assertTrue("短按要顶到这一块原本的动作（书架行进作品数据页），不能变成点不动的死区", opened)
    }

    @Test
    fun longPressCopiesTheTagAndConsumesTheGesture() {
        var copied: String? = null
        val (_, chip) = cardChips(onCopy = { copied = it })

        val consumed = chip.performLongClick()

        assertEquals("长按复制的是这一格自己的值", "魔幻", copied)
        assertTrue("长按必须被 chip 消费，否则会同时触发卡片的其它长按（多选）", consumed)
    }

    @Test
    fun dataPageChipsCopyOnLongPressOnly() {
        // 作品数据页用的是「字段名 + chips」那一支，容器没有点击动作
        var copied: String? = null
        val container = LinearLayout(context)
        BookMetadataChips.addRow(container, "标签", listOf("魔幻"), onCopy = { copied = it })
        // 行容器里第 0 个是字段名，第 1 个才是 chip
        val row = container.getChildAt(0) as android.view.ViewGroup
        val chip = row.getChildAt(1) as TextView

        chip.performClick()
        assertNull("作品数据页同样不再短按复制", copied)

        chip.performLongClick()
        assertEquals("魔幻", copied)
    }
}
