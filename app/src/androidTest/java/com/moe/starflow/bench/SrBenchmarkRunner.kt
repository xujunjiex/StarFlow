package com.moe.starflow.bench

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * **超分模型真机性能基准**（不是单元测试，是一次性的测量工具）。
 *
 * 用法：
 * ```
 * .\gradlew :app:assembleDebugAndroidTest
 * adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
 * adb shell am instrument -w com.moe.starflow.test/com.moe.starflow.bench.SrBenchmarkRunner
 * adb shell cat /storage/emulated/0/Android/data/com.moe.starflow/files/sr_benchmark.txt
 * ```
 *
 * 为什么不用 androidx.test：那要联网拉依赖，而本项目连 `androidx.test:runner` 都没缓存。
 * `android.app.Instrumentation` 是**框架类**，零依赖，`onStart` 里跑完把报告写文件即可。
 *
 * ⚠️ 这是**测量工具**，不是产品路径：输入用全 0（卷积耗时与内容无关，还省掉 fp16 转换），
 *    也不做 halo 裁剪（相对推理耗时可忽略）。产品路径仍然只有 `AnimeJaNaiEngine` 一条。
 */
class SrBenchmarkRunner : Instrumentation() {

    private val tag = "SrBench"

    /** 与引擎一致的补齐口径：四边各补 PAD，再对齐到 16 的倍数 */
    private val pad = 48
    private fun align16(v: Int) = ((v + 15) / 16) * 16

    override fun onStart() {
        val report = StringBuilder()
        val sb = StringBuilder()
        try {
            val ctx = targetContext
            val srDir = File(ctx.getExternalFilesDir(null), "sr")
            val models = (srDir.listFiles { f -> f.name.endsWith(".onnx") } ?: emptyArray())
                .sortedBy { it.length() }
            line(sb, "设备=${android.os.Build.MODEL} 核数=${Runtime.getRuntime().availableProcessors()}")
            line(sb, "输入=640x960(对齐补边后 ${align16(640 + pad * 2)}x${align16(960 + pad * 2)})  每档：预热 1 次 + 计时 2 次，取最小")
            line(sb, "模型数=${models.size}")
            for (m in models) {
                val info = runCatching { sessionInfo(m) }.getOrNull()
                val type = info?.first ?: "?"
                val inW = info?.second ?: "?"
                line(sb, "")
                line(sb, "── ${m.name}  ${m.length() / 1024}KB  输入类型=$type")
                // 配置集合：线程扫描 + 优化等级 + 一个 NNAPI 尝试
                val configs = mutableListOf<Cfg>()
                for (t in intArrayOf(4, 6, 8)) configs += Cfg(t, OrtSession.SessionOptions.OptLevel.ALL_OPT, false)
                configs += Cfg(8, OrtSession.SessionOptions.OptLevel.BASIC_OPT, false)
                configs += Cfg(8, OrtSession.SessionOptions.OptLevel.ALL_OPT, true)
                for (c in configs) {
                    val r = try {
                        measure(m, c)
                    } catch (t: Throwable) {
                        "失败: ${t.javaClass.simpleName}: ${t.message?.take(80)}"
                    }
                    line(sb, "  ${c.label}  ->  $r")
                }
            }
        } catch (t: Throwable) {
            line(sb, "整轮失败: ${t.javaClass.simpleName}: ${t.message}")
            Log.e(tag, "benchmark failed", t)
        }
        report.append(sb)
        // ① 落盘（我用 adb 直接读它）② 顺手进 LogCollector 的统一日志
        runCatching {
            File(targetContext.getExternalFilesDir(null), "sr_benchmark.txt").writeText(report.toString())
        }
        runCatching {
            com.moe.starflow.utils.LogCollector.i(tag, "===== 超分基准开始 =====\n$report")
        }
        finish(0, Bundle().apply { putString("report", report.toString()) })
    }

    private data class Cfg(val threads: Int, val opt: OrtSession.SessionOptions.OptLevel, val nnapi: Boolean) {
        val label: String get() = "threads=$threads ${opt.name}${if (nnapi) " +NNAPI" else ""}"
    }

    /** @return (输入类型名, 输出宽/输入宽) */
    private fun sessionInfo(f: File): Pair<String, String> {
        val env = OrtEnvironment.getEnvironment()
        env.createSession(f.absolutePath, OrtSession.SessionOptions()).use { s ->
            val name = s.inputNames.first()
            val t = (s.inputInfo[name]?.info as? TensorInfo)?.type
            val outName = s.outputNames.first()
            val ot = (s.outputInfo[outName]?.info as? TensorInfo)?.type
            return (t?.name ?: "?") to "out=${ot?.name}"
        }
    }

    /** 真实推理计时；返回 `min=xxx ms  (run1=, run2=)  输出=WxH` */
    private fun measure(f: File, cfg: Cfg): String {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setMemoryPatternOptimization(true)
            setCPUArenaAllocator(true)
            setOptimizationLevel(cfg.opt)
            setIntraOpNumThreads(cfg.threads)
            if (cfg.nnapi) addNnapi()
        }
        val t0 = SystemClock.elapsedRealtime()
        val session = env.createSession(f.absolutePath, opts)
        val initMs = SystemClock.elapsedRealtime() - t0

        val w = 640; val h = 960
        val iw = align16(w + pad * 2); val ih = align16(h + pad * 2)
        val name = session.inputNames.first()
        val isFp16 = ((session.inputInfo[name]?.info as? TensorInfo)?.type == OnnxJavaType.FLOAT16)
        val bytesPer = if (isFp16) 2 else 4
        // 输入全 0：fp16 的 0.0 就是 0x0000，fp32 的 0.0 就是 0f —— 都不需要做数值转换。
        val buf: ByteBuffer = ByteBuffer.allocateDirect(iw * ih * 3 * bytesPer).order(ByteOrder.nativeOrder())
        buf.rewind()
        val shape = longArrayOf(1, 3, ih.toLong(), iw.toLong())
        val tensor = if (isFp16) OnnxTensor.createTensor(env, buf, shape, OnnxJavaType.FLOAT16)
        else OnnxTensor.createTensor(env, buf.asFloatBuffer(), shape)

        var outW = 0; var outH = 0
        var min = Long.MAX_VALUE
        val times = mutableListOf<Long>()
        try {
            for (i in 0..2) {   // 0 = 预热，1/2 = 计时
                val s0 = SystemClock.elapsedRealtime()
                val r = session.run(mapOf(name to tensor))
                val e0 = SystemClock.elapsedRealtime() - s0
                val out = r[0] as? OnnxTensor
                val sh = out?.info?.shape
                outW = sh?.getOrNull(3)?.toInt() ?: 0
                outH = sh?.getOrNull(2)?.toInt() ?: 0
                r.close()
                if (i > 0) { times += e0; if (e0 < min) min = e0 }
            }
        } finally {
            runCatching { tensor.close() }
            runCatching { session.close() }
            runCatching { opts.close() }
        }
        return "min=${min}ms  (${times.joinToString("/")})  输出=${outW}x$outH  装载=${initMs}ms"
    }

    private fun line(sb: StringBuilder, s: String) {
        sb.append(s).append('\n')
        Log.i(tag, s)
    }
}
