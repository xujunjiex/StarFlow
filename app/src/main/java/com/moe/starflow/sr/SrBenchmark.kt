package com.moe.starflow.sr

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.os.SystemClock
import com.moe.starflow.utils.LogCollector
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * **超分模型真机性能基准**（开发者工具，不是产品路径）。
 *
 * ## 怎么跑
 * ```
 * adb shell am start -n com.moe.starflow/.MainActivity --es sr_bench 1
 * adb shell cat /storage/emulated/0/Android/data/com.moe.starflow/files/sr_benchmark.txt
 * ```
 * ⚠️ 只在 `BuildConfig.DEBUG` 下会被 `MainActivity` 触发；正常启动（没有那个 extra）零影响。
 *
 * ## 为什么放在 App 里而不是 instrumentation 测试
 * MIUI 对 `adb install` 测试 APK 有 USB 安装保护（`INSTALL_FAILED_USER_RESTRICTED`），
 * 而 App 本体一直装得上 —— 用本体跑就不需要用户去改系统设置。
 *
 * ## 测什么
 * 每个已下载模型 × { 线程 4/6/8 } × { ALL_OPT / BASIC_OPT } × { CPU / +NNAPI }，
 * 输入 640×960（补边对齐后 736×1056，与真机页同量级），**预热 1 次 + 计时 2 次，报告最小值**。
 * 同时打印每个模型的**输入张量类型**（fp16 还是 fp32）——
 * cunet 大概率 fp32、AnimeJaNai 确定 fp16，同尺寸下按百万像素比耗时，就能量出
 * 「ORT CPU EP 跑 fp16 要插 Cast、XNNPACK 用不上」到底占多少。
 *
 * ⚠️ 输入用全 0：卷积耗时与内容无关，还省掉 fp16 数值转换；也不做 halo 裁剪（相对推理耗时可忽略）。
 */
object SrBenchmark {

    private const val TAG = "SrBench"
    private const val PAD = 48
    private const val IN_W = 640
    private const val IN_H = 960

    private fun align16(v: Int) = ((v + 15) / 16) * 16

    /** 每一步都**立刻追加写盘**：swin 可能要跑十几分钟，中途被杀也不至于全丢。 */
    private fun emit(out: File, sb: StringBuilder, s: String) {
        sb.append(s).append('\n')
        LogCollector.i(TAG, s)
        runCatching { out.appendText(s + "\n") }
    }

    /** 后台线程跑完整轮，立即返回（调用方不会被阻塞）。 */
    fun run(context: Context, modelFilter: String? = null, threads: Int = 4) {
        val app = context.applicationContext
        Thread {
            val out = File(app.getExternalFilesDir(null), "sr_benchmark.txt")
            // ⚠️ **不截断**：App 从最近任务重开时 intent 还带着 extra → 基准会重跑一遍，
            //    截断就把上一次的结果全丢了（踩过）。改成追加 + 打分隔线，多次运行可对比取最优。
            val sb = StringBuilder()
            try {
                val srDir = File(app.getExternalFilesDir(null), "sr")
                val models = (srDir.listFiles { f -> f.name.endsWith(".onnx") } ?: emptyArray())
                    .filter { modelFilter == null || it.name.contains(modelFilter, ignoreCase = true) }
                    .sortedBy { it.length() }
                emit(out, sb, "===== 运行 ${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} =====")
                emit(out, sb, "设备=${android.os.Build.MODEL}  核数=${Runtime.getRuntime().availableProcessors()}")
                emit(out, sb, "输入=${IN_W}x$IN_H (对齐补边后 ${align16(IN_W + PAD * 2)}x${align16(IN_H + PAD * 2)})  预热1+计时2，取最小")
                emit(out, sb, "线程=$threads  过滤=${modelFilter ?: "全部"}  模型数=${models.size}  当前负载=${runCatching { java.io.File("/proc/loadavg").readText().trim() }.getOrDefault("?")}")
                for (m in models) {
                    val type = runCatching { inputTypeName(m) }.getOrElse { "?(${it.javaClass.simpleName})" }
                    emit(out, sb, "")
                    emit(out, sb, "── ${m.name}  ${m.length() / 1024}KB  输入=$type")
                    // 只跑**实测最优**那一档（ALL_OPT）：
                    // 慢模型（cunet/swin）一页几十秒，档位多了根本跑不完。
                    // 线程数由调用方给（默认 4）：**别占满 8 核**，否则测试期间手机没法用。
                    val configs = listOf(Cfg(threads, OrtSession.SessionOptions.OptLevel.ALL_OPT, false))
                    for (c in configs) {
                        val r = try {
                            measure(m, c)
                        } catch (t: Throwable) {
                            "失败: ${t.javaClass.simpleName}: ${t.message?.take(90)}"
                        }
                        emit(out, sb, "  ${c.label}  ->  $r")
                    }
                }
                emit(out, sb, "")
                emit(out, sb, "===== 完成 =====")
            } catch (t: Throwable) {
                emit(out, sb, "整轮失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }.apply { name = "sr-bench"; isDaemon = true }.start()
    }

    private data class Cfg(val threads: Int, val opt: OrtSession.SessionOptions.OptLevel, val nnapi: Boolean) {
        val label: String get() = "threads=$threads ${opt.name}${if (nnapi) " +NNAPI" else ""}"
    }

    private fun inputTypeName(f: File): String {
        val env = OrtEnvironment.getEnvironment()
        env.createSession(f.absolutePath, OrtSession.SessionOptions()).use { s ->
            val n = s.inputNames.first()
            val t = (s.inputInfo[n]?.info as? TensorInfo)?.type
            return t?.name ?: "?"
        }
    }

    /** @return `min=xxx ms  (run1/run2)  输出=WxH  装载=xxx ms` */
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

        val iw = align16(IN_W + PAD * 2)
        val ih = align16(IN_H + PAD * 2)
        val name = session.inputNames.first()
        val isFp16 = ((session.inputInfo[name]?.info as? TensorInfo)?.type == OnnxJavaType.FLOAT16)
        val bytesPer = if (isFp16) 2 else 4
        // 全 0 输入：fp16 的 0.0 = 0x0000、fp32 的 0.0 = 0f —— 都不需要做数值转换
        val buf: ByteBuffer = ByteBuffer.allocateDirect(iw * ih * 3 * bytesPer).order(ByteOrder.nativeOrder())
        buf.rewind()
        val shape = longArrayOf(1, 3, ih.toLong(), iw.toLong())
        val tensor = if (isFp16) OnnxTensor.createTensor(env, buf, shape, OnnxJavaType.FLOAT16)
        else OnnxTensor.createTensor(env, buf.asFloatBuffer(), shape)

        var outW = 0
        var outH = 0
        var min = Long.MAX_VALUE
        val times = mutableListOf<Long>()
        try {
            for (i in 0..1) {   // 0 = 预热，1 = 计时（只跑两遍：慢模型一遍就几十秒）
                val s0 = SystemClock.elapsedRealtime()
                val r = session.run(mapOf(name to tensor))
                val e0 = SystemClock.elapsedRealtime() - s0
                val sh = (r[0] as? OnnxTensor)?.info?.shape
                outW = sh?.getOrNull(3)?.toInt() ?: 0
                outH = sh?.getOrNull(2)?.toInt() ?: 0
                r.close()
                if (i > 0) {
                    times += e0
                    if (e0 < min) min = e0
                }
            }
        } finally {
            runCatching { tensor.close() }
            runCatching { session.close() }
            runCatching { opts.close() }
        }
        val mp = outW.toDouble() * outH / 1_000_000.0
        val perMp = if (mp > 0) "%.0f ms/MP".format(min / mp) else "?"
        return "min=${min}ms  (${times.joinToString("/")})  输出=${outW}x$outH  $perMp  装载=${initMs}ms"
    }
}
