package com.moe.starflow.translate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 框选 → 裁剪坐标换算的不变量测试（**纯 JVM**，无 Robolectric）。
 *
 * 参照 `LayoutEngineTest` 的既有范式：几何内核不引入 Android 类型，
 * 因为单测开了 `unitTests.returnDefaultValues`，`Rect`/`RectF` 在纯 JVM 下是桩类
 * （`width()` 返回 0），拿真实类型根本测不了。
 *
 * 本文件是该目录（`translate/screenshot/`）的**第一份测试** —— 此前
 * `ScreenshotManager` / `CropView` / `Shooter` / providers 与所有 `crop.left + offset`
 * 换算零覆盖，这也是同一条链路反复返工却查无实据的原因之一。
 */
class CropSpaceTest {

    private fun win(w: Int, h: Int, ox: Int = 0, oy: Int = 0) = WinGeom(w, h, ox, oy)
    private fun frame(w: Int, h: Int) = FrameGeom(w, h)

    // ---------- 恒等分支（回归基线，最高优先级） ----------

    /**
     * ⚠️ 硬约束：帧与窗口同几何且原点为 0 时**必须原样返回**。
     * 这是「本来正常的设备零回归」的唯一保证 —— 加了取整或缩放就会在这类设备上引入偏移。
     */
    @Test
    fun sameSizeAndZeroOrigin_isExactIdentity() {
        val crop = CropSpace.IntRect(95, 1384, 1169, 1784)
        val res = CropSpace.resolveCropRect(crop, win(1220, 2712), frame(1220, 2712))

        assertTrue("应走恒等分支", res.identical)
        assertTrue("恒等分支必须有依据", res.calibrated)
        assertEquals("左", crop.left, res.rect.left)
        assertEquals("上", crop.top, res.rect.top)
        assertEquals("右", crop.right, res.rect.right)
        assertEquals("下", crop.bottom, res.rect.bottom)
    }

    /**
     * 恒等分支**不做任何算术**的强证明：给一组「换算一碰就会变」的值
     * （含负坐标、越界、非整比例），结果仍必须逐字等于输入。
     */
    @Test
    fun identityBranch_performsNoArithmetic() {
        val awkward = listOf(
            CropSpace.IntRect(-37, -11, 3, 5),        // 负坐标
            CropSpace.IntRect(0, 0, 99999, 99999),    // 越界
            CropSpace.IntRect(7, 13, 7, 13),          // 空矩形
            CropSpace.IntRect(1, 2, 3, 4),            // 极小
        )
        for (crop in awkward) {
            val res = CropSpace.resolveCropRect(crop, win(100, 100), frame(100, 100))
            assertTrue("$crop 应恒等", res.identical)
            assertEquals("$crop 左被改动", crop.left, res.rect.left)
            assertEquals("$crop 上被改动", crop.top, res.rect.top)
            assertEquals("$crop 右被改动", crop.right, res.rect.right)
            assertEquals("$crop 下被改动", crop.bottom, res.rect.bottom)
        }
    }

    // ---------- 未定标分支（当前行为，待实测后替换） ----------

    /**
     * ⚠️ 尺寸不同但**尚未实测出帧语义** → 必须原样返回并标 `calibrated=false`，
     * 由调用方记日志。**在拿到实测数据前不得在此补比例公式**：
     * 「整屏等比缩放+黑边」与「1:1 取景」两种语义下公式完全不同，猜错就是整体偏移。
     *
     * 这条测试的作用是「锁住未定标状态」——将来定标时它会失败，提醒实现者一并更新。
     */
    @Test
    fun sizeMismatch_isLeftUncalibrated() {
        val crop = CropSpace.IntRect(100, 200, 400, 600)
        val res = CropSpace.resolveCropRect(crop, win(2400, 1080), frame(2316, 1080))

        assertFalse("未定标时不应声称已换算", res.calibrated)
        assertFalse("未定标时也不该走恒等", res.identical)
        assertEquals("未定标时必须原样返回（不做猜测）", crop, res.rect)
    }

    /**
     * 帧与窗口**同尺寸**时，无论窗口原点在哪，裁剪矩形都可直接使用 ——
     * 帧覆盖的就是窗口那块像素区，两者同为窗口相对坐标，origin 与裁剪无关。
     *
     * （曾经把 origin==0 塞进恒等判据，是错的：会在「同尺寸且窗口不在显示原点」时误判为未定标。）
     */
    @Test
    fun sameSize_isIdentityRegardlessOfWindowOrigin() {
        val crop = CropSpace.IntRect(10, 20, 110, 220)
        for (origin in listOf(0 to 0, 0 to 84, 138 to 0, 138 to 84, -50 to -50)) {
            val win = win(1080, 2400, ox = origin.first, oy = origin.second)
            val res = CropSpace.resolveCropRect(crop, win, frame(1080, 2400))
            assertTrue("origin=$origin 同尺寸应恒等", res.identical)
            assertTrue("origin=$origin 应有依据", res.calibrated)
            assertEquals("origin=$origin 不应改动矩形", crop, res.rect)
        }
    }

    // ---------- 退化输入 ----------

    @Test
    fun degenerateInputs_doNotCrashAndStayUncalibrated() {
        val crop = CropSpace.IntRect(1, 2, 3, 4)
        val cases = listOf(
            "窗口宽为 0" to (win(0, 100) to frame(100, 100)),
            "窗口高为 0" to (win(100, 0) to frame(100, 100)),
            "帧宽为 0" to (win(100, 100) to frame(0, 100)),
            "帧高为 0" to (win(100, 100) to frame(100, 0)),
            "负尺寸" to (win(-5, 100) to frame(100, 100)),
        )
        for ((desc, pair) in cases) {
            val res = CropSpace.resolveCropRect(crop, pair.first, pair.second)
            assertFalse("$desc 不应标为已定标", res.calibrated)
            assertEquals("$desc 应原样返回", crop, res.rect)
        }
    }

    // ---------- 桥接与工具 ----------

    @Test
    fun fromRectF_truncatesTowardZero() {
        // toInt() 截断（非四舍五入）—— 与旧实现 cropRect.left.toInt() 一致，勿改
        val r = CropSpace.fromRectF(10.9f, 20.2f, 110.7f, 220.1f)
        assertEquals(10, r.left)
        assertEquals(20, r.top)
        assertEquals(110, r.right)
        assertEquals(220, r.bottom)
        assertEquals(100, r.width)
        assertEquals(200, r.height)
    }

    @Test
    fun intRect_isEmptyForNonPositiveExtent() {
        assertTrue(CropSpace.IntRect(5, 5, 5, 9).isEmpty())    // 宽 0
        assertTrue(CropSpace.IntRect(5, 5, 9, 5).isEmpty())    // 高 0
        assertTrue(CropSpace.IntRect(9, 9, 5, 5).isEmpty())    // 反向
        assertFalse(CropSpace.IntRect(0, 0, 1, 1).isEmpty())
    }

    @Test
    fun nearlyEquals_respectsTolerance() {
        val a = CropSpace.IntRect(10, 10, 100, 100)
        assertTrue(CropSpace.nearlyEquals(a, CropSpace.IntRect(11, 10, 100, 100), tolerance = 1))
        assertFalse(CropSpace.nearlyEquals(a, CropSpace.IntRect(12, 10, 100, 100), tolerance = 1))
        assertTrue(CropSpace.nearlyEquals(a, a))
    }

    /** 诊断串必须带上定标所需的三个量（frame / window / origin），否则真机定标无从下手。 */
    @Test
    fun describe_carriesCalibrationTriple() {
        val win = win(2574, 1220, ox = 138, oy = 0)
        val frame = frame(2574, 1220)
        val crop = CropSpace.IntRect(0, 0, 10, 10)
        val line = CropSpace.describe(crop, win, frame, CropSpace.resolveCropRect(crop, win, frame))

        for (token in listOf("frame=2574x1220", "window=2574x1220", "origin=(138,0)", "identity=", "calibrated=")) {
            assertTrue("诊断串缺少 $token：$line", line.contains(token))
        }
    }
}
