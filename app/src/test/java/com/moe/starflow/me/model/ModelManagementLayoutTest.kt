package com.moe.starflow.me.model

import android.view.LayoutInflater
import android.view.View
import android.widget.RadioButton
import android.widget.TextView
import com.moe.starflow.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * `fragment_model_management.xml` 的 include 结构守卫。
 *
 * 行模板（`item_model_row_*`）内部的 ID 在多次 include 之间是**重复的**，因此
 * `ModelManagementFragment` 必须逐行以「行根 View」为作用域查找控件。本测试把这条前提锁死：
 * 行根之间互相独立、作用域查找取到的是本行的控件、全局查找会串到第一行去。
 *
 * 如果哪天有人给行模板加了新的内部 ID，请在这里补断言。
 */
@RunWith(RobolectricTestRunner::class)
class ModelManagementLayoutTest {

    private fun inflate(): View {
        val ctx = RuntimeEnvironment.getApplication()
        return LayoutInflater.from(ctx).inflate(R.layout.fragment_model_management, null, false)
    }

    /** 9 个模型行的行根 View（顺序同 ModelManagementFragment.modelRows） */
    private val rowRootIds = listOf(
        R.id.rtdetr_row,
        R.id.manga_ocr_row,
        R.id.v5_det_row,
        R.id.v5_rec_zh_row,
        R.id.v5_rec_en_row,
        R.id.v5_rec_ko_row,
        R.id.v5_rec_ru_row,
        R.id.ppocrv6_medium_det_row,
        R.id.ppocrv6_medium_rec_row
    )

    @Test
    fun everyRowRootExistsAndIsDistinct() {
        val root = inflate()
        val roots = rowRootIds.map { root.findViewById<View>(it) }
        rowRootIds.forEachIndexed { i, id ->
            assertNotNull("行根 View 缺失: $id", roots[i])
        }
        // 行根 id 必须唯一 —— 否则 Fragment 找不到对应的行
        assertEquals("行根 View 计数不符", rowRootIds.size, roots.toSet().size)
    }

    @Test
    fun scopedLookupFindsEachRowsOwnChildren() {
        val root = inflate()
        for (id in rowRootIds) {
            val row = root.findViewById<View>(id)
            assertNotNull("行 $id 缺 row_status", row.findViewById<TextView>(R.id.row_status))
            assertNotNull("行 $id 缺 row_action", row.findViewById<TextView>(R.id.row_action))
            assertNotNull("行 $id 缺 row_cancel", row.findViewById<TextView>(R.id.row_cancel))
        }
    }

    /**
     * 模板内部 ID 确实是重复的 —— 这正是必须作用域查找的原因。
     *
     * 全局查找永远只返回**文档序里第一个**匹配（这里是 PP-OCRv6 段里的行，不是
     * [rowRootIds] 的首项 —— 那个列表跟的是 `ModelManagementFragment.modelRows` 的顺序，
     * 与 XML 文档顺序无关）。拿它去渲染别的行就会串数据，所以断言不依赖任何顺序。
     */
    @Test
    fun globalLookupCollidesAcrossRows_soScopingIsMandatory() {
        val root = inflate()
        val global = root.findViewById<TextView>(R.id.row_status)
        assertNotNull("全局查找应至少命中一个 row_status", global)

        val perRow = rowRootIds.map {
            root.findViewById<View>(it).findViewById<TextView>(R.id.row_status)
        }

        // 作用域查找能区分出 9 个互相独立的控件……
        assertEquals("每行的 row_status 必须是不同实例", rowRootIds.size, perRow.toSet().size)
        // ……而全局查找只能落到其中一行上，对其余 8 行都是错的
        assertEquals("全局查找只能命中一行", 1, perRow.count { it === global })
        assertEquals("其余各行都拿不到自己的控件", rowRootIds.size - 1, perRow.count { it !== global })
    }

    @Test
    fun browserButtonsMatchTheirTemplate() {
        val root = inflate()

        // 模板 A（单浏览器）：rtdetr / v5_det / v6 medium det+rec
        for (id in listOf(
            R.id.rtdetr_row, R.id.v5_det_row,
            R.id.ppocrv6_medium_det_row, R.id.ppocrv6_medium_rec_row
        )) {
            val row = root.findViewById<View>(id)
            assertNotNull("行 $id 缺 row_browser", row.findViewById<TextView>(R.id.row_browser))
            assertNull("行 $id 不该有 row_browser_model", row.findViewById<TextView>(R.id.row_browser_model))
        }

        // 模板 B（onnx + 字典双浏览器）
        for (id in listOf(
            R.id.v5_rec_zh_row, R.id.v5_rec_en_row, R.id.v5_rec_ko_row, R.id.v5_rec_ru_row
        )) {
            val row = root.findViewById<View>(id)
            assertNotNull("行 $id 缺 row_browser_model", row.findViewById<TextView>(R.id.row_browser_model))
            assertNotNull("行 $id 缺 row_browser_dict", row.findViewById<TextView>(R.id.row_browser_dict))
            assertNull("行 $id 不该有 row_browser", row.findViewById<TextView>(R.id.row_browser))
        }

        // 模板 C（manga-ocr：encoder/decoder/vocab）
        val mangaRow = root.findViewById<View>(R.id.manga_ocr_row)
        for (id in listOf(
            R.id.row_browser_encoder, R.id.row_browser_decoder, R.id.row_browser_vocab
        )) {
            assertNotNull("manga-ocr 行缺浏览器按钮 $id", mangaRow.findViewById<TextView>(id))
        }
    }

    /** 分组标题/切档 RadioButton/存储路径这些行外控件不受 include 重构影响 */
    @Test
    fun nonRowViewsStillResolve() {
        val root = inflate()
        for (id in listOf(
            R.id.mlkit_group_title, R.id.mlkit_group_selected,
            R.id.ppocrv6_group_title, R.id.ppocrv6_group_selected,
            R.id.ppocrv5_group_title, R.id.ppocrv5_group_selected,
            R.id.rt_manga_group_title, R.id.rt_manga_group_selected,
            R.id.model_storage_path
        )) {
            assertNotNull("布局缺少控件 $id", root.findViewById<View>(id))
        }
        assertNotNull("缺少 small 切档", root.findViewById<RadioButton>(R.id.ppocrv6_tier_small))
        assertNotNull("缺少 medium 切档", root.findViewById<RadioButton>(R.id.ppocrv6_tier_medium))
    }
}
