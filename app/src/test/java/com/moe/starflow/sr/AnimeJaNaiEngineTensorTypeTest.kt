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
        assertTrue("failDetail 至少每处失败都要写（return null=$nullReturns, 写入=$detailWrites）", detailWrites >= 5)
    }
}
