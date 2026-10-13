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
data class BookRef(val locationId: String, val kind: String, val uuid: String) {
    val key get() = "$locationId:$kind:$uuid"
}

class LibrarySession internal constructor(val app: Context, val storage: LibraryStorage,
    val database: LibraryDatabase, val generation: Long) {
    val locationId get() = storage.locationId
    val workingRoot: File = (storage as? FileLibraryStorage)?.root
        ?: File(app.filesDir, "library_sources/$locationId").apply { mkdirs() }
    val saveState = MutableStateFlow(LibrarySaveState())
    private val catalog get() = database.catalog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ioLock = Any()
    private val journal = LibraryJournal(storage)
    private var prepared = false
    private var readOnly = false
    private val coverUris = java.util.concurrent.ConcurrentHashMap<String, String>()
    val sourcePresence = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun context(base: Context = app): Context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        val librarySession = this@LibrarySession
    }.let { LibraryContext(it, this) }

    fun local(path: String) = File(workingRoot, safeLibraryPath(path))
    fun relative(file: File): String {
        val base = workingRoot.canonicalFile
        val at = file.canonicalFile
        require(at.path.startsWith(base.path + File.separator)) { "文件不属于当前书库：$file" }
        return safeLibraryPath(at.relativeTo(base).invariantSeparatorsPath)
    }
    fun cover(path: String): String = if (storage is FileLibraryStorage || local(path).isFile) local(path).path
        else coverUris[path] ?: local(path).path

    /** Runs on IO before a newly selected root is published. Failure never creates an empty catalog. */
    fun prepare() = synchronized(ioLock) {
        if (prepared) return@synchronized
        try {
            storage.list("") // Distinguish inaccessible from an empty root before creating anything.
            if (storage.exists(".starflow") && !storage.exists(".starflow/library.json"))
                throw IOException("书库管理目录存在，但身份记录缺失；已停止覆盖")
            if (!storage.exists(".starflow/library.json")) {
                if (catalog.meta("manifestId") != null || !catalog.pending().isEmpty())
                    throw IOException("原书库身份记录缺失；已停止覆盖，本地资料保留")
                listOf("manga_import", "novel_import", ".starflow/catalog", ".starflow/books", ".starflow/legacy")
                    .forEach(storage::mkdir)
                val manifest = JSONObject().put("format", 1).put("libraryId", UUID.randomUUID().toString())
                storage.writeNew(".starflow/library.json", "application/json").use { it.write(manifest.toString().toByteArray()) }
            }
            val manifest = JSONObject(storage.read(".starflow/library.json").bufferedReader().use { it.readText() })
            if (manifest.getInt("format") != 1) throw IOException("不支持此书库记录版本；已停止覆盖")
            validateManifest(manifest)
            restore()
            recoverInterruptedImports()
            prepared = true
            // Cover handles are cheap; source archives and original image trees remain lazy.
            listOf("manga", "novel").flatMap { catalog.books(it) }.forEach { book ->
                val p = JSONObject(book.body).optString("coverPath")
                if (p.isNotEmpty() && p != "null") runCatching { coverUris[p] = storage.uri(p).toString() }
            }
            flush()
        } catch (e: Exception) {
            readOnly = e is org.json.JSONException || e.message?.contains("停止覆盖") == true
            saveState.value = LibrarySaveState(pending = runCatching { catalog.pending().size }.getOrDefault(saveState.value.pending), error = e.message ?: e.javaClass.simpleName)
            if (!prepared || readOnly) throw e
        }
    }

    private fun validateManifest(manifest: JSONObject) {
        if (manifest.getInt("format") != 1) throw IOException("不支持此书库记录版本；已停止覆盖")
        val identity = manifest.getString("libraryId")
        if (!LibraryCodec.UUID_PATTERN.matches(identity)) throw IOException("书库身份记录损坏；已停止覆盖")
        val previous = catalog.meta("manifestId")
        if (previous != null && previous != identity) throw IOException("目录书库身份已更换；已停止覆盖，本地数据保留")
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
        catalog.byUuid(uuid)?.let { if (it.kind != kind || it.id != id) throw IOException("书库作品 UUID 重复；已停止覆盖") }
    }

    private suspend fun applyOperation(operation: JSONObject) {
        when (operation.getString("component")) {
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
        database.runInTransaction {
            catalog.meta(LibraryMeta(name, value))
            enqueue(JSONObject().put("component", "meta").put("name", name).put("value", value))
        }
        flush()
    }
    internal fun releaseBookCopies(uuid: String, source: String) = synchronized(ioLock) {
        if (storage is FileLibraryStorage || catalog.pending().any { it.body.contains(uuid) }) return@synchronized
        if (LibrarySources.hasReaders(local(source))) return@synchronized
        local(source).deleteRecursively()
        local(".starflow/books/$uuid").deleteRecursively()
    }
    internal fun close() = synchronized(ioLock) {
        scope.cancel()
        database.close()
    }
    fun books(kind: String): List<LibraryBook> = catalog.books(kind)
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
            validateBook(kind, id, body)
            catalog.put(LibraryBook(kind, id, uuid, body.toString(), body.getString("localRoot"), deleting, error))
            catalog.meta(LibraryMeta("importing:$kind:$id", ""))
            enqueue(operation)
        }
        scheduleFlush()
    }
    fun removeBook(kind: String, id: Long) {
        database.runInTransaction {
            val uuid = catalog.book(kind, id)?.uuid
            uuid?.let { catalog.meta(LibraryMeta("removed:$it", "1")) }
            catalog.remove(kind, id)
            enqueue(JSONObject().put("component", "book").put("kind", kind).put("id", id).put("remove", true).put("uuid", uuid))
        }
        scheduleFlush()
    }
    fun finishRemoval(kind: String, id: Long) {
        database.runInTransaction {
            enqueue(JSONObject().put("component", "book").put("kind", kind).put("id", id).put("remove", true))
        }
        flush()
    }
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
        saveState.value = saveState.value.copy(saving = true, pending = catalog.pending().size)
        scope.launch { runCatching { flush() } }
    }
    fun flush() = synchronized(ioLock) {
        if (readOnly) throw IOException(saveState.value.error ?: "书库记录不可写；已停止覆盖")
        if (!prepared) return@synchronized
        try {
            validateManifest(JSONObject(storage.read(".starflow/library.json").bufferedReader().use { it.readText() }))
            val pending = catalog.pending()
            saveState.value = LibrarySaveState(saving = pending.isNotEmpty(), pending = pending.size)
            // A complete marker may precede the local ACK when the process was interrupted.
            restore()
            catalog.pending().forEach { write ->
                val operation = JSONObject(write.body)
                if (operation.optString("component") == "book" && !operation.optBoolean("remove") && !operation.optBoolean("deleting")) {
                    val body = operation.getJSONObject("book")
                    val source = body.getString("localRoot")
                    if (operation.optBoolean("sourceRequired") || local(source).exists() || storage.exists(source)) publishTree(source)
                    val cover = body.optString("coverPath")
                    if (cover.isNotEmpty() && cover != "null") publishTree(cover)
                }
                if (operation.optString("component") == "asset" && !operation.optBoolean("remove"))
                    publishTree(operation.getString("revision"))
                val sequence = (catalog.meta("commitSequence")?.toLongOrNull() ?: 0) + 1
                val payload = ".starflow/catalog/${write.token}.json"
                val bytes = write.body.toByteArray(Charsets.UTF_8)
                if (operation.optString("component") == "book" && !operation.optBoolean("remove") && !operation.optBoolean("deleting")) {
                    val uuid = operation.getJSONObject("book").getString("bookUuid")
                    writeVerified(".starflow/books/$uuid/records/${write.token}.json", bytes)
                }
                if (operation.optString("component") in setOf("manga", "sr", "novel")) {
                    val owners = linkedSetOf<String>()
                    fun collect(value: Any?) {
                        when (value) {
                            is JSONObject -> {
                                if (value.has("bookKey")) owners += value.getString("bookKey")
                                value.keys().forEach { collect(value.opt(it)) }
                            }
                            is JSONArray -> for (i in 0 until value.length()) collect(value.opt(i))
                        }
                    }
                    collect(operation.opt("args"))
                    owners.filter { catalog.byUuid(it) != null }.forEach { uuid ->
                        if (!LibraryCodec.UUID_PATTERN.matches(uuid)) throw IOException("作品记录 UUID 损坏；已停止覆盖")
                        writeVerified(".starflow/books/$uuid/records/${write.token}.json", bytes)
                    }
                }
                writeVerified(payload, bytes)
                val marker = JSONObject().put("sequence", sequence).put("token", write.token)
                    .put("parent", catalog.meta("head") ?: "").put("payload", payload).put("digest", LibraryCodec.digest(bytes))
                writeVerified(".starflow/catalog/${sequence.toString().padStart(20, '0')}-${write.token}.commit.json", marker.toString().toByteArray())
                database.runInTransaction {
                    if (operation.optString("component") == "book" && operation.optBoolean("remove")) {
                        val kind = operation.getString("kind")
                        val id = operation.getLong("id")
                        (catalog.book(kind, id)?.uuid ?: operation.optString("uuid").takeIf { LibraryCodec.UUID_PATTERN.matches(it) })
                        ?.let { catalog.meta(LibraryMeta("removed:$it", "1")) }
                        catalog.remove(kind, id)
                    }
                    if (operation.optString("component") == "asset") {
                        catalog.meta(LibraryMeta("asset:${operation.getString("path")}",
                            if (operation.optBoolean("remove")) "" else operation.getString("revision")))
                    }
                    recordCleanup(operation)
                    catalog.meta(LibraryMeta("head", write.token))
                    catalog.meta(LibraryMeta("commitSequence", sequence.toString()))
                    catalog.acknowledge(write.sequence)
                }
                if (operation.optString("component") == "book" && !operation.optBoolean("remove")) {
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
                if (!path.startsWith(".starflow/books/")) throw IOException("产物清理定位越界；已停止覆盖")
                storage.delete(path)
                local(path).delete()
                catalog.meta(LibraryMeta(item.name, "complete"))
            }
            saveState.value = LibrarySaveState(pending = catalog.pending().size)
            if ((catalog.meta("commitSequence")?.toLongOrNull() ?: 0) % 100L == 0L) checkpoint()
        } catch (e: Exception) {
            readOnly = e is org.json.JSONException || e.message?.contains("停止覆盖") == true
            saveState.value = LibrarySaveState(pending = runCatching { catalog.pending().size }.getOrDefault(saveState.value.pending), error = e.message ?: e.javaClass.simpleName)
            throw e
        }
    }
    internal fun compact() = synchronized(ioLock) { checkpoint(compact = true) }
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
    private fun checkpoint(compact: Boolean = false) {
        val head = catalog.meta("head") ?: return
        if (head.isEmpty() || !compact && catalog.meta("snapshotHead") == head) return
        val oldChain = if (compact) journal.chain() else emptyList()
        val history = oldChain.firstOrNull()?.operation?.optJSONArray("history")?.let { values ->
            (0 until values.length()).mapTo(linkedSetOf()) { values.getString(it) }
        } ?: linkedSetOf()
        oldChain.forEach { history += it.token }
        var snapshot: JSONObject? = null
        database.runInTransaction {
            if (catalog.pending().isNotEmpty()) return@runInTransaction
            val books = JSONArray()
            listOf("manga", "novel").flatMap(catalog::books).forEach { book ->
                books.put(JSONObject().put("kind", book.kind).put("id", book.id).put("body", JSONObject(book.body))
                    .put("deleting", book.deleting).put("error", book.error ?: JSONObject.NULL))
            }
            val rows = JSONArray()
            fun row(component: String, value: Any) {
                rows.put(JSONObject().put("component", component).put("method", "upsert")
                    .put("args", JSONArray().put(LibraryCodec.encode(value, locationId))))
            }
            catalog.allTranslations().forEach { row("manga", it) }
            catalog.allResolutions().forEach { row("sr", it) }
            catalog.allParagraphs().forEach { row("novel", it) }
            val meta = JSONObject()
            val activeUuids = listOf("manga", "novel").flatMap(catalog::books).mapTo(hashSetOf()) { it.uuid }
            catalog.allMeta().filter { it.name.startsWith("next:") || it.name.startsWith("removed:") || it.name.startsWith("legacySource:") || it.name == "legacyMigrated" || it.name.startsWith("cleanup:") && it.value == "pending" ||
                it.name.startsWith("asset:") && it.name.removePrefix("asset:.starflow/books/").substringBefore('/') in activeUuids }
                .forEach { meta.put(it.name, it.value) }
            snapshot = JSONObject().put("sequence", catalog.meta("commitSequence")!!.toLong())
                .put("books", books).put("rows", rows).put("meta", meta)
            if (compact) enqueue(snapshot!!.put("component", "checkpoint").put("history", JSONArray(history)))
        }
        if (compact) {
            if (snapshot == null) throw IOException("尚有待保存修改，不能完成记录清理")
            flush()
            val token = snapshot!!.getString("token")
            if (catalog.pending().any { it.token == token }) throw IOException("清理提交尚未保存到书库")
            storage.list(".starflow/catalog").filter { node -> history.any { node.path.contains(it) } }
                .forEach { storage.delete(it.path) }
            return
        }
        snapshot?.let { body ->
            val prefix = ".starflow/catalog/${body.getLong("sequence").toString().padStart(20, '0')}-$head.snapshot"
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            writeVerified("$prefix.json", bytes)
            writeVerified("$prefix.complete.json", JSONObject().put("head", head).put("payload", "$prefix.json")
                .put("digest", LibraryCodec.digest(bytes)).toString().toByteArray(Charsets.UTF_8))
            catalog.meta(LibraryMeta("snapshotHead", head))
        }
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
                        val encoded = JSONArray().also { array -> values.forEach { array.put(LibraryCodec.encode(it, locationId)) } }
                        if (!method.name.startsWith("delete")) {
                            encoded.toString().let { json -> catalog.books(if (component == "novel") "novel" else "manga")
                                .filter { it.deleting && json.contains(it.uuid) }.forEach { throw IllegalStateException("作品正在删除，不能写入旧任务结果") } }
                            values.forEach { value ->
                                val rows = if (value is Iterable<*>) value.toList() else listOf(value)
                                rows.forEach { row -> when (row) {
                                    is ImportedPageTranslation -> assertWritable(row.mangaKey ?: throw IllegalStateException("作品引用缺失"))
                                    is ImportedPageSr -> assertWritable(row.mangaKey ?: throw IllegalStateException("作品引用缺失"))
                                    is NovelParagraphTranslation -> assertWritable(row.novelKey)
                                } }
                            }
                        }
                        val result = database.withTransaction {
                            values.forEach { value ->
                                val rows = if (value is Iterable<*>) value.toList() else listOf(value)
                                if (!method.name.startsWith("delete")) rows.forEach { row -> when (row) {
                                    is ImportedPageTranslation -> assertWritable(row.mangaKey ?: throw IllegalStateException("作品引用缺失"))
                                    is ImportedPageSr -> assertWritable(row.mangaKey ?: throw IllegalStateException("作品引用缺失"))
                                    is NovelParagraphTranslation -> assertWritable(row.novelKey)
                                } }
                            }
                            val returned = invokeSuspend(rawDao(component), method, values)
                            enqueue(JSONObject().put("component", component).put("method", method.name).put("args", encoded))
                            returned
                        }
                        scheduleFlush()
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
