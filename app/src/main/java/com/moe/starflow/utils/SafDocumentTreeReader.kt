package com.moe.starflow.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.IOException

/** One child row from a SAF tree query; using cursor metadata avoids per-item DocumentFile IPCs. */
object SafDocumentTreeReader {

    data class Child(
        val documentId: String,
        val uri: Uri,
        val displayName: String?,
        val mimeType: String?,
    ) {
        val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    /**
     * Query a directory once for document id/name/type. DocumentFile.name/isDirectory would issue
     * additional provider queries for every child, making large trees slow and hiding which query failed.
     */
    fun listChildren(context: Context, treeUri: Uri, parentDocumentId: String, logTag: String): List<Child> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        try {
            val cursor = context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    OpenableColumns.DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            ) ?: throw IOException("DocumentsProvider 对子项查询返回 null cursor")
            return cursor.use {
                val idColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val mimeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (idColumn < 0) {
                    throw IOException("DocumentsProvider 子项结果缺少 DOCUMENT_ID 列")
                }
                val out = ArrayList<Child>(it.count)
                var missingName = nameColumn < 0
                var missingMime = mimeColumn < 0
                while (it.moveToNext()) {
                    val documentId = it.getString(idColumn)
                        ?: throw IOException("DocumentsProvider 子项 DOCUMENT_ID 为空，row=${it.position}")
                    val name = if (nameColumn >= 0 && !it.isNull(nameColumn)) it.getString(nameColumn) else null
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    val cursorMime = if (mimeColumn >= 0 && !it.isNull(mimeColumn)) it.getString(mimeColumn) else null
                    val mime = cursorMime ?: runCatching { context.contentResolver.getType(documentUri) }
                        .onFailure { e ->
                            LogCollector.w(
                                logTag,
                                "SAF 子项 MIME 查询失败；authority=${treeUri.authority}, uri=$documentUri, " +
                                    "exception=${e.javaClass.simpleName}, message=${e.message}",
                                e,
                            )
                        }
                        .getOrNull()
                    if (name.isNullOrBlank()) missingName = true
                    if (mime.isNullOrBlank()) missingMime = true
                    out += Child(
                        documentId = documentId,
                        uri = documentUri,
                        displayName = name?.takeIf { value -> value.isNotBlank() },
                        mimeType = mime?.takeIf { value -> value.isNotBlank() },
                    )
                }
                if (missingName || missingMime) {
                    LogCollector.w(
                        logTag,
                        "SAF 子项元数据不完整；authority=${treeUri.authority}, queryUri=$childrenUri, " +
                            "rows=${out.size}, displayNameMissing=$missingName, mimeTypeMissing=$missingMime",
                    )
                }
                out
            }
        } catch (e: Exception) {
            LogCollector.e(
                logTag,
                "SAF 子项查询失败；authority=${treeUri.authority}, queryUri=$childrenUri, " +
                    "exception=${e.javaClass.simpleName}, message=${e.message}",
                e,
            )
            throw e
        }
    }
}
