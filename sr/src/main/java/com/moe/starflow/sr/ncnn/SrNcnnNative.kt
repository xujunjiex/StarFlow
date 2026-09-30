package com.moe.starflow.sr.ncnn

import java.nio.ByteBuffer

/**
 * `:sr` 原生模块的 JNI 门面 —— **本模块唯一的 Kotlin 文件**。
 *
 * 引擎选择、模型路径解析、Bitmap 拆装、失败降级都在 :app 的
 * `com.moe.starflow.sr.NcnnSrEngine` 里；这里只做「参数原样传下去、结果原样拿回来」。
 *
 * ## 像素契约
 * 输入输出都是**紧凑 RGB8**（不是 Bitmap、不是 RGBA）：
 * - `inBuf` 长度 `w * h * 3`
 * - `outBuf` 长度 `(w*scale) * (h*scale) * 3`
 * - 必须是 `allocateDirect`，否则原生侧拿不到地址（会返回 false 并打日志）
 *
 * 之所以不走 Bitmap：Bitmap 是 RGBA，多一个 alpha 通道会被引擎一起算（白费 1/3 算力），
 * 而且要走 jnigraphics。RGB 直传更省也更可控。
 *
 * ## 生命周期
 * ```
 * val handle = SrNcnnNative.create(family, paramPath, binPath, gpuId, numThreads)  // 0 = 失败
 * ...      SrNcnnNative.process(handle, in, out, w, h, scale, noise, prepad, tile)
 * finally  SrNcnnNative.release(handle)
 * ```
 * **同一个 handle 内部有锁**，可并发调用（阅读器预取与截图链路会同时来）；
 * 但 `release` 之后不得再 `process`。
 */
object SrNcnnNative {

    /** waifu2x 家族（upconv_7 / cunet），参数语义：noise = 降噪档 -1~3 */
    const val FAMILY_WAIFU2X = 0

    /** SRMD 家族，参数语义：noise = 退化强度 -1~10 */
    const val FAMILY_SRMD = 1

    /** Real-CUGAN 家族（koto 在用），noise 忽略；syncgap 固定 3 */
    const val FAMILY_REALCUGAN = 2

    /** Real-ESRGAN 家族（x4plus / anime 6B 等），noise 忽略 */
    const val FAMILY_REALESRGAN = 3

    init {
        System.loadLibrary("starflow_sr")
    }

    /**
     * 建引擎实例（含加载模型）。
     *
     * @param gpuId `0` 起为 Vulkan 设备号；**-1 = CPU**（慢 6~8 倍，只作兜底）。
     *   ⚠️ 只有 Waifu2x / RealCUGAN 有 CPU 路径：SRMD 与 Real-ESRGAN 是纯 Vulkan，
     *   设备无 Vulkan 时对这两族直接返回 0（否则原生侧空 vkdev 解引用 → SIGSEGV）。
     * @param numThreads 推理线程数（Kotlin 侧 `SrThreads` = 核数-2 夹 2..8）。≤0 = 原生侧按 2 兜底。
     * @return handle；**0 表示失败**（调用方必须当作"超分不可用"处理，不要重试轰炸）。
     *   ⚠️ 原生侧会先校验 `param` 有 ncnn 魔数、`bin` 非空 —— vendored 引擎的 `load()`
     *   返回值不可用（恒定 `return 0`），不这样查的话这里永远不会拿到 0。
     */
    external fun create(
        family: Int,
        paramPath: String,
        binPath: String,
        gpuId: Int,
        numThreads: Int
    ): Long

    /**
     * 跑一次超分。
     *
     * @return true 成功。false = 失败（**调用方静默降级到原图**，绝不因此让用户翻不了页）
     */
    external fun process(
        handle: Long,
        inBuf: ByteBuffer,
        outBuf: ByteBuffer,
        w: Int,
        h: Int,
        scale: Int,
        noise: Int,
        prepadding: Int,
        tileSize: Int
    ): Boolean

    /** 释放实例。可重复调用。 */
    external fun release(handle: Long)

    /** Vulkan 设备名；无 GPU 时返回 "CPU"。仅用于设置页/日志展示。 */
    external fun gpuName(): String

    /** Vulkan 设备数量 */
    external fun gpuCount(): Int

    /** GPU 可用堆（MB），用于选 tile 尺寸；无 GPU 时 0 */
    external fun heapBudgetMb(): Int

    /**
     * 原生侧按显存算出的 tile 兜底值（≥32）。
     *
     * ⚠️ **不要自己另写一套**：这个值与 nihui 各工具 `main.cpp` 的默认策略一致，
     * 而 tile 会决定 RealCUGAN 走哪条网络分支（见 sr_jni.cpp 顶部注释）。
     * `process(..., tileSize = 0)` 时原生侧就用它。
     */
    external fun autoTileSize(): Int
}
