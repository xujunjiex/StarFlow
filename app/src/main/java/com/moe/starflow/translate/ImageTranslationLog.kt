package com.moe.starflow.translate

import android.os.SystemClock
import com.moe.starflow.utils.LogCollector
import translationapi.TranslationResponseDiagnostics

/**
 * 图片直传的**可诊断日志**（唯一 tag [TAG]，经 [LogCollector] 落盘到 `starflow.log`）。
 *
 * ## 为什么会话式 + 每条请求只两行
 * - 日志窗口只有 300 行：按像素/文字块打日志会把它冲干净，故障现场反而没了；
 * - 但排查又需要「一次请求的完整上下文」：谁发的、多大图、走哪个接口、HTTP 多少、
 *   服务商错误码是什么、耗时多久 —— 这些字段必须能**拼在同一条线**上。
 *   所以：开始 1 行（`→`）+ 结束 1 行（`✓/✗/⏹`），中间只更新字段、不落盘。
 *
 * ## 字段约定（稳定、可 grep，不要随意改名）
 * ```text
 * → provider=baidu ctx=reader page=12 lang=ja->zh src=2100x2970 upload=1240x1754 scale=0.590
 * ✓ provider=baidu ctx=reader page=12 http=200 blocks=23 textOnly=false chars=412 bytes=412KB 1830ms
 * ✗ provider=baidu ctx=reader page=12 stage=provider code=54001 http=200 1830ms msg="..."
 * ⏹ provider=baidu ctx=reader page=12 1200ms
 * ```
 * - `provider` 是**稳定 id**（`baidu` / `tencent` / `custom#1`），不是本地化名称；
 * - `ctx` 由宿主给：`reader page=N` / `novel image=<file>` / `manga-overlay` / `game`；
 * - `stage` 见 [ImageApiException]：`http` / `empty` / `parse` / `provider` / `encode` / `config` / `request`。
 *
 * ## 安全
 * - **绝不**记录 AppID/Secret/Authorization、图片 base64、完整请求体；
 * - 错误消息与响应预览统一过 [TranslationResponseDiagnostics.safeMessage]（脱敏 + 240 字截断）。
 */
object ImageTranslationLog {

    const val TAG = "ImageTranslate"

    /** 开始一次请求；返回的会话负责在成功/失败/取消时各落**一条**结束日志。 */
    fun start(
        provider: String,
        requestContext: String,
        sourceLanguage: String,
        targetLanguage: String,
        sourceWidth: Int,
        sourceHeight: Int,
        upload: ImageUpload.Prepared,
    ): Session {
        val session = Session(
            provider = provider,
            context = requestContext,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            uploadWidth = upload.bitmap.width,
            uploadHeight = upload.bitmap.height,
            uploadScale = upload.scaleToOriginal,
        )
        LogCollector.d(
            TAG,
            "→ provider=$provider ctx=${ctxOrDash(requestContext)} " +
                "lang=$sourceLanguage->$targetLanguage " +
                "src=${sourceWidth}x$sourceHeight upload=${upload.bitmap.width}x${upload.bitmap.height} " +
                "scale=${"%.3f".format(java.util.Locale.US, upload.scaleToOriginal)}",
        )
        return session
    }

    /** 接口不存在/凭据缺失：宿主调用，保证日志里有一行「为什么一上来就没打请求」。 */
    fun configMissing(provider: String, requestContext: String, missing: String) {
        // ⚠️ 未配置时**每一页都会走到这里**（阅读器/小说每页都试一次），不节流的话
        // 一次整章翻译就能把 300 行窗口全刷成同一句，把真正的故障现场挤掉。
        val key = "$provider|${hostOf(requestContext)}|$missing"
        val now = SystemClock.elapsedRealtime()
        val last = configMissingAt[key]
        if (last != null && now - last < CONFIG_LOG_INTERVAL_MS) return
        configMissingAt[key] = now
        LogCollector.e(
            TAG,
            "✗ provider=$provider ctx=${ctxOrDash(requestContext)} stage=config missing=$missing " +
                "msg=\"图片翻译接口未配置，未发出请求\"",
        )
    }

    /**
     * **还没发出请求**就失败（位图缩放/编码分配失败等）：此时没有 [Session]，
     * 但也必须留一行 —— 否则用户只看到"失败"而日志里连 provider 都没有。
     */
    fun prepareFailed(
        provider: String,
        requestContext: String,
        sourceWidth: Int,
        sourceHeight: Int,
        error: Throwable,
    ) {
        LogCollector.e(
            TAG,
            "✗ provider=$provider ctx=${ctxOrDash(requestContext)} stage=encode " +
                "src=${sourceWidth}x$sourceHeight " +
                "msg=\"${TranslationResponseDiagnostics.safeMessage(error.message ?: error.javaClass.simpleName)}\"",
        )
    }

    /** 一次请求的字段收集器；[ok]/[fail]/[cancelled] 只生效一次。 */
    class Session internal constructor(
        private val provider: String,
        private val context: String,
        private val sourceLanguage: String,
        private val targetLanguage: String,
        private val sourceWidth: Int,
        private val sourceHeight: Int,
        private val uploadWidth: Int,
        private val uploadHeight: Int,
        private val uploadScale: Float,
    ) {

        private val startedAt = SystemClock.elapsedRealtime()
        private var uploadBytes = -1
        private var httpStatus: Int? = null
        private var finished = false

        /** 上传副本编码后的字节数（JPEG）；失败在编码前也能照常收尾。 */
        fun uploaded(bytes: Int) {
            uploadBytes = bytes
        }

        /** 拿到 HTTP 响应后立刻记录（成功/失败都要，方便区分「网络没通」与「服务商拒绝」）。 */
        fun http(status: Int) {
            httpStatus = status
        }

        fun ok(blocks: Int, textOnly: Boolean, chars: Int) {
            if (finished) return
            finished = true
            LogCollector.i(
                TAG,
                "✓ provider=$provider ctx=${ctxOrDash(context)} http=${httpStatus ?: "-"} " +
                    "blocks=$blocks textOnly=$textOnly chars=$chars " +
                    "bytes=${sizeText(uploadBytes)} ${elapsedText()}",
            )
        }

        /**
         * 失败落盘。
         *
         * @param fallbackStage 异常本身不是 [ImageApiException] 时用的阶段（编码/请求准备阶段常见 `encode`/`request`）
         */
        fun fail(error: Throwable, fallbackStage: String = "request") {
            if (finished) return
            finished = true
            val typed = error as? ImageApiException
            val stage = typed?.stage ?: stageOfNetwork(error, fallbackStage)
            val code = typed?.providerCode?.takeIf { it.isNotBlank() }
            val status = typed?.httpStatus ?: httpStatus
            val detail = TranslationResponseDiagnostics.safeMessage(
                error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            )
            LogCollector.e(
                TAG,
                "✗ provider=$provider ctx=${ctxOrDash(context)} stage=$stage " +
                    "code=${code ?: "-"} http=${status ?: "-"} " +
                    "src=${sourceWidth}x$sourceHeight upload=${uploadWidth}x$uploadHeight " +
                    "scale=${"%.3f".format(java.util.Locale.US, uploadScale)} bytes=${sizeText(uploadBytes)} " +
                    "${elapsedText()} msg=\"$detail\"",
            )
        }

        /** 取消不是失败：只记 D 级，避免用户导出日志后把「自己点了停止」当成接口故障。 */
        fun cancelled() {
            if (finished) return
            finished = true
            LogCollector.d(
                TAG,
                "⏹ provider=$provider ctx=${ctxOrDash(context)} ${elapsedText()}",
            )
        }

        private fun elapsedText(): String = "${SystemClock.elapsedRealtime() - startedAt}ms"
    }

    private fun ctxOrDash(value: String): String = value.ifBlank { "-" }

    /**
     * `ctx` 的宿主部分（`reader page=12` → `reader`）：
     * 配置类日志按**宿主**去重，而不是按页去重（否则每页一条）。
     */
    private fun hostOf(requestContext: String): String =
        requestContext.substringBefore(' ').ifBlank { "-" }

    /** 配置类日志的节流表与窗口（同一个宿主 + 同一个原因 30 秒内只记一条）。 */
    private val configMissingAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val CONFIG_LOG_INTERVAL_MS = 30_000L

    /**
     * 网络类异常的**阶段细分**：日志里 `stage=timeout` / `stage=network` 能直接区分
     * 「服务商拒绝」与「根本没连上」，这两类问题的处理方式完全不同。
     */
    private fun stageOfNetwork(error: Throwable, fallback: String): String {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 6) {
            when (current) {
                is java.io.InterruptedIOException -> return "timeout" // 含 SocketTimeoutException
                is java.net.UnknownHostException,
                is java.net.ConnectException,
                is java.net.NoRouteToHostException,
                is javax.net.ssl.SSLException -> return "network"
            }
            current = current.cause
            depth++
        }
        return fallback
    }

    private fun sizeText(bytes: Int): String = when {
        bytes < 0 -> "-"
        bytes < 1024 -> "${bytes}B"
        else -> "${bytes / 1024}KB"
    }
}
