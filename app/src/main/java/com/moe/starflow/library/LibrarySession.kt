package com.moe.starflow.library

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.room.withTransaction
import com.moe.starflow.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

data class LibrarySaveState(val saving: Boolean = false, val pending: Int = 0, val error: String? = null)

/** Identity failures retain local records until the user explicitly rebuilds the library. */
class LibraryIdentityException(message: String, cause: Throwable? = null) : IOException(message, cause)
data class BookRef(val locationId: String, val kind: String, val uuid: String) {
    val key get() = "$locationId:$kind:$uuid"
}

class LibrarySession internal constructor(val app: Context, val storage: LibraryStorage,
    val database: LibraryDatabase, val generation: Long, internal val storageGeneration: String? = null) {
    val locationId get() = storage.locationId
    val workingRoot: File = (storage as? FileLibraryStorage)?.root
        ?: File(app.filesDir, "library_sources/$locationId${storageGeneration?.let { "_$it" }.orEmpty()}").apply { mkdirs() }
    val saveState = MutableStateFlow(LibrarySaveState())
    private val catalog get() = database.catalog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ioLock = Any()
    private val catalogReadLock = Any()
    private val journal = LibraryJournal(storage)
    private var prepared = false
    private var readOnly = false
    @Volatile private var closed = false
    internal val isClosed get() = closed
    private val coverUris = java.util.concurrent.ConcurrentHashMap<String, String>()
    val sourceIssues = java.util.concurrent.ConcurrentHashMap<String, String>()
    val sourcePresence = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun context(base: Context = app): Context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        val librarySession = this@LibrarySession
    }.let { LibraryContext(it, this) }

    fun local(path: String) = File(workingRoot, safeLibraryPath(path))
    fun relative(file: File): String {
        val base = workingRoot.canonicalPath
        val at = file.canonicalPath
        return requireNotNull(libraryRelativePath(base, at)) { "文件不属于当前书库：$file" }
    }
    fun cover(path: String): String = if (storage is FileLibraryStorage || local(path).isFile) local(path).path
        else coverUris[path] ?: local(path).path

    /** Runs on IO before a newly selected root is published. Failure never creates an empty catalog. */
    fun prepare() = synchronized(ioLock) {
        check(!closed) { "书库会话已切换，请刷新" }
        var validationComplete = false
        try {
            LibraryReinitialization.resume(app, storage)
            storage.invalidateLookups()
            if (prepared) {
                val manifest = readManifest()
                validateManifest(manifest)
                validateStructure(manifest)
                LibraryReinitialization.finish(app, storage, storageGeneration)
                readOnly = false
                return@synchronized
            }
            storage.list("") // Distinguish inaccessible from an empty root before creating anything.
            if (storage.exists(".starflow") && !storage.exists(".starflow/library.json"))
                throw LibraryIdentityException("书库管理目录存在，但身份记录缺失；已停止覆盖")
            if (!storage.exists(".starflow/library.json")) {
                if (catalog.meta("manifestId") != null || !catalog.pending().isEmpty())
                    throw LibraryIdentityException("原书库身份记录缺失；已停止覆盖，本地资料保留")
                listOf("manga_import", "novel_import", ".starflow/books")
                    .forEach(storage::mkdir)
                val manifest = JSONObject().put("format", 1).put("libraryId", UUID.randomUUID().toString())
                storage.writeNew(".starflow/library.json", "application/json").use { it.write(manifest.toString().toByteArray()) }
            }
            val manifest = readManifest()
            if (catalog.meta("manifestId") == null && storage.exists(".starflow/storage.json"))
                throw LibraryIdentityException("本机书库记录缺失，目录中仍有书库管理信息；可覆盖重建并重新扫描原文件")
            validateManifest(manifest)
            validateStructure(manifest)
            LibraryReinitialization.finish(app, storage, storageGeneration)
            // Import portable records only when no authoritative local database exists yet.
            if (catalog.meta("recordStorage") != "app-database") {
                val hasLocalState = catalog.meta("head") != null || catalog.books("manga").isNotEmpty() ||
                    catalog.books("novel").isNotEmpty() || catalog.pending().isNotEmpty()
                if (!hasLocalState && !storage.exists(".starflow/storage.json") && storage.exists(".starflow/catalog")) restore()
                database.runInTransaction {
                    catalog.meta(LibraryMeta("recordStorage", "app-database"))
                    catalog.meta(LibraryMeta("legacyDirectoryCleanup", "pending"))
                }
            }
            recoverInterruptedImports()
            readOnly = false
            prepared = true
            // Cover handles are cheap; source archives and original image trees remain lazy.
            listOf("manga", "novel").flatMap { catalog.books(it) }.forEach { book ->
                val p = JSONObject(book.body).optString("coverPath")
                if (p.isNotEmpty() && p != "null") runCatching { coverUris[p] = storage.uri(p).toString() }
            }
            validationComplete = true
            flush()
        } catch (e: Exception) {
            readOnly = !validationComplete || e is LibraryIdentityException || e is org.json.JSONException || e.message?.contains("停止覆盖") == true
            saveState.value = LibrarySaveState(pending = runCatching { catalog.pending().size }.getOrDefault(saveState.value.pending), error = e.message ?: e.javaClass.simpleName)
            if (!validationComplete || readOnly) throw e
        }
    }

    private fun readManifest(): JSONObject {
        if (!storage.exists(".starflow/library.json"))
            throw LibraryIdentityException("原书库身份记录缺失；已停止覆盖，本地资料保留")
        return try { JSONObject(storage.read(".starflow/library.json").bufferedReader().use { it.readText() }) }
        catch (e: org.json.JSONException) { throw LibraryIdentityException("书库身份记录损坏；已停止覆盖", e) }
    }
    private fun validateStructure(manifest: JSONObject) {
        if (storage.exists(".starflow/storage.json")) {
            val mode = try { JSONObject(storage.read(".starflow/storage.json").bufferedReader().use { it.readText() }) }
                catch (e: org.json.JSONException) { throw LibraryIdentityException("书库管理文件损坏：.starflow/storage.json", e) }
            if (mode.optInt("format", -1) != 1 || mode.optString("records") != "app-database" ||
                mode.optString("libraryId") != manifest.getString("libraryId"))
                throw LibraryIdentityException("书库管理文件不匹配：.starflow/storage.json")
        } else if (catalog.meta("recordStorage") == "app-database")
            throw LibraryIdentityException("书库管理文件缺失：.starflow/storage.json")
        val root = storage.list("")
        listOf("manga_import", "novel_import").forEach { path ->
            val node = root.singleOrNull { it.path == path }
            if (node == null) {
                // The identity was validated before this check. Recreate only empty source
                // containers; missing books and their processing records remain in the catalog.
                storage.mkdir(path)
                if (storage.list("").singleOrNull { it.path == path }?.directory != true)
                    throw IOException("无法重建书库入口目录：$path")
            } else if (!node.directory) throw IOException("书库入口不是目录：$path；请重新选择存储目录，原文件保留")
        }
        val books = storage.list(".starflow").singleOrNull { it.path == ".starflow/books" }
            ?: throw IOException("书库管理目录缺失：.starflow/books")
        if (!books.directory) throw IOException("书库管理路径不是目录：.starflow/books")
    }

    /** Keep scheduled publication out of an explicitly confirmed identity replacement. */
    internal fun <T> withStorageLock(block: () -> T): T = synchronized(ioLock) { block() }

    /** Private historical records and durable page identities; never used as a second live database. */
    internal fun recordArchive(path: String): File {
        val safe = safeLibraryPath(path)
        require(safe.startsWith(".starflow/"))
        val identity = catalog.meta("manifestId") ?: throw IOException("书库身份尚未载入")
        return File(app.filesDir, "library_record_archives/$locationId/$identity/$safe")
    }

    /** One-time verified evacuation of the former portable database. Originals/artifacts stay put. */
    private fun migrateDirectoryRecords() {
        val roots = mutableListOf(".starflow/catalog", ".starflow/legacy")
        if (storage.exists(".starflow/books")) storage.list(".starflow/books").filter { it.directory }.forEach {
            val uuid = it.path.substringAfterLast('/')
            if (LibraryCodec.UUID_PATTERN.matches(uuid)) roots += "${it.path}/records"
        }
        fun archive(path: String) {
            if (!storage.exists(path)) return
            val parent = path.substringBeforeLast('/', "")
            val node = storage.list(parent).single { it.path == path }
            if (node.directory) storage.list(path).forEach { archive(it.path) }
            else {
                val target = recordArchive(path)
                val digest = storage.read(path).use(LibraryCodec::digest)
                if (target.isFile && target.inputStream().use(LibraryCodec::digest) == digest) return
                target.parentFile!!.mkdirs()
                val pending = File(target.parentFile, target.name + ".migrating")
                try {
                    storage.read(path).use { input -> pending.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
                    if (pending.inputStream().use(LibraryCodec::digest) != digest) throw IOException("旧记录备份校验失败：$path")
                    if (!pending.renameTo(target)) throw IOException("旧记录备份无法提交：$path")
                } finally { pending.delete() }
            }
        }
        roots.forEach(::archive)
        listOf("manga", "novel").flatMap(catalog::books).map { JSONObject(it.body).optString("sourceIndexPath") }
            .filter { it.startsWith(".starflow/books/") }.forEach(::archive)
        val mode = JSONObject().put("format", 1).put("records", "app-database").put("libraryId", catalog.meta("manifestId"))
        writeVerified(".starflow/storage.json", mode.toString().toByteArray(Charsets.UTF_8))
        roots.forEach { if (storage.exists(it)) storage.delete(it) }
        catalog.meta(LibraryMeta("legacyDirectoryCleanup", "complete"))
    }

    private fun validateManifest(manifest: JSONObject) {
        if (manifest.optInt("format", -1) != 1) throw LibraryIdentityException("不支持此书库记录版本；已停止覆盖")
        val identity = manifest.optString("libraryId")
        if (!LibraryCodec.UUID_PATTERN.matches(identity)) throw LibraryIdentityException("书库身份记录损坏；已停止覆盖")
        val previous = catalog.meta("manifestId")
        if (previous != null && previous != identity) throw LibraryIdentityException("目录书库身份已更换；已停止覆盖，本地数据保留")
        if (previous == null) catalog.meta(LibraryMeta("manifestId", identity))
    }

    private fun restore() {
        val commits = journal.chain(catalog.pending().mapTo(hashSetOf()) { it.token })
        val head = commits.lastOrNull()?.token ?: ""
        val chain = commits.map { it.operation }
        val localHead = catalog.meta("head") ?: ""
        if (head == localHead) return
        val acknowledged = catalog.pending().map { it.token }.toSet()
        val localIndex = chain.indexOfLast { it.optString("token") == localHead }
        val ancestry = commits.firstOrNull()?.operation?.optJSONArray("history")?.let { values ->
            (0 until values.length()).map { values.getString(it) }
        }.orEmpty()
        if (localHead.isNotEmpty() && localIndex < 0 && localHead !in ancestry) throw IOException("目录记录版本冲突；已停止覆盖，本地未保存数据保留")
        val snapshot = if (localHead.isEmpty() && catalog.pending().isEmpty() && commits.firstOrNull()?.sequence == 1L) readSnapshot(commits) else null
        val snapshotIndex = snapshot?.optInt("sequence", 0) ?: 0
        val tail = chain.drop(maxOf(localIndex + 1, snapshotIndex))
        if (catalog.pending().isNotEmpty() && tail.any { it.optString("token") !in acknowledged })
            throw IOException("目录记录版本冲突；已停止覆盖，本地未保存数据保留")
        runBlocking {
            database.withTransaction {
                snapshot?.let { restoreSnapshot(it) }
                tail.forEach { operation ->
                    val alreadyApplied = operation.getString("token") in acknowledged
                    if (!alreadyApplied || operation.optString("component") == "asset" || operation.optBoolean("remove"))
                        applyOperation(operation)
                    recordCleanup(operation)
                    catalog.pending().firstOrNull { it.token == operation.getString("token") }?.let { catalog.acknowledge(it.sequence) }
                }
                catalog.meta(LibraryMeta("head", head))
                catalog.meta(LibraryMeta("commitSequence", commits.lastOrNull()?.sequence?.toString() ?: "0"))
            }
        }
    }

    private fun recordCleanup(operation: JSONObject) {
        val extra = operation.optJSONArray("cleanupAssets")
        val paths = listOfNotNull(operation.optString("cleanup").takeIf { it.isNotEmpty() }) +
            if (extra == null) emptyList() else (0 until extra.length()).map { extra.getString(it) }
        paths.forEach { raw ->
            val path = safeLibraryPath(raw)
            if (!path.startsWith(".starflow/books/")) throw IOException("产物清理定位越界；已停止覆盖")
            catalog.meta(LibraryMeta("cleanup:$path", "pending"))
        }
        if (extra != null) for (i in 0 until extra.length()) catalog.meta(LibraryMeta("asset:${extra.getString(i)}", ""))
    }

    private fun validateBook(kind: String, id: Long, body: JSONObject) {
        if (kind !in setOf("manga", "novel")) throw IOException("书库作品类型损坏；已停止覆盖")
        val uuid = body.getString("bookUuid")
        if (!LibraryCodec.UUID_PATTERN.matches(uuid)) throw IOException("书库作品 UUID 损坏；已停止覆盖")
        val source = safeLibraryPath(body.getString("localRoot"))
        if (!source.startsWith("${kind}_import/")) throw IOException("书库来源定位越界；已停止覆盖")
        val cover = body.optString("coverPath")
        if (cover.isNotEmpty() && cover != "null" && !safeLibraryPath(cover).startsWith(".starflow/books/$uuid/"))
            throw IOException("书库封面定位越界；已停止覆盖")
        val index = body.optString("sourceIndexPath")
        if (index.isNotBlank() && index != "null" && !safeLibraryPath(index).startsWith(".starflow/books/$uuid/source-index/"))
            throw IOException("Source index does not belong to this book")
        catalog.byUuid(uuid)?.let { if (it.kind != kind || it.id != id) throw IOException("书库作品 UUID 重复；已停止覆盖") }
    }

    private suspend fun applyOperation(operation: JSONObject) {
        when (operation.getString("component")) {
            "layout" -> applyMangaLayout(operation)
            "checkpoint" -> restoreSnapshot(operation)
            "meta" -> catalog.meta(LibraryMeta(operation.getString("name"), operation.getString("value")))
            "asset" -> {
                val path = operation.getString("path")
                if (operation.optBoolean("remove")) catalog.meta(LibraryMeta("asset:$path", ""))
                else catalog.meta(LibraryMeta("asset:$path", operation.getString("revision")))
            }
            "book" -> {
                val kind = operation.getString("kind")
                val id = operation.getLong("id")
                if (operation.optBoolean("remove")) {
                    (catalog.book(kind, id)?.uuid ?: operation.optString("uuid").takeIf { LibraryCodec.UUID_PATTERN.matches(it) })
                        ?.let { catalog.meta(LibraryMeta("removed:$it", "1")) }
                    catalog.remove(kind, id)
                } else {
                    val body = operation.getJSONObject("book")
                    validateBook(kind, id, body)
                    catalog.put(LibraryBook(kind, id, body.getString("bookUuid"), body.toString(),
                        body.getString("localRoot"), operation.optBoolean("deleting"),
                        operation.optString("error").takeIf { it.isNotEmpty() && it != "null" }))
                }
            }
            else -> {
                val dao = rawDao(operation.getString("component"))
                val method = daoInterface(operation.getString("component")).methods.first { it.name == operation.getString("method") }
                val values = operation.getJSONArray("args")
                val args = method.genericParameterTypes.dropLast(1).mapIndexed { i, type ->
                    LibraryCodec.decode(values.get(i), type, locationId)
                }.toTypedArray()
                invokeSuspend(dao, method, args)
            }
        }
    }

    private suspend fun applyMangaLayout(operation: JSONObject) {
        val id = operation.getLong("id")
        val body = operation.getJSONObject("book")
        validateBook("manga", id, body)
        val key = "$locationId:manga:${body.getString("bookUuid")}"
        val values = operation.getJSONArray("mapping")
        val mapping = IntArray(values.length()) { values.getInt(it) }
        if (mapping.isNotEmpty()) {
            database.translations().rekeyPages(id, key, mapping)
            database.resolutions().rekeyPages(id, key, mapping)
        }
        catalog.put(LibraryBook("manga", id, body.getString("bookUuid"), body.toString(), body.getString("localRoot")))
    }

    suspend fun updateMangaLayout(expected: LibraryBook, body: JSONObject, mapping: IntArray) {
        val operation = JSONObject().put("component", "layout").put("id", expected.id)
            .put("book", body).put("mapping", org.json.JSONArray(mapping.toList()))
        database.withTransaction {
            val latest = catalog.book("manga", expected.id) ?: return@withTransaction
            check(latest.uuid == expected.uuid && !latest.deleting && latest.body == expected.body) { "Book changed during source enumeration" }
            applyMangaLayout(operation)
            enqueue(operation)
        }
        scheduleFlush()
        LibrarySessions.changes.value += 1
    }

    internal fun beginImport(kind: String, id: Long) {
        val uuid = uuid(kind, id)
        catalog.meta(LibraryMeta("importing:$kind:$id", "${kind}_import/$uuid"))
    }
    internal fun abandonImport(kind: String, id: Long) {
        catalog.meta(LibraryMeta("importing:$kind:$id", ""))
    }
    private fun recoverInterruptedImports() {
        catalog.allMeta().filter { it.name.startsWith("importing:") && it.value.isNotEmpty() }.forEach { item ->
            val parts = item.name.split(':')
            val kind = parts[1]
            val id = parts[2].toLong()
            val active = if (kind == "manga") com.moe.starflow.mangaimport.data.ImportManager.activeIdsForScanner()
                else com.moe.starflow.novel.data.NovelImportManager.activeIdsForScanner()
            if (id in active || catalog.book(kind, id) != null) return@forEach
            val uuid = item.value.substringAfterLast('/')
            if (!LibraryCodec.UUID_PATTERN.matches(uuid) || item.value != "${kind}_import/$uuid")
                throw IOException("导入恢复定位损坏；已停止覆盖")
            // This durable intent names only this application's uncommitted manual copy.
            if (storage is FileLibraryStorage) storage.delete(item.value)
            else if (local(item.value).exists() && !local(item.value).deleteRecursively()) throw IOException("内部半成品清理失败：${item.value}")
            abandonImport(kind, id)
        }
    }

    internal fun persistMeta(name: String, value: String) {
        database.runInTransaction { catalog.meta(LibraryMeta(name, value)) }
    }
    internal fun releaseBookCopies(uuid: String, source: String) = synchronized(ioLock) {
        if (storage is FileLibraryStorage || catalog.pending().any { it.body.contains(uuid) }) return@synchronized
        if (LibrarySources.hasReaders(local(source))) return@synchronized
        local(source).deleteRecursively()
        local(".starflow/books/$uuid").deleteRecursively()
    }
    internal fun close() = synchronized(ioLock) {
        closed = true
        readOnly = true
        scope.cancel()
        synchronized(catalogReadLock) { database.close() }
    }
    fun books(kind: String): List<LibraryBook> = synchronized(catalogReadLock) {
        if (closed) emptyList() else try { catalog.books(kind) }
        catch (e: Exception) {
            readOnly = true
            saveState.value = saveState.value.copy(error = e.message ?: e.javaClass.simpleName)
            emptyList()
        }
    }
    fun assetPath(path: String): String = catalog.meta("asset:$path")?.takeIf { it.isNotEmpty() } ?: path
    fun publishArtifact(file: File) {
        val path = relative(file)
        val revision = if (file.extension == "json") path.removeSuffix(".json") + ".${UUID.randomUUID()}.revision.json" else path
        if (revision != path) file.copyTo(local(revision), overwrite = false)
        database.runInTransaction {
            enqueue(JSONObject().put("component", "asset").put("path", path).put("revision", revision))
        }
        flush()
        if (saveState.value.error != null) throw IOException(saveState.value.error)
    }
    fun publishRevision(logical: File, pending: File, cleanupAssets: List<File> = emptyList()) {
        val path = relative(logical)
        val revision = path.removeSuffix(".json") + ".${UUID.randomUUID()}.revision.json"
        pending.copyTo(local(revision), overwrite = false)
        database.runInTransaction {
            enqueue(JSONObject().put("component", "asset").put("path", path).put("revision", revision)
                .put("cleanupAssets", JSONArray(cleanupAssets.map(::relative))))
        }
        flush()
        if (saveState.value.pending != 0) throw IOException("未保存到书库：${saveState.value.error}")
    }
    fun deleteArtifact(file: File) {
        val path = relative(file)
        val actual = assetPath(path)
        database.runInTransaction {
            enqueue(JSONObject().put("component", "asset").put("path", path).put("remove", true).put("cleanup", actual))
        }
        flush()
        if (saveState.value.pending != 0) throw IOException("未保存到书库")
    }

    fun uuid(kind: String, id: Long): String = synchronized(this) {
        catalog.book(kind, id)?.uuid ?: catalog.meta("uuid:$kind:$id") ?: UUID.randomUUID().toString().also {
            catalog.meta(LibraryMeta("uuid:$kind:$id", it))
        }
    }
    fun nextId(kind: String): Long = synchronized(this) {
        val next = maxOf(catalog.meta("next:$kind")?.toLongOrNull() ?: 0,
            catalog.books(kind).maxOfOrNull { it.id } ?: 0) + 1
        catalog.meta(LibraryMeta("next:$kind", next.toString()))
        next
    }
    fun assertWritable(key: String) {
        check(!readOnly) { "书库记录不可写：${saveState.value.error}" }
        check(key.startsWith("$locationId:")) { "作品引用不属于捕获的书库" }
        val book = catalog.byUuid(key.substringAfterLast(':')) ?: throw IllegalStateException("作品已删除，不能写入旧任务结果")
        check(key.substringAfter(':').substringBefore(':') == book.kind) { "作品引用类型不匹配" }
        check(!book.deleting && catalog.meta("removed:${book.uuid}") != "1") { "作品已删除或正在删除，不能写入任务结果" }
    }
    fun putBook(kind: String, id: Long, body: JSONObject, deleting: Boolean = false, error: String? = null, stagingCover: String? = null) {
        check(!readOnly) { "书库记录不可写：${saveState.value.error}" }
        val old = catalog.book(kind, id)
        if (old?.deleting == true && !deleting) throw IOException("作品正在删除")
        val uuid = body.optString("bookUuid").ifEmpty { uuid(kind, id) }
        require(LibraryCodec.UUID_PATTERN.matches(uuid)) { "作品 UUID 损坏" }
        catalog.byUuid(uuid)?.let { owner -> check(owner.kind == kind && owner.id == id) { "作品 UUID 重复，已停止覆盖" } }
        body.put("bookUuid", uuid)
        validateBook(kind, id, body)
        val operation = JSONObject().put("component", "book").put("kind", kind).put("id", id)
            .put("book", body).put("deleting", deleting).put("error", error ?: JSONObject.NULL)
            .put("sourceRequired", old == null && !deleting)
        val previousCover = old?.let { JSONObject(it.body).optString("coverPath") }
        if (!previousCover.isNullOrEmpty() && previousCover != "null" && previousCover != body.optString("coverPath"))
            operation.put("cleanup", previousCover)
        stagingCover?.takeIf { it.startsWith(".starflow/staging/covers/") }?.let { operation.put("stagedCover", it) }
        database.runInTransaction {
            val current = catalog.book(kind, id)
            check(!readOnly) { "书库记录不可写" }
            if (!deleting && (current?.deleting == true || catalog.meta("removed:$uuid") == "1"))
                throw IOException("作品已删除或正在删除，不能写入旧记录")
            if (kind == "manga" && current != null) {
                val previous = JSONObject(current.body)
                if (previous.optInt("lastReadPage") != body.optInt("lastReadPage") ||
                    previous.optInt("pageOrderVersion") != body.optInt("pageOrderVersion"))
                    LegacyRecordActivation.cancelProgress(this, uuid)
            }
            validateBook(kind, id, body)
            catalog.put(LibraryBook(kind, id, uuid, body.toString(), body.getString("localRoot"), deleting, error))
            catalog.meta(LibraryMeta("importing:$kind:$id", ""))
            if (deleting) discardBookFileOperations(uuid)
            else {
                val previous = old?.let { JSONObject(it.body) }
                val artifacts = listOf("coverPath", "sourceIndexPath").mapNotNull { field ->
                    body.optString(field).takeIf { it.isNotBlank() && it != "null" && it != previous?.optString(field) }
                }
                operation.put("artifacts", JSONArray(artifacts))
                if (old == null || artifacts.isNotEmpty() || stagingCover != null) enqueue(operation)
            }
        }
        scheduleFlush()
    }
    private fun discardBookFileOperations(uuid: String) {
        catalog.pending().filter { it.body.contains(uuid) }.forEach { catalog.acknowledge(it.sequence) }
    }
    fun removeBook(kind: String, id: Long) {
        database.runInTransaction {
            catalog.book(kind, id)?.uuid?.let { uuid ->
                catalog.meta(LibraryMeta("removed:$uuid", "1"))
                discardBookFileOperations(uuid)
            }
            catalog.remove(kind, id)
        }
        LibrarySessions.changes.value += 1
    }
    fun finishRemoval(kind: String, id: Long) = removeBook(kind, id)
    internal fun enqueue(operation: JSONObject) {
        if (operation.optString("component") == "asset" && !operation.optBoolean("remove")) {
            val path = operation.getString("path")
            if (path.startsWith(".starflow/books/")) {
                val uuid = path.removePrefix(".starflow/books/").substringBefore('/')
                val owner = catalog.byUuid(uuid) ?: throw IllegalStateException("作品已删除，不能提交产物")
                check(!owner.deleting) { "作品正在删除，不能提交产物" }
            }
        }
        if (operation.optString("component") == "asset" && !operation.optBoolean("remove")) {
            val old = catalog.meta("asset:${operation.getString("path")}")
            if (!old.isNullOrEmpty() && old != operation.getString("revision")) operation.put("cleanup", old)
        }
        val token = UUID.randomUUID().toString()
        operation.put("token", token)
        catalog.enqueue(LibraryWrite(token = token, body = operation.toString()))
    }
    fun scheduleFlush() {
        val pending = catalog.pendingCount()
        if (!prepared || pending == 0 && !catalog.hasPendingCleanup()) return
        saveState.value = saveState.value.copy(saving = true, pending = pending)
        scope.launch { runCatching { flush() } }
    }
    fun flush() = synchronized(ioLock) {
        if (readOnly) throw IOException(saveState.value.error ?: "书库记录不可写；已停止覆盖")
        if (!prepared) return@synchronized
        try {
            validateManifest(readManifest())
            saveState.value = LibrarySaveState(saving = catalog.pending().isNotEmpty(), pending = catalog.pending().size)
            if (catalog.meta("legacyDirectoryCleanup") == "pending") migrateDirectoryRecords()
            catalog.pending().forEach { write ->
                val operation = JSONObject(write.body)
                val component = operation.optString("component")
                if (component == "book" && !operation.optBoolean("remove") && !operation.optBoolean("deleting")) {
                    val body = operation.getJSONObject("book")
                    if (operation.optBoolean("sourceRequired")) publishTree(body.getString("localRoot"))
                    val identity = JSONObject().put("format", 1).put("kind", operation.getString("kind"))
                        .put("uuid", body.getString("bookUuid")).put("source", body.getString("localRoot"))
                        .put("sourceName", body.optString("sourceName").ifBlank { body.optString("title") })
                    writeVerified(".starflow/books/${body.getString("bookUuid")}/source.json", identity.toString().toByteArray(Charsets.UTF_8))
                    val artifacts = operation.optJSONArray("artifacts") ?: JSONArray(listOf("coverPath", "sourceIndexPath")
                        .map(body::optString).filter { it.isNotBlank() && it != "null" })
                    val latest = catalog.byUuid(body.getString("bookUuid"))?.let { JSONObject(it.body) }
                    for (i in 0 until artifacts.length()) {
                        val path = artifacts.getString(i)
                        val stillReferenced = latest != null && listOf("coverPath", "sourceIndexPath").any { latest.optString(it) == path }
                        if (stillReferenced && (operation.has("artifacts") || local(path).exists() || storage.exists(path))) publishTree(path)
                    }
                }
                if (component == "layout") {
                    val path = operation.getJSONObject("book").optString("sourceIndexPath")
                    val body = operation.getJSONObject("book")
                    val latest = catalog.byUuid(body.getString("bookUuid"))
                    if (path.isNotBlank() && latest != null && !latest.deleting && JSONObject(latest.body).optString("sourceIndexPath") == path) publishTree(path)
                }
                if (component == "asset" && !operation.optBoolean("remove")) publishTree(operation.getString("revision"))
                // Existing outbox rows are already applied to the authoritative local records.
                // Only asset references and old deferred removals need their final local commit.
                database.runInTransaction {
                    if (component == "book" && operation.optBoolean("remove")) {
                        val kind = operation.getString("kind")
                        val id = operation.getLong("id")
                        catalog.book(kind, id)?.uuid?.let { catalog.meta(LibraryMeta("removed:$it", "1")) }
                        catalog.remove(kind, id)
                    }
                    if (component == "asset") catalog.meta(LibraryMeta("asset:${operation.getString("path")}",
                        if (operation.optBoolean("remove")) "" else operation.getString("revision")))
                    recordCleanup(operation)
                    catalog.acknowledge(write.sequence)
                }
                if (component == "book" && !operation.optBoolean("remove")) {
                    val cover = operation.getJSONObject("book").optString("coverPath")
                    if (cover.isNotEmpty() && cover != "null") runCatching { coverUris[cover] = storage.uri(cover).toString() }
                }
                val stagedCover = operation.optString("stagedCover")
                if (stagedCover.startsWith(".starflow/staging/covers/")) runCatching {
                    if (storage is FileLibraryStorage) storage.delete(stagedCover) else local(stagedCover).delete()
                }
            }
            catalog.allMeta().filter { it.name.startsWith("cleanup:") && it.value == "pending" }.forEach { item ->
                val path = safeLibraryPath(item.name.removePrefix("cleanup:"))
                val ownerUuid = path.removePrefix(".starflow/books/").substringBefore('/')
                val owner = catalog.byUuid(ownerUuid)
                if (owner != null && BookRecordLocks.exporting(BookRef(locationId, owner.kind, ownerUuid))) return@forEach
                if (!path.startsWith(".starflow/books/")) throw IOException("产物清理定位越界；已停止覆盖")
                storage.delete(path)
                local(path).delete()
                catalog.meta(LibraryMeta(item.name, "complete"))
            }
            saveState.value = LibrarySaveState(pending = catalog.pending().size)
        } catch (e: Exception) {
            readOnly = e is LibraryIdentityException || e is org.json.JSONException || e.message?.contains("停止覆盖") == true
            saveState.value = LibrarySaveState(pending = runCatching { catalog.pending().size }.getOrDefault(saveState.value.pending), error = e.message ?: e.javaClass.simpleName)
            throw e
        }
    }
    internal fun compact() = flush()
    private fun readSnapshot(commits: List<LibraryCommit>): JSONObject? {
        val tokens = commits.associateBy { it.token }
        return storage.list(".starflow/catalog").filter { it.path.endsWith(".snapshot.complete.json") }
            .sortedByDescending { it.path }.firstNotNullOfOrNull { node -> runCatching {
                val marker = JSONObject(storage.read(node.path).bufferedReader().use { it.readText() })
                val commit = tokens[marker.getString("head")] ?: return@runCatching null
                val bytes = storage.read(marker.getString("payload")).use { it.readBytes() }
                if (LibraryCodec.digest(bytes) != marker.getString("digest")) return@runCatching null
                JSONObject(String(bytes, Charsets.UTF_8)).takeIf { it.getLong("sequence") == commit.sequence }
            }.getOrNull() }
    }
    private suspend fun restoreSnapshot(snapshot: JSONObject) {
        catalog.clearBooks(); catalog.clearTranslations(); catalog.clearResolutions(); catalog.clearParagraphs(); catalog.clearAssets()
        val books = snapshot.getJSONArray("books")
        for (i in 0 until books.length()) {
            val item = books.getJSONObject(i)
            val body = item.getJSONObject("body")
            validateBook(item.getString("kind"), item.getLong("id"), body)
            catalog.put(LibraryBook(item.getString("kind"), item.getLong("id"), body.getString("bookUuid"),
                body.toString(), body.getString("localRoot"), item.optBoolean("deleting"),
                item.optString("error").takeIf { it.isNotEmpty() && it != "null" }))
        }
        val meta = snapshot.getJSONObject("meta")
        meta.keys().forEach { name -> catalog.meta(LibraryMeta(name, meta.getString(name))) }
        val rows = snapshot.getJSONArray("rows")
        for (i in 0 until rows.length()) applyOperation(rows.getJSONObject(i))
    }
    private fun writeVerified(path: String, bytes: ByteArray) {
        if (storage.exists(path)) {
            if (storage.read(path).use { it.readBytes() }.contentEquals(bytes)) return
            storage.delete(path) // This UUID belongs exclusively to this durable outbox entry.
        }
        storage.writeNew(path, "application/json").use { it.write(bytes) }
        if (!storage.read(path).use { it.readBytes() }.contentEquals(bytes)) throw IOException("书库记录写入不完整：$path")
    }
    fun publishTree(path: String) {
        if (storage is FileLibraryStorage) return
        val source = local(path)
        if (!source.exists() && path.startsWith(".starflow/books/") && "/source-index/" in path) {
            val retained = recordArchive(path)
            if (retained.isFile) { source.parentFile!!.mkdirs(); retained.copyTo(source, overwrite = false) }
        }
        if (!source.exists()) {
            if (!storage.exists(path)) throw IOException("书库来源或产物缺失：$path")
            return
        }
        if (source.isDirectory) {
            storage.mkdir(path)
            (source.listFiles() ?: throw IOException("内部临时目录无法读取：$source"))
                .forEach { publishTree(relative(it)) }
        } else {
            val ownership = "upload:$path"
            val uploading = catalog.meta(ownership) == "pending"
            if (storage.exists(path) && !uploading) return // Existing scan sources are not rewritten.
            catalog.meta(LibraryMeta(ownership, "pending"))
            if (storage.exists(path)) storage.delete(path) // Only our interrupted staged upload.
            try {
                val copied = source.inputStream().use { input -> storage.writeNew(path).use { output -> input.copyTo(output) } }
                var verified = 0L
                storage.read(path).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val count = input.read(buffer); if (count < 0) break; verified += count }
                }
                if (copied != source.length() || verified != copied) throw IOException("书库文件写入不完整：$path")
                if (path.startsWith(".starflow/books/") &&
                    storage.read(path).use(LibraryCodec::digest) != source.inputStream().use(LibraryCodec::digest))
                    throw IOException("书库产物验证失败：$path")
                catalog.meta(LibraryMeta(ownership, "complete"))
            } catch (e: Exception) {
                throw IOException("书库目录写入失败：$path：${e.message}", e)
            }
        }
    }

    private fun rawDao(component: String): Any = when (component) {
        "manga" -> database.translations()
        "sr" -> database.resolutions()
        "novel" -> database.paragraphs()
        else -> error("Unknown library record: $component")
    }
    private fun daoInterface(component: String): Class<*> = when (component) {
        "manga" -> ImportedPageTranslationDao::class.java
        "sr" -> ImportedPageSrDao::class.java
        else -> NovelParagraphTranslationDao::class.java
    }
    private val proxies = mutableMapOf<String, Any>()
    @Suppress("UNCHECKED_CAST")
    private fun <T> dao(component: String): T = synchronized(proxies) {
        proxies.getOrPut(component) {
            val type = daoInterface(component)
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, supplied ->
                val args = supplied ?: emptyArray()
                val mutation = listOf("upsert", "insert", "delete", "mark", "reset", "rekey", "rewrite").any { method.name.startsWith(it) }
                if (!mutation) method.invoke(rawDao(component), *args)
                else {
                    @Suppress("UNCHECKED_CAST") val continuation = args.last() as Continuation<Any?>
                    val values = args.dropLast(1).toTypedArray()
                    val operation: suspend () -> Any? = {
                        check(!readOnly) { "书库记录不可写" }
                        if (!method.name.startsWith("delete")) {
                            values.forEach { value ->
                                val rows = if (value is Iterable<*>) value.toList() else listOf(value)
                                rows.forEach { row -> when (row) {
                                    is ImportedPageTranslation -> assertWritable(row.mangaKey ?: throw IllegalStateException("作品引用缺失"))
                                    is ImportedPageSr -> assertWritable(row.mangaKey ?: throw IllegalStateException("作品引用缺失"))
                                    is NovelParagraphTranslation -> assertWritable(row.novelKey)
                                    is String -> if (row.startsWith("$locationId:") && LibraryCodec.UUID_PATTERN.matches(row.substringAfterLast(':'))) assertWritable(row)
                                } }
                            }
                        }
                        val result = database.withTransaction {
                            // A clear must remain authoritative even while missing legacy sources defer activation.
                            if (method.name == "deletePage" || method.name == "deletePageRange") {
                                val key = values.getOrNull(1) as? String
                                val owner = key?.substringAfterLast(':')?.let(catalog::byUuid)
                                if (owner != null && key == "$locationId:${owner.kind}:${owner.uuid}" &&
                                    owner.id == values.firstOrNull()) LegacyRecordActivation.cancelPending(this@LibrarySession, owner.uuid)
                            }
                            values.forEach { value ->
                                val rows = if (value is Iterable<*>) value.toList() else listOf(value)
                                if (!method.name.startsWith("delete")) rows.forEach { row -> when (row) {
                                    is ImportedPageTranslation -> assertWritable(row.mangaKey ?: throw IllegalStateException("作品引用缺失"))
                                    is ImportedPageSr -> assertWritable(row.mangaKey ?: throw IllegalStateException("作品引用缺失"))
                                    is NovelParagraphTranslation -> assertWritable(row.novelKey)
                                    is String -> if (row.startsWith("$locationId:") && LibraryCodec.UUID_PATTERN.matches(row.substringAfterLast(':'))) assertWritable(row)
                                } }
                            }
                            val returned = invokeSuspend(rawDao(component), method, values)
                            returned
                        }
                        result
                    }
                    operation.startCoroutineUninterceptedOrReturn(continuation)
                }
            }
        } as T
    }
    fun importedPageTranslationDao(): ImportedPageTranslationDao = dao("manga")
    fun importedPageSrDao(): ImportedPageSrDao = dao("sr")
    fun novelParagraphTranslationDao(): NovelParagraphTranslationDao = dao("novel")
}

internal class LibraryContext(base: Context, val session: LibrarySession) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this
}

private suspend fun invokeSuspend(target: Any, method: Method, values: Array<Any?>): Any? =
    suspendCoroutineUninterceptedOrReturn { continuation ->
        try { method.invoke(target, *(values + continuation)) }
        catch (e: InvocationTargetException) { throw e.targetException }
    }
