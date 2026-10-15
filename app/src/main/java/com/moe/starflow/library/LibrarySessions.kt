package com.moe.starflow.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object LibrarySessions {
    private const val PREFS = "library_locations"
    private val sessions = ConcurrentHashMap<String, LibrarySession>()
    @Volatile private var active: LibrarySession? = null
    private val uses = AtomicInteger()
    @Volatile private var switching = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val changes = MutableStateFlow(0L)

    fun current(context: Context): LibrarySession {
        if (context is LibraryContext) return context.session
        if (context.applicationContext is LibraryContext) return (context.applicationContext as LibraryContext).session
        val callerApp = context.applicationContext
        active?.takeIf { it.app === callerApp }?.let { return it }
        return synchronized(this) {
            if (active?.app !== callerApp) {
                sessions.values.forEach { it.close() }; sessions.clear(); active = null
            }
            active ?: run {
                val app = context.applicationContext
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val selected = prefs.getString("tree", null)
                val backend = if (selected != null) SafLibraryStorage(app, Uri.parse(selected)) else defaultStorage(app)
                create(app, backend).also { active = it }
            }
        }
    }
    private fun defaultStorage(context: Context): FileLibraryStorage {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = prefs.getString("defaultPath", null) ?: (context.getExternalFilesDir(null) ?: context.filesDir).path.also {
            check(prefs.edit().putString("defaultPath", it).commit()) { "无法保存默认书库位置" }
        }
        return FileLibraryStorage(File(path))
    }
    fun defaultDisplayPath(context: Context): String = defaultStorage(context.applicationContext).displayPath
    private fun databaseName(location: String, generation: String?) = "library_$location${generation?.let { "_$it" }.orEmpty()}.db"
    internal fun openDatabase(context: Context, location: String, generation: String?): LibraryDatabase =
        Room.databaseBuilder(context, LibraryDatabase::class.java, databaseName(location, generation))
            .openHelperFactory(LibraryOpenHelperFactory()).allowMainThreadQueries().build()
    private fun create(context: Context, storage: LibraryStorage): LibrarySession = sessions.getOrPut(storage.locationId) {
        val generation = LibraryReinitialization.generation(context, storage.locationId)
        LibrarySession(context, storage, openDatabase(context, storage.locationId, generation), changes.value + 1, generation)
    }
    private fun sourceIdentities(session: LibrarySession) = org.json.JSONArray(
        listOf("manga", "novel").flatMap(session::books).mapNotNull { book ->
            runCatching {
                require(LibraryCodec.UUID_PATTERN.matches(book.uuid) && book.kind in setOf("manga", "novel"))
                require(safeLibraryPath(book.source).startsWith("${book.kind}_import/"))
                val body = org.json.JSONObject(book.body)
                org.json.JSONObject().put("format", 1).put("kind", book.kind).put("uuid", book.uuid)
                    .put("source", book.source).put("sourceName", body.optString("sourceName").takeUnless { it.isBlank() || it == "null" }
                        ?: body.optString("title"))
            }.getOrNull()
        })
    private fun knownSafRoots(context: Context): List<SafLibraryStorage> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("knownTrees", "[]")!!
        val values = org.json.JSONArray(raw)
        return (0 until values.length()).map { SafLibraryStorage(context, Uri.parse(values.getString(it))) }
    }
    private fun rememberTree(context: Context, tree: Uri?) {
        if (tree == null) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val values = org.json.JSONArray(prefs.getString("knownTrees", "[]"))
        val roots = (0 until values.length()).mapTo(linkedSetOf()) { values.getString(it) }
        roots += tree.toString()
        check(prefs.edit().putString("knownTrees", org.json.JSONArray(roots).toString()).commit()) { "无法保存书库目录身份" }
    }
    private fun rejectNestedLibraries(storage: LibraryStorage) {
        val seen = hashSetOf<String>()
        fun walk(path: String) {
            if (!seen.add(storage.uri(path).toString())) throw IOException("提供器目录出现循环，已停止选择")
            storage.list(path).filter { it.directory && it.path.substringAfterLast('/') != ".starflow" }.forEach { child ->
                if (storage.exists("${child.path}/.starflow/library.json")) throw IOException("选择的目录包含另一个书库，不能嵌套")
                walk(child.path)
            }
        }
        walk("")
    }

    fun pin(context: Context) = current(context).context(context.applicationContext)
    fun forLocation(context: Context, location: String): LibrarySession {
        val captured = (context as? LibraryContext)?.session ?: (context.applicationContext as? LibraryContext)?.session
        if (captured != null && (location.isEmpty() || captured.locationId == location)) {
            if (captured.isClosed) throw IOException("书库会话已切换，请刷新后重试")
            return captured
        }
        return if (location.isEmpty()) current(context) else sessions[location]
            ?: throw IOException("此作品属于另一个书库，请先选择对应存储目录")
    }
    suspend fun prepare(context: Context) {
        val session = current(context)
        withContext(Dispatchers.IO) {
            session.prepare()
            LibraryMigration.migrate(context, session)
            session.flush()
            changes.value += 1
        }
    }
    suspend fun prepareImport(context: Context, kind: String) {
        prepare(context)
        withContext(Dispatchers.IO) { LibraryImportAccess.check(current(context), kind) }
    }
    /** Checks only indexed sources; it never discovers or recognizes new works. */
    suspend fun refreshSources(context: Context) {
        val session = current(context)
        withContext(Dispatchers.IO) {
            try {
                session.prepare()
                session.flush()
                (session.books("manga") + session.books("novel")).forEach { book ->
                    session.sourcePresence[book.source] = session.storage.exists(book.source)
                }
            } catch (e: Exception) {
                session.saveState.value = session.saveState.value.copy(error = e.message ?: e.javaClass.simpleName)
            }
            changes.value += 1
        }
    }

    fun launch(context: Context, caller: CoroutineScope = scope, block: suspend CoroutineScope.() -> Unit): Job {
        val lease = synchronized(this) {
            val captured = current(context)
            if (switching || current(captured.app) !== captured) {
                return Job().apply { cancel(CancellationException("书库正在切换或捕获的会话已停止接受新操作")) }
            }
            use()
        }
        return caller.launch {
            try { block() } finally { lease.close() }
        }.also { job -> job.invokeOnCompletion { lease.close() } }
    }
    fun release(block: suspend () -> Unit) { scope.launch { runCatching { block() } } }
    fun use(): Closeable {
        uses.incrementAndGet()
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        return Closeable { if (closed.compareAndSet(false, true)) uses.decrementAndGet() }
    }
    internal fun inspectionLease(): Closeable {
        check(!switching) { "正在切换书库，请等待切换完成" }
        val lease = use()
        if (switching) {
            lease.close()
            throw IOException("正在切换书库，请等待切换完成")
        }
        return lease
    }
    suspend fun select(context: Context, tree: Uri?, reinitialize: Boolean = false) = withContext(Dispatchers.IO) {
        var resetLocation: String? = null
        synchronized(this@LibrarySessions) {
            check(!switching) { "正在切换书库，请等待切换完成" }
            switching = true
        }
        try {
            while (uses.get() != 0 || com.moe.starflow.translate.batch.TranslationJobRegistry.hasActiveJobs.value ||
                com.moe.starflow.sr.SrProcessor.isBusy() || com.moe.starflow.manga.OcrLock.isRunning) delay(100)
            synchronized(this@LibrarySessions) {
            val app = context.applicationContext
            val storage = if (tree == null) defaultStorage(app) else SafLibraryStorage(app, tree)
            val old = current(app)
            if (tree != null) app.contentResolver.takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (old.locationId == storage.locationId && !reinitialize) {
                rememberTree(app, tree)
                old.prepare(); LibraryMigration.migrate(app, old); old.flush()
                return@synchronized
            }
            if (storage is SafLibraryStorage) {
                val known = sessions.values.mapNotNull { it.storage as? SafLibraryStorage } + knownSafRoots(app)
                known.filter { it.locationId != storage.locationId }.forEach {
                    if (storage.overlaps(it)) throw IOException("书库目录不能相互嵌套")
                }
            }
            if (storage is FileLibraryStorage && old.storage is FileLibraryStorage) {
                val a = storage.root.canonicalPath
                val b = old.storage.root.canonicalPath
                check(!a.startsWith(b + File.separator) && !b.startsWith(a + File.separator)) { "书库目录不能相互嵌套" }
            }
            // The app's fixed default root also contains models/logs created by other tools.
            // Those unrelated subdirectories must not prevent returning to this library.
            if (storage is SafLibraryStorage) rejectNestedLibraries(storage)
            var target = create(app, storage)
            if (reinitialize) {
                val previous = target
                previous.withStorageLock {
                    if (LibraryReinitialization.generation(app, storage.locationId) == previous.storageGeneration)
                        LibraryReinitialization.start(app, storage, databaseName(storage.locationId, previous.storageGeneration),
                            runCatching { previous.database.catalog().meta("manifestId") }.getOrNull(), previous.storageGeneration, sourceIdentities(previous))
                    sessions.remove(storage.locationId, previous)
                    target = create(app, storage)
                    // Switch the same root's in-memory handle before closing its previous database.
                    if (active === previous) active = target
                    previous.close()
                    resetLocation = storage.locationId
                }
            }
            target.prepare()
            LibraryMigration.migrate(app, target)
            runCatching { target.flush() }
            if (target.saveState.value.error == null) (target.books("manga") + target.books("novel")).forEach {
                target.sourcePresence[it.source] = target.storage.exists(it.source)
            }
            val probe = ".starflow/probe-${java.util.UUID.randomUUID()}"
            try {
                storage.writeNew(probe).use { it.write(byteArrayOf(83, 70)) }
                check(storage.read(probe).use { it.readBytes() }.contentEquals(byteArrayOf(83, 70))) { "目录写入验证失败" }
            } finally { runCatching { storage.delete(probe) } }
            // An unsuccessful old outbox remains attached to its physical location.
            runCatching { old.flush() }
            rememberTree(app, tree)
            check(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                if (tree == null) remove("tree") else putString("tree", tree.toString())
            }.commit()) { "无法保存存储目录选择" }
            active = target
        }
        } finally {
            synchronized(this@LibrarySessions) { switching = false }
            resetLocation?.let(LibraryScanner::discardReports)
            // A reset replaces the session even at the same location, including a retryable cleanup failure.
            changes.value += 1
            active?.takeIf { it.locationId == resetLocation }?.let {
                LibraryScanner.start(it.context(), "manga")
                LibraryScanner.start(it.context(), "novel")
            }
        }
    }
    fun findFile(file: File): Pair<LibrarySession, String>? {
        val path = file.canonicalPath
        return sessions.values.map { it to it.workingRoot.canonicalPath }
            .sortedByDescending { it.second.length }.firstNotNullOfOrNull { (session, base) ->
            libraryRelativePath(base, path)?.let { session to it }
        }
    }
    fun sourceExists(file: File): Boolean = try {
        val located = findFile(file)
        if (located == null) file.exists() else {
            val (session, path) = located
            // Root errors must not turn every entry into a missing-file placeholder.
            if (session.saveState.value.error != null) true
            else if (session.storage is FileLibraryStorage) file.exists()
            else session.sourcePresence[path] ?: true
        }
    } catch (e: Exception) {
        // A shelf probe must not crash the main thread or label an inaccessible library missing.
        sessions.values.filter { runCatching {
            libraryRelativePath(it.workingRoot.absolutePath, file.absolutePath) != null
        }.getOrDefault(false) }
            .forEach { it.saveState.value = it.saveState.value.copy(error = e.message ?: e.javaClass.simpleName) }
        true
    }
    fun checkReaderIntent(context: Context, intent: Intent, kind: String, id: Long) {
        if (switching) throw IOException("正在切换书库，请等待切换完成")
        val session = current(context)
        val requested = intent.getStringExtra("library_location").orEmpty()
        if (requested != session.locationId)
            throw IOException("此入口属于另一个书库，请先选择对应存储目录")
        val uuid = intent.getStringExtra("library_book_uuid").orEmpty()
        val book = session.database.catalog().book(kind, id)
        if (uuid.isEmpty() || book?.uuid != uuid || book.deleting)
            throw IOException("作品已删除、正在删除或此入口已失效")
    }
}

/** Only requested sources are materialized; image-folder pages are streamed individually. */
object LibrarySources {
    private val materialized = ConcurrentHashMap<String, String>()
    private val references = ConcurrentHashMap<String, AtomicInteger>()
    internal fun hasReaders(file: File): Boolean {
        val path = file.canonicalPath
        return references.any { (key, count) -> count.get() > 0 && (key == path || key.startsWith(path + File.separator)) }
    }
    fun acquire(file: File): Closeable {
        val lease = LibrarySessions.use()
        val located = LibrarySessions.findFile(file)
        if (located == null) return lease
        val owner = listOf("manga", "novel").flatMap(located.first::books).firstOrNull { it.source == located.second }
        val recordLease = try { owner?.let { BookRecordLocks.acquire(BookRef(located.first.locationId, it.kind, it.uuid), source = true) } }
            catch (e: Exception) { lease.close(); throw e }
        val key = file.canonicalPath
        synchronized(materialized) { references.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet() }
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        return Closeable {
            if (closed.compareAndSet(false, true)) {
                try { synchronized(materialized) {
                    if (references[key]?.decrementAndGet() == 0) {
                        val session = located.first
                        if (session.storage !is FileLibraryStorage) {
                            val book = listOf("manga", "novel").flatMap(session::books).firstOrNull { it.source == located.second }
                            if (book != null) session.releaseBookCopies(book.uuid, book.source)
                            else if (session.storage.exists(located.second)) file.deleteRecursively()
                        }
                        materialized.remove(file.path)
                    }
                } } finally { recordLease?.close(); lease.close() }
            }
        }
    }

    fun file(file: File, folderTexts: Boolean = false, forceReload: Boolean = false): File {
        val located = LibrarySessions.findFile(file) ?: return file
        val (session, path) = located
        if (session.storage is FileLibraryStorage) return session.local(session.assetPath(path))
        synchronized(materialized) {
            val actual = session.assetPath(path)
            if (forceReload) {
                check(!hasReaders(file)) { "Source is still in use" }
                materialized.remove(file.path)
            }
            if (file.exists() && materialized[file.path] == actual) return file
            if (!forceReload && file.exists() && actual == path && session.database.catalog().pending().any { it.body.contains(path) }) return file // Durable staged source awaits publication.
            if (file.isFile && actual == path && !session.storage.exists(path)) return file
            if (folderTexts) {
                file.mkdirs()
                fun copyTexts(at: String) {
                    session.storage.list(at).forEach { node ->
                        if (node.directory) { session.local(node.path).mkdirs(); copyTexts(node.path) }
                        else if (node.path.substringAfterLast('.').lowercase() in setOf("txt", "html", "htm", "xhtml", "epub", "zip"))
                            materialize(session, node.path, session.local(node.path))
                    }
                }
                copyTexts(path)
            } else materialize(session, actual, file)
            materialized[file.path] = actual
            return file
        }
    }
    private fun materialize(session: LibrarySession, path: String, target: File) {
        target.parentFile?.mkdirs()
        val pending = File(target.parentFile, target.name + ".reading")
        try {
            session.storage.read(path).use { input -> pending.outputStream().use { input.copyTo(it) } }
            if (!pending.renameTo(target)) throw IOException("内部临时读取文件无法提交：${target.path}")
        } catch (e: Exception) {
            pending.delete()
            throw IOException("读取或内部临时空间不足：${target.path}；${e.message}", e)
        }
    }
    fun listFiles(file: File): List<LibraryNode>? {
        val (session, root) = LibrarySessions.findFile(file) ?: return null
        if (session.storage is FileLibraryStorage) return null
        val out = mutableListOf<LibraryNode>()
        val seen = hashSetOf<String>()
        fun walk(path: String) {
            if (!seen.add(session.storage.uri(path).toString())) throw IOException("提供器目录出现循环：$path")
            session.storage.list(path).forEach { node ->
                if (node.directory) walk(node.path)
                else out += node
            }
        }
        walk(root)
        return out
    }
    fun listImages(file: File): List<String>? {
        val root = LibrarySessions.findFile(file)?.second ?: return null
        return listFiles(file)?.filter { com.moe.starflow.mangaimport.data.ArchivedMangaReader.isImageFile(it.path) }
            ?.map { it.path.removePrefix("$root/") }
    }
    fun open(file: File): java.io.InputStream {
        val (session, path) = LibrarySessions.findFile(file) ?: return file.inputStream()
        return if (session.storage is FileLibraryStorage) file.inputStream() else session.storage.read(session.assetPath(path))
    }
}
