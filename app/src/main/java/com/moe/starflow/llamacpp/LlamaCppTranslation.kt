package com.moe.starflow.llamacpp

import android.content.Context
import com.moe.starflow.R
import com.moe.starflow.translate.TranslationResult
import com.moe.starflow.translate.TranslationStatusOverlay
import com.moe.starflow.translate.TranslationTextAPI
import com.moe.starflow.utils.LogCollector
import translationapi.llamacpp.LlamaCppNative
import translationapi.llamacpp.LlamaCppStreamCallback
import java.io.File

/**
 * 通用本地 GGUF 翻译引擎（llama.cpp 设备端推理）。
 *
 * 与老的 `HyMT2Translation` 的关系：那个只会跑内置 Hy-MT2（手工拼 hy 角色标记）；
 * 这个接受**任意模型描述符** [LlamaCppModel]，加载后按 `nativeModelInfo` 的 `has_hy` 决定通道：
 *
 *  - `hyProfile = true`：Hy-MT2 专用通道（`nativeTranslate*`，角色标记由桥接补，前缀缓存＝固定指令）
 *  - 否则：通用通道（`nativeTranslateRaw*`，prompt 由模型自带 Jinja 模板渲染，前缀缓存＝原文之前的渲染片段）
 *
 * 保留老引擎的全部运行时保障：epoch 世代号 + inFlight 计数（防 use-after-free）、
 * keepAlive 共享热实例、abort 取消、崩溃日志、warmUp 预加载（并**按审计结论把 warmUp 线程也设成
 * MAX_PRIORITY**，否则线程池 worker 会继承普通优先级）。
 */
class LlamaCppTranslation(
    context: Context,
    private val model: LlamaCppModel,
) : TranslationTextAPI {

    override val modelName: String get() = model.displayName

    private val ctx = context.applicationContext
    private val statusOverlay = TranslationStatusOverlay.getInstance(ctx)
    private val initLock = Any()

    /** 崩溃日志目录是否已设置（避免重复 native 调用） */
    @Volatile private var crashDirSet = false

    @Volatile private var handle: Long = 0L
    @Volatile private var currentTask: Thread? = null
    @Volatile private var cancelled = false
    /** 任务世代号：cancelTranslation()/release() 递增，使在途任务的结果失效 */
    @Volatile private var currentEpoch = 0L
    /** 正在 native 调用中的任务数：release() 等它归零后才释放模型，杜绝 use-after-free */
    @Volatile private var inFlight = 0
    @Volatile var released = false
    @Volatile var keepAlive = false

    /** 运行时判定：该模型是否含 hy 角色标记（以模型元数据为准，不信清单里的提示位） */
    @Volatile private var hyProfile: Boolean = model.hyProfile

    /** 模型架构/名字（诊断与 UI 展示） */
    @Volatile var modelArch: String = ""
        private set
    @Volatile var modelDisplayFromGguf: String = ""
        private set

    // ───────────────────────── 翻译入口 ─────────────────────────

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
        cancelled = false
        val epoch = currentEpoch
        val targetName = LlamaCppLanguages.getTargetName(targetLanguage)
        val params = currentParams()
        currentTask = Thread {
            try {
                val tLoad0 = System.currentTimeMillis()
                val h = ensureLoaded()
                LogCollector.d(TAG, "$modelName ensureLoaded 耗时=${System.currentTimeMillis() - tLoad0}ms (h=$h)")
                if (h == 0L) {
                    callback(TranslationResult.Error(Exception(loadFailureMessage())))
                    return@Thread
                }
                var registered = false
                try {
                    val nativeHandle = synchronized(initLock) {
                        if (handle != 0L && currentEpoch == epoch) {
                            inFlight++; registered = true; handle
                        } else 0L
                    }
                    if (nativeHandle == 0L) {
                        callback(TranslationResult.Error(Exception(ctx.getString(R.string.llamacpp_released_translate))))
                        return@Thread
                    }

                    // 两条通道：Hy-MT2 手工拼装 / 通用模板渲染
                    val prompt: String
                    val prefix: String
                    if (hyProfile) {
                        prompt = LlamaCppPrompt.buildHy(params.promptTemplate, targetName, text)
                        prefix = LlamaCppPrompt.buildHyPrefix(params.promptTemplate, targetName)
                    } else {
                        val rendered = LlamaCppPrompt.buildGeneric(nativeHandle, params, targetName, text)
                        prompt = rendered.prompt
                        prefix = rendered.prefix
                    }
                    LogCollector.d(TAG, "$modelName 翻译 ${sourceLanguage}→$targetLanguage hy=$hyProfile prefix=${prefix.length}字")
                    LogCollector.d(TAG, "$modelName prompt:\n$prompt")

                    val tNative0 = System.currentTimeMillis()
                    val result = if (onPartial != null) {
                        val cb = object : LlamaCppStreamCallback {
                            override fun onPhase(phase: String) { onPhase?.invoke(phase) }
                            override fun onToken(text: String) { onPartial(text) }
                        }
                        if (hyProfile) {
                            LlamaCppNative.nativeTranslateStreaming(
                                nativeHandle, prompt, prefix, params.temperature, params.topP,
                                params.topK, params.repetitionPenalty, params.maxTokens, cb
                            )
                        } else {
                            LlamaCppNative.nativeTranslateRawStreaming(
                                nativeHandle, prompt, prefix, params.temperature, params.topP,
                                params.topK, params.repetitionPenalty, params.maxTokens, cb
                            )
                        }
                    } else {
                        if (hyProfile) {
                            LlamaCppNative.nativeTranslate(
                                nativeHandle, prompt, prefix, params.temperature, params.topP,
                                params.topK, params.repetitionPenalty, params.maxTokens
                            )
                        } else {
                            LlamaCppNative.nativeTranslateRaw(
                                nativeHandle, prompt, prefix, params.temperature, params.topP,
                                params.topK, params.repetitionPenalty, params.maxTokens
                            )
                        }
                    }.trim()
                    LogCollector.d(TAG, "$modelName native 耗时=${System.currentTimeMillis() - tNative0}ms result=$result")

                    if (result == "__PROMPT_TOO_LONG__") {
                        callback(TranslationResult.Error(Exception(ctx.getString(R.string.llamacpp_error_text_too_long))))
                        return@Thread
                    }
                    if (cancelled || currentEpoch != epoch) {
                        callback(TranslationResult.Error(Exception(ctx.getString(R.string.error_translation_cancelled))))
                    } else if (result.isEmpty()) {
                        callback(TranslationResult.Error(Exception(ctx.getString(R.string.llamacpp_empty_result))))
                    } else {
                        callback(TranslationResult.Success(result))
                    }
                } finally {
                    if (registered) synchronized(initLock) { inFlight-- }
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "$modelName 翻译异常: ${e.message}", e)
                callback(TranslationResult.Error(e))
            }
        }.apply {
            // 高优先级：解码线程在系统高负载时不被前台饿死（否则 decode 明显变慢）
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    // ───────────────────────── 对话入口（所有对话功能都支持导入模型） ─────────────────────────

    /**
     * 多轮对话推理：Hy-MT2 通道走 `nativeTranslateChat`（角色标记由桥接拼），
     * 通用通道走「模型自带模板渲染 + nativeTranslateRawStreaming」。
     */
    fun chatNative(
        systemPrompt: String,
        roles: IntArray,
        contents: Array<String>,
        onPhase: (String) -> Unit,
        onPartial: (String) -> Unit,
        callback: (TranslationResult) -> Unit
    ) {
        cancelled = false
        val epoch = currentEpoch
        val params = currentParams()
        currentTask = Thread {
            try {
                val h = ensureLoaded()
                if (h == 0L) {
                    callback(TranslationResult.Error(Exception(loadFailureMessage())))
                    return@Thread
                }
                var registered = false
                try {
                    val nativeHandle = synchronized(initLock) {
                        if (handle != 0L && currentEpoch == epoch) { inFlight++; registered = true; handle } else 0L
                    }
                    if (nativeHandle == 0L) {
                        callback(TranslationResult.Error(Exception(ctx.getString(R.string.llamacpp_released_translate))))
                        return@Thread
                    }
                    val cb = object : LlamaCppStreamCallback {
                        override fun onPhase(phase: String) { onPhase(phase) }
                        override fun onToken(text: String) { onPartial(text) }
                    }
                    val result = if (hyProfile) {
                        LlamaCppNative.nativeTranslateChat(
                            nativeHandle, systemPrompt, roles, contents,
                            params.temperature, params.topP, params.topK,
                            params.repetitionPenalty, params.maxTokens, cb
                        )
                    } else {
                        val prompt = LlamaCppPrompt.buildGenericChat(
                            nativeHandle, systemPrompt, roles, contents, params.enableThinking
                        )
                        LlamaCppNative.nativeTranslateRawStreaming(
                            nativeHandle, prompt, "", params.temperature, params.topP,
                            params.topK, params.repetitionPenalty, params.maxTokens, cb
                        )
                    }.trim()
                    if (cancelled || currentEpoch != epoch) {
                        callback(TranslationResult.Error(Exception(ctx.getString(R.string.error_chat_cancelled))))
                    } else if (result.isEmpty()) {
                        callback(TranslationResult.Error(Exception(ctx.getString(R.string.llamacpp_empty_result))))
                    } else {
                        callback(TranslationResult.Success(result))
                    }
                } finally {
                    if (registered) synchronized(initLock) { inFlight-- }
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "$modelName 对话异常: ${e.message}", e)
                callback(TranslationResult.Error(e))
            }
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    // ───────────────────────── 加载 / 释放 ─────────────────────────

    /**
     * 每次推理现读参数：采样/提示词类参数改了**不用重载模型**（加载期参数见 LlamaCppSharedHolder.keyOf）。
     * 清单里查不到（模型被删）时退回本实例创建时的快照，保证行为可预期。
     */
    private fun currentParams(): LlamaCppParams =
        LlamaCppModelStore.byId(model.id)?.params ?: model.params
    private fun loadFailureMessage(): String = when {
        released -> ctx.getString(R.string.llamacpp_released_translate)
        !model.absoluteFile.isFile -> ctx.getString(R.string.llamacpp_model_file_missing, model.displayName)
        else -> ctx.getString(R.string.llamacpp_init_failed)
    }

    /** 懒加载：首次使用才初始化模型。失败返回 0。 */
    private fun ensureLoaded(): Long {
        setupCrashDir()
        if (released) return 0L
        if (handle != 0L) return handle
        synchronized(initLock) {
            if (released) return 0L
            if (handle != 0L) return handle
            val file: File = model.absoluteFile
            if (!file.isFile) {
                statusOverlay.showError(ctx.getString(R.string.llamacpp_model_file_missing, model.displayName))
                return 0L
            }
            // 一次头部扫描同时拿到「兼容性结论」与「张量布局」：原先是 check 与 ensureRetagged
            // 各自解析一遍同一个文件（头部要读整段元数据，含 12 万个 vocab token），没必要读两遍。
            val analysis = GgufQuantCheck.analyze(file)
            // 不支持的量化（如腾讯 2-bit 私有格式）直接给明确提示，不喂给 native ——
            // 喂了只会得到一个看不懂的加载失败，或更糟：按错类型解出垃圾
            if (analysis.compat == GgufQuantCheck.Compat.UNSUPPORTED) {
                LogCollector.e(TAG, "$modelName 量化类型不被当前引擎支持：${file.name}")
                statusOverlay.showError(ctx.getString(R.string.llamacpp_error_unsupported_quant))
                return 0L
            }
            // 内置 1.25-bit（以及用户导入的同款文件）：设备端把张量类型 42 改写成 43。
            // 幂等；判定看**文件内容**而不是标记文件，见 GgufTypeRetag / patches/README.md。
            val retagged = GgufTypeRetag.ensureRetagged(file, analysis)
            if (retagged) {
                GgufTypeRetag.retaggedMd5(file)?.let { md5 ->
                    Thread { runCatching { LlamaCppModelStore.markRetagged(model.id, md5) } }.start()
                }
            }

            val params = currentParams()
            val epochAtEntry = currentEpoch
            handle = LlamaCppNative.nativeInit(file.absolutePath, params.threads, params.batchThreads, params.contextSize)
            if (handle == 0L) {
                statusOverlay.showError(ctx.getString(R.string.llamacpp_init_failed))
                return 0L
            }
            if (currentEpoch != epochAtEntry) {
                HyMt2StyleRelease(handle)
                handle = 0L
                return 0L
            }
            // 运行时读模型元信息：架构、是否含 hy 角色标记（决定用哪条通道）
            runCatching {
                val info = LlamaCppNative.nativeModelInfo(handle)
                info.lineSequence().filter { it.contains('=') }.forEach { line ->
                    val k = line.substringBefore('=')
                    val v = line.substringAfter('=')
                    when (k) {
                        "arch" -> modelArch = v
                        "name" -> modelDisplayFromGguf = v
                        "has_hy" -> hyProfile = v == "1"
                    }
                }
                LogCollector.d(TAG, "$modelName 模型信息 arch=$modelArch name=$modelDisplayFromGguf hy=$hyProfile")
            }.onFailure { LogCollector.w(TAG, "读取模型信息失败：${it.message}") }
            return handle
        }
    }

    private fun HyMt2StyleRelease(h: Long) {
        runCatching { LlamaCppNative.nativeRelease(h) }
    }

    override fun cancelTranslation() {
        currentEpoch++
        cancelled = true
        val task = currentTask
        if (task?.isAlive == true) task.interrupt()
        currentTask = null
        synchronized(initLock) {
            if (handle != 0L) LlamaCppNative.nativeAbort(handle)
        }
    }

    override fun release() {
        if (keepAlive) {
            LogCollector.d(TAG, "$modelName release(keepAlive)：保留模型，仅取消在途任务")
            cancelTranslation()
            val t = currentTask
            if (t?.isAlive == true) runCatching { t.join(2000) }
            currentTask = null
            return
        }
        val task = currentTask
        cancelTranslation()
        task?.let { t ->
            if (t.isAlive) {
                try { t.join(3000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }
        currentTask = null
        val oldHandle: Long
        synchronized(initLock) {
            oldHandle = handle
            handle = 0L
        }
        val deadline = System.currentTimeMillis() + 3000
        while (synchronized(initLock) { inFlight } > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        if (oldHandle != 0L) synchronized(initLock) { runCatching { LlamaCppNative.nativeRelease(oldHandle) } }
        released = true
        statusOverlay.release()
    }

    /** 后台预加载模型（共享常驻场景），把加载挪到用户第一次翻译之前。 */
    fun warmUp() {
        setupCrashDir()
        if (handle != 0L) return
        Thread {
            try {
                val t0 = System.currentTimeMillis()
                val h = ensureLoaded()
                LogCollector.d(TAG, "$modelName warmUp 完成 h=$h 耗时=${System.currentTimeMillis() - t0}ms")
            } catch (e: Exception) {
                LogCollector.e(TAG, "$modelName warmUp 失败: ${e.message}", e)
            }
        }.apply {
            // ⚠️ 必须也是高优先级：llama.cpp 的 worker 线程在**创建时**继承 leader 的 nice，
            //    如果首次加载（从而建线程池）发生在普通优先级的 warmUp 线程上，
            //    整个池子全程普通优先级，之前调高解码线程优先级就白费了（2026-09 上游源码审计）。
            priority = Thread.MAX_PRIORITY
            name = "LlamaCpp-WarmUp"
            start()
        }
    }

    /** 设置统一日志文件 + 安装 native 崩溃处理器（幂等，线程安全） */
    private fun setupCrashDir() {
        if (crashDirSet) return
        synchronized(initLock) {
            if (crashDirSet) return
            try {
                val file = File(ctx.getExternalFilesDir(null), "logs")
                    .resolve(LogCollector.LOG_FILE_NAME)
                LlamaCppNative.nativeSetLogFile(file.absolutePath)
                crashDirSet = true
            } catch (e: Throwable) {
                LogCollector.d(TAG, "nativeSetLogFile 不可用: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "LlamaCppTranslation"
    }
}
