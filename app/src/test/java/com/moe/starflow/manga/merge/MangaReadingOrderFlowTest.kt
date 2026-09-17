package com.moe.starflow.manga.merge

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import com.moe.starflow.manga.types.CroppedBubble
import com.moe.starflow.manga.types.QuadBox
import com.moe.starflow.manga.types.TextDirection
import com.moe.starflow.manga.types.TextRegion
import com.moe.starflow.manga.types.TextRegionGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 竖排流向（`Manga_Text_Direction`）对**排序层**生效的不变量。
 *
 * 这一层决定的是**送进翻译的源文拼接顺序**，渲染层再复用同一顺序上屏。
 * 两条链路（截屏翻译 `TextRegionMerger` / 增量 `MangaSpatialGrouping`）都要覆盖 ——
 * 它们对同一版式给出相反列序的话，同一本书换个模式读就会前后不一致。
 *
 * ⚠️ 关键契约：**竖排跟设置走、横排恒左→右**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MangaReadingOrderFlowTest {

    private fun vRegion(text: String, left: Int, top: Int, right: Int, bottom: Int): TextRegion {
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

    private fun bubble(left: Int, top: Int, right: Int, bottom: Int) = CroppedBubble(
        croppedBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
        rect = Rect(left, top, right, bottom),
        classId = 0,
        confidence = 1f
    )

    /** 含指定文本的组的拼接串；找不到返回 null。 */
    private fun groupOf(groups: List<TextRegionGroup>, text: String): String? =
        groups.firstOrNull { it.texts.any { t -> t == text } }?.texts?.joinToString("")

    // ---------- 竖排：拼接顺序随设置 ----------

    /**
     * 三列竖排并排 → 合并成一组，组内拼接顺序即「送进翻译的源文顺序」。
     *
     * 用**三列**而不是两列：两列无法区分「按列序排」与「碰巧的对/错」。
     *
     * ⚠️ 几何必须真的进 `canMergeRegion` 的**竖排分支**，否则测的是「没合并」：
     * - 列距 < `charSize × CHAR_GAP_TOLERANCE`（字号 40 → 列距须 < 40）
     * - 各列 `top` 对齐（竖排分支靠 `topAligned` / `centerYAligned` 连边）
     */
    @Test
    fun merge_verticalFlowControlsConcatenationOrder() {
        val cols = listOf(
            vRegion("左", 0, 0, 40, 200),
            vRegion("中", 70, 0, 110, 200),
            vRegion("右", 140, 0, 180, 200)
        )

        val rl = TextRegionMerger.merge(cols, verticalDirection = TextDirection.VERTICAL_RL)
        val lr = TextRegionMerger.merge(cols, verticalDirection = TextDirection.VERTICAL_LR)

        // 未合并成一组 → 本用例失去意义，直接失败而不是静默跳过（防假通过）
        assertEquals("三列竖排应合并成 1 组；实际 RL=$rl", 1, rl.size)
        assertEquals("三列竖排应合并成 1 组；实际 LR=$lr", 1, lr.size)

        assertEquals("右→左：右列在前", listOf("右", "中", "左"), rl[0].texts)
        assertEquals("左→右：左列在前", listOf("左", "中", "右"), lr[0].texts)
    }

    // ---------- sortByReadingOrder：竖排主键必须是 x ----------

    /**
     * ⚠️ **竖排的排序主键必须是 x，不能是 y。**
     *
     * 旧实现是 `compareBy { top }.thenByDescending { left }` —— 对横排正确，对竖排错误：
     * 真实竖排各列的 `top` 不会像素级齐平，只要差一点，`left` 这个**次级键永不参与比较**，
     * 列序完全由 y 噪声决定、设置静默失效。
     *
     * 本用例把两列的 top **故意错开**（10 vs 40）且与列序**相反**：
     * 按 x 排 → RL=[右,左]、LR=[左,右]；按 y 排 → 两种设置都为 [右,左]，LR 就错了。
     */
    @Test
    fun sortByReadingOrder_verticalPrimaryKeyIsXNotY() {
        // 右列 top=10（更靠上）、左列 top=40 —— y 序与 x 序相反
        val rightCol = Rect(70, 10, 110, 200)
        val leftCol = Rect(0, 40, 40, 230)
        val input = listOf(leftCol, rightCol)

        val rl = MangaSpatialGrouping.sortByReadingOrder(input, { it }, TextDirection.VERTICAL_RL)
        assertEquals("右→左：右列在前（不得被 top 带偏）", listOf(70, 0), rl.map { it.left })

        val lr = MangaSpatialGrouping.sortByReadingOrder(input, { it }, TextDirection.VERTICAL_LR)
        assertEquals("左→右：左列在前（旧实现这里是错的）", listOf(0, 70), lr.map { it.left })
    }

    /** 同列内的多段竖排：两种设置都必须自上而下。 */
    @Test
    fun sortByReadingOrder_sameColumnIsTopDown() {
        val upper = Rect(0, 0, 40, 100)
        val lower = Rect(0, 120, 40, 220)
        for (dir in listOf(TextDirection.VERTICAL_RL, TextDirection.VERTICAL_LR)) {
            val out = MangaSpatialGrouping.sortByReadingOrder(
                listOf(lower, upper), { it }, dir
            )
            assertEquals("dir=$dir 同列必须上→下", listOf(0, 120), out.map { it.top })
        }
    }

    /**
     * ⚠️ **横排恒上→下、行内左→右，不受设置影响**（横排句子被反转 = 数据被改坏）。
     */
    @Test
    fun sortByReadingOrder_horizontalIgnoresSetting() {
        val row0Left = Rect(0, 0, 100, 30)
        val row0Right = Rect(200, 0, 300, 30)
        val row1 = Rect(0, 60, 100, 90)
        val input = listOf(row0Right, row1, row0Left)

        for (dir in listOf(TextDirection.VERTICAL_RL, TextDirection.VERTICAL_LR)) {
            val out = MangaSpatialGrouping.sortByReadingOrder(input, { it }, dir)
            assertEquals(
                "dir=$dir 横排必须上→下、行内左→右",
                listOf(0 to 0, 200 to 0, 0 to 60),
                out.map { it.left to it.top }
            )
        }
    }

    /** 同页混排：竖排优先于横排（竖排正文先读，横排标题后读）。 */
    @Test
    fun sortByReadingOrder_verticalBeforeHorizontal() {
        val vertical = Rect(0, 0, 40, 200)
        val horizontal = Rect(0, 210, 200, 240)
        val out = MangaSpatialGrouping.sortByReadingOrder(
            listOf(horizontal, vertical), { it }, TextDirection.VERTICAL_RL
        )
        assertEquals("竖排应在前", 200, out[0].bottom)
        assertEquals("横排应在后", 240, out[1].bottom)
    }

    // ---------- 横排：不随设置变 ----------

    /**
     * ⚠️ **横排的拼接顺序必须与设置无关**。
     * 横排句子被反转 = 送进翻译的数据被改坏（不只是显示问题）。
     */
    @Test
    fun merge_horizontalOrderUnaffectedByFlow() {
        val line1 = vRegion("第一行文本", 0, 0, 200, 20)
        val line2 = vRegion("第二行文本", 0, 25, 200, 45)
        val input = listOf(line1, line2)

        val rl = TextRegionMerger.merge(input, verticalDirection = TextDirection.VERTICAL_RL)
        val lr = TextRegionMerger.merge(input, verticalDirection = TextDirection.VERTICAL_LR)

        val t1 = groupOf(rl, "第一行文本")
        val t2 = groupOf(lr, "第一行文本")

        assertNotNull("横排两行应合并成一组；实际 RL=$rl", t1)
        assertNotNull("同上；实际 LR=$lr", t2)
        assertEquals("横排顺序不得随竖排设置变", t1, t2)
        assertEquals("横排应按第一行在前", "第一行文本第二行文本", t1)
    }

    // ---------- 组标签 ----------

    /** 合并组的方向标签必须跟随设置（`OverlayRenderer.buildMergedItem` 依赖它选锚角）。 */
    @Test
    fun mergedGroupDirectionFollowsSetting() {
        val a = vRegion("あ", 0, 0, 20, 80)
        val b = vRegion("い", 0, 85, 20, 165)
        val rl = TextRegionMerger.merge(listOf(a, b), verticalDirection = TextDirection.VERTICAL_RL)
        val lr = TextRegionMerger.merge(listOf(a, b), verticalDirection = TextDirection.VERTICAL_LR)

        assertEquals(TextDirection.VERTICAL_RL, rl[0].direction)
        assertEquals(TextDirection.VERTICAL_LR, lr[0].direction)
    }

    // ---------- MangaSpatialGrouping（增量路径） ----------

    /**
     * ⚠️ `sortByMangaReadingOrder` **固定右→左，不接受方向参数**。
     *
     * 它只服务 RT-DETR-V2 增量路径，而 RT-DETR 只识别日文、右→左是唯一正确的列序。
     * `Manga_Text_Direction` 只适配 PP 系列模型，不要给这个函数接方向
     * （曾误接过 —— 会让 RT-DETR 在用户切「左→右」时排错日文列序）。
     */
    @Test
    fun sortByMangaReadingOrder_isAlwaysRightToLeft() {
        val leftCol = bubble(0, 0, 50, 200)
        val rightCol = bubble(100, 0, 150, 200)
        val sorted = MangaSpatialGrouping.sortByMangaReadingOrder(listOf(leftCol, rightCol))
        assertEquals("RT-DETR 路径固定右列在前", 100, sorted[0].rect.left)
        assertEquals("RT-DETR 路径固定右列在前（含同 top 情形）", 0, sorted[1].rect.left)
    }

    /**
     * ⚠️ **两条链路的竖排列序必须给出同一答案**。
     * 截屏翻译走 `TextRegionMerger`，增量走 `MangaSpatialGrouping`；
     * 两者不一致的话，同一本书换个模式读就会前后颠倒。
     *
     * ⚠️ 读的是 `TextRegionGroup.texts` / `memberIndices`（**排好序的**），
     * 不是 `members` —— 后者是 `nodes.map{}` 且 `nodes = nodeSet.toList()`，
     * 是 Set 迭代序、**不保证**阅读顺序。生产路径读的也是 `texts`。
     */
    @Test
    fun bothPathsAgreeOnColumnOrder() {
        // 列宽 40、列距 30（< charSize×CHAR_GAP_TOLERANCE）→ 必合并成 1 组
        val lText = vRegion("L", 0, 0, 40, 200)
        val rText = vRegion("R", 70, 0, 110, 200)

        for (dir in listOf(TextDirection.VERTICAL_RL, TextDirection.VERTICAL_LR)) {
            // PP 路径的另一半：行裁剪后排序同样按设置
            val viaGrouping = MangaSpatialGrouping
                .sortByReadingOrder(
                    listOf(bubble(0, 0, 40, 200), bubble(70, 0, 110, 200)), { it.rect }, dir
                )
                .map { it.rect.left }

            // 截屏路径：从已排序的 members 索引取几何
            val merged = TextRegionMerger.merge(listOf(lText, rText), verticalDirection = dir)
            assertEquals("dir=$dir 应只有一个组；实际=$merged", 1, merged.size)
            val viaMerger = merged[0].memberIndices.map { merged[0].members[it].quad.aabb.left }

            assertEquals("dir=$dir 两条链路的列序必须一致", viaGrouping, viaMerger)
            assertEquals(
                "dir=$dir 文本顺序应与几何顺序同步",
                merged[0].texts,
                viaMerger.map { if (it == 0) "L" else "R" }
            )
        }
    }
}
