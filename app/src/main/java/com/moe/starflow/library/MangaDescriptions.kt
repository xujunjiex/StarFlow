package com.moe.starflow.library

import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.mangaimport.data.MangaSourceIndex
import com.moe.starflow.mangaimport.data.ComicMetadata
import com.moe.starflow.mangaimport.data.ComicMetadataParser
import com.moe.starflow.mangaimport.data.MangaChapter
import com.moe.starflow.R
import com.moe.starflow.utils.LogCollector
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Work-owned description overrides. Room owns edits; the outbox owns portable-file retries. */
object MangaDescriptions {
    private const val FORMAT = "starflow-work-metadata"
    const val DIRECTORY_FILE = "starflow.json"

    fun sidecarPath(source: String, directory: Boolean): String =
        safeLibraryPath(if (directory) "$source/$DIRECTORY_FILE" else "$source.starflow.json")

    private fun json(text: String): JSONObject = JSONObject(text).also {
        require(it.optString("format") == FORMAT && it.optInt("version") == 1 && it.has("descriptionOverride")) {
            "Unrecognized StarFlow metadata"
        }
        require(it.isNull("descriptionOverride") || it.get("descriptionOverride") is String)
        require(LibraryCodec.UUID_PATTERN.matches(it.getString("revision")))
    }

    /** Called only for a new catalog entry, so an old file never overwrites active local edits. */
    fun restore(value: ImportedManga): ImportedManga = try {
        val path = if (!value.isArchive && !value.isPdf) File(value.localRoot, DIRECTORY_FILE).path
            else value.localRoot + ".starflow.json"
        readSidecar(File(path))?.let(::json)?.let { data ->
            value.copy(descriptionOverride = if (data.isNull("descriptionOverride")) null else data.getString("descriptionOverride"),
                descriptionRevision = data.optString("revision"))
        } ?: value
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        LogCollector.w("MangaDescriptions", "简介副本未恢复：${e.message}")
        value
    }

    private fun readSidecar(file: File): String? {
        val located = LibrarySessions.findFile(file)
        if (located != null && located.first.storage.exists(located.second))
            return located.first.storage.read(located.second).use { BookMetadataReader.readText(it) }
        return file.takeIf(File::isFile)?.inputStream()?.use { BookMetadataReader.readText(it) }
    }

    fun original(session: LibrarySession, manga: ImportedManga): String {
        val source = File(manga.localRoot)
        if (!session.storage.exists(session.relative(source))) return manga.description
        return LibrarySources.acquire(source).use {
            com.moe.starflow.mangaimport.data.ComicMetadataParser.summaryText(
                BookMetadataReader.bookMetadata(source, manga.chapters, MangaSourceIndex.load(session, manga.sourceIndexPath)))
        }
    }

    internal fun legacyDescriptions(context: android.content.Context, meta: ComicMetadata): Set<String> {
        val contexts = listOf(context) + listOf("en", "zh").map { language ->
            val config = android.content.res.Configuration(context.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(language))
            context.createConfigurationContext(config)
        }
        return contexts.flatMap { localized -> listOf(true, false).flatMap { tags ->
            val generated = ComicMetadataParser.buildDescription(localized, meta, tags)
            listOf(generated.trim(), normalizeLegacy(localized, generated))
        } }.toSet().plus(ComicMetadataParser.summaryText(meta)).filter(String::isNotBlank).toSet()
    }

    internal fun normalizeLegacy(context: android.content.Context, text: String): String {
        val withoutSource = ComicMetadataParser.stripSourceLines(text, context.getString(R.string.comic_meta_source, "").trim())
        return ComicMetadataParser.extractTagsLine(withoutSource, context.getString(R.string.comic_meta_tags, "").trim()).second.trim()
    }

    /** Cache a successful source read so every screen uses the same effective description.
     * Only owned metadata fields change; concurrent edits/progress/layout are read in the transaction. */
    internal fun cacheSource(session: LibrarySession, expected: LibraryBook, metadata: ComicMetadata?) {
        synchronized(com.moe.starflow.mangaimport.data.ImportedMangaStore) { session.database.runInTransaction {
            val current = session.database.catalog().byUuid(expected.uuid) ?: return@runInTransaction
            val body = JSONObject(current.body)
            val prior = JSONObject(expected.body)
            if (current.deleting || current.source != expected.source ||
                body.optString("sourceIndexPath") != prior.optString("sourceIndexPath")) return@runInTransaction
            val legacy = body.optInt("metadataVersion") < BookMetadataReader.VERSION
            val description = body.optString("description")
            if (metadata == null && body.isNull("descriptionOverride") && description.isNotBlank() &&
                body.optString("descriptionRevision").isEmpty() && MangaChapter.listFromJson(body.optString("chapters")).size > 1)
                return@runInTransaction
            if (legacy && body.isNull("descriptionOverride") && description.isNotBlank()) {
                val candidates = metadata?.let { legacyDescriptions(session.app, it) }.orEmpty()
                if (description.trim() !in candidates) {
                    body.put("descriptionOverride", description).put("descriptionRevision", "legacy-preserved")
                }
            }
            body.put("description", ComicMetadataParser.summaryText(metadata)).put("metadataVersion", BookMetadataReader.VERSION)
            if (metadata != null) body.put("tags", org.json.JSONArray(ComicMetadataParser.bookTags(metadata)))
            if (body.toString() != current.body) {
                session.putBook("manga", current.id, body)
                LibrarySessions.changes.value += 1
            }
        } }
    }

    internal fun enqueue(session: LibrarySession, uuid: String, revision: String) {
        val row = session.database.catalog().byUuid(uuid) ?: throw IOException("Work was removed")
        session.enqueue(JSONObject().put("component", "metadata").put("uuid", uuid)
            .put("source", row.source).put("revision", revision))
    }

    /** Flush serializes file writers. A book lease excludes deletion during replacement. */
    internal fun publish(session: LibrarySession, operation: JSONObject) {
        val uuid = operation.getString("uuid")
        val catalog = session.database.catalog()
        val initial = catalog.byUuid(uuid) ?: return
        if (initial.deleting) return
        val lease = BookRecordLocks.tryAcquire(BookRef(session.locationId, "manga", uuid))
            ?: throw IOException("作品正在更新，简介文件副本等待重试")
        lease.use {
            val row = catalog.byUuid(uuid) ?: return
            val body = JSONObject(row.body)
            if (row.deleting || row.source != operation.getString("source") ||
                body.optString("descriptionRevision") != operation.getString("revision")) return
            val storage = session.storage
            if (!storage.exists(row.source)) throw IOException("作品来源不可用，简介已保存在应用内")
            val directory = !body.optBoolean("isArchive") && !body.optBoolean("isPdf")
            val path = sidecarPath(row.source, directory)
            val bytes = JSONObject().put("format", FORMAT).put("version", 1)
                .put("revision", operation.getString("revision"))
                .put("descriptionOverride", body.opt("descriptionOverride") ?: JSONObject.NULL)
                .toString(2).toByteArray(Charsets.UTF_8)
            require(bytes.size <= com.moe.starflow.mangaimport.data.ComicMetadataParser.MAX_METADATA_BYTES)
            json(String(bytes, Charsets.UTF_8))
            val temporary = path.substringBeforeLast('/', "") + "/.starflow-description-$uuid.tmp"
            fun matches(at: String): Boolean = storage.exists(at) && storage.read(at).use {
                BookMetadataReader.readText(it)?.toByteArray(Charsets.UTF_8)?.contentEquals(bytes) == true
            }
            val recoveryKey = "metadataReplacing:$uuid"
            val replacing = catalog.meta(recoveryKey) == path
            if (matches(path)) {
                if (storage.exists(temporary)) storage.delete(temporary)
                catalog.meta(LibraryMeta(recoveryKey, ""))
                return
            }
            // Never overwrite an unrelated file which happens to use our chosen name.
            if (storage.exists(path) && !replacing) storage.read(path).use {
                json(BookMetadataReader.readText(it) ?: throw IOException("简介副本过大"))
            }
            if (!matches(temporary)) {
                if (storage.exists(temporary)) storage.delete(temporary)
                storage.writeNew(temporary, "application/json").use { it.write(bytes) }
                if (!matches(temporary)) throw IOException("简介临时副本校验失败")
            }
            val latest = catalog.byUuid(uuid) ?: return
            if (latest.deleting || latest.source != row.source ||
                JSONObject(latest.body).optString("descriptionRevision") != operation.getString("revision")) return
            if (storage is FileLibraryStorage) {
                java.nio.file.Files.move(storage.file(temporary).toPath(), storage.file(path).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } else {
                // Providers need not support rename. The verified temporary and durable intent
                // remain until the final file verifies, including after process interruption.
                catalog.meta(LibraryMeta(recoveryKey, path))
                if (storage.exists(path)) storage.delete(path)
                storage.writeNew(path, "application/json").use { it.write(bytes) }
            }
            if (!matches(path)) throw IOException("简介文件副本校验失败")
            if (storage.exists(temporary)) storage.delete(temporary)
            catalog.meta(LibraryMeta(recoveryKey, ""))
        }
    }
}
