package com.moe.starflow.manga.merge

import android.graphics.PointF
import com.moe.starflow.manga.types.QuadBox
import com.moe.starflow.manga.types.TextRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TextRegionMerger 多场景合并测试。
 *
 * 核心关注：不允许"交叉合并"——即长行+长行、短行+短行跳着合并，
 * 无视垂直相邻关系。正确行为应是按垂直相邻配对（前两两）或整块合并。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextRegionMergerTest {

    /** 构造横排 QuadBox（isVertical=false）。 */
    private fun hRegion(text: String, left: Int, top: Int, right: Int, bottom: Int): TextRegion {
        val quad = QuadBox(
            arrayOf(
                PointF(left.toFloat(), top.toFloat()),
                PointF(right.toFloat(), top.toFloat()),
                PointF(right.toFloat(), bottom.toFloat()),
                PointF(left.toFloat(), bottom.toFloat())
            ),
            text = text
        )
        return TextRegion(quad, text = text)
    }

    /** 从合并组提取文字集合（去重，用于断言）。 */
    private fun textsOf(groups: List<com.moe.starflow.manga.types.TextRegionGroup>): List<Set<String>> =
        groups.map { it.texts.toSet() }

    /** 断言：没有任何一个合并组同时包含 a 和 b（即 a、b 未被合并到一起）。 */
    private fun assertNotMerged(groups: List<com.moe.starflow.manga.types.TextRegionGroup>, a: String, b: String) {
        for (g in groups) {
            val t = g.texts.joinToString("")
            assertFalse("不应合并 '$a' 和 '$b'（实际组内: $t）", t.contains(a) && t.contains(b))
        }
    }

    /** 断言：a 和 b 在同一个合并组内。 */
    private fun assertMerged(groups: List<com.moe.starflow.manga.types.TextRegionGroup>, a: String, b: String) {
        val merged = groups.any { g ->
            val t = g.texts.joinToString("")
            t.contains(a) && t.contains(b)
        }
        assertTrue("应合并 '$a' 和 '$b'，但未在同一组", merged)
    }

    /**
     * 场景 1：连续短行（对话），全部左对齐垂直紧邻 → 应合并成一大段。
     */
    @Test
    fun consecutiveShortLines_mergeIntoOne() {
        val regions = listOf(
            hRegion("老师，我们学校的", 0, 0, 300, 50),
            hRegion("布，有没有预调剂", 0, 50, 250, 100),
            hRegion("及时关注官网信息", 0, 100, 250, 150),
            hRegion("有没有调剂群?", 0, 150, 200, 200)
        )
        val groups = TextRegionMerger.merge(regions)
        // 4 行左对齐紧邻 → 应合并为 1 组
        assertEquals("连续短行应合并为 1 组", 1, groups.size)
    }

    /**
     * 场景 2：长短交替（用户报的交叉合并）。
     * [长][短][长][短] 垂直排列，左对齐。
     * 合法输出二选一：(a) 前两两 [长0+短1]/[长2+短3]，(b) 整块 4 行合并。
     * 非法输出（交叉）：长行们跳过中间短行互并、或短行们跳着互并。
     */
    @Test
    fun alternatingLongShort_noCrossMerge() {
        val regions = listOf(
            hRegion("老师我还想确认下咱们专业调剂", 0, 0, 500, 50),   // 长
            hRegion("硬性要求呢?", 0, 50, 150, 100),                  // 短
            hRegion("例如本科院校层次是否", 0, 100, 480, 150),        // 长
            hRegion("是否限制相关专业", 0, 150, 160, 200)             // 短
        )
        val groups = TextRegionMerger.merge(regions)

        // 硬约束 1：每个合并组在垂直阅读顺序上必须"连续"——不允许出现
        // 长1+长2 同组但短1 不在同组的"跳行交叉"。检查方式：任一组合并时，
        // 若含长1 与长2，则必须同时含短1（即不是跳着合并）。
        for (g in groups) {
            val t = g.texts.joinToString("")
            val hasL1 = t.contains("老师我还想确认下")
            val hasL2 = t.contains("例如本科院校层次")
            val hasS1 = t.contains("硬性要求呢?")
            if (hasL1 && hasL2) {
                assertTrue(
                    "交叉：长1与长2同组但跳过中间短1（组内: $t）",
                    hasS1
                )
            }
        }
        // 硬约束 2：垂直相邻的长行+短行必须合并（不能拆开相邻的一句话）
        assertMerged(groups, "老师我还想确认下", "硬性要求呢?")
        assertMerged(groups, "例如本科院校层次", "是否限制相关专业")
    }

    /**
     * 场景 3：两栏布局（水平分离）→ 不应合并。
     */
    @Test
    fun twoColumns_horizontallySeparated_notMerged() {
        val regions = listOf(
            hRegion("左栏第一行", 0, 0, 200, 50),
            hRegion("左栏第二行", 0, 50, 200, 100),
            hRegion("右栏第一行", 600, 0, 800, 50),
            hRegion("右栏第二行", 600, 50, 800, 100)
        )
        val groups = TextRegionMerger.merge(regions)
        // 左栏 2 行合并、右栏 2 行合并 → 2 组
        assertEquals("左右两栏应各自合并为 2 组", 2, groups.size)
        assertMerged(groups, "左栏第一行", "左栏第二行")
        assertMerged(groups, "右栏第一行", "右栏第二行")
    }

    /**
     * 场景 4：不同字号相邻 → 不应合并（字号比超出阈值）。
     */
    @Test
    fun differentFontSize_notMerged() {
        // 大字号（fontSize 由 QuadBox 高度推导，两行高差大）
        val regions = listOf(
            hRegion("大标题", 0, 0, 300, 80),
            hRegion("小正文", 0, 80, 200, 110)
        )
        val groups = TextRegionMerger.merge(regions)
        // 字号比 80/30 ≈ 2.67 > 2.0 → 不合并 → 2 组
        assertEquals("不同字号不应合并", 2, groups.size)
    }

    /**
     * 场景 5：远距离分离（垂直间隙大）→ 不合并。
     */
    @Test
    fun farVerticalGap_notMerged() {
        val regions = listOf(
            hRegion("第一段", 0, 0, 300, 50),
            hRegion("第二段", 0, 300, 300, 350)   // 间隙 250，远超 charSize*1
        )
        val groups = TextRegionMerger.merge(regions)
        assertEquals("垂直远距离不应合并", 2, groups.size)
    }

    /**
     * 场景 7：合并开关关闭时，每个 region 独立成组（表格/多栏场景）。
     * 即使相邻行也不合并。
     */
    @Test
    fun mergeDisabled_eachRegionIndependent() {
        // 模拟开关关闭：无法直接设 prefs（单测无 SharedPreferences），
        // 验证 mergeEnabled=false 时逻辑。通过反射设字段避免依赖 Android prefs。
        val field = TextRegionMerger::class.java.getDeclaredField("mergeEnabled")
        field.isAccessible = true
        val oldValue = field.getBoolean(TextRegionMerger)
        try {
            field.setBoolean(TextRegionMerger, false)
            val regions = listOf(
                hRegion("第一行", 0, 0, 200, 50),
                hRegion("第二行", 0, 50, 150, 100)   // 紧邻第一行，正常应合并
            )
            val groups = TextRegionMerger.merge(regions)
            assertEquals("开关关闭时相邻行不合并", 2, groups.size)
        } finally {
            field.setBoolean(TextRegionMerger, oldValue)
        }
    }
}
