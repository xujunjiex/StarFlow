package com.moe.starflow.library

import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

/** A custom tree that the system deletes when the app is uninstalled. */
class LibraryLocationUnsafeException(message: String) : IOException(message)

/**
 * 书库根目录的卸载安全性。
 *
 * `Android/data`、`Android/obb` 和 `Android/media` 属于应用专属空间，卸载时由系统清理，
 * 不能作为“自定义目录”的卸载安全承诺。Android 10 的文件选择器仍可能返回这些路径，
 * 因此在选择入口直接拒绝；Android 11+ 的系统选择器已经隐藏它们。
 *
 * 标准外部存储提供器的 tree document ID 是 `volumeId:path`，可以据此判断。第三方提供器的
 * document ID 是不透明定位符，不能解释成文件路径，这里不做猜测。
 */
internal object LibraryLocationSafety {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private val appPrivateDirectories = setOf("data", "obb", "media")

    fun unsafeTreeReason(tree: Uri): String? {
        if (tree.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        return unsafeDocumentReason(documentId)
    }

    /** Returns the matched `Android/...` directory, or null when the tree is outside app-specific storage. */
    internal fun unsafeDocumentReason(documentId: String): String? {
        val segments = documentId.substringAfter(':', "")
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() }
        if (segments.size < 2 || !segments[0].equals("Android", ignoreCase = true)) return null
        val directory = segments[1].lowercase()
        return if (directory in appPrivateDirectories) "Android/$directory" else null
    }
}
