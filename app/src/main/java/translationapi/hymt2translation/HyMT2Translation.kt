package translationapi.hymt2translation

import android.content.Context
import com.moe.starflow.R
import com.moe.starflow.download.ModelDownloadRepository
import com.moe.starflow.download.ModelKey
import com.moe.starflow.translate.TranslationResult
import com.moe.starflow.translate.TranslationTextAPI
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.translate.TranslationStatusOverlay

class HyMT2Translation(context: Context) : TranslationTextAPI {
    private val ctx = context.applicationContext
    private val statusOverlay = TranslationStatusOverlay.getInstance(ctx)
    private val initLock = Any()

    /** 崩溃日志目录是否已设置（避免重复 native 调用） */
    @Volatile private var crashDirSet = false

    @Volatile private var handle: Long = 0L
    @Volatile private var currentTask: Thread? = null
    @Volatile private var cancelled = false
    /** 任务世代号：cancelTranslation()/release() 递增，使在途任务的结果失效，避免旧线程误交付 Success */
    @Volatile private var currentEpoch = 0L
    /** 正在 native 调用中的任务数：release() 等待其归零后才释放模型，杜绝 use-after-free */
    @Volatile private var inFlight = 0
    /** 是否已 release（共享持有器据此判断是否需重建实例） */
    @Volatile var released = false
    /** 共享常驻标志：release() 只取消在途任务、不释放模型（供全 app 复用同一热模型） */
    @Volatile var keepAlive = false

    override fun getTranslation(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        callback: (TranslationResult) -> Unit
    ) = translateInternal(text, sourceLanguage, targetLanguage, onPhase = null, onPartial = null, callback)

    override fun getTranslationStreaming(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        onPhase: (String) -> Unit,
        onPartial: (String) -> Unit,
        callback: (TranslationResult) -> Unit
    ) = translateInternal(text, sourceLanguage, targetLanguage, onPhase, onPartial, callback)

    private fun translateInternal(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        onPhase: ((String) -> Unit)?,
        onPartial: ((String) -> Unit)?,
        callback: (TranslationResult) -> Unit
    ) {
        cancelled = false  // 每次新翻译重置取消标志
        val epoch = currentEpoch  // 本任务所属世代
        val targetName = HyMt2Languages.getTargetName(targetLanguage)
        currentTask = Thread {
            try {
                val tLoad0 = System.currentTimeMillis()
                val h = ensureLoaded()
                LogCollector.d(TAG, "Hy-MT2 ensureLoaded 耗时=${System.currentTimeMillis() - tLoad0}ms (h=$h)")
                logMemory("ensureLoaded 后")
                if (h == 0L) {
                    val downloaded = ModelDownloadRepository.getInstance(ctx)
                        .isFullyDownloaded(ModelKey.HY_MT2_GROUP)
                    val msg = when {
                        released -> ctx.getString(R.string.hymt2_released_translate)  // 实例已被换出/重建，服务需重建 translator
                        downloaded -> ctx.getString(R.string.hymt2_init_failed)
                        else -> ctx.getString(R.string.hymt2_not_download_translate)
                    }
                    callback(TranslationResult.Error(Exception(msg)))
                    return@Thread
                }
                // 进入 native 前登记在途调用：release() 会在 inFlight 归零后才释放模型，
                // 且此处重新校验 handle/世代，杜绝 release() 后仍用已释放句柄（use-after-free）。
                // 登记 + 调用 + 递减包在同一 try/finally，保证任何异常路径都释放 inFlight。
                var registered = false
                try {
                    val nativeHandle = synchronized(initLock) {
                        if (handle != 0L && currentEpoch == epoch) {
                            inFlight++
                            registered = true
                            handle
                        } else 0L
                    }
                    if (nativeHandle == 0L) {
                        callback(TranslationResult.Error(Exception(ctx.getString(R.string.error_hy_released))))
                        return@Thread
                    }
                    val s = HyMt2Params.read(CustomPreference.getInstance(ctx).getSharedPreferences())
                    val prompt = HyMt2Prompt.build(s.promptTemplate, targetName, text)
                    val prefix = HyMt2Prompt.buildPrefix(s.promptTemplate, targetName)  // 固定指令前缀，用于前缀 KV 缓存
                    LogCollector.d(TAG, "Hy-MT2 翻译开始: $sourceLanguage→$targetLanguage")
                    LogCollector.d(TAG, "Hy-MT2 翻译 sys指令: $prefix")
                    LogCollector.d(TAG, "Hy-MT2 翻译 user原文: $text")
                    LogCollector.d(TAG, "Hy-MT2 翻译 完整prompt: $prompt")
                    val tNative0 = System.currentTimeMillis()
                    val result = if (onPartial != null) {
                        val cb = object : HyMt2StreamCallback {
                            override fun onPhase(phase: String) { onPhase?.invoke(phase) }
                            override fun onToken(text: String) { onPartial(text) }
                        }
                        HyMt2Native.nativeTranslateStreaming(
                            nativeHandle, prompt, prefix, s.temperature, s.topP, s.topK, s.repetitionPenalty, s.maxTokens, cb
                        )
                    } else {
                        HyMt2Native.nativeTranslate(
                            nativeHandle, prompt, prefix, s.temperature, s.topP, s.topK, s.repetitionPenalty, s.maxTokens
                        )
                    }.trim()
                    // native 超长 prompt 防御标记（token 超 context 时返回，避免 ggml_abort 闪退）
                    if (result == "__PROMPT_TOO_LONG__") {
                        LogCollector.e(TAG, "Hy-MT2 文本过长超过模型上下文，已拒绝翻译")
                        callback(TranslationResult.Error(Exception(ctx.getString(R.string.error_text_too_long))))
                        return@Thread
                    }
                    LogCollector.d(TAG, "Hy-MT2 native 调用耗时=${System.currentTimeMillis() - tNative0}ms")
                    LogCollector.d(TAG, "Hy-MT2 翻译完成: result=$result")
                    if (cancelled || currentEpoch != epoch) {
                        // 本任务已取消/被后续任务取代：丢弃结果
                        LogCollector.d(TAG, "Hy-MT2 翻译已取消，丢弃结果")
                        callback(TranslationResult.Error(Exception(ctx.getString(R.string.error_translation_cancelled))))
                    } else {
                        callback(TranslationResult.Success(result))
                    }
                } finally {
                    if (registered) synchronized(initLock) { inFlight-- }
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Hy-MT2 翻译异常: ${e.message}", e)
                callback(TranslationResult.Error(e))
            }
        }.apply {
            // 高优先级：解码线程在系统高负载时不被前台饿死（否则 decode 慢 280 倍）
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /**
     * 多轮对话（共享实例）：调 nativeTranslateChat，复用 inFlight/epoch 生命周期管理。
     * 保持 keepAlive（不释放模型），供对话模式复用同一热模型。
     */
    fun chatNative(
        roles: IntArray,
        contents: Array<String>,
        systemPrompt: String,
        temperature: Float,
        topP: Float,
        topK: Int,
        repetitionPenalty: Float,
        maxTokens: Int,
        onPhase: (String) -> Unit,
        onPartial: (String) -> Unit,
        callback: (TranslationResult) -> Unit
    ) {
        cancelled = false  // 每次新对话重置取消标志
        val epoch = currentEpoch
        currentTask = Thread {
            try {
                val h = ensureLoaded()
                if (h == 0L) {
                    callback(TranslationResult.Error(Exception(ctx.getString(R.string.error_model_not_ready))))
                    return@Thread
                }
                var registered = false
                try {
                    val nativeHandle = synchronized(initLock) {
                        if (handle != 0L && currentEpoch == epoch) { inFlight++; registered = true; handle } else 0L
                    }
                    if (nativeHandle == 0L) {
                        callback(TranslationResult.Error(Exception(ctx.getString(R.string.error_hy_released))))
                        return@Thread
                    }
                    val cb = object : HyMt2StreamCallback {
                        override fun onPhase(phase: String) { onPhase(phase) }
                        override fun onToken(text: String) { onPartial(text) }
                    }
                    val result = HyMt2Native.nativeTranslateChat(
                        nativeHandle, systemPrompt, roles, contents,
                        temperature, topP, topK, repetitionPenalty, maxTokens, cb
                    ).trim()
                    if (cancelled || currentEpoch != epoch) {
                        callback(TranslationResult.Error(Exception(ctx.getString(R.string.error_chat_cancelled))))
                    } else {
                        callback(TranslationResult.Success(result))
                    }
                } finally {
                    if (registered) synchronized(initLock) { inFlight-- }
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Hy-MT2 对话异常: ${e.message}", e)
                callback(TranslationResult.Error(e))
            }
        }.apply {
            // 高优先级：解码线程在系统高负载时不被前台饿死（否则 decode 慢 280 倍）
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** 懒加载：首次翻译才初始化模型。失败返回 0。 */
    private fun ensureLoaded(): Long {
        // 设置 native 崩溃日志目录 + 安装信号处理器：所有 bridge 日志 + 崩溃 backtrace 落盘。
        // 懒调用 + 幂等，保证 nativeInit 之前处理器已就位（崩溃可捕获）
        setupCrashDir()
        // 已 release 的实例（共享持有器换出/重建）绝不再重载模型：
        // 否则服务缓存的旧实例会在 handle=0 时绕过持有器重新 nativeInit 一个 440MB 僵尸模型
        if (released) return 0L
        if (handle != 0L) return handle
        synchronized(initLock) {
            if (released) return 0L
            if (handle != 0L) return handle
            if (!ModelDownloadRepository.getInstance(ctx).isFullyDownloaded(ModelKey.HY_MT2_GROUP)) {
                statusOverlay.showError(ctx.getString(R.string.hymt2_not_download_translate))
                return 0L
            }
            val s = HyMt2Params.read(CustomPreference.getInstance(ctx).getSharedPreferences())
            val modelFile = ModelDownloadRepository.getInstance(ctx).targetFilePath(ModelKey.HY_MT2_GROUP)
            // 不占用共享状态浮层：加载约 2s，由游戏/漫画流程自己的"翻译中"提示覆盖，避免把进度提示顶掉
            val epochAtEntry = currentEpoch
            handle = HyMt2Native.nativeInit(modelFile.absolutePath, s.threads, s.batchThreads, s.contextSize)
            if (handle == 0L) {
                statusOverlay.showError(ctx.getString(R.string.hymt2_init_failed))
                return 0L
            }
            if (currentEpoch != epochAtEntry) {
                // release()/cancelTranslation() 在加载期间发生：立即释放刚加载的模型，防止泄漏
                HyMt2Native.nativeRelease(handle)
                handle = 0L
                return 0L
            }
            return handle
        }
    }

    override fun cancelTranslation() {
        currentEpoch++  // 使所有在途任务结果失效，不会被误当作 Success 交付
        cancelled = true
        val task = currentTask
        if (task?.isAlive == true) task.interrupt()
        currentTask = null
        synchronized(initLock) {
            if (handle != 0L) HyMt2Native.nativeAbort(handle)
        }
    }

    override fun release() {
        if (keepAlive) {
            // 共享常驻：取消在途任务但保留模型，供后续复用（避免每次页面切换重载 440MB 冷模型）
            LogCollector.d(TAG, "Hy-MT2 release(keepAlive)：保留模型，仅取消在途任务")
            cancelTranslation()
            val t = currentTask
            if (t?.isAlive == true) runCatching { t.join(2000) }
            currentTask = null
            return
        }
        // 先捕获在途线程再取消：cancelTranslation() 会置空 currentTask，必须先捕获才能 join
        val task = currentTask
        cancelTranslation()
        task?.let { t ->
            if (t.isAlive) {
                try {
                    t.join(3000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        currentTask = null
        // 置零句柄：阻止新的 native 调用进入（登记处会校验 handle/世代）
        val oldHandle: Long
        synchronized(initLock) {
            oldHandle = handle
            handle = 0L
        }
        // 等所有已登记的 native 调用退出（abort 已生效，通常 <1s），再释放模型，杜绝 use-after-free
        val deadline = System.currentTimeMillis() + 3000
        while (synchronized(initLock) { inFlight } > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        if (oldHandle != 0L) {
            synchronized(initLock) {
                HyMt2Native.nativeRelease(oldHandle)
            }
        }
        released = true
        statusOverlay.release()
    }

    /** 后台预加载模型（共享常驻场景），把加载挪到后台，避免首次翻译时才等模型就绪 */
    fun warmUp() {
        setupCrashDir()
        if (handle != 0L) return
        Thread {
            try {
                val t0 = System.currentTimeMillis()
                val h = ensureLoaded()
                LogCollector.d(TAG, "Hy-MT2 warmUp 完成 h=$h 耗时=${System.currentTimeMillis() - t0}ms")
            } catch (e: Exception) {
                LogCollector.e(TAG, "Hy-MT2 warmUp 失败: ${e.message}", e)
            }
        }.start()
    }

    /** 设置统一日志文件 + 安装 native 崩溃处理器（幂等，线程安全） */
    private fun setupCrashDir() {
        if (crashDirSet) return
        synchronized(initLock) {
            if (crashDirSet) return
            try {
                // 与 Java 层 LogCollector 同一文件：所有日志统一一个地方
                val file = java.io.File(ctx.getExternalFilesDir(null), "logs")
                    .resolve(com.moe.starflow.utils.LogCollector.LOG_FILE_NAME)
                HyMt2Native.nativeSetLogFile(file.absolutePath)
                crashDirSet = true
            } catch (e: Throwable) {
                // 单测 JVM 无 libhymt2.so → UnsatisfiedLinkError，忽略（仅真机生效）
                LogCollector.d(TAG, "nativeSetLogFile 不可用: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "HyMT2Translation"
    }

    /** 内存诊断：可用内存 + 进程实际驻留(RSS)，判断模型页是否被换出 */
    private fun logMemory(where: String) {
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            // 读 /proc/self/status 的 VmRSS（进程实际驻留内存，换出的页不计入）
            val rssKb = try {
                java.io.File("/proc/self/status").readLines()
                    .firstOrNull { it.startsWith("VmRSS:") }?.substringAfter(":")?.trim()
                    ?.substringBefore("kB")?.trim()?.toLong() ?: 0L
            } catch (_: Throwable) { 0L }
            LogCollector.d(TAG, "内存[$where]: 可用=${mi.availMem / 1024 / 1024}MB VmRSS=${rssKb / 1024}MB 低内存=${mi.lowMemory}")
        } catch (_: Exception) {
        }
    }
}
