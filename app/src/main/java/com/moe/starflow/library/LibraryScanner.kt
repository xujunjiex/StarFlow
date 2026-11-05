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
    val runId: String = java.util.UUID.randomUUID().toString(), val issues: List<LibraryScanIssue> = emptyList()) {
    val needsRecovery: Boolean get() = issues.any { it.scope == LibraryIssueScope.LIBRARY }
}

object LibraryScanner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val state = MutableStateFlow(LibraryScanState())
    private val reports = ArrayDeque<LibraryScanState>()
    private val requests = hashSetOf<String>()
    @Synchronized fun consumeFailures(location: String = state.value.location, kind: String = state.value.kind): List<String>? {
        return consumeReport(location, kind)?.failures
    }
    @Synchronized internal fun peekReport(location: String, kind: String): LibraryScanState? =
        reports.firstOrNull { it.location == location && it.kind == kind }
    @Synchronized internal fun acknowledgeReport(runId: String) { reports.removeAll { it.runId == runId } }
    @Synchronized fun consumeReport(location: String, kind: String): LibraryScanState? {
        val report = reports.firstOrNull { it.location == location && it.kind == kind } ?: return null
        reports.remove(report)
        return report
    }
    @Synchronized private fun completed(result: LibraryScanState): LibraryScanState {
        if (result.issues.isNotEmpty()) reports.addLast(result)
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
        val pinned = try { LibrarySessions.pin(context) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { initializationFailed(context, kind, e); return }
        val session = LibrarySessions.current(pinned)
        val key = "${session.locationId}:${session.generation}:$kind"
        if (!requests.add(key)) return
        LibrarySessions.launch(pinned, scope) {
            scan(pinned, kind)
        }.invokeOnCompletion { synchronized(this) { requests.remove(key) } }
    }
    private fun initializationFailed(context: Context, kind: String, error: Exception): LibraryScanState {
        val prefs = context.getSharedPreferences("library_locations", Context.MODE_PRIVATE)
        val target = prefs.getString("tree", null) ?: prefs.getString("defaultPath", null).orEmpty()
        val location = runCatching {
            prefs.getString("tree", null)?.let { SafLibraryStorage(context, android.net.Uri.parse(it)).locationId }
                ?: FileLibraryStorage(java.io.File(target)).locationId
        }.getOrDefault(target)
        val issue = LibraryScanIssue(LibraryIssueScope.LIBRARY,
            context.getString(com.moe.starflow.R.string.library_stage_prepare), target,
            libraryFailureDetail(error), reason = libraryErrorKind(error).name)
        val result = LibraryScanState(location, kind, issues = listOf(issue), failures = listOf(issue.text(context)))
        LogCollector.e("LibraryScanner", "run=${result.runId} kind=$kind location=$location stage=${issue.stage}", error)
        return completed(result)
    }
    suspend fun scan(context: Context, kind: String): LibraryScanState = LibraryWork.sourceMutations.withLock {
        withContext(Dispatchers.IO) {
            val pinned = try { LibrarySessions.pin(context) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { return@withContext initializationFailed(context, kind, e) }
            val session = LibrarySessions.current(pinned)
            var lease: java.io.Closeable? = null
            val runId = java.util.UUID.randomUUID().toString()
            val problems = LibraryScanProblems(pinned, session, kind, runId)
            fun stage(id: Int) = pinned.getString(id)
            var libraryStage = stage(com.moe.starflow.R.string.library_stage_prepare)
            var added = 0
            try {
                lease = LibrarySessions.use()
                session.storage.invalidateLookups()
                state.value = LibraryScanState(session.locationId, kind, running = true, runId = runId)
                LibrarySessions.prepare(pinned)
                val root = if (kind == "manga") "manga_import" else "novel_import"
                libraryStage = stage(com.moe.starflow.R.string.library_stage_catalog)
                val known = session.scanBooks(kind)
                val validKnown = known.filter { book ->
                    try {
                        if (kind == "manga") ImportedMangaStore.validateScanPayload(book.body)
                        else NovelStore.validateScanPayload(book.body)
                        session.local(book.source)
                        true
                    }
                    catch (e: Exception) { problems.failed(libraryStage, book.source, e, book); false }
                }
                if (kind == "manga") MangaDuplicateRepair.repair(session, mutableListOf(),
                    onFailure = { book, e -> problems.failed(stage(com.moe.starflow.R.string.library_stage_duplicates), book.source, e, book) },
                    onNotice = { book, detail -> problems.add(LibraryScanIssue(LibraryIssueScope.NOTICE,
                        stage(com.moe.starflow.R.string.library_stage_duplicates), book.source, detail, book.uuid,
                        outcome = LibraryBookOutcome.RETAINED)) })
                libraryStage = stage(com.moe.starflow.R.string.library_stage_enumerate)
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
                validKnown.forEach { book ->
                    try {
                        session.sourcePresence[book.source] = sourceExists(book.source)
                        session.sourceIssues.remove(book.source)
                    } catch (e: Exception) {
                        session.sourceIssues[book.source] = libraryFailureDetail(e)
                        problems.failed(stage(com.moe.starflow.R.string.library_stage_presence), book.source, e, book)
                    }
                }
                // Novel legacy formatting is unchanged. Manga text requires full metadata verification below.
                val sourcePrefix = pinned.getString(com.moe.starflow.R.string.comic_meta_source, "").trim()
                val staleDescriptions = validKnown.filter { book ->
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
                    catch (e: Exception) { problems.failed(stage(com.moe.starflow.R.string.library_stage_metadata), root, e) }
                }
                if (kind == "manga") MangaDirectoryUpdates.refresh(pinned, session, validKnown, mutableListOf(), listings,
                    onFailure = { book, e -> problems.failed(stage(com.moe.starflow.R.string.library_stage_index), book.source, e, book) })
                else refreshEmptyNovels(pinned, session, validKnown, problems)
                // Metadata-only changes refresh source fields without touching overrides or page layout.
                val repairs = validKnown.mapNotNull { previous ->
                    val book = session.database.catalog().byUuid(previous.uuid) ?: return@mapNotNull null
                    val body = try { org.json.JSONObject(book.body) } catch (e: org.json.JSONException) {
                        problems.failed(stage(com.moe.starflow.R.string.library_stage_catalog), book.source, e, book)
                        return@mapNotNull null
                    }
                    if (book.deleting || session.sourcePresence[book.source] != true) return@mapNotNull null
                    if (kind == "novel") return@mapNotNull book.takeIf {
                        body.optInt("chapterCount") != 0 && body.optString("description").isBlank() &&
                            body.optInt("metadataVersion") < BookMetadataReader.VERSION
                    }
                    try {
                        book.takeIf { session.database.catalog().meta("metadataRead${BookMetadataReader.VERSION}:${book.uuid}") !=
                            metadataFingerprint(session, book) }
                    } catch (e: CancellationException) { throw e }
                    catch (e: android.database.sqlite.SQLiteException) { throw e }
                    catch (e: Exception) {
                        LogCollector.w("LibraryScanner", "作品元数据检查跳过：${book.source}; ${e.message}")
                        null
                    }
                }
                val candidates = discover(session, root, known, kind, problems, listings)
                val total = repairs.size + candidates.size
                state.value = state.value.copy(total = total)
                repairs.forEachIndexed { index, book ->
                    coroutineContext.ensureActive()
                    try {
                        backfillDescription(pinned, session, book)
                    } catch (e: CancellationException) { throw e }
                    catch (e: android.database.sqlite.SQLiteException) { throw e }
                catch (e: Exception) { LogCollector.w("LibraryScanner", "作品元数据暂未更新：${book.source}; ${e.message}") }
                    state.value = state.value.copy(done = index + 1, failures = problems.failures, issues = problems.issues)
                }
                // Optional metadata never blocks source discovery.
                if (kind == "manga") try { clearStaleMangaDescriptions(pinned, session, validKnown) }
                catch (e: CancellationException) { throw e }
                catch (e: android.database.sqlite.SQLiteException) { throw e }
                catch (e: Exception) { LogCollector.w("LibraryScanner", "旧简介核对暂未完成", e) }
                candidates.forEachIndexed { index, node ->
                    coroutineContext.ensureActive()
                    var id: Long? = null
                    var localLease: java.io.Closeable? = null
                    var stage = stage(com.moe.starflow.R.string.library_stage_reserve)
                    var candidateTitle: String? = null
                    try {
                        id = if (kind == "manga") ImportManager.reserveScanId(pinned, null)
                            else NovelImportManager.reserveScanId(pinned, null)
                        val reserved = id ?: return@forEachIndexed
                        stage = pinned.getString(com.moe.starflow.R.string.library_stage_source)
                        val identity = readSourceIdentity(session, node, kind)
                        val indexedUuid = identity?.getString("uuid")
                        if (indexedUuid != null && session.database.catalog().byUuid(indexedUuid) == null)
                            session.database.catalog().meta(LibraryMeta("uuid:$kind:$reserved", indexedUuid))
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
                        candidateTitle = title
                        val emptyDirectory: Boolean
                        stage = pinned.getString(com.moe.starflow.R.string.library_stage_inspect)
                        if (kind == "manga") {
                            if (!sourceNode.directory && ArchiveTypes.detect(source) == ArchiveKind.ZIP &&
                                ZipFile(source).use { it.getEntry("META-INF/container.xml") != null }) return@forEachIndexed
                            val manga = MangaImporter.inspectLocalSource(pinned, source, reserved, title, sourceNode = sourceNode)
                            if (manga.pageCount <= 0 && !sourceNode.directory) throw java.io.IOException("来源中没有可读漫画图片")
                            emptyDirectory = sourceNode.directory && manga.pageCount == 0
                            added += ImportedMangaStore.mergeScanned(pinned, listOf(manga))
                        } else {
                            if (sourceNode.directory) LibrarySources.file(source, folderTexts = true)
                            val novel = NovelImporter.inspectLocalSource(pinned, source, reserved, title, allowEmptyDirectory = sourceNode.directory)
                            emptyDirectory = sourceNode.directory && novel.chapterCount == 0
                            added += NovelStore.mergeScanned(pinned, listOf(novel))
                        }
                        session.sourcePresence[sourcePath] = true
                        stage = pinned.getString(com.moe.starflow.R.string.library_stage_save)
                        problems.files(session.flushPending())
                        if (emptyDirectory) {
                            val notice = pinned.getString(com.moe.starflow.R.string.library_empty_directory_added, node.path)
                            problems.add(LibraryScanIssue(LibraryIssueScope.NOTICE, stage, node.path, notice,
                                outcome = LibraryBookOutcome.RETAINED))
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        if (e !is IllegalStateException || e.message != NovelImporter.ERROR_NO_TEXT_CHAPTER)
                            problems.failed(stage, node.path, e,
                                id?.let { session.database.catalog().book(kind, it) },
                                outcome = if (id?.let { session.database.catalog().book(kind, it) } != null)
                                    LibraryBookOutcome.RETAINED else LibraryBookOutcome.NOT_ADDED,
                                directory = node.directory,
                                uuidHint = node.path.substringAfterLast('/').takeIf { LibraryCodec.UUID_PATTERN.matches(it) }
                                    ?: id?.let { session.database.catalog().meta("uuid:$kind:$it") },
                                titleHint = candidateTitle)
                    } finally {
                        try {
                            localLease?.close()
                            id?.let { session.discardUncommittedArtifacts(kind, it) }
                        } catch (e: Exception) {
                            problems.failed(pinned.getString(com.moe.starflow.R.string.library_stage_cleanup), node.path, e,
                                outcome = LibraryBookOutcome.RETAINED)
                        } finally {
                            id?.let { if (kind == "manga") ImportManager.releaseScanIds(listOf(it)) else NovelImportManager.releaseScanIds(listOf(it)) }
                        }
                        state.value = state.value.copy(done = repairs.size + index + 1, added = added, failures = problems.failures, issues = problems.issues)
                    }
                }
                libraryStage = stage(com.moe.starflow.R.string.library_stage_save)
                problems.files(session.flushPending())
                LogCollector.i("LibraryScanner", "run=$runId kind=$kind location=${session.locationId} added=$added issues=${problems.issues.size}")
                completed(state.value.copy(running = false, done = total, added = added, failures = problems.failures, issues = problems.issues))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                com.moe.starflow.utils.LogCollector.e("LibraryScanner", "扫描书库失败：${session.storage.displayPath}", e)
                if (e is LibraryMigrationException) libraryStage = stage(com.moe.starflow.R.string.library_stage_migrate)
                problems.add(LibraryScanIssue(LibraryIssueScope.LIBRARY, libraryStage, session.storage.displayPath,
                    libraryFailureDetail(e), reason = libraryErrorKind(e).name), e)
                completed(state.value.copy(running = false, added = added, failures = problems.failures, issues = problems.issues))
            } finally {
                state.value = state.value.copy(running = false)
                lease?.close()
            }
        }
    }
    private fun readSourceIdentity(session: LibrarySession, node: LibraryNode, kind: String): org.json.JSONObject? {
        val uuid = node.path.substringAfterLast('/').takeIf { LibraryCodec.UUID_PATTERN.matches(it) } ?: return null
        val path = ".starflow/books/$uuid/source.json"
        if (!session.storage.exists(path)) return null
        val identity = try {
            org.json.JSONObject(session.storage.read(path).bufferedReader().use { it.readText() })
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw java.io.IOException("$path: 原来源标识无法读取；已保留原文件", e) }
        val differences = mutableListOf<String>()
        mapOf("format" to "1", "kind" to kind, "uuid" to uuid).forEach { (field, expected) ->
            if (identity.optString(field) != expected) differences += "$field: expected=$expected, actual=${identity.opt(field)}"
        }
        val source = identity.optString("source")
        val valid = runCatching { safeLibraryPath(source) }.getOrNull()
        if (valid == null || valid != node.path && !valid.startsWith("${node.path}/"))
            differences += "source: expected=${node.path}[/…], actual=$source"
        if (differences.isNotEmpty()) throw java.io.IOException("$path: 原来源标识对不上；已保留原文件\n" + differences.joinToString("\n"))
        return identity
    }

    /** Empty directories already own their paths; discover their first text chapters without making a new book. */
    private suspend fun refreshEmptyNovels(context: Context, session: LibrarySession, known: List<LibraryBook>, problems: LibraryScanProblems) {
        known.filter { !it.deleting && session.sourcePresence[it.source] == true }.forEach { book ->
            try {
                val body = org.json.JSONObject(book.body)
                if (body.optString("format") != NovelFormat.FOLDER.name || body.optInt("chapterCount") != 0) return@forEach
                val source = session.local(book.source)
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
                    problems.failed(context.getString(com.moe.starflow.R.string.library_stage_index), book.source, e, book)
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
            catch (e: android.database.sqlite.SQLiteException) { throw e }
            catch (e: Exception) { LogCollector.w("LibraryScanner", "旧简介核对跳过: ${book.source}; ${e.message}") }
        }
    }

    private fun discover(session: LibrarySession, root: String, known: List<LibraryBook>, kind: String, problems: LibraryScanProblems, listings: MutableMap<String, List<LibraryNode>>): List<LibraryNode> {
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
            return kind == "novel" && try {
                com.moe.starflow.novel.parser.NovelFormatDetector.detect(session.local(node.path)) != null
            } catch (e: Exception) {
                problems.failed(session.app.getString(com.moe.starflow.R.string.library_stage_inspect), node.path, e,
                    outcome = LibraryBookOutcome.NOT_ADDED)
                false
            }
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
