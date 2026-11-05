package com.moe.starflow.library

import com.moe.starflow.utils.LogCollector
import android.content.Context
import com.moe.starflow.mangaimport.data.*
import com.moe.starflow.novel.data.*
import com.moe.starflow.novel.model.NovelFormat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withLock
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

data class LibraryScanState(val location: String = "", val kind: String = "", val running: Boolean = false,
    val done: Int = 0, val total: Int = 0, val added: Int = 0, val failures: List<String> = emptyList(),
    val runId: String = java.util.UUID.randomUUID().toString(), val needsRecovery: Boolean = false)

object LibraryScanner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val state = MutableStateFlow(LibraryScanState())
    private val reports = ArrayDeque<LibraryScanState>()
    private val requests = hashSetOf<String>()
    @Synchronized fun consumeFailures(location: String = state.value.location, kind: String = state.value.kind): List<String>? {
        return consumeReport(location, kind)?.failures
    }
    @Synchronized fun consumeReport(location: String, kind: String): LibraryScanState? {
        val report = reports.firstOrNull { it.location == location && it.kind == kind } ?: return null
        reports.remove(report)
        return report
    }
    @Synchronized private fun completed(result: LibraryScanState): LibraryScanState {
        if (result.failures.isNotEmpty()) reports.addLast(result)
        state.value = result
        LibrarySessions.changes.value += 1
        return result
    }
    @Synchronized internal fun discardReports(location: String) { reports.removeAll { it.location == location } }
    @Synchronized internal fun deferReport(report: LibraryScanState) {
        if (reports.none { it.runId == report.runId }) reports.addFirst(report)
    }
    @Synchronized fun start(context: Context, kind: String) {
        require(kind == "manga" || kind == "novel")
        val pinned = LibrarySessions.pin(context)
        val session = LibrarySessions.current(pinned)
        val key = "${session.locationId}:${session.generation}:$kind"
        if (!requests.add(key)) return
        LibrarySessions.launch(pinned, scope) {
            scan(pinned, kind)
        }.invokeOnCompletion { synchronized(this) { requests.remove(key) } }
    }
    suspend fun scan(context: Context, kind: String): LibraryScanState = LibraryWork.sourceMutations.withLock {
        withContext(Dispatchers.IO) {
            val pinned = LibrarySessions.pin(context)
            val session = LibrarySessions.current(pinned)
            val lease = LibrarySessions.use()
            val failures = mutableListOf<String>()
            val informational = hashSetOf<String>()
            var added = 0
            try {
                session.storage.invalidateLookups()
                state.value = LibraryScanState(session.locationId, kind, running = true)
                LibrarySessions.prepare(pinned)
                val root = if (kind == "manga") "manga_import" else "novel_import"
                if (kind == "manga") MangaDuplicateRepair.repair(session, failures)
                val known = session.books(kind)
                // Overall index and candidate enumeration precede per-book inspection.
                val listings = mutableMapOf(root to session.storage.list(root))
                val members = mutableMapOf(root to listings.getValue(root).mapTo(hashSetOf()) { it.path })
                fun sourceExists(path: String): Boolean {
                    val parent = path.substringBeforeLast('/', "")
                    if (parent != root && parent.isNotEmpty() && !sourceExists(parent)) return false
                    val children = members.getOrPut(parent) {
                        listings.getOrPut(parent) { session.storage.list(parent) }.mapTo(hashSetOf()) { it.path }
                    }
                    return path in children
                }
                known.forEach { book ->
                    try { session.sourcePresence[book.source] = sourceExists(book.source) }
                    catch (e: Exception) { failures += "${session.storage.displayPath}/${book.source}：[检查来源] ${e.message}" }
                }
                // Novel legacy formatting is unchanged. Manga text requires full metadata verification below.
                val sourcePrefix = pinned.getString(com.moe.starflow.R.string.comic_meta_source, "").trim()
                val staleDescriptions = known.filter { book ->
                    val body = org.json.JSONObject(book.body)
                    val description = body.optString("description")
                    if (kind == "manga" || book.deleting || description.isBlank()) return@filter false
                    ComicMetadataParser.stripSourceLines(description, sourcePrefix) != description
                }.mapTo(mutableSetOf()) { it.uuid }
                if (staleDescriptions.isNotEmpty()) {
                    coroutineContext.ensureActive()
                    try {
                        normalizeDescriptions(pinned, staleDescriptions, sourcePrefix)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failures += "[清理简介] ${e.message}" }
                }
                if (kind == "manga") MangaDirectoryUpdates.refresh(pinned, session, known, failures, listings)
                else refreshEmptyNovels(pinned, session, known, failures)
                // Metadata-only changes refresh source fields without touching overrides or page layout.
                val repairs = known.mapNotNull { previous ->
                    val book = session.database.catalog().byUuid(previous.uuid) ?: return@mapNotNull null
                    val body = org.json.JSONObject(book.body)
                    if (book.deleting || session.sourcePresence[book.source] != true) return@mapNotNull null
                    if (kind == "novel") return@mapNotNull book.takeIf {
                        body.optInt("chapterCount") != 0 && body.optString("description").isBlank() &&
                            body.optInt("metadataVersion") < BookMetadataReader.VERSION
                    }
                    try {
                        book.takeIf { session.database.catalog().meta("metadataRead${BookMetadataReader.VERSION}:${book.uuid}") !=
                            metadataFingerprint(session, book) }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        LogCollector.w("LibraryScanner", "作品元数据检查跳过：${book.source}; ${e.message}")
                        null
                    }
                }
                val candidates = discover(session, root, known, kind, failures, listings)
                val total = repairs.size + candidates.size
                state.value = state.value.copy(total = total)
                repairs.forEachIndexed { index, book ->
                    coroutineContext.ensureActive()
                    try {
                        backfillDescription(pinned, session, book)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { LogCollector.w("LibraryScanner", "作品元数据暂未更新：${book.source}; ${e.message}") }
                    state.value = state.value.copy(done = index + 1, failures = failures.toList())
                }
                // 一次性清理：根目录没有元数据的漫画，清掉旧版本从章节/包内拼进书简介的文字。
                // ⚠️ 失败必须静音（不写 failures）——扫描错误会触发书库恢复/重建提示。
                if (kind == "manga") {
                    clearStaleMangaDescriptions(pinned, session, known)
                }
                candidates.forEachIndexed { index, node ->
                    val id = if (kind == "manga") ImportManager.reserveScanId(pinned, null)
                        else NovelImportManager.reserveScanId(pinned, null)
                    if (id == null) return@forEachIndexed
                    var localLease: java.io.Closeable? = null
                    var stage = "读取来源"
                    var emptyDirectory = false
                    try {
                        val hintedUuid = node.path.substringAfterLast('/').takeIf { LibraryCodec.UUID_PATTERN.matches(it) }
                        val identityPath = hintedUuid?.let { ".starflow/books/$it/source.json" }
                        val identity = identityPath?.takeIf(session.storage::exists)?.let {
                            org.json.JSONObject(session.storage.read(it).bufferedReader().use { reader -> reader.readText() })
                        }?.takeIf {
                            val original = safeLibraryPath(it.optString("source"))
                            it.optInt("format") == 1 && it.optString("kind") == kind && it.optString("uuid") == hintedUuid &&
                                (original == node.path || original.startsWith("${node.path}/"))
                        }
                        val indexedUuid = identity?.getString("uuid")
                        if (indexedUuid != null && session.database.catalog().byUuid(indexedUuid) == null)
                            session.database.catalog().meta(LibraryMeta("uuid:$kind:$id", indexedUuid))
                        // UUID 目录是导入副本的外壳；按已保存的文件/子目录解析，保留原格式和分章。
                        // 原来源缺失时停止，不能把外壳内的其他内容收录并覆盖 source.json。
                        val sourcePath = identity?.getString("source")?.let(::safeLibraryPath) ?: node.path
                        val sourceNode = if (sourcePath == node.path) node else {
                            val parent = sourcePath.substringBeforeLast('/')
                            listings.getOrPut(parent) { session.storage.list(parent) }.singleOrNull { it.path == sourcePath }
                                ?: throw java.io.IOException("原作品来源不存在：$sourcePath；已保留原来源记录")
                        }
                        val source = session.local(sourcePath)
                        if (sourceNode.directory && session.storage !is FileLibraryStorage) source.mkdirs()
                        localLease = LibrarySources.acquire(source)
                        if (!sourceNode.directory) LibrarySources.file(source)
                        val title = identity?.optString("sourceName")?.takeIf { it.isNotBlank() }
                            ?: if (sourceNode.directory) source.name else source.nameWithoutExtension.ifBlank { source.name }
                        stage = "识别作品"
                        if (kind == "manga") {
                            if (!sourceNode.directory && ArchiveTypes.detect(source) == ArchiveKind.ZIP &&
                                ZipFile(source).use { it.getEntry("META-INF/container.xml") != null }) return@forEachIndexed
                            val manga = MangaImporter.inspectLocalSource(pinned, source, id, title, sourceNode = sourceNode)
                            if (manga.pageCount <= 0 && !sourceNode.directory) throw java.io.IOException("来源中没有可读漫画图片")
                            emptyDirectory = sourceNode.directory && manga.pageCount == 0
                            added += ImportedMangaStore.mergeScanned(pinned, listOf(manga))
                        } else {
                            if (sourceNode.directory) LibrarySources.file(source, folderTexts = true)
                            val novel = NovelImporter.inspectLocalSource(pinned, source, id, title, allowEmptyDirectory = sourceNode.directory)
                            emptyDirectory = sourceNode.directory && novel.chapterCount == 0
                            added += NovelStore.mergeScanned(pinned, listOf(novel))
                        }
                        session.sourcePresence[sourcePath] = true
                        stage = "提交书库"
                        session.flush()
                        if (session.hasPendingSourceFiles()) throw java.io.IOException("未保存到书库：${session.saveState.value.error}")
                        if (emptyDirectory) {
                            val notice = pinned.getString(com.moe.starflow.R.string.library_empty_directory_added, node.path)
                            informational += notice; failures += notice
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        if (e !is IllegalStateException || e.message != NovelImporter.ERROR_NO_TEXT_CHAPTER)
                            failures += "${session.storage.displayPath}/${node.path}：[$stage] ${e.javaClass.simpleName}：${e.message}"
                    } finally {
                        localLease?.close()
                        try { session.discardUncommittedArtifacts(kind, id) }
                        catch (e: Exception) {
                            failures += "${node.path}：[清理未提交产物] ${e.message}"
                        }
                        if (kind == "manga") ImportManager.releaseScanIds(listOf(id)) else NovelImportManager.releaseScanIds(listOf(id))
                        state.value = state.value.copy(done = repairs.size + index + 1, added = added, failures = failures.toList())
                    }
                }
                session.flush()
                completed(state.value.copy(running = false, done = total, added = added, failures = failures.toList(),
                    needsRecovery = failures.any { it !in informational }))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                com.moe.starflow.utils.LogCollector.e("LibraryScanner", "扫描书库失败：${session.storage.displayPath}", e)
                completed(state.value.copy(running = false, failures = failures + "${session.storage.displayPath}：${e.javaClass.simpleName}：${e.message}", needsRecovery = true))
            } finally {
                state.value = state.value.copy(running = false)
                lease.close()
            }
        }
    }
    /** Empty directories already own their paths; discover their first text chapters without making a new book. */
    private suspend fun refreshEmptyNovels(context: Context, session: LibrarySession, known: List<LibraryBook>, failures: MutableList<String>) {
        known.filter { !it.deleting && session.sourcePresence[it.source] == true }.forEach { book ->
            val body = org.json.JSONObject(book.body)
            if (body.optString("format") != NovelFormat.FOLDER.name || body.optInt("chapterCount") != 0) return@forEach
            val source = session.local(book.source)
            try {
                LibrarySources.acquire(source).use {
                    LibrarySources.file(source, folderTexts = true)
                    val parsed = NovelImporter.inspectLocalSource(context, source, book.id, body.optString("title"))
                    val previous = NovelStore.load(context).firstOrNull { it.bookUuid == book.uuid } ?: return@use
                    NovelStore.patch(context, previous) { latest -> latest.copy(chapterCount = parsed.chapterCount,
                        sizeBytes = parsed.sizeBytes, author = latest.author ?: parsed.author,
                        description = latest.description.ifBlank { parsed.description },
                        metadataVersion = parsed.metadataVersion, coverPath = latest.coverPath ?: parsed.coverPath) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e !is IllegalStateException || e.message != NovelImporter.ERROR_NO_TEXT_CHAPTER)
                    failures += "${session.storage.displayPath}/${book.source}: ${e.message}"
            }
        }
    }

    /** Existing novel-only legacy source-line migration. */
    private fun normalizeDescriptions(
        context: Context,
        uuids: Set<String>,
        sourcePrefix: String,
    ) {
        NovelStore.load(context).filter { it.bookUuid in uuids }.forEach { value ->
            NovelStore.patch(context, value) { latest ->
                latest.copy(description = ComicMetadataParser.stripSourceLines(latest.description, sourcePrefix),
                    metadataVersion = BookMetadataReader.VERSION)
            }
        }
    }

    private fun metadataFingerprint(session: LibrarySession, book: LibraryBook): String =
        LibraryCodec.digest((org.json.JSONObject(book.body).optString("sourceIndexPath") +
            BookMetadataReader.reviewFingerprint(session.local(book.source))).toByteArray(Charsets.UTF_8))

    private suspend fun backfillDescription(context: Context, session: LibrarySession, book: LibraryBook) {
        val source = session.local(book.source)
        val body = org.json.JSONObject(session.database.catalog().byUuid(book.uuid)?.body ?: book.body)
        val directory = if (book.kind == "manga") !body.optBoolean("isArchive") && !body.optBoolean("isPdf")
            else body.optString("format") == NovelFormat.FOLDER.name
        if (directory && session.storage !is FileLibraryStorage) source.mkdirs()
        val fingerprint = if (book.kind == "manga") metadataFingerprint(session, book.copy(body = body.toString())) else null
        LibrarySources.acquire(source).use {
            if (!directory) LibrarySources.file(source)
            val metadata = if (book.kind == "manga") BookMetadataReader.bookMetadata(
                source, MangaChapter.listFromJson(body.optString("chapters")),
                MangaSourceIndex.load(session, body.optString("sourceIndexPath"))
            ) else ComicMetadataParser.parse(BookMetadataReader.sourceTexts(source))
            if (book.kind == "manga") {
                MangaDescriptions.cacheSource(session, book.copy(body = body.toString()), metadata)
                // The pre-read facts are captured before I/O; a change during reading is retried next scan.
                session.database.catalog().meta(LibraryMeta("metadataRead${BookMetadataReader.VERSION}:${book.uuid}", fingerprint!!))
            } else {
                val value = NovelStore.load(context).firstOrNull { it.bookUuid == book.uuid } ?: return
                val embedded = if (value.format != NovelFormat.TXT) {
                    if (directory) LibrarySources.file(source, folderTexts = true)
                    val parsed = com.moe.starflow.novel.parser.NovelParsers.forFormat(value.format).parse(source)
                    ComicMetadata(writer = parsed.author, summary = parsed.description.takeIf(String::isNotBlank))
                } else ComicMetadata(writer = value.author)
                val merged = metadata?.let { ComicMetadataParser.merge(it, embedded) } ?: embedded
                val description = ComicMetadataParser.buildDescription(context, merged)
                NovelStore.patch(context, value) { latest ->
                    latest.copy(description = latest.description.ifBlank { description }, author = latest.author ?: merged.writer, metadataVersion = BookMetadataReader.VERSION)
                }
            }
        }
    }
    /** Correct verified chapter metadata leaked into book fields, including records already at VERSION. */
    private suspend fun clearStaleMangaDescriptions(context: Context, session: LibrarySession, known: List<LibraryBook>) {
        val records = ImportedMangaStore.load(context).associateBy { it.bookUuid }
        known.filter { !it.deleting && session.sourcePresence[it.source] == true }.forEach { book ->
            coroutineContext.ensureActive()
            val record = records[book.uuid] ?: return@forEach
            if (record.chapters.size <= 1 || record.descriptionOverride != null || record.descriptionRevision.isNotEmpty() ||
                record.description.isBlank() && record.tags.isEmpty()) return@forEach
            val reviewKey = "metadataReview9:${book.uuid}"
            try {
                val source = session.local(book.source)
                val fingerprint = LibraryCodec.digest((listOf(record.sourceIndexPath, record.descriptionRevision,
                    record.description, record.tags).toString() + BookMetadataReader.reviewFingerprint(source)).toByteArray(Charsets.UTF_8))
                if (session.database.catalog().meta(reviewKey) == fingerprint) return@forEach
                LibrarySources.acquire(source).use {
                    // Even malformed work-level data is evidence of ownership: don't clear on parse failure.
                    if (BookMetadataReader.bookRootTexts(source).isNotEmpty() ||
                        BookMetadataReader.sidecarTexts(source).isNotEmpty()) return@use
                    val index = MangaSourceIndex.load(session, record.sourceIndexPath)
                    val metadata = record.chapters.mapNotNull { chapter ->
                        coroutineContext.ensureActive()
                        BookMetadataReader.chapterMetadata(source, chapter, index)
                    }
                    val descriptions = metadata.flatMap { MangaDescriptions.legacyDescriptions(context, it) }
                        .filter(String::isNotBlank).toSet()
                    val tagSets = metadata.map { ComicMetadataParser.bookTags(it).toSet() }.filter { it.isNotEmpty() }
                    val clearDescription = record.descriptionRevision.isEmpty() && record.description.trim() in descriptions
                    val clearTags = record.tags.isNotEmpty() && record.tags.toSet() in tagSets
                    if (metadata.isNotEmpty()) ImportedMangaStore.patch(context, record) { latest ->
                        if (latest.descriptionOverride != null || latest.descriptionRevision.isNotEmpty()) return@patch latest
                        latest.copy(
                            metadataVersion = BookMetadataReader.VERSION,
                            descriptionOverride = if (!clearDescription && latest.description.isNotBlank() && latest.description == record.description)
                                latest.description else latest.descriptionOverride,
                            descriptionRevision = if (!clearDescription && latest.description.isNotBlank() && latest.description == record.description)
                                "legacy-preserved" else latest.descriptionRevision,
                            description = if (clearDescription && latest.descriptionRevision.isEmpty() && latest.description == record.description) "" else latest.description,
                            tags = if (clearTags && latest.tags == record.tags) emptyList() else latest.tags,
                        )
                    }
                }
                session.database.catalog().meta(LibraryMeta(reviewKey, fingerprint))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { LogCollector.w("LibraryScanner", "旧简介核对跳过: ${book.source}; ${e.message}") }
        }
    }

    private fun discover(session: LibrarySession, root: String, known: List<LibraryBook>, kind: String, failures: MutableList<String>, listings: MutableMap<String, List<LibraryNode>>): List<LibraryNode> {
        val out = mutableListOf<LibraryNode>()
        val retired = session.database.catalog().allMeta().filter { it.name.startsWith("legacySource:") && it.value == "1" }
            .map { it.name.removePrefix("legacySource:") }
        val ownedSources = (known.map { it.source } + retired).toHashSet()
        val copiedRoots = known.mapTo(hashSetOf()) { "$root/${it.uuid}" }
        val ownedParents = hashSetOf<String>()
        for (source in ownedSources) {
            var parent = source.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) { ownedParents += parent; parent = parent.substringBeforeLast('/', "") }
        }
        fun owned(path: String): Boolean {
            var ancestor = path
            while (ancestor.isNotEmpty()) {
                if (ancestor in ownedSources) return true
                ancestor = ancestor.substringBeforeLast('/', "")
            }
            return false
        }
        // beginImport persists this path before creating the copy. Queued numeric ids own no
        // source yet; assigning them UUIDs here would bypass the import write-access preflight.
        val importing = session.database.catalog().allMeta()
            .filter { it.name.startsWith("importing:$kind:") && it.value.isNotEmpty() }.mapTo(hashSetOf()) { it.value }
        fun supported(node: LibraryNode): Boolean {
            if (node.directory) return true
            val ext = node.path.substringAfterLast('.').lowercase()
            val knownExtensions = if (kind == "manga")
                setOf("zip", "cbz", "rar", "cbr", "7z", "pdf")
            else setOf("txt", "epub", "zip", "html", "htm", "xhtml")
            if (ext in knownExtensions) return true
            // Novel imports also accept HTML/plain-text files with missing or nonstandard suffixes.
            // Sniff only candidates missed by the cheap extension check; the detector reads a small prefix.
            return kind == "novel" && runCatching {
                com.moe.starflow.novel.parser.NovelFormatDetector.detect(session.local(node.path)) != null
            }.getOrDefault(false)
        }
        listings.getValue(root).forEach { node ->
            val name = node.path.substringAfterLast('/')
            if (name.startsWith('.') || name == "sr_ncnn_benchmark.txt" || node.path in importing) return@forEach
            if (owned(node.path)) return@forEach
            // A previously catalogued nested source already owns this wrapper. Do not create
            // a second manga for its parent while preserving the original identity and records.
            if (node.path in copiedRoots || node.path in ownedParents) return@forEach
            if (supported(node)) out += node
        }
        return out.distinctBy { it.path }
    }
}
