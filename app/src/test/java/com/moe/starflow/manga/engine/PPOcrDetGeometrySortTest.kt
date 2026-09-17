package com.moe.starflow.manga.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * det 候选顺序不变量（`Manga_Text_Direction` 对 OCR 生效）。
 *
 * 扫描序同时决定**识别顺序**与**源文拼接顺序** —— 两条链路共用 `boxes` 的排列，
 * 所以这里直接对着「送进翻译的字符串顺序」写断言，而不是只对着实现细节。
 *
 * ⚠️ 断言里的几何一律走生产 helper（[PPOcrDetGeometry.boxLeft] 等），
 * 不在测试里另写一份 min/max —— 否则测试与实现会一起错、照样通过。
 *
 * ⚠️ `verticalScanFlowIsLr` 是 object 级 [Volatile] 状态，用例之间必须复位。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PPOcrDetGeometrySortTest {

    @After
    fun resetFlow() {
        PPOcrDetGeometry.verticalScanFlowIsLr = false
    }

    /** 轴对齐四边形 `[x0,y0, x1,y1, x2,y2, x3,y3]` = TL, TR, BR, BL。 */
    private fun box(l: Float, t: Float, r: Float, b: Float) =
        floatArrayOf(l, t, r, t, r, b, l, b)

    /** 竖排（高 > 宽）。 */
    private fun vBox(l: Float, t: Float, r: Float, b: Float) = box(l, t, r, b)

    /** 横排（宽 > 高）。 */
    private fun hBox(l: Float, t: Float, r: Float, b: Float) = box(l, t, r, b)

    /** 有序的左边界列表，代表阅读顺序。 */
    private fun lefts(boxes: List<FloatArray>) = boxes.map { PPOcrDetGeometry.boxLeft(it) }

    /** 有序的上边界列表。 */
    private fun tops(boxes: List<FloatArray>) = boxes.map { PPOcrDetGeometry.boxTop(it) }

    private fun sort(boxes: List<FloatArray>, lr: Boolean): List<FloatArray> =
        PPOcrDetGeometry.sortDetCandidates(boxes, List(boxes.size) { 0f }, lr).boxes

    // ---------- 竖排：列序随设置 ----------

    /**
     * 两列竖排左右并排 —— 日漫最常见的「右列先读」版式。
     * 这是用户切换 `Manga_Text_Direction` 时**必须**改变结果的那一项。
     */
    @Test
    fun vertical_flowControlsColumnOrder() {
        val leftCol = vBox(0f, 0f, 40f, 200f)
        val rightCol = vBox(60f, 0f, 100f, 200f)
        val input = listOf(leftCol, rightCol)

        assertEquals("右→左应取右列在前", listOf(60f, 0f), lefts(sort(input, lr = false)))
        assertEquals("左→右应取左列在前", listOf(0f, 60f), lefts(sort(input, lr = true)))
    }

    /** 同一列内的多段竖排：两种流向都必须自上而下（列内方向不随设置变）。 */
    @Test
    fun vertical_sameColumnIsAlwaysTopDown() {
        val upper = vBox(0f, 0f, 40f, 100f)
        val lower = vBox(0f, 120f, 40f, 220f)
        val input = listOf(lower, upper)   // 故意倒序输入

        for (lr in listOf(false, true)) {
            assertEquals("lr=$lr 同列必须上→下", listOf(0f, 120f), tops(sort(input, lr)))
        }
    }

    /**
     * ⚠️ **横排恒定左→右，不受竖排设置影响**。
     * 该设置的语义是「竖排文字的列排列方向」；若让它反转横排，
     * 送进翻译的横排句子会被整体倒过来 —— 那是把数据改坏，不是显示问题。
     */
    @Test
    fun horizontal_staysLeftToRightRegardlessOfFlow() {
        val row0Left = hBox(0f, 0f, 100f, 30f)
        val row0Right = hBox(200f, 0f, 300f, 30f)
        val row1 = hBox(0f, 60f, 100f, 90f)
        val input = listOf(row0Right, row1, row0Left)

        val expected = listOf(0f, 200f, 0f)  // (0,0) → (200,0) → (0,60)
        for (lr in listOf(false, true)) {
            assertEquals("lr=$lr 横排必须上→下、行内左→右", expected, lefts(sort(input, lr)))
        }
    }

    /** 同页混排（竖排列 + 横排标题）：竖排整体在前。 */
    @Test
    fun mixedOrientation_verticalComesFirst() {
        val vertical = vBox(0f, 0f, 40f, 200f)      // 高 200
        val horizontal = hBox(0f, 220f, 200f, 250f) // 高 30
        val result = sort(listOf(horizontal, vertical), lr = false)

        fun height(b: FloatArray) = PPOcrDetGeometry.boxBottom(b) - PPOcrDetGeometry.boxTop(b)
        assertEquals("竖排应排在横排之前", 0f, PPOcrDetGeometry.boxLeft(result[0]))
        assertEquals("首元素应为竖排（高 200）", 200f, height(result[0]))
        assertEquals("末元素应为横排（高 30）", 30f, height(result[1]))
    }

    // ---------- 分数对齐（平行数组） ----------

    /**
     * ⚠️ `boxes` 与 `scores` 是**平行数组**，重排必须同步。
     * 不同步的话识别置信度会串到别的框上 —— 表现为随机丢框，极难排查。
     */
    @Test
    fun reorderKeepsScoresAlignedWithBoxes() {
        val leftCol = vBox(0f, 0f, 40f, 200f)
        val rightCol = vBox(60f, 0f, 100f, 200f)
        val result = PPOcrDetGeometry.sortDetCandidates(
            listOf(leftCol, rightCol), listOf(0.11f, 0.99f), verticalScanFlowIsLr = false
        )
        assertEquals("右列在前", listOf(60f, 0f), lefts(result.boxes))
        assertEquals("分数必须跟着自己的框走", listOf(0.99f, 0.11f), result.scores)
    }

    /** 长度不一致时不得抛异常、不得错位（保底：boxes 重排，scores 原样）。 */
    @Test
    fun scoreLengthMismatchDoesNotThrow() {
        val a = vBox(0f, 0f, 40f, 200f)
        val result = PPOcrDetGeometry.sortDetCandidates(listOf(a), emptyList(), verticalScanFlowIsLr = false)
        assertEquals(1, result.boxes.size)
        assertTrue(result.scores.isEmpty())
    }

    /** 空输入 / 单元素：原样返回，不得抛异常。 */
    @Test
    fun degenerateInputsAreNoOps() {
        assertTrue(PPOcrDetGeometry.sortDetCandidates(emptyList(), emptyList(), false).boxes.isEmpty())
        assertEquals(1, sort(listOf(vBox(0f, 0f, 40f, 200f)), lr = true).size)
    }

    /** 排序是**置换**：不增不减不重复（用顶点元组比对，不用可能撞值的 left）。 */
    @Test
    fun sortIsAPermutation() {
        val input = listOf(
            vBox(0f, 0f, 40f, 200f), hBox(0f, 210f, 300f, 240f), vBox(60f, 0f, 100f, 200f)
        )
        val keys = input.map { it.toList() }
        for (lr in listOf(false, true)) {
            val out = sort(input, lr).map { it.toList() }
            assertEquals("lr=$lr 数量必须保持", keys.size, out.size)
            assertEquals("lr=$lr 必须是原集合的一个排列", keys.toSet(), out.toSet())
        }
    }

    // ---------- reorder：平行数组同步重排 ----------

    /**
     * ⚠️ `reorder` 是「boxes/texts 平行数组」的正确同步方式。
     *
     * 游戏模式 PP 路径靠它把 `OcrResult.texts` 按框的阅读顺序重排 ——
     * 没有它就只能 `texts.joinToString("")` 吃 det 候选序，横屏时会整段翻。
     */
    @Test
    fun reorderAlignsParallelArrayWithBoxes() {
        val leftCol = vBox(0f, 0f, 40f, 200f)
        val rightCol = vBox(60f, 0f, 100f, 200f)
        val r = PPOcrDetGeometry.sortDetCandidates(
            listOf(leftCol, rightCol), listOf(0f, 0f), verticalScanFlowIsLr = false
        )
        assertEquals("右列在前", listOf(60f, 0f), lefts(r.boxes))
        assertEquals("平行数组必须跟着自己的框走", listOf("右", "左"), r.reorder(listOf("左", "右")))
    }

    /** 长度不一致时原样返回，不得抛异常、不得错位。 */
    @Test
    fun reorderMismatchedLengthReturnsUnchanged() {
        val r = PPOcrDetGeometry.sortDetCandidates(
            listOf(vBox(0f, 0f, 40f, 200f), vBox(60f, 0f, 100f, 200f)),
            listOf(0f, 0f), verticalScanFlowIsLr = false
        )
        assertEquals(listOf("only"), r.reorder(listOf("only")))
    }

    /** 未排序时 `reorder` 是恒等（`sourceIndices` 默认 0..n-1）。 */
    @Test
    fun reorderIsIdentityForUntouchedResult() {
        val r = PPOcrDetGeometry.BoxScoreResult(emptyList(), emptyList())
        assertEquals(emptyList<String>(), r.reorder(emptyList<String>()))
    }

    // ---------- findContours 与排序的**解耦** ----------

    /** 造一张只有白色块的掩码；blobs 为 `[left, top, right, bottom]`。 */
    private fun mask(w: Int, h: Int, blobs: List<IntArray>): Bitmap {
        val px = IntArray(w * h) { Color.TRANSPARENT }
        for (b in blobs) {
            for (y in b[1] until b[3]) for (x in b[0] until b[2]) px[y * w + x] = Color.WHITE
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun contourLefts(list: List<List<Point>>) = list.map { c -> c.minOf { it.x } }

    /**
     * `findContours` 恒为栅格扫描（左上→右下），**不随 [PPOcrDetGeometry.verticalScanFlowIsLr] 变**。
     *
     * 这是**刻意的解耦**：阅读顺序的权威只有 [PPOcrDetGeometry.sortDetCandidates] 一处。
     * 若哪天有人图省事把扫描方向也接上设置，就会冒出第二套排序机制 ——
     * 而 `runDet` 末尾的重排会把它完全覆盖，改动没有任何用户可见效果，
     * 却让「列序到底谁说了算」变成两个答案。本用例把这个契约钉死。
     */
    @Test
    fun findContours_scanDirectionIsFlowIndependent() {
        val m = mask(100, 20, listOf(intArrayOf(5, 0, 15, 20), intArrayOf(60, 0, 70, 20)))

        PPOcrDetGeometry.verticalScanFlowIsLr = false
        val rl = contourLefts(PPOcrDetGeometry.findContours(m, 100, 20, 4))
        PPOcrDetGeometry.verticalScanFlowIsLr = true
        val lr = contourLefts(PPOcrDetGeometry.findContours(m, 100, 20, 4))

        assertFalse("不应为空集", rl.isEmpty())
        assertEquals("扫描序不得随设置变（排序权威只在 sortDetCandidates）", rl, lr)
        // 栅格序：左块先被发现
        assertEquals(listOf(5, 60), rl)

        m.recycle()
    }
}
