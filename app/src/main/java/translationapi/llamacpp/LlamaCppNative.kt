package translationapi.llamacpp

/**
 * llama.cpp 通用推理 JNI 桥声明。
 *
 * 对应 `llamacpp/src/main/cpp/llamacpp_bridge.cpp`（独立 Gradle 模块 `:llamacpp`，
 * llama.cpp 以 submodule 源码编译，pin 与 1.25-bit 量化补丁见 `patches/README.md`）。
 *
 * ⚠️ 与老的 `translationapi.hymt2translation.HyMt2Native`（预编译 libhymt2.so）并存于迁移期：
 * 符号名 `Java_translationapi_llamacpp_LlamaCppNative_*` 与 C++ 侧逐字对应，函数名/参数变化时
 * 必须两边同时改，否则运行期 UnsatisfiedLinkError。
 */
object LlamaCppNative {
    init {
        System.loadLibrary("llamacpp")
    }

    /**
     * 设置统一日志文件路径（<logs>/starflow.log）+ 安装 SIGSEGV/SIGABRT 处理器。
     * 之后所有 bridge 日志 + 崩溃 backtrace 都追加到该文件（与 Java 层 LogCollector 同一文件）。
     */
    external fun nativeSetLogFile(path: String)

    /**
     * 测试用：安装崩溃处理器 + 打开日志文件后触发 native SIGSEGV（验证崩溃日志/backtrace 捕获链路）。
     */
    external fun nativeTriggerNativeCrash(path: String)

    /** 加载模型 + 建 context。返回句柄，0 = 失败。 */
    external fun nativeInit(modelPath: String, nThreads: Int, nBatchThreads: Int, nCtx: Int): Long

    /**
     * 翻译一段已拼装好提示词的文本，返回译文。
     * @param prefix 固定指令前缀（不含待翻译文本），用于前缀 KV 缓存；传空串则每次都全量 prefill。
     */
    external fun nativeTranslate(
        handle: Long,
        prompt: String,
        prefix: String,
        temperature: Float,
        topP: Float,
        topK: Int,
        repetitionPenalty: Float,
        maxTokens: Int
    ): String

    /**
     * 流式翻译：生成过程中回调阶段与译文片段（后台线程）。返回完整译文。
     * @param prefix 固定指令前缀（不含待翻译文本），用于前缀 KV 缓存；传空串则每次都全量 prefill。
     */
    external fun nativeTranslateStreaming(
        handle: Long,
        prompt: String,
        prefix: String,
        temperature: Float,
        topP: Float,
        topK: Int,
        repetitionPenalty: Float,
        maxTokens: Int,
        callback: LlamaCppStreamCallback
    ): String

    /** 释放模型与 context。 */
    external fun nativeRelease(handle: Long)

    /** 中止当前推理（设置 native 端取消标志，解码循环提前退出）。 */
    external fun nativeAbort(handle: Long)

    /**
     * 多轮对话推理（Hy-MT2 profile）：组装 [BOS]{system}<sys_end><hy_User>m1<hy_Assistant>m2...<hy_Assistant>。
     * @param roles 每条消息角色：0=user, 1=assistant；contents 对应文本。
     * @param systemPrompt 对话系统提示词（固定，用于前缀 KV 缓存）。
     */
    external fun nativeTranslateChat(
        handle: Long,
        systemPrompt: String,
        roles: IntArray,
        contents: Array<String>,
        temperature: Float,
        topP: Float,
        topK: Int,
        repetitionPenalty: Float,
        maxTokens: Int,
        callback: LlamaCppStreamCallback?
    ): String
}

/**
 * 流式推理回调：
 * - [onPhase]：阶段通知，phase = "prefill"（读取原文中）/ "generate"（生成译文中）
 * - [onToken]：每生成一段译文回调，text 为「累积到当前的完整译文」
 *
 * ⚠️ 这两个方法由 C++ 侧 GetMethodID 查找，必须保留名字与签名（proguard 也有 keep 规则）。
 */
interface LlamaCppStreamCallback {
    fun onPhase(phase: String)
    fun onToken(text: String)
}
