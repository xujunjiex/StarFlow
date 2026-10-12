package com.moe.starflow.novel.data

import android.content.Context
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.FileAccessDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Recovers novel entries from app-owned files and indexes newly added content on shelf refresh. */
object NovelLibraryScanner {

    private const val TAG = "NovelLibraryScanner"
    private val mutex = Mutex()
    private val reportedFailures = mutableSetOf<String>()

    data class Result(val added: Int, val failures: List<String>)

    private data class Candidate(val source: File, val title: String, val preferredId: Long?)

    suspend fun scan(context: Context): Result = mutex.withLock {
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val known = NovelStore.load(app).toMutableList()
            val initialIds = known.mapTo(mutableSetOf()) { it.id }
            val knownPaths = known.mapNotNull { canonical(it.localRoot) }.toMutableSet()
            val activeIds = NovelImportManager.activeIdsForScanner()
            var added = 0
            val failures = mutableListOf<String>()
            val scanReservations = mutableListOf<Long>()

            try {
                discover(app, knownPaths, activeIds, failures).forEach { candidate ->
                    if (NovelImportManager.isSourceBeingDeleted(candidate.source)) return@forEach
                    val path = canonical(candidate.source.absolutePath) ?: return@forEach
                    if (path in knownPaths) return@forEach
                    val id = NovelImportManager.reserveScanId(app, candidate.preferredId) ?: return@forEach
                    scanReservations += id
                    try {
                        val novel = NovelImporter.inspectLocalSource(app, candidate.source, id, candidate.title)
                        known += novel
                        knownPaths += canonical(novel.localRoot) ?: novel.localRoot
                        added++
                        LogCollector.i(TAG, "自动扫描恢复小说: ${novel.title} (${novel.chapterCount} 章, ${novel.localRoot})")
                    } catch (e: Exception) {
                        // An ordinary folder with no text chapters is not a failed novel import.
                        if (e !is IllegalStateException || e.message != NovelImporter.ERROR_NO_TEXT_CHAPTER) {
                            val detail = describe(e)
                            val fullDetail = "${candidate.source.absolutePath}: $detail"
                            if (recordFailure(candidate.source, fullDetail)) {
                                failures += fullDetail
                            }
                            LogCollector.e(TAG, "自动扫描小说失败: ${candidate.source.absolutePath}; $detail", e)
                        }
                    }
                }
                if (added > 0) {
                    val latest = NovelStore.load(app).toMutableList()
                    val latestIds = latest.mapTo(mutableSetOf()) { it.id }
                    val latestPaths = latest.mapNotNullTo(mutableSetOf()) { canonical(it.localRoot) }
                    known.filter { it.id !in initialIds }.forEach { recovered ->
                        val path = canonical(recovered.localRoot) ?: recovered.localRoot
                        if (recovered.id !in latestIds && path !in latestPaths) {
                            latest += recovered
                            latestIds += recovered.id
                            latestPaths += path
                        }
                    }
                    NovelStore.save(app, latest)
                }
                Result(added, failures)
            } finally {
                NovelImportManager.releaseScanIds(scanReservations)
            }
        }
    }

    private fun discover(
        context: Context,
        knownPaths: Set<String>,
        activeIds: Set<Long>,
        failures: MutableList<String>,
    ): List<Candidate> {
        val appFiles = context.getExternalFilesDir(null) ?: context.filesDir
        val novelRoot = NovelStorageDir.root(context)
        val roots = listOf(novelRoot to false, appFiles to true)
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Candidate>()

        roots.forEach { (root, isAppRoot) ->
            val children = listChildren(root, failures) ?: return@forEach
            for (child in children) {
                if (isAppRoot && child.name in APP_MANAGED_DIRS) continue
                if (isAppRoot && child.name in APP_MANAGED_FILES) continue
                if (child.name.startsWith('.')) continue
                if (child.isFile) {
                    if (hasNovelExtension(child)) addFileCandidate(child, null, seen, out)
                    continue
                }
                if (!child.isDirectory) continue
                val directoryId = child.name.toLongOrNull()
                if (directoryId != null && directoryId in activeIds) continue

                val childEntries = listChildren(child, failures).orEmpty()
                val directFiles = childEntries.filter { it.isFile && hasNovelExtension(it) }
                val directoryPath = canonical(child.absolutePath)
                // A SAF folder import is indexed at the numeric book directory. Keep that exact
                // source so the existing row is matched; an old single-file import stores the file.
                val hasChildDirectories = childEntries.any { it.isDirectory }
                if (directoryId != null && directFiles.size == 1 && !hasChildDirectories && directoryPath !in knownPaths) {
                    addFileCandidate(directFiles.single(), directoryId, seen, out)
                } else {
                    addDirectoryCandidate(child, directoryId, seen, out)
                }
            }
        }
        return out
    }

    /** Keep directory enumeration failures visible with the exact path and File API result. */
    private fun listChildren(directory: File, failures: MutableList<String>): Array<File>? {
        val children = try {
            directory.listFiles()
        } catch (e: Exception) {
            val detail = "枚举目录失败：${directory.absolutePath}；${describe(e)}"
            if (recordFailure(directory, detail)) failures += detail
            LogCollector.e(TAG, detail, e)
            return null
        }
        if (children != null) return children

        val state = FileAccessDiagnostics.describe(directory)
        val detail = "枚举目录失败：${directory.absolutePath}；File.listFiles() 返回 null（$state）"
        if (recordFailure(directory, detail)) failures += detail
        LogCollector.e(TAG, detail)
        return null
    }

    private fun hasNovelExtension(file: File): Boolean =
        file.extension.lowercase(Locale.ROOT) in SUPPORTED_FILE_EXTENSIONS

    private fun addFileCandidate(
        file: File,
        parentId: Long?,
        seen: MutableSet<String>,
        out: MutableList<Candidate>,
    ) {
        val path = canonical(file.absolutePath) ?: return
        if (!seen.add(path)) return
        out += Candidate(
            source = file,
            title = file.nameWithoutExtension.ifBlank { file.name },
            preferredId = parentId ?: file.parentFile?.name?.toLongOrNull(),
        )
    }

    private fun addDirectoryCandidate(
        directory: File,
        parentId: Long?,
        seen: MutableSet<String>,
        out: MutableList<Candidate>,
    ) {
        val path = canonical(directory.absolutePath) ?: return
        if (!seen.add(path)) return
        out += Candidate(
            source = directory,
            title = directory.name,
            preferredId = directory.name.toLongOrNull() ?: parentId,
        )
    }

    private fun canonical(path: String): String? = runCatching { File(path).canonicalPath }.getOrNull()

    private fun recordFailure(source: File, detail: String): Boolean {
        val size = runCatching { source.length() }.getOrNull()
        val modified = runCatching { source.lastModified() }.getOrNull()
        val key = "${canonical(source.absolutePath)}|$size|$modified|$detail"
        return reportedFailures.add(key)
    }

    private fun describe(error: Throwable): String {
        val chain = mutableListOf<String>()
        val seen = mutableSetOf<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current) && chain.size < 3) {
            chain += listOfNotNull(
                current.javaClass.simpleName.takeIf { it.isNotBlank() },
                current.localizedMessage?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() },
            ).joinToString(": ")
            current = current.cause
        }
        return chain.joinToString(" ← ").take(400).ifBlank { "未知错误" }
    }

    private val SUPPORTED_FILE_EXTENSIONS = setOf("txt", "epub", "zip", "html", "htm", "xhtml")
    private val APP_MANAGED_DIRS = setOf(
        "manga_import", "novel_import", "covers", "logs", "models", "cache", "translation",
        "manga_ocr_download", "rt_detr", "ppocrv6", "ppocrv5", "sr", "sr_cache", "sr_bench_out",
        "llamacpp", "font",
    )
    private val APP_MANAGED_FILES = setOf("sr_ncnn_benchmark.txt")
}
