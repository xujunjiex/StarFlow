package com.moe.starflow.sr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moe.starflow.sr.ncnn.SrNcnnNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `:sr` 原生超分引擎的**真机**验证。
 *
 * JVM 单测（`app/src/test`）跑不到这里：`System.loadLibrary("starflow_sr")` 在 JVM 上必然失败，
 * Vulkan 设备更是只有真机才有。而这条链路最容易出的恰恰是「编译通过、真机上加载不了/跑不出图」
 * —— 所以这一层必须有仪器化测试兜住。
 *
 * 跑法（模型需要先推到 app 的 `files/sr/`）：
 * ```
 * pwsh tools/sr-research/push_device_models.ps1        # 推一个小模型
 * ./gradlew :app:connectedDebugAndroidTest
 * ```
 * 没推模型的用例会被 `assumeTrue` **跳过而不是失败**，所以 CI 上不推模型也能过。
 */
@RunWith(AndroidJUnit4::class)
class NcnnSrEngineDeviceTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * 把测试模型准备好并返回 (param, bin)；拿不到就返回 null（用例会 `assumeTrue` 跳过）。
     *
     * ⚠️ 模型**放在 androidTest 的 assets 里**，由测试自己拷进 app 的 `files/sr/`。
     * 不能靠"事先 adb push 到 app 目录" —— `connectedAndroidTest` **会先卸载再安装被测 app**，
     * 那一步会把 app 外部数据目录一起删掉，push 进去的模型必然消失。
     */
    private fun prepareModel(): Pair<File, File>? {
        val dir = ctx.getExternalFilesDir("sr") ?: return null
        if (!dir.exists() && !dir.mkdirs()) return null
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for (name in listOf("w2x_up7_anime_m1.param", "w2x_up7_anime_m1.bin")) {
            val dst = File(dir, name)
            if (dst.isFile && dst.length() > 0L) continue
            try {
                assets.open("sr_test/$name").use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (t: Throwable) {
                println("[SrNcnn] 模型资产缺失 $name: ${t.message}")
                return null
            }
        }
        val param = File(dir, "w2x_up7_anime_m1.param")
        val bin = File(dir, "w2x_up7_anime_m1.bin")
        return if (param.isFile && bin.isFile) param to bin else null
    }

    /** 原生库能加载、能看到 Vulkan 设备（看不到就说明 .so 没打进包或符号没导出） */
    @Test
    fun nativeLibraryLoadsAndSeesVulkan() {
        val count = SrNcnnNative.gpuCount()
        val name = SrNcnnNative.gpuName()
        val heap = SrNcnnNative.heapBudgetMb()
        val tile = SrNcnnNative.autoTileSize()
        println("[SrNcnn] gpuCount=$count name=$name heap=${heap}MB tile=$tile")
        assertTrue("原生库应至少看到一个 Vulkan 设备（gpuCount=$count）", count >= 1)
        assertTrue("设备名不应为空", name.isNotBlank())
        assertTrue("tile 兜底值应 >= 32（实际 $tile）", tile >= 32)
    }

    /**
     * 端到端：真实模型文件 + 真实图片。
     *
     * 校验三件事，缺一不可：
     *  1. `initialize()` 成功（模型能被 ncnn 加载）
     *  2. 输出尺寸 = 输入 × scale
     *  3. **输出内容不是常量** —— 早期踩过「走了错的网络分支，process() 正常返回但图全是 0」，
     *     只看尺寸会以为一切正常
     */
    @Test
    fun upconv7AnimeUpscalesRealImage() {
        val pair = prepareModel()
        assumeTrue("测试模型资产不可用，跳过（见 prepareModel 注释）", pair != null)
        val (param, bin) = pair!!

        val engine = NcnnSrEngine(
            paramFile = param,
            binFile = bin,
            family = SrNcnnNative.FAMILY_WAIFU2X,
            scale = 2,
            noise = -1,      // upconv_7 必须不降噪
            prepadding = 7,
        )
        assertTrue("引擎初始化失败（模型损坏 / ncnn 加载不了）", engine.initialize())
        try {
            val w = 128
            val h = 160
            val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            // ⚠️ 用有结构的图案，不要纯色：纯色下「空跑」和「真跑」看起来一样
            val px = IntArray(w * h) { i ->
                val x = i % w
                val y = i / w
                Color.rgb((x * 2) and 0xFF, (y * 2) and 0xFF, ((x xor y) * 3) and 0xFF)
            }
            src.setPixels(px, 0, w, 0, 0, w, h)

            val t0 = System.currentTimeMillis()
            val out = engine.upscale(src)
            val dt = System.currentTimeMillis() - t0

            assertNotNull("upscale 返回 null（推理失败，看 logcat 的 SrNcnn/SrNcnnEngine）", out)
            out!!
            assertEquals("输出宽应为输入的 2 倍", w * 2, out.width)
            assertEquals("输出高应为输入的 2 倍", h * 2, out.height)

            val op = IntArray(out.width * out.height)
            out.getPixels(op, 0, out.width, 0, 0, out.width, out.height)
            val distinct = op.toSet().size
            println("[SrNcnn] ${w}x$h -> ${out.width}x${out.height} in ${dt}ms, distinct=${distinct}")
            assertTrue("输出几乎常量（distinct=$distinct）—— 引擎可能空跑或走了错的分支", distinct > 16)
        } finally {
            engine.release()
        }
    }

    /** 释放后再调用必须安全返回 null（不能野指针） */
    @Test
    fun upscaleAfterReleaseReturnsNull() {
        val pair = prepareModel()
        assumeTrue("测试模型资产不可用，跳过", pair != null)
        val (param, bin) = pair!!

        val engine = NcnnSrEngine(param, bin, SrNcnnNative.FAMILY_WAIFU2X, 2, -1, 7)
        assertTrue(engine.initialize())
        engine.release()
        val src = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        assertEquals("释放后 upscale 必须安全返回 null", null, engine.upscale(src))
    }
}
