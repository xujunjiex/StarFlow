package com.moe.starflow.translate

import java.io.IOException

/**
 * 图片直传失败的结构化原因 —— 让**用户导出的日志**能一眼分清失败发生在哪一层。
 *
 * 以前所有失败都只是 `IOException("...")`：日志里只有一段文本，排查时无法回答
 * 「是网络/鉴权/额度/解析/空结果」这类基本问题。现在每处抛错都带 [stage]，日志按
 * `stage=` 字段输出（见 [ImageTranslationLog]）。
 *
 * ⚠️ [message] 会直接呈现给用户（阅读器失败行 / 状态浮层），必须**包含**原始原因；
 * 里面不要塞密钥、base64 或超长响应体（响应预览走 `TranslationResponseDiagnostics`，已脱敏截断）。
 */
class ImageApiException(
    message: String,
    /** 失败层：`http` / `empty` / `parse` / `provider` / `encode` / `config` / `request`。 */
    val stage: String,
    /** 服务商错误码（百度 `error_code` / 腾讯 `Error.Code`），没有就是 null。 */
    val providerCode: String? = null,
    /** HTTP 状态码（能拿到时）。 */
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)
