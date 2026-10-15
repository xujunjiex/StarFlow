package com.moe.starflow.library

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.UUID

/** Confirmed reset removes only this location's previous records after the new identity is verified. */
internal object LibraryReinitialization {
    private const val PREFS = "library_locations"
    private val headers = listOf(".starflow/library.json", ".starflow/storage.json")
    private fun pending(context: Context, location: String) =
        AtomicFile(File(context.filesDir, "library_reinitializations/$location/pending.json"))
    private fun save(file: AtomicFile, plan: JSONObject) {
        file.baseFile.parentFile!!.mkdirs()
        val output = file.startWrite()
        try { output.write(plan.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    private fun accessibleRoot(storage: LibraryStorage) {
        if (storage is FileLibraryStorage && !storage.root.exists()) storage.mkdir("")
        storage.invalidateLookups()
        val nodes = storage.list("")
        nodes.filter { it.path == "manga_import" || it.path == "novel_import" }.forEach {
            if (!it.directory) throw IOException("${it.path} 是现有文件，已保留原文件；请重新选择存储目录")
        }
        if (storage is FileLibraryStorage) listOf(".starflow", ".starflow/books").forEach {
            if (java.nio.file.Files.isSymbolicLink(File(storage.root, it).toPath()))
                throw IOException("书库管理目录是符号链接，已保留原文件；请重新选择存储目录：$it")
        }
    }
    private fun preserveEntry(storage: LibraryStorage, path: String, identity: String) {
        require(path == ".starflow" || path in headers || path == ".starflow/books" ||
            Regex("\\.starflow/books/[0-9a-fA-F-]{36}(/source\\.json)?").matches(path))
        storage.rename(path, path.substringAfterLast('/') + ".replaced-$identity-${UUID.randomUUID()}")
    }
    private fun directory(storage: LibraryStorage, path: String, identity: String) {
        if (storage is FileLibraryStorage && java.nio.file.Files.isSymbolicLink(File(storage.root, path).toPath()))
            throw IOException("书库管理路径是符号链接，已保留原文件；请重新选择存储目录：$path")
        val node = storage.list(path.substringBeforeLast('/', "")).singleOrNull { it.path == path }
        if (node != null && (!node.directory || runCatching { storage.list(path) }.isFailure)) preserveEntry(storage, path, identity)
        storage.mkdir(path)
    }

    fun generation(context: Context, location: String): String? {
        val file = pending(context, location)
        if (file.baseFile.exists()) return JSONObject(file.openRead().bufferedReader().use { it.readText() }).getString("generation")
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("generation:$location", null)
    }

    fun start(context: Context, storage: LibraryStorage, previousDatabase: String, previousIdentity: String?, previousGeneration: String?, sources: JSONArray) {
        val file = pending(context, storage.locationId)
        if (!file.baseFile.exists()) {
            accessibleRoot(storage)
            val identity = UUID.randomUUID().toString()
            val database = LibrarySessions.openDatabase(context, storage.locationId, identity)
            try {
                database.runInTransaction {
                    val catalog = database.catalog()
                    catalog.meta(LibraryMeta("manifestId", identity))
                    catalog.meta(LibraryMeta("recordStorage", "app-database"))
                    catalog.meta(LibraryMeta("legacyDirectoryCleanup", "complete"))
                    catalog.meta(LibraryMeta("legacyMigrated", "1"))
                }
            } finally { database.close() }
            val plan = JSONObject().put("format", 1).put("location", storage.locationId)
                .put("generation", identity).put("previousDatabase", previousDatabase)
                .put("previousIdentity", previousIdentity ?: JSONObject.NULL)
                .put("previousGeneration", previousGeneration ?: JSONObject.NULL)
                .put("createdAt", System.currentTimeMillis()).put("sources", sources)
            save(file, plan)
        }
        resume(context, storage)
    }

    fun resume(context: Context, storage: LibraryStorage) {
        val file = pending(context, storage.locationId)
        if (!file.baseFile.exists()) return
        val plan = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        check(plan.getString("location") == storage.locationId)
        val identity = plan.getString("generation")
        require(LibraryCodec.UUID_PATTERN.matches(identity))
        accessibleRoot(storage)
        val desired = mapOf(
            headers[0] to JSONObject().put("format", 1).put("libraryId", identity).toString().toByteArray(Charsets.UTF_8),
            headers[1] to JSONObject().put("format", 1).put("records", "app-database").put("libraryId", identity).toString().toByteArray(Charsets.UTF_8))
        // Only management entries may be replaced. Source roots are created only if absent.
        listOf("manga_import", "novel_import").forEach(storage::mkdir)
        directory(storage, ".starflow", identity)
        directory(storage, ".starflow/books", identity)
        desired.forEach { (path, bytes) ->
            val present = storage.exists(path)
            val actual = if (present) runCatching { storage.read(path).use { it.readBytes() } }.getOrNull() else null
            if (actual?.contentEquals(bytes) == true) return@forEach
            // Preserve the old entry even when unreadable or accidentally a directory.
            if (present) preserveEntry(storage, path, identity)
            storage.writeNew(path, "application/json").use { it.write(bytes) }
            if (!storage.read(path).use { it.readBytes() }.contentEquals(bytes)) throw IOException("书库身份写入校验失败：$path")
        }
        val sources = plan.optJSONArray("sources") ?: JSONArray()
        for (i in 0 until sources.length()) {
            val source = sources.getJSONObject(i)
            val uuid = source.getString("uuid")
            val kind = source.getString("kind")
            require(LibraryCodec.UUID_PATTERN.matches(uuid) && kind in setOf("manga", "novel"))
            require(safeLibraryPath(source.getString("source")).startsWith("${kind}_import/"))
            val folder = ".starflow/books/$uuid"
            directory(storage, folder, identity)
            val path = "$folder/source.json"
            val bytes = source.toString().toByteArray(Charsets.UTF_8)
            if (storage.exists(path) && runCatching { storage.read(path).use { it.readBytes() }.contentEquals(bytes) }.getOrDefault(false)) continue
            if (storage.exists(path)) preserveEntry(storage, path, identity)
            storage.writeNew(path, "application/json").use { it.write(bytes) }
            if (!storage.read(path).use { it.readBytes() }.contentEquals(bytes)) throw IOException("作品来源标识写入校验失败：$path")
        }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("generation:${storage.locationId}", identity).commit()) { "无法保存新书库身份" }
        // Keep the intent until the previous session is closed and its records are removed.
    }

    fun finish(context: Context, storage: LibraryStorage, generation: String?) {
        val file = pending(context, storage.locationId)
        if (!file.baseFile.exists()) return
        val plan = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        val identity = plan.getString("generation")
        if (generation != identity) return
        val oldDatabase = plan.getString("previousDatabase")
        val prefix = "library_${storage.locationId}"
        require(oldDatabase == "$prefix.db" || Regex("${prefix}_[0-9a-fA-F-]{36}\\.db").matches(oldDatabase))
        require(oldDatabase != "${prefix}_$identity.db")
        val databaseFiles = listOf("", "-wal", "-shm", "-journal").map { File(context.getDatabasePath(oldDatabase).path + it) }
        if (databaseFiles.any { it.exists() }) {
            context.deleteDatabase(oldDatabase)
            if (databaseFiles.any { it.exists() }) throw IOException("新书库已建立，但旧内部数据库清理失败，请重试")
        }
        fun removePrivate(relative: String) {
            val target = File(context.filesDir, relative).canonicalFile
            require(target.path.startsWith(context.filesDir.canonicalPath + File.separator))
            if (target.exists() && !target.deleteRecursively()) throw IOException("旧书库内部资料清理失败，请重试：$relative")
        }
        val archiveRoot = File(context.filesDir, "library_record_archives/${storage.locationId}")
        if (archiveRoot.isDirectory) (archiveRoot.listFiles() ?: throw IOException("无法枚举旧内部记录，请重试"))
            .filter { it.name != identity }.forEach { entry ->
                require(entry.canonicalPath.startsWith(archiveRoot.canonicalPath + File.separator))
                removePrivate("library_record_archives/${storage.locationId}/${entry.name}")
            }
        else if (archiveRoot.exists()) removePrivate("library_record_archives/${storage.locationId}")
        val oldGeneration = plan.optString("previousGeneration").takeIf { LibraryCodec.UUID_PATTERN.matches(it) }
        val workingName = storage.locationId + oldGeneration?.let { "_$it" }.orEmpty()
        if (workingName != "${storage.locationId}_$identity") removePrivate("library_sources/$workingName")
        file.delete()
    }
}
