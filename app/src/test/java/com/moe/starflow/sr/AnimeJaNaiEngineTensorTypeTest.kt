package com.moe.starflow.sr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **ONNX 输入张量类型的回归守卫**（源码级，纯 JVM）。
 *
 * ## 这条守卫是拿真机 log 换来的
 * ```
 * ORT_INVALID_ARGUMENT: Unexpected input data type.
 *   Actual: (tensor(int16)), expected: (tensor(float16))
 * ```
 * ORT 的 Java API **靠 buffer 类型推断张量类型**：`createTensor(env, ShortBuffer, shape)` 造出来的是
 * `tensor(int16)`，而 AnimeJaNai / waifu2x 那 11 个模型要的是 `tensor(float16)`。
 * 正确写法只有一条：`createTensor(env, ByteBuffer, shape, OnnxJavaType.FLOAT16)`。
 *
 * ⚠️ **这个坑在 Python 侧不存在**（`np.float16` 自带 dtype），所以"本机 python 验证过能跑"
 * 推不出"设备上能跑" —— 上一轮我就是这么被骗过去的（11 个模型全部跑不通却以为修好了）。
 */
class AnimeJaNaiEngineTensorTypeTest {

    private fun src() = File("src/main/java/com/moe/starflow/sr/AnimeJaNaiEngine.kt")
        .readText().replace("\r\n", "\n")

    @Test
    fun fp16InputUsesOnnxJavaTypeNotShortBuffer() {
        val s = src()
        assertTrue(
            "fp16 输入必须走 ByteBuffer + OnnxJavaType.FLOAT16",
            s.contains("OnnxTensor.createTensor(e, buf, shape, OnnxJavaType.FLOAT16)"),
        )
        assertFalse(
            "绝不能用 ShortBuffer 建输入张量 —— ORT 会当成 tensor(int16)，模型要的是 tensor(float16)",
            s.contains("createTensor(e, buf.asShortBuffer(), shape)"),
        )
        assertFalse(
            "也不许从别处拿 ShortBuffer 去建张量",
            s.contains("createTensor(e, sb"),
        )
    }

    @Test
    fun haloProbeIsLargeEnoughForCunet() {
        // 真机报错：`output 1400x1944, expected 1472x2016 (iw=736 ih=1008 halo=0)` —— 差的 72
        // 正是 cunet 的 halo，也就是说 halo **没被探测出来**。
        // 根因：探测尺寸太小（64 → 输出 128），而 cunet 的 72 占了 56%，被"异常"判据
        // `halo >= n*scale/2`（72 >= 64）判成探测失败 → 返回 0。
        val s = src()
        val probe = Regex("const val PROBE = (\\d+)").find(s)!!.groupValues[1].toInt()
        assertTrue("探测尺寸必须 >= 256（cunet 的 72px halo 才不会被判成异常），实际 $probe", probe >= 256)
        assertTrue("要有更大的补救探测尺寸", s.contains("const val PROBE_LARGE"))
        assertTrue("第一次不可信要换尺寸重试", s.contains("probeHaloOnce(s, PROBE_LARGE)"))
        assertFalse(
            "不许再用 `halo >= n*scale/2` 当异常判据（它会把 cunet 判死）",
            s.contains("halo >= n * scale / 2"),
        )
        assertTrue(
            "判据应是「输出至少 16px」",
            s.contains("halo > n * scale - 16"),
        )
    }
    @Test
    fun ortsessionUsesTheMeasuredFastestConfig() {
        // 真机实测（SrBenchmark，2026-10，Redmi 2407FRK8EC / 8 核）同一模型同一尺寸：
        //   threads=4 ALL_OPT 2237ms | threads=6 1981ms | threads=8 1758ms
        //   threads=8 BASIC_OPT 2215ms（慢 26%）| threads=8 +NNAPI 1772ms（另一模型 6087ms，慢 3.5 倍）
        // ⇒ ALL_OPT + 8 线程；NNAPI 一律不要。
        val s = src()
        assertTrue("必须用 ALL_OPT（比 BASIC_OPT 快 26%）", s.contains("OptLevel.ALL_OPT"))
        assertFalse("不许退回 BASIC_OPT", s.contains("OptLevel.BASIC_OPT"))
        // 线程数改为**按核数自动决定**（用户口径：8 核就用 6 核）→ 策略收在 SrThreads.kt，
        // 引擎与基准工具共用同一个函数，避免两处漂移。
        assertTrue("引擎要用共用的 srThreads()", s.contains("srThreads()"))
        val policy = java.io.File("src/main/java/com/moe/starflow/sr/SrThreads.kt").readText()
        assertTrue("策略必须是 核数 - 2", policy.contains("availableProcessors() - 2"))
        assertTrue("要钳在 2..8（4 核机器上写死 8 只会抢核）", policy.contains("coerceIn(2, 8)"))
        assertFalse("不许引入 NNAPI（实测无收益，还有模型慢 3.5 倍）", s.contains("addNnapi"))
    }
    @Test
    fun inputBufferIsRewoundBeforeTensorCreation() {
        // ORT 从 ByteBuffer 的当前 position 开始读；不归零就会读到上一轮的尾巴
        assertTrue("建张量前必须 rewind", src().contains("buf.rewind()"))
    }

    @Test
    fun failDetailIsRecordedForEveryNullReturn() {
        // 用户口径：失败必须能说清原因 —— 每个 return null 前面都该写一次 failDetail，
        // 否则 UI 只能给一句"推理失败"（那就是上一版被投诉的模糊提示）
        val s = src()
        val nullReturns = Regex("return null").findAll(s).count()
        val detailWrites = Regex("failDetail = ").findAll(s).count()
        // ⚠️ 断言的是**真实的不变式**（写入 ≥ 失败出口数），不是随手挑的阈值 5 ——
        //    写死 5 的话删掉任意几处 detail 写入用例照样绿，等于没锁（2026-10 审查发现）。
        assertTrue(
            "failDetail 至少每处失败出口都要写一次（return null=$nullReturns, 写入=$detailWrites）",
            detailWrites >= nullReturns,
        )
    }
}
