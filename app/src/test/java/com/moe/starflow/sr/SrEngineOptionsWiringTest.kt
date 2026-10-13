package com.moe.starflow.sr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P1「按 TachiyomiX 的方法重构超分」的**接线守卫**（源码级，纯 JVM）。
 *
 * 为什么必须源码级：这一版新增的两个能力一个在 **C++（`:sr`）里**、
 * 一个要**穿过 4 层**（清单 JSON → ModelInfo → SuperResolutionEngine → NcnnSrEngine → JNI → 引擎），
 * JVM 单测碰不到 Vulkan，跑得起来也测不出"参数没传下去"。而漏传的症状是**静默的**：
 * 功能"没坏"，只是白丢一半速度（fp16 算术）或白跑几秒（跳过阈值）。
 *
 * ⚠️ 断言用**相对路径 + 存在性兜底**（与 `ModelManagementLayoutTest.fragmentSrIdsAllExistInLayout` 同做法）：
 * 工作目录不是 Gradle 模块目录时直接跳过，避免 CI/IDE 下假红。
 */
class SrEngineOptionsWiringTest {

    private fun read(path: String): String? = File(path).takeIf { it.isFile }?.readText()

    private val jni get() = read("../sr/src/main/cpp/sr_jni.cpp")

    // ───────────────────────── fp16 算术 ─────────────────────────

    @Test
    fun jniTakesTheFp16FlagAndSetsItBeforeLoad() {
        val src = jni ?: return
        assertTrue(
            "create() 必须收下 fp16Arithmetic（不然上层开关到不了引擎）",
            src.contains("jint numThreads, jboolean fp16Arithmetic,") &&
                src.contains("jstring inputBlob, jstring outputBlob, jboolean bgr)"),
        )
        // 每个 family 都必须「先赋值、再 load」：ncnn 的 opt 在 load() 里固化，顺序反了就静默无效
        for (family in listOf("Waifu2x", "SRMD", "RealCUGAN", "RealESRGAN")) {
            val assign = src.indexOf("new $family(")
            assertTrue("$family 的构造点找不到", assign > 0)
            val set = src.indexOf("->fp16_arithmetic =", assign)
            val load = src.indexOf("->load(param, bin)", assign)
            assertTrue("$family 缺 fp16_arithmetic 赋值", set > assign)
            assertTrue("$family 必须先赋 fp16_arithmetic 再 load()（opt 在 load 时固化）", set < load)
        }
    }

    @Test
    fun everyEngineChecksDeviceCapabilityInsteadOfHardcodingFalse() {
        for (eng in listOf("waifu2x", "srmd", "realcugan", "realesrgan")) {
            val cpp = read("../sr/src/main/cpp/engines/$eng.cpp") ?: return
            val hdr = read("../sr/src/main/cpp/engines/$eng.h") ?: return
            assertTrue(
                "$eng: 不能再把 use_fp16_arithmetic 写死（只开 storage 省的是显存，不是算力）",
                !cpp.contains("net.opt.use_fp16_arithmetic = false;"),
            )
            assertTrue(
                "$eng: 必须按设备能力 + 上层开关决定（照 TachiyomiX 的写法）",
                cpp.contains("support_fp16_arithmetic()") && cpp.contains("fp16_arithmetic &&"),
            )
            assertTrue("$eng.h 必须暴露 fp16_arithmetic 供 JNI 在 load 前设置", hdr.contains("bool fp16_arithmetic"))
        }
    }

    @Test
    fun kotlinSideThreadsTheFlagThrough() {
        val native = read("../sr/src/main/java/com/moe/starflow/sr/ncnn/SrNcnnNative.kt") ?: return
        assertTrue("JNI 门面必须声明 fp16Arithmetic 形参", native.contains("fp16Arithmetic: Boolean"))
        assertTrue(
            "门面注释要写清「create 时定、process 改不动」，否则后人会想挪到 process",
            native.contains("在 `load()` 里固化"),
        )
        val engine = read("src/main/java/com/moe/starflow/sr/NcnnSrEngine.kt") ?: return
        assertTrue("NcnnSrEngine 要有 fp16Arithmetic 参数", engine.contains("private val fp16Arithmetic: Boolean"))
        assertTrue("NcnnSrEngine 必须把它传进 create", engine.contains("GPU_ID, srThreads(),\n                fp16Arithmetic"))
        val facade = read("src/main/java/com/moe/starflow/sr/SuperResolutionEngine.kt") ?: return
        assertTrue("引擎工厂必须从清单取（不许写死 true/false）", facade.contains("fp16Arithmetic = info.srFp16Arithmetic"))
        val info = read("src/main/java/com/moe/starflow/download/ModelInfo.kt") ?: return
        assertTrue("ModelInfo 要有 srFp16Arithmetic 字段", info.contains("val srFp16Arithmetic: Boolean"))
        val repo = read("src/main/java/com/moe/starflow/download/ModelDownloadRepository.kt") ?: return
        assertTrue(
            "清单解析：缺字段要默认 true（对齐 TachiyomiX 模型包默认），别默认 false 把速度白丢了",
            repo.contains("optBoolean(\"fp16Arithmetic\", true)"),
        )
    }

    // ───────────────────────── 跳过阈值 ─────────────────────────

    @Test
    fun inputBudgetDefaultUsesTheExplicitMpOption() {
        assertEquals(1.5f, SrOptions().maxInputMegapixels)
        assertEquals(0f, SrOptions(maxInputMegapixels = 0f).maxInputMegapixels)
    }

    @Test
    fun engineLevelOptionsRebuildTheEngineAndReachTheNativeLayer() {
        val facade = read("src/main/java/com/moe/starflow/sr/SuperResolutionEngine.kt") ?: return
        assertTrue(
            "引擎缓存键必须带选项指纹（tile/精度/fp16 是 net 级、load 时固化，只按模型缓存＝设置不生效）",
            facade.contains("val cacheKey = key.name + \"#\" + options.engineKey()"),
        )
        assertTrue(
            "引擎级选项必须真的传进 NcnnSrEngine",
            facade.contains("tileSize = options.tileSize.coerceAtLeast(SrOptions.MIN_TILE_SIZE)"),
        )
        assertTrue(
            "fp16 算术要「清单 && 用户开关」同时允许（默认关，对齐参照项目）",
            facade.contains("fp16Arithmetic = info.srFp16Arithmetic && options.fp16Arithmetic"),
        )
        val engine = read("src/main/java/com/moe/starflow/sr/NcnnSrEngine.kt") ?: return
        assertTrue("NcnnSrEngine 的 fp16 默认值必须也是 false", engine.contains("private val fp16Arithmetic: Boolean = false"))
    }
}
