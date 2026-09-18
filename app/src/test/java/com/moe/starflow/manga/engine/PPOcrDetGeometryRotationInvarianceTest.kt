package com.moe.starflow.manga.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **方向不变性**：同一段内容，无论帧是竖屏还是横屏，`sortDetCandidates` 给出的
 * 相对阅读顺序必须**完全一致**。
 *
 * 依据（用户的硬约束）：设备转屏时前台应用跟着一起转，用户看到的版面**相对物理屏幕没变**。
 * 所以帧里同一块内容的几何形状（列还是行）不变 —— 判定用的是「高>宽」，与帧的绝对
 * 宽高无关。本测试把这个契约钉死。
 *
 * 场景建模：竖屏帧 1080x2400，两列竖排（各 40x300），列距 30 → 左列 (100,500)，右列 (170,500)。
 * 横屏帧 2400x1080，同一内容出现在屏幕中段 → 左列 (1600,300)，右列 (1670,300)。
 * 两者的相对几何（宽 40、高 300、间距 30、右列在左列右边）**完全相同**，
 * 只有绝对坐标平移。排序结果必须相同。
 */
class PPOcrDetGeometryRotationInvarianceTest {

    /** [x0,y0,x1,y1,x2,y2,x3,y3] 顺时针四角。 */
    private fun box(left: Int, top: Int, w: Int, h: Int): FloatArray = floatArrayOf(
        left.toFloat(), top.toFloat(),
        (left + w).toFloat(), top.toFloat(),
        (left + w).toFloat(), (top + h).toFloat(),
        left.toFloat(), (top + h).toFloat()
    )

    private fun textsOf(r: PPOcrDetGeometry.BoxScoreResult, texts: List<String>) = r.reorder(texts)

    @Test
    fun `两列竖排_竖屏与横屏给出相同列序_RL`() {
        // 竖屏帧 1080x2400
        val portrait = listOf(box(100, 500, 40, 300), box(170, 500, 40, 300)) // [左列, 右列]
        // 横屏帧 2400x1080：同一内容的平移副本
        val landscape = listOf(box(1600, 300, 40, 300), box(1670, 300, 40, 300))

        val p = PPOcrDetGeometry.sortDetCandidates(portrait, listOf(1f, 1f), verticalScanFlowIsLr = false)
        val l = PPOcrDetGeometry.sortDetCandidates(landscape, listOf(1f, 1f), verticalScanFlowIsLr = false)

        assertEquals(
            "RL：竖屏与横屏的列序必须一致（都应是右列在前）",
            textsOf(p, listOf("L", "R")), textsOf(l, listOf("L", "R"))
        )
        assertEquals(listOf("R", "L"), textsOf(p, listOf("L", "R")))
    }

    @Test
    fun `两列竖排_竖屏与横屏给出相同列序_LR`() {
        val portrait = listOf(box(100, 500, 40, 300), box(170, 500, 40, 300))
        val landscape = listOf(box(1600, 300, 40, 300), box(1670, 300, 40, 300))

        val p = PPOcrDetGeometry.sortDetCandidates(portrait, listOf(1f, 1f), verticalScanFlowIsLr = true)
        val l = PPOcrDetGeometry.sortDetCandidates(landscape, listOf(1f, 1f), verticalScanFlowIsLr = true)

        assertEquals(
            "LR：竖屏与横屏的列序必须一致（都应是左列在前）",
            textsOf(p, listOf("L", "R")), textsOf(l, listOf("L", "R"))
        )
        assertEquals(listOf("L", "R"), textsOf(p, listOf("L", "R")))
    }

    /**
     * ⚠️ 核心断言：**LR 模式下，竖排内容不能被排成「横排的上→下」**。
     * 两列竖排左右并排（top 相同），若被误判成横排，排序键会退化成 top（相同）→ left 升序，
     * 结果恰好也是「左→右」——用这个样本分不出对错。
     * 所以这里把两列的 **top 错开**：真竖排按 x 主键 → 仍按列序；误判横排按 top → 上列先出。
     */
    @Test
    fun `LR下竖排必须按列序而不是按top`() {
        // 右列在下方（top 更大），左列在上方 —— 若按 top 排会把左列先出（巧合正确），
        // 故让右列 top 更小：按 top 排会先出右列，按 LR 列序应出左列。
        val boxes = listOf(
            box(100, 900, 40, 300),   // 左列，靠下
            box(170, 500, 40, 300)    // 右列，靠上
        )
        val r = PPOcrDetGeometry.sortDetCandidates(boxes, listOf(1f, 1f), verticalScanFlowIsLr = true)
        assertEquals(
            "LR 应左列在前，与 top 高低无关（按 top 排会先出右列 = 误判成横排）",
            listOf("L", "R"), textsOf(r, listOf("L", "R"))
        )
    }

    @Test
    fun `横排恒左到右_两个方向设置都一样`() {
        // 一行横排两个框并排（宽 > 高）。boxes[0] 在右(x=300)、boxes[1] 在左(x=100)
        val boxes = listOf(box(300, 500, 200, 40), box(100, 500, 200, 40))
        val texts = listOf("R", "L")
        val rl = PPOcrDetGeometry.sortDetCandidates(boxes, listOf(1f, 1f), verticalScanFlowIsLr = false)
        val lr = PPOcrDetGeometry.sortDetCandidates(boxes, listOf(1f, 1f), verticalScanFlowIsLr = true)
        assertEquals("横排恒左→右（左框是 boxes[1]=\"L\"）", listOf("L", "R"), textsOf(rl, texts))
        assertEquals("横排不受设置影响", listOf("L", "R"), textsOf(lr, texts))
    }

    /** 判定必须是「高>宽」，与绝对宽高无关：40x300 与 400x3000 结论相同。 */
    @Test
    fun `竖排判定只看形状不看绝对尺寸`() {
        val small = PPOcrDetGeometry.sortDetCandidates(
            listOf(box(100, 500, 40, 300), box(170, 500, 40, 300)), listOf(1f, 1f), false
        )
        val big = PPOcrDetGeometry.sortDetCandidates(
            listOf(box(1000, 5000, 400, 3000), box(1700, 5000, 400, 3000)), listOf(1f, 1f), false
        )
        assertEquals(listOf("R", "L"), textsOf(small, listOf("L", "R")))
        assertEquals(listOf("R", "L"), textsOf(big, listOf("L", "R")))
        assertTrue(small.sourceIndices.isNotEmpty())
    }
}
