package com.moe.starflow.mangaimport.data

import android.content.Context
import com.moe.starflow.utils.FileAccessDiagnostics
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Recovers manga entries from app-owned files and indexes newly added content on shelf refresh. */
object MangaLibraryScanner {

    private const val TAG = "MangaLibraryScanner"
    private val mutex = Mutex()
    private val reportedFailures = mutableSetOf<String>()

    data class Result(val added: Int, val failures: List<String>)

    private data class Candidate(val source: File, val title: String, val preferredId: Long?)

    suspend fun scan(context: Context): Result = mutex.withLock {
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val known = ImportedMangaStore.load(app).toMutableList()
            val initialIds = known.mapTo(mutableSetOf()) { it.id }
            val knownPaths = known.mapNotNull { canonical(it.localRoot) }.toMutableSet()
            val activeIds = ImportManager.activeIdsForScanner()
            var added = 0
            val failures = mutableListOf<String>()
            val scanReservations = mutableListOf<Long>()

            try {
                discover(app, activeIds, failures).forEach { candidate ->
                    val expectedRoot = runCatching { MangaImporter.scannedLocalRoot(app, candidate.source) }
                        .getOrElse { e ->
                            val detail = "${candidate.source.absolutePath}: ${describe(e)}"
                            if (recordFailure(candidate.source, detail)) failures += detail
                            LogCollector.e(TAG, "扫描漫画时无法判定存储路径: ${candidate.source.absolutePath}", e)
                            return@forEach
                        }
                    val expectedPath = canonical(expectedRoot.absolutePath) ?: expectedRoot.absolutePath
                    if (expectedPath in knownPaths) return@forEach

                    val id = ImportManager.reserveScanId(app, candidate.preferredId) ?: return@forEach
                    scanReservations += id
                    try {
                        val manga = MangaImporter.inspectLocalSource(app, candidate.source, id, candidate.title)
                        if (manga.pageCount == 0) {
                            val detail = "${candidate.source.absolutePath}: 压缩包/目录中没有可读漫画图片"
                            if (recordFailure(candidate.source, detail)) failures += detail
                            LogCollector.w(TAG, "自动扫描跳过无图片漫画: ${candidate.source.absolutePath}")
                        } else {
                            known += manga
                            knownPaths += canonical(manga.localRoot) ?: manga.localRoot
                            added++
                            LogCollector.i(TAG, "自动扫描恢复漫画: ${manga.title} (${manga.pageCount} 页, ${manga.localRoot})")
                        }
                    } catch (e: Exception) {
                        val detail = describe(e)
                        val fullDetail = "${candidate.source.absolutePath}: $detail"
                        if (recordFailure(candidate.source, fullDetail)) {
                            failures += fullDetail
                        }
                        LogCollector.e(TAG, "自动扫描漫画失败: ${candidate.source.absolutePath}; $detail", e)
                    }
                }
                if (added > 0) {
                    val latest = ImportedMangaStore.load(app).toMutableList()
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
                    ImportedMangaStore.save(app, latest)
                }
                Result(added, failures)
            } finally {
                ImportManager.releaseScanIds(scanReservations)
            }
        }
    }

    private fun discover(context: Context, activeIds: Set<Long>, failures: MutableList<String>): List<Candidate> {
        val appFiles = context.getExternalFilesDir(null) ?: context.filesDir
        val mangaRoot = StorageDirStore.root(context)
        val roots = listOf(mangaRoot to false, appFiles to true)
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Candidate>()

        roots.forEach { (root, isAppRoot) ->
            val children = listChildren(root, failures) ?: return@forEach
            for (child in children) {
                if (isAppRoot && child.name in APP_MANAGED_DIRS) continue
                if (child.name.startsWith(".starflow_scan_")) continue
                if (child.isFile) {
                    addArchiveCandidate(child, null, seen, out, failures)
                    continue
                }
                if (!child.isDirectory) continue
                val directoryId = child.name.toLongOrNull()
                if (directoryId != null && directoryId in activeIds) continue

                // Legacy RAR/7z imports keep pages/ below their numeric book directory.
                val pages = File(child, MangaImporter.EXTRACT_DIR)
                val contentDir = if (directoryId != null && pages.isDirectory &&
                    ArchivedMangaReader.listImageFilesInDir(pages).isNotEmpty()
                ) pages else child

                listChildren(contentDir, failures)?.filter { it.isFile }?.forEach { file ->
                    addArchiveCandidate(file, directoryId, seen, out, failures)
                }
                if (ArchivedMangaReader.listImageFilesInDir(contentDir).isNotEmpty()) {
                    addDirectoryCandidate(contentDir, directoryId, seen, out)
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

    private fun addArchiveCandidate(
        file: File,
        parentId: Long?,
        seen: MutableSet<String>,
        out: MutableList<Candidate>,
        failures: MutableList<String>,
    ) {
        if (!file.isFile) return
        val extension = file.extension.lowercase(Locale.ROOT)
        // Avoid opening every page image once as a possible archive while walking image folders.
        if (ArchivedMangaReader.isImageFile(file.name)) return
        val inspection = ArchiveTypes.inspect(file)
        if (inspection.readFailure != null) {
            if (extension !in ARCHIVE_EXTENSIONS && extension.isNotEmpty()) return
            val detail = "读取漫画文件头失败：${file.absolutePath}；${describe(inspection.readFailure)}"
            if (recordFailure(file, detail)) failures += detail
            LogCollector.e(TAG, detail, inspection.readFailure)
            return
        }
        if (inspection.kind == ArchiveKind.UNKNOWN) {
            if (extension in ARCHIVE_EXTENSIONS) {
                val detail = "文件扩展名 .${file.extension} 与内容不符，文件头不是受支持的 ZIP/RAR/7z/PDF：${file.absolutePath}"
                if (recordFailure(file, detail)) failures += detail
                LogCollector.w(TAG, detail)
            }
            return
        }
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
        val title = if (directory.name == MangaImporter.EXTRACT_DIR) {
            directory.parentFile?.name ?: directory.name
        } else directory.name
        out += Candidate(
            source = directory,
            title = title,
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

    private val APP_MANAGED_DIRS = setOf(
        "manga_import", "novel_import", "covers", "logs", "models", "cache", "translation",
        "manga_ocr_download", "rt_detr", "ppocrv6", "ppocrv5", "sr", "sr_cache", "sr_bench_out",
        "llamacpp", "font",
    )
    private val ARCHIVE_EXTENSIONS = setOf("zip", "cbz", "rar", "cbr", "7z", "pdf")
}
