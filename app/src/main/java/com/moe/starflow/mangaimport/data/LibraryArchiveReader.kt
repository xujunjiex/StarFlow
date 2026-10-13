package com.moe.starflow.mangaimport.data

import com.moe.starflow.library.LibrarySessions
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.LinkedHashMap

/** A serial native cursor with a small disposable cache of requested entries, never a whole-book extraction. */
class LibraryArchiveReader(private val file: File) : Closeable {
    private val cacheRoot = File(LibrarySessions.findFile(file)?.first?.app?.cacheDir
        ?: File(System.getProperty("java.io.tmpdir") ?: file.parent), "archive_pages/${hash(file.path)}/${java.util.UUID.randomUUID()}")
    private var handle = 0L
    private var input: InputStream? = null
    private var position = -1
    private var closed = false
    private val cached = LinkedHashMap<String, File>(4, 0.75f, true)
    val entries: List<String> = enumerate()

    private fun start(): Long {
        input = file.inputStream().buffered()
        return LibArchiveExtractor.open(input!!).also { handle = it; position = -1 }
    }
    private fun free() {
        if (handle != 0L) { Archive.free(handle); handle = 0 }
        input?.close(); input = null
    }
    private fun enumerate(): List<String> {
        val out = mutableListOf<String>()
        try {
            val native = start()
            while (true) {
                val entry = Archive.readNextHeader(native)
                if (entry == 0L) break
                if (out.size >= 200_000) throw IOException("压缩包条目过多：${file.name}")
                val name = LibArchiveExtractor.entryName(entry)
                if (name != null && !android.system.OsConstants.S_ISDIR(ArchiveEntry.stat(entry).stMode)) {
                    val normalized = name.replace('\\', '/')
                    if (normalized.startsWith('/') || normalized.split('/').any { it == ".." || it == "." })
                        throw IOException("压缩包包含越界条目：$name")
                    if (normalized in out) throw IOException("压缩包包含重名条目：$name")
                    out += normalized
                }
                Archive.readDataSkip(native)
            }
            return out
        } finally { free() }
    }
    @Synchronized
    fun page(name: String, maxBytes: Long = Long.MAX_VALUE): File {
        check(!closed) { "Archive reader is closed" }
        cached[name]?.takeIf { it.isFile }?.let { if (it.length() > maxBytes) throw IOException("压缩包条目过大：$name"); return it }
        val target = entries.indexOf(name)
        if (target < 0) throw IOException("压缩包条目不存在：$name")
        if (handle == 0L || target <= position) { free(); start() }
        while (true) {
            val entry = Archive.readNextHeader(handle)
            if (entry == 0L) throw IOException("压缩包条目读取失败：$name")
            val at = LibArchiveExtractor.entryName(entry)?.replace('\\', '/')
            if (at != name) { Archive.readDataSkip(handle); continue }
            position = target
            cacheRoot.mkdirs()
            val result = File(cacheRoot, hash(name))
            val pending = File(cacheRoot, result.name + ".tmp")
            try {
                pending.outputStream().use { output ->
                    var written = 0L
                    val buffer = ByteBuffer.allocateDirect(64 * 1024)
                    while (true) {
                        Archive.readData(handle, buffer)
                        buffer.flip()
                        if (!buffer.hasRemaining()) break
                        written += buffer.remaining()
                        if (written > maxBytes) throw IOException("压缩包条目过大：$name")
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes); output.write(bytes); buffer.clear()
                    }
                }
                if (!pending.renameTo(result)) throw IOException("无法提交页面临时文件：${result.path}")
            } catch (e: Exception) { pending.delete(); free(); throw IOException("压缩包读取失败或内部临时空间不足：$name：${e.message}", e) }
            cached[name] = result
            while (cached.size > 4) {
                val oldest = cached.entries.first()
                oldest.value.delete(); cached.remove(oldest.key)
            }
            return result
        }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        free()
        cached.values.forEach { it.delete() }
        cached.clear()
        cacheRoot.delete()
    }
    @Synchronized fun openEntry(name: String, maxBytes: Long = Long.MAX_VALUE): InputStream = page(name, maxBytes).inputStream()
    companion object {
        private data class Shared(val reader: LibraryArchiveReader, var references: Int)
        private val readers = mutableMapOf<String, Shared>()
        class Lease internal constructor(val reader: LibraryArchiveReader, private val release: () -> Unit) : Closeable {
            private val closed = java.util.concurrent.atomic.AtomicBoolean()
            override fun close() { if (closed.compareAndSet(false, true)) release() }
        }
        fun acquire(file: File): Lease = synchronized(readers) {
            val key = file.canonicalPath
            val shared = readers.getOrPut(key) { Shared(LibraryArchiveReader(file), 0) }
            shared.references++
            Lease(shared.reader) { synchronized(readers) {
                if (--shared.references == 0) { readers.remove(key); shared.reader.close() }
            } }
        }
    }
    private fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
}
