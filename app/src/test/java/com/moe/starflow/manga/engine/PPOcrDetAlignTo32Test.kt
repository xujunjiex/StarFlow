package com.moe.starflow.manga.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * det 输入边长 **32 对齐**的回归守卫（`PPOcrDetGeometry.alignTo32`）。
 *
 * 对齐官方 RapidOCR：`int(round(x / 32) * 32)`
 * （`.reference/RapidOCR-main/python/rapidocr/ch_ppocr_det/utils.py:100`）。
 *
 * ⚠️ **必须四舍五入，不能整除截断**。截断版 `(x / 32) * 32` 对**两个轴各自独立**
 * 生效、每次最多白扔 31px；小尺寸裁剪时占比极高且两轴不等 → 把图**压扁** →
 * 竖排小字认不出、det 框数暴涨、每框只认出零散一两个字。
 *
 * 用例尺寸全部取自实测日志（PP-OCRv6 + 竖排框选）。
 */
class PPOcrDetAlignTo32Test {

    @Test
    fun `日志实测尺寸 - 94x258 曾被截成 64x256`() {
        // 官方 96x256；截断版 64x256 → 横向被压掉 32%，det=10 个碎框
        assertEquals(96, PPOcrDetGeometry.alignTo32(94))
        assertEquals(256, PPOcrDetGeometry.alignTo32(258))
    }

    @Test
    fun `日志实测尺寸 - 148x253 曾被截成 128x224`() {
        // 官方 160x256；截断版 128x224 → 两轴各缩一成多，「武部沙織」丢字
        assertEquals(160, PPOcrDetGeometry.alignTo32(148))
        assertEquals(256, PPOcrDetGeometry.alignTo32(253))
    }

    @Test
    fun `日志实测尺寸 - 116x268 两版一致所以当时看起来是好的`() {
        assertEquals(128, PPOcrDetGeometry.alignTo32(116))
        assertEquals(256, PPOcrDetGeometry.alignTo32(268))
    }

    /** 169x278 是旧版碰巧与官方一致的样例（这解释了为什么大框看起来正常）。 */
    @Test
    fun `日志实测尺寸 - 169x278 两版巧合一致`() {
        assertEquals(160, PPOcrDetGeometry.alignTo32(169))
        assertEquals(288, PPOcrDetGeometry.alignTo32(278))
    }

    /** 四舍五入的边界：每格中点是分界（32→48 向上、32→47 向下）。 */
    @Test
    fun `向上进位与向下舍入`() {
        assertEquals(32, PPOcrDetGeometry.alignTo32(17))   // 17/32=0.53 → 1 → 32
        assertEquals(32, PPOcrDetGeometry.alignTo32(31))   // 31/32=0.97 → 1 → 32
        assertEquals(32, PPOcrDetGeometry.alignTo32(33))   // 33/32=1.03 → 1 → 32（不是 64）
        assertEquals(64, PPOcrDetGeometry.alignTo32(49))   // 49/32=1.53 → 2 → 64
        assertEquals(64, PPOcrDetGeometry.alignTo32(64))   // 整格原样
        assertEquals(64, PPOcrDetGeometry.alignTo32(48))   // ⚠️ 48 = 32×1.5 是**中点**，进位到 64
        assertEquals(32, PPOcrDetGeometry.alignTo32(47))   // 47/32=1.47 → 1 → 32
        assertEquals(320, PPOcrDetGeometry.alignTo32(313)) // 313/32=9.78 → 10 → 320
    }

    /** 下限 32：极小输入不能对齐成 0（那会让 ONNX 拿到空张量）。 */
    @Test
    fun `下限保护为 32`() {
        assertEquals(32, PPOcrDetGeometry.alignTo32(0))
        assertEquals(32, PPOcrDetGeometry.alignTo32(1))
        assertEquals(32, PPOcrDetGeometry.alignTo32(-5))
    }

    /**
     * ⚠️ **两轴独立对齐会轻微改变宽高比**，这是官方行为，但偏差必须有界：
     * 每个轴最多偏 16px（round 的固有误差），**旧的截断版最多偏 31px 且方向单一**。
     * 这里钉住「偏差 ≤ 半格」这条性质。
     */
    @Test
    fun `每轴偏差不超过半格 16px`() {
        for (v in 32..2000) {
            val aligned = PPOcrDetGeometry.alignTo32(v)
            assert(kotlin.math.abs(aligned - v) <= 16) {
                "alignTo32($v)=$aligned 偏差超过 16px"
            }
            assertEquals("必须是 32 的倍数", 0, aligned % 32)
        }
    }
}
