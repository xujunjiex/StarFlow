package com.moe.starflow
import com.moe.starflow.translate.widget.*

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.moe.starflow.download.ModelDownloadRepository
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import translationapi.hymt2translation.HyMt2Native

class StarFlowApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // 日志文件落盘（native 崩溃后日志仍保留）——必须最先初始化
        LogCollector.init(this)

        // 全局主题（跟随系统/浅色/暗色）：必须在任何 Activity 创建前应用
        ThemeManager.apply(applicationContext)

        // 全局语言：就地改应用基资源 locale（悬浮球/漫画服务等 Service、阅读器/图库等非 BaseActivity
        // 都读应用上下文资源，必须进程级应用，否则它们一直显示系统默认语言中文）
        com.moe.starflow.utils.LanguageManager.applyLanguage(applicationContext)

        // Java 层未捕获异常 → 把堆栈写入 starflow.log（进程死亡前落盘），便于排查闪退。
        // 不打断系统默认处理：先写日志，再交给原 default handler（结束进程 + 系统崩溃报告）。
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                LogCollector.e("UncaughtException", "线程 ${thread.name} 未捕获异常", throwable)
            } catch (_: Throwable) {
            }
            prevHandler?.uncaughtException(thread, throwable)
        }

        // 尽早安装 native 崩溃处理器 + 打开统一日志文件：不等 Hy-MT2 初始化，
        // 覆盖 PP-OCR/ONNX/RT-DETR/sentencepiece 等所有 native 库的崩溃（8 Elite 场景关键）
        installNativeCrashHandler()

        // 记录上次进程退出原因（Android 11+）：崩溃/ANR/被杀/内存不足一律写入日志
        logPreviousExitReasons()

        // Initialize model download repository
        val repo = ModelDownloadRepository.getInstance(this)
        GlobalScope.launch(Dispatchers.IO) {
            try {
                repo.loadModelList()
                repo.initialize()
            } catch (e: Exception) {
                LogCollector.e("StarFlowApp", "Failed to init model download repo", e)
            }
        }
    }

    /**
     * 安装 native 崩溃处理器（幂等）。调用 HyMt2Native.nativeSetLogFile 打开统一日志文件 +
     * 安装 SIGSEGV/SIGABRT 等信号处理器。单测 JVM 无 libhymt2.so → UnsatisfiedLinkError，忽略。
     */
    private fun installNativeCrashHandler() {
        try {
            LogCollector.logFilePath?.let { HyMt2Native.nativeSetLogFile(it) }
        } catch (_: Throwable) {
        }
    }

    /**
     * 记录上次进程退出原因（Android 11+，ActivityManager.getHistoricalProcessExitReasons）。
     * 崩溃/ANR/被信号杀死/内存不足/冻结 等异常退出写 E 级（红色醒目）；正常退出（主动退出/用户
     * 停止）写 I 级。启动时查询——当前进程尚未退出，返回的是"上一次进程为何退出"。
     */
    private fun logPreviousExitReasons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val reasons = am.getHistoricalProcessExitReasons(packageName, 0, 5)
            for (r in reasons) {
                if (r.pid == android.os.Process.myPid()) continue  // 理论上不会出现当前进程，防御
                val (label, isError) = exitReasonLabel(r)
                val detail = "描述=${r.description ?: ""} 状态=${r.status}"
                if (isError) {
                    LogCollector.e("ExitReason", "上次进程退出(${label}): $detail")
                } else {
                    LogCollector.i("ExitReason", "上次进程退出(${label}): $detail")
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 退出原因 → (标签, 是否异常退出)。异常退出写 E 级，正常退出写 I 级。
     * ⚠️ 只引用 API 30 就存在的常量；FREEZER/PERMISSION_CHANGE/INITIALIZATION_FAILURE 等
     * 高版本常量直接引用会在低版本设备 NoSuchFieldError，统一走 else（未知，仍记 E 级）。
     */
    private fun exitReasonLabel(r: ApplicationExitInfo): Pair<String, Boolean> = when (r.reason) {
        ApplicationExitInfo.REASON_CRASH -> "崩溃 CRASH" to true
        ApplicationExitInfo.REASON_ANR -> "无响应 ANR" to true
        ApplicationExitInfo.REASON_SIGNALED -> "被信号杀死 SIGNALED" to true
        ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被杀 LOW_MEMORY" to true
        ApplicationExitInfo.REASON_EXIT_SELF -> "主动退出 EXIT_SELF" to false
        else -> "其他(${r.reason})" to true
    }
}
