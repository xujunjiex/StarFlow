package com.moe.starflow.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** Optional SAF metadata. Opening/reading the URI remains the authority for whether import can proceed. */
object SafDocumentMetadata {

    data class Result(val displayName: String?, val sizeBytes: Long?)

    /**
     * Query both optional columns once and keep provider failures visible in the app log.
     * DocumentsProviders may omit DISPLAY_NAME/SIZE, return a null cursor, or fail this IPC while
     * still supporting openInputStream(uri); none of those metadata outcomes blocks content reads.
     */
    fun query(context: Context, uri: Uri, logTag: String): Result {
        val combined = try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )
            if (cursor == null) {
                LogCollector.w(logTag, "SAF 元数据查询返回 null cursor；authority=${uri.authority}, uri=$uri")
                Result(null, null)
            } else cursor.use {
                if (!it.moveToFirst()) {
                    LogCollector.w(logTag, "SAF 元数据查询没有返回行；authority=${uri.authority}, uri=$uri")
                    Result(null, null)
                } else {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0 && !it.isNull(nameIndex)) it.getString(nameIndex) else null
                    val size = if (sizeIndex >= 0 && !it.isNull(sizeIndex)) it.getLong(sizeIndex).takeIf { n -> n >= 0L } else null
                    if (nameIndex < 0 || sizeIndex < 0 || (nameIndex >= 0 && name == null) || (sizeIndex >= 0 && size == null)) {
                        LogCollector.w(
                            logTag,
                            "SAF 元数据列缺失或为空；authority=${uri.authority}, uri=$uri, " +
                                "displayNameColumn=${nameIndex >= 0}, sizeColumn=${sizeIndex >= 0}, " +
                                "displayNamePresent=${name != null}, sizePresent=${size != null}",
                        )
                    }
                    Result(name, size)
                }
            }
        } catch (e: Exception) {
            LogCollector.w(
                logTag,
                "SAF 双列元数据查询抛出 ${e.javaClass.simpleName}；authority=${uri.authority}, uri=$uri, " +
                    "columns=${OpenableColumns.DISPLAY_NAME},${OpenableColumns.SIZE}, message=${e.message}",
                e,
            )
            Result(null, null)
        }
        // Some providers accept one OpenableColumns projection but reject the combined projection.
        // Retry only the missing field so a metadata quirk cannot prevent opening the content stream.
        val name = combined.displayName ?: queryName(context, uri, logTag)
        val size = combined.sizeBytes ?: querySize(context, uri, logTag)
        return Result(name, size)
    }

    private fun queryName(context: Context, uri: Uri, logTag: String): String? = try {
        querySingleColumn(context, uri, OpenableColumns.DISPLAY_NAME, logTag)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        LogCollector.w(
            logTag,
            "SAF DISPLAY_NAME 单列查询失败；authority=${uri.authority}, uri=$uri, " +
                "exception=${e.javaClass.simpleName}, message=${e.message}",
            e,
        )
        null
    }

    private fun querySize(context: Context, uri: Uri, logTag: String): Long? = try {
        querySingleColumn(context, uri, OpenableColumns.SIZE, logTag)?.toLongOrNull()?.takeIf { it >= 0L }
    } catch (e: Exception) {
        LogCollector.w(
            logTag,
            "SAF SIZE 单列查询失败；authority=${uri.authority}, uri=$uri, " +
                "exception=${e.javaClass.simpleName}, message=${e.message}",
            e,
        )
        null
    }

    private fun querySingleColumn(context: Context, uri: Uri, column: String, logTag: String): String? {
        val cursor = context.contentResolver.query(uri, arrayOf(column), null, null, null)
        if (cursor == null) {
            LogCollector.w(logTag, "SAF 单列元数据查询返回 null cursor；authority=${uri.authority}, uri=$uri, column=$column")
            return null
        }
        return cursor.use {
            if (!it.moveToFirst()) {
                LogCollector.w(logTag, "SAF 单列元数据查询没有返回行；authority=${uri.authority}, uri=$uri, column=$column")
                return null
            }
            val index = it.getColumnIndex(column)
            if (index < 0) {
                LogCollector.w(logTag, "SAF 单列元数据结果缺少列；authority=${uri.authority}, uri=$uri, column=$column")
                null
            } else if (it.isNull(index)) {
                LogCollector.w(logTag, "SAF 单列元数据为空；authority=${uri.authority}, uri=$uri, column=$column")
                null
            } else {
                it.getString(index)
            }
        }
    }

    /** URI fallback is deliberately local: it must not issue a second DocumentsProvider query. */
    fun fallbackName(uri: Uri): String? = uri.lastPathSegment
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.takeIf { it.isNotBlank() }
}
