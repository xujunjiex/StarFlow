package com.moe.starflow.sr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 超分基准的**触发入口**（开发者工具）。
 *
 * 为什么不用 MainActivity：app 启动会 `LlamaCppSharedHolder.warmUp()` 后台加载本地大模型
 * （实测 461MB / 抢 6 个核），和基准抢 CPU 与内存 → 测出来的数字全是污染的
 * （同一模型三次差 2.4 倍、某些模型"">80 秒""都是这么来的）。
 * 从广播触发只创建 Application、**不起任何 Activity** → 环境干净。
 *
 * 用法（只在 debug 包有效；正式包 `BuildConfig.DEBUG` 直接 return）：
 * ```
 * adb shell am broadcast -a com.moe.starflow.SR_BENCH --es model upconv7
 * adb shell am broadcast -a com.moe.starflow.SR_BENCH --es model cunet --ei threads 6
 * adb shell cat /storage/emulated/0/Android/data/com.moe.starflow/files/sr_benchmark.txt
 * ```
 * 不带 `--es model` 就跑 `files/sr/` 下全部模型。
 */
class SrBenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!com.moe.starflow.BuildConfig.DEBUG) return
        if (intent.action != ACTION) return
        val filter = intent.getStringExtra("model")
        val threads = intent.getIntExtra("threads", srThreads())
        SrBenchmark.run(context.applicationContext, filter, threads)
    }

    companion object {
        const val ACTION = "com.moe.starflow.SR_BENCH"
    }
}