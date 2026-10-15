package com.moe.starflow.library

import android.content.Context
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.room.withTransaction
import com.moe.starflow.R
import com.moe.starflow.utils.AppNotice
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class SourceAvailability { AVAILABLE, MISSING, PARTIAL, UNREADABLE, CHANGED }

object BookData {
    fun resolve(context: Context, ref: BookRef): Pair<LibrarySession, LibraryBook> {
        val session = LibrarySessions.forLocation(context, ref.locationId)
        val book = session.database.catalog().byUuid(ref.uuid)
        require(book?.kind == ref.kind) { context.getString(R.string.book_reference_expired) }
        return session to book!!
    }

    fun availability(session: LibrarySession, book: LibraryBook): SourceAvailability {
        if (session.sourceIssues.containsKey(book.source)) return SourceAvailability.CHANGED
        if (session.sourcePresence[book.source] == false) {
            if (!session.storage.exists(book.source)) return SourceAvailability.MISSING
            val index = runCatching { com.moe.starflow.mangaimport.data.MangaSourceIndex.load(session, JSONObject(book.body).optString("sourceIndexPath")) }.getOrNull()
            return if (index != null && index.pages.any { !it.missing }) SourceAvailability.PARTIAL else SourceAvailability.MISSING
        }
        if (!session.storage.exists(book.source)) return SourceAvailability.MISSING
        val body = JSONObject(book.body)
        return when {
            body.optInt("changedPages") > 0 -> SourceAvailability.CHANGED
            body.optInt("missingPages") > 0 -> SourceAvailability.PARTIAL
            else -> SourceAvailability.AVAILABLE
        }
    }

    /** Capture this book only, even when another library uses the same UUID. */
    suspend fun snapshot(context: Context, ref: BookRef, includeRecords: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
        val (session, _) = resolve(context, ref)
        session.database.withTransaction {
            val (_, book) = resolve(context, ref)
            val body = JSONObject(book.body)
            val catalog = session.database.catalog()
            val reconciled = catalog.allMeta().filter { it.name.startsWith("duplicateOf:") && it.value == ref.uuid }
                .map { it.name.removePrefix("duplicateOf:") }.filter(LibraryCodec.UUID_PATTERN::matches)
            val artifactOwners = reconciled + ref.uuid
            JSONObject().put("formatVersion", 1).put("capturedAt", System.currentTimeMillis())
                .put("recordStorage", "app-database").put("databaseRecordsSaved", true)
                .put("book", body).put("kind", ref.kind).put("uuid", ref.uuid)
                .put("libraryLocation", ref.locationId).put("library", session.storage.displayPath)
                .put("source", book.source).put("deleting", book.deleting).put("deletionError", book.error ?: JSONObject.NULL)
                .put("reconciledBookUuids", JSONArray(reconciled))
                .put("pendingWrites", JSONArray(catalog.pending().filter { it.body.contains(ref.uuid) }.map { JSONObject(it.body) }))
                .put("assets", JSONObject().also { assets -> catalog.allMeta().filter {
                    artifactOwners.any { uuid -> it.name.startsWith("asset:.starflow/books/$uuid/") }
                }.forEach { assets.put(it.name.removePrefix("asset:"), it.value) } })
                .put("counts", JSONObject().put("translation", catalog.translationCount(book.id, ref.key)).put("sr", catalog.resolutionCount(book.id, ref.key)).put("paragraph", catalog.paragraphCount(book.id, ref.key)))
                .put("records", JSONObject().also { records ->
                    if (includeRecords) {
                        records.put("translation", LibraryCodec.encode(catalog.bookTranslations(book.id, ref.key), ref.locationId))
                        records.put("sr", LibraryCodec.encode(catalog.bookResolutions(book.id, ref.key), ref.locationId))
                        records.put("paragraph", LibraryCodec.encode(catalog.bookParagraphs(book.id, ref.key), ref.locationId))
                    }
                })
        }.also { data ->
            val book = resolve(context, ref).second
            data.put("availability", runCatching { availability(session, book).name }.getOrDefault(SourceAvailability.UNREADABLE.name))
            data.put("saveError", session.saveState.value.error ?: JSONObject.NULL)
            data.put("sourceIssue", session.sourceIssues[book.source] ?: JSONObject.NULL)
            runCatching { com.moe.starflow.mangaimport.data.MangaSourceIndex.load(session, JSONObject(book.body).optString("sourceIndexPath")) }.getOrNull()?.let { index ->
                data.put("sourceSummary", JSONObject().put("knownPages", index.pages.size)
                    .put("missingPages", if (data.optString("availability") == "MISSING") index.pages.size else index.pages.count { it.missing })
                    .put("pagesRequiringReview", index.pages.count { !it.missing && (it.path in index.problems) })
                    .put("availablePages", if (data.optString("availability") == "MISSING") 0 else index.pages.count { !it.missing && it.path !in index.problems })
                    .put("missingSources", JSONArray(index.pages.filter { it.missing }.map { it.path }.distinct())))
            }
        }
    }

    suspend fun recordPage(context: Context, ref: BookRef, type: String, offset: Int): JSONArray = withContext(Dispatchers.IO) {
        val (session, book) = resolve(context, ref)
        val catalog = session.database.catalog()
        val rows: Any = when (type) {
            "translation" -> catalog.bookTranslations(book.id, ref.key, 100, offset)
            "sr" -> catalog.bookResolutions(book.id, ref.key, 100, offset)
            else -> catalog.bookParagraphs(book.id, ref.key, 100, offset)
        }
        val result = LibraryCodec.encode(rows, ref.locationId) as JSONArray
        for (i in 0 until result.length()) {
            val row = result.getJSONObject(i)
            row.put("stateLabel", context.getString(when (row.optInt("state")) {
                1 -> R.string.book_state_running
                2 -> R.string.book_state_success
                3 -> R.string.book_state_failed
                4 -> if (type == "sr") R.string.book_state_cancelled else R.string.book_state_ocr
                5 -> R.string.book_state_stopping
                6 -> R.string.book_state_result_missing
                else -> R.string.book_state_idle
            }))
            if (type == "sr") row.put("artifactAvailable", row.optString("sourcePageKey").takeIf { it.isNotBlank() && it != "null" }?.let {
                com.moe.starflow.sr.SrStore.existsForSource(context, book.id, ref.key, it)
            } ?: JSONObject.NULL)
        }
        result
    }

    /** ZIP writing is independent of a reader and never reconstructs missing originals. */
    suspend fun writePackage(context: Context, ref: BookRef, output: OutputStream): List<String> {
        val (session, _) = resolve(context, ref)
        LibrarySessions.use().use { _ -> BookRecordLocks.acquire(ref).use {
            check(!LibraryDeletion.isDeleting(ref.locationId, ref.kind, ref.uuid)) { context.getString(R.string.library_deleting) }
            val snapshot = snapshot(context, ref)
            val missing = mutableListOf<String>()
            val paths = linkedSetOf<String>()
            val sr = snapshot.getJSONObject("records").optJSONArray("sr") ?: JSONArray()
            val book = resolve(context, ref).second
            for (i in 0 until sr.length()) {
                val row = sr.getJSONObject(i)
                if (row.optInt("state") == com.moe.starflow.data.ImportedPageSr.STATE_SUCCESS) {
                    val sourceKey = row.optString("sourcePageKey").takeUnless { it.isBlank() || it == "null" }
                    if (sourceKey == null || !com.moe.starflow.sr.SrStore.existsForSource(context, book.id, ref.key, sourceKey))
                        missing += "sr/page:${row.optInt("pageIndex")}: artifact unavailable"
                }
            }
            val reconciled = snapshot.getJSONArray("reconciledBookUuids")
            val roots = ((0 until reconciled.length()).map(reconciled::getString) + ref.uuid)
                .flatMap { uuid -> listOf(".starflow/books/$uuid", ".starflow/legacy/$uuid") }
            fun collect(path: String) {
                try { if (session.storage.exists(path)) session.storage.list(path).forEach {
                    if (it.directory) collect(it.path) else paths += it.path
                } } catch (e: Exception) { missing += "$path: ${e.message}" }
                session.local(path).takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && !it.name.endsWith(".tmp") }
                    ?.forEach { paths += session.relative(it) }
                val archive = session.recordArchive(path)
                archive.takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && !it.name.endsWith(".migrating") }
                    ?.forEach { paths += "$path/${it.relativeTo(archive).invariantSeparatorsPath}" }
            }
            roots.forEach(::collect)
            val body = snapshot.getJSONObject("book")
            listOf("coverPath", "sourceIndexPath").map(body::optString).filter { it.isNotBlank() && it != "null" }.forEach { paths += it }
            val assets = snapshot.getJSONObject("assets")
            assets.keys().forEach { key -> assets.optString(key).takeIf { it.isNotBlank() }?.let(paths::add) }
            ZipOutputStream(output).use { zip ->
                fun text(name: String, value: String) { zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
                text("book-data.json", snapshot.toString(2))
                text("README.txt", context.getString(R.string.book_records_readme))
                for (path in paths) {
                    currentCoroutineContext().ensureActive()
                    if (roots.none { path.startsWith("$it/") }) { missing += path; continue }
                    val staged = java.io.File.createTempFile("book-record-", ".tmp", context.cacheDir)
                    try {
                        try {
                            val local = session.local(safeLibraryPath(path))
                            val archive = session.recordArchive(path)
                            val input = when {
                                archive.isFile -> archive.inputStream()
                                local.isFile -> local.inputStream()
                                else -> session.storage.read(path)
                            }
                            input.use { stream -> staged.outputStream().use { target ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) { currentCoroutineContext().ensureActive(); val n = stream.read(buffer); if (n < 0) break; target.write(buffer, 0, n) }
                            } }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { missing += "$path: ${e.message}"; continue }
                        // Output failures must abort the package, rather than masquerade as a missing artifact.
                        zip.putNextEntry(ZipEntry("artifacts/$path"))
                        staged.inputStream().use { stream ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) { currentCoroutineContext().ensureActive(); val n = stream.read(buffer); if (n < 0) break; zip.write(buffer, 0, n) }
                        }
                        zip.closeEntry()
                    } finally { staged.delete() }
                }
                text("manifest.json", JSONObject().put("formatVersion", 1).put("uuid", ref.uuid).put("kind", ref.kind)
                    .put("originalIncluded", false).put("complete", missing.isEmpty()).put("missingArtifacts", JSONArray(missing)).toString(2))
            }
            return missing
        } }
    }
}

object BookRecordExport {
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    fun start(context: Context, ref: BookRef) {
        if (jobs.containsKey(ref.key)) return
        val (session, book) = BookData.resolve(context, ref)
        val app = session.context(context.applicationContext)
        val job = LibrarySessions.launch(app) {
            var uri: android.net.Uri? = null
            try {
                withContext(Dispatchers.Main) { AppNotice.show(context, context.getString(R.string.book_records_exporting)) }
                withContext(Dispatchers.IO) {
                    val title = JSONObject(book.body).optString("title").replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(70)
                    val name = "${title}_${ref.uuid}_${System.currentTimeMillis()}_records.zip"
                    uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS); put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }) ?: error("Cannot create download")
                    val missing = (app.contentResolver.openOutputStream(uri!!) ?: error("Cannot open download")).use { BookData.writePackage(app, ref, it) }
                    app.contentResolver.update(uri!!, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    uri = null
                    withContext(Dispatchers.Main) { AppNotice.show(context, context.getString(
                        if (missing.isEmpty()) R.string.book_records_exported else R.string.book_records_exported_partial, name, missing.size)) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { withContext(Dispatchers.Main) { AppNotice.show(context, e.message.orEmpty(), AppNotice.Style.ERROR) } }
            finally { uri?.let { app.contentResolver.delete(it, null, null) }; jobs.remove(ref.key); session.scheduleFlush() }
        }
        jobs[ref.key] = job
        job.invokeOnCompletion { jobs.remove(ref.key, job) }
    }
}
