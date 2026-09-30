package com.moe.starflow.me.model

import android.view.LayoutInflater
import android.view.View
import android.widget.RadioButton
import android.widget.TextView
import com.moe.starflow.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        // ⚠️ 必须套 app 主题再 inflate：布局里有 Material 的 TabLayout（超分 Tab，2026-10 新增），
        // 裸 application context 的主题不是 Material 主题 → TabLayout 构造直接抛
        // UnsupportedOperationException/InflateException，6 个用例会一起红。
        // 项目里 fragment_history / fragment_openai_api 也用 TabLayout，但只有本测试会 inflate 整个布局。
        val themed = androidx.appcompat.view.ContextThemeWrapper(ctx, R.style.Theme_MT)
        return LayoutInflater.from(themed).inflate(R.layout.fragment_model_management, null, false)
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

    /**
     * 超分 Tab 结构守卫（2026-10）。
     *
     * ⚠️ 本页与 OCR Tab **完全同构**：行是 XML 里的 `<include>`（2026-10 改版前是代码动态
     * inflate 的，那时的断言是「sr_content 必须为空」—— 已随改版反转）。
     * 这里锁三件事：
     * ① 两个 ScrollView + TabLayout 都在；
     * ② 3 个族的「组标题 / 当前使用」与 11 个模型行都在，行根唯一、作用域查找能拿到本行控件；
     * ③ **Fragment 里写的 id 与 XML 里的 id 一一对应**（两边各写一份清单，错位了
     *    「当前使用」会标到别的族上，而这种错在 UI 上是"看着有点怪"、极难归因）。
     */
    @Test
    fun superResolutionTabStructureIsSound() {
        val root = inflate()
        assertNotNull("缺少顶部 TabLayout", root.findViewById<View>(R.id.model_tabs))
        assertNotNull("缺少 OCR ScrollView", root.findViewById<View>(R.id.ocr_scroll))
        assertNotNull("缺少超分 ScrollView", root.findViewById<View>(R.id.sr_scroll))

        val srContent = root.findViewById<android.widget.LinearLayout>(R.id.sr_content)
        assertNotNull("缺少超分容器 sr_content", srContent)
        assertTrue("超分行必须在 XML 里（与 OCR Tab 同构，不再动态 inflate）", srContent.childCount > 0)
    }

    /** 超分组：组标题 + 「当前使用」+ 每行 include 的模板控件 */
    @Test
    fun superResolutionGroupsAndRowsAreComplete() {
        val root = inflate()

        // 7 个族：AnimeJaNai（ONNX）+ 6 个 ncnn 族（waifu2x upconv_7 动漫/照片、cunet、SRMD、
        // Real-CUGAN、Real-ESRGAN）。swin 已移除（16MB/档、15-19 秒/页、且无法转 ncnn）。
        val titles = listOf(
            R.id.sr_aji_group_title, R.id.sr_w2xa_group_title, R.id.sr_w2xp_group_title,
            R.id.sr_w2xc_group_title, R.id.sr_srmd_group_title, R.id.sr_cugan_group_title,
            R.id.sr_rsrgan_group_title
        )
        for (id in titles) assertNotNull("缺超分组标题 $id", root.findViewById<View>(id))

        val selecteds = listOf(
            R.id.sr_aji_group_selected, R.id.sr_w2xa_group_selected, R.id.sr_w2xp_group_selected,
            R.id.sr_w2xc_group_selected, R.id.sr_srmd_group_selected, R.id.sr_cugan_group_selected,
            R.id.sr_rsrgan_group_selected
        )
        for (id in selecteds) assertNotNull("缺超分组「当前使用」$id", root.findViewById<View>(id))

        val rows = srRowIds()
        assertEquals("超分行数应为 28（5 AnimeJaNai + 23 ncnn）", 28, rows.size)
        val roots = rows.map { root.findViewById<View>(it) }
        rows.forEachIndexed { i, id -> assertNotNull("超分行根缺失: $id", roots[i]) }
        assertEquals("超分行根 id 必须唯一", rows.size, roots.toSet().size)

        for (id in rows) {
            val row = root.findViewById<View>(id)
            assertNotNull("超分行 $id 缺 row_status", row.findViewById<TextView>(R.id.row_status))
            assertNotNull("超分行 $id 缺 row_action", row.findViewById<TextView>(R.id.row_action))
            assertNotNull("超分行 $id 缺 row_browser", row.findViewById<TextView>(R.id.row_browser))
        }
    }

    /**
     * ⚠️ **Fragment 里的超分 id 必须全部存在于布局里**。
     *
     * `ModelManagementFragment.srFamilies` 与 `fragment_model_management.xml` 各写一份清单
     * （XML 管版式、代码管语义）。漏一个 id 的后果不是崩溃，而是**某个族的「当前使用」永远不显示**
     * 或**某行不可点** —— 静默、且看着像"偶尔不灵"。所以在这里做一次源码级对齐检查。
     */
    @Test
    fun fragmentSrIdsAllExistInLayout() {
        val fragment = java.io.File("src/main/java/com/moe/starflow/me/model/ModelManagementFragment.kt")
        if (!fragment.isFile) return   // 非 Gradle 工作目录下跳过
        val src = fragment.readText()
        val ids = Regex("R\\.id\\.(sr_[a-z0-9_]+)").findAll(src).map { it.groupValues[1] }.toSet()
        assertTrue("Fragment 里一个超分 id 都没解析到（正则或常量改名了？）", ids.size >= 14)

        val layout = java.io.File("src/main/res/layout/fragment_model_management.xml").readText()
        val missing = ids.filter { "@+id/$it" !in layout }
        assertTrue("Fragment 引用了布局里不存在的 id: $missing", missing.isEmpty())
    }

    /**
     * 超分模型行的行根 id（顺序同 ModelManagementFragment.srFamilies）。
     *
     * ⚠️ 加/删模型时这里**必须同步** —— 它和布局 XML、Fragment、downloadinfo.json
     * 是同一份清单的四个副本，任一处漏改都由本测试或 `verify_sr_wiring.py` 抓出来。
     */
    private fun srRowIds() = listOf(
        // AnimeJaNai（ONNX，CPU）
        R.id.sr_aji_balanced_row,
        R.id.sr_aji_perf_row,
        R.id.sr_aji_sharp1_balanced_row,
        R.id.sr_aji_sharp1_perf_row,
        R.id.sr_aji_sd_row,
        // waifu2x upconv_7 动漫（ncnn，5 档）
        R.id.sr_w2xa_m1_row,
        R.id.sr_w2xa_n0_row,
        R.id.sr_w2xa_n1_row,
        R.id.sr_w2xa_n2_row,
        R.id.sr_w2xa_n3_row,
        // waifu2x upconv_7 照片（ncnn，5 档）
        R.id.sr_w2xp_m1_row,
        R.id.sr_w2xp_n0_row,
        R.id.sr_w2xp_n1_row,
        R.id.sr_w2xp_n2_row,
        R.id.sr_w2xp_n3_row,
        // waifu2x cunet（ncnn，5 档）
        R.id.sr_w2xc_m1_row,
        R.id.sr_w2xc_n0_row,
        R.id.sr_w2xc_n1_row,
        R.id.sr_w2xc_n2_row,
        R.id.sr_w2xc_n3_row,
        // SRMD（ncnn，2 档）
        R.id.sr_srmd_x2_row,
        R.id.sr_srmd_nf_x2_row,
        // Real-CUGAN（ncnn，5 档）
        R.id.sr_cugan_cons_row,
        R.id.sr_cugan_d1_row,
        R.id.sr_cugan_d2_row,
        R.id.sr_cugan_d3_row,
        R.id.sr_cugan_dn_row,
        // Real-ESRGAN（ncnn，4x）
        R.id.sr_rsrgan_a6b_row
    )
}
