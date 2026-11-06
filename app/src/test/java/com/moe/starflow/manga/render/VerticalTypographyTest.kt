package com.moe.starflow.manga.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 竖排字格切分/步进的纯 JVM 测试。
 *
 * 这一层是「排版内核」与「渲染器」的共同事实来源：TCY 合并、单数字不合并、
 * 4 位上限、全角数字和列高计算都在这里钉死；LayoutEngine 只消费结果。
 */
class VerticalTypographyTest {

    private fun texts(text: String) = VerticalTypography.units(text).map { it.text }
    private fun kinds(text: String) = VerticalTypography.units(text).map { it.kind }

    @Test
    fun units_twoDigitRunBecomesTcy() {
        assertEquals(listOf("20", "年"), texts("20年"))
        assertEquals(
            listOf(VerticalUnit.Kind.TCY, VerticalUnit.Kind.CHAR),
            kinds("20年")
        )
    }

    @Test
    fun units_singleDigitStaysChar() {
        assertEquals(listOf("3", "年"), texts("3年"))
        assertEquals(
            listOf(VerticalUnit.Kind.CHAR, VerticalUnit.Kind.CHAR),
            kinds("3年")
        )
    }

    @Test
    fun units_longRunSplitsAtFour() {
        assertEquals(listOf("1234", "5"), texts("12345"))
        assertEquals(
            listOf(VerticalUnit.Kind.TCY, VerticalUnit.Kind.CHAR),
            kinds("12345")
        )
        assertEquals(listOf("1234", "56"), texts("123456"))
        assertEquals(
            listOf(VerticalUnit.Kind.TCY, VerticalUnit.Kind.TCY),
            kinds("123456")
        )
    }

    @Test
    fun units_fullWidthDigitsAlsoTcy() {
        assertEquals(listOf("２０", "年"), texts("２０年"))
        assertEquals(VerticalUnit.Kind.TCY, VerticalTypography.units("２０年")[0].kind)
    }

    @Test
    fun units_onlyGroupsTheDigitRunInsideText() {
        assertEquals(listOf("A", "20", "B"), texts("A20B"))
        assertEquals(
            listOf(VerticalUnit.Kind.CHAR, VerticalUnit.Kind.TCY, VerticalUnit.Kind.CHAR),
            kinds("A20B")
        )
    }

    @Test
    fun units_coverEveryCharacterContiguously() {
        val text = "20年前，他说过……今天一定会回来！？"
        val units = VerticalTypography.units(text)
        assertEquals(text, units.joinToString("") { it.text })
        assertEquals(0, units.first().start)
        assertEquals(text.length, units.last().end)
        for (i in 1 until units.size) {
            assertEquals("区间必须连续", units[i - 1].end, units[i].start)
        }
    }

    @Test
    fun columnHeight_sumsAdvancesAndTracking() {
        val units = VerticalTypography.units("20年")   // TCY + CHAR
        assertEquals(20f, VerticalTypography.columnHeight(units, 10f, 0f), 0.01f)
        assertEquals(22f, VerticalTypography.columnHeight(units, 10f, 2f), 0.01f)
        assertEquals(0f, VerticalTypography.columnHeight(emptyList(), 10f, 2f), 0.01f)
    }

    @Test
    fun tcyNeverExceedsFourDigits() {
        val tcy = VerticalTypography.units("12345678").filter { it.isTcy }
        assertEquals(listOf("1234", "5678"), tcy.map { it.text })
        assertTrue(tcy.all { it.text.length <= VerticalTypography.TCY_MAX_DIGITS })
    }
}
