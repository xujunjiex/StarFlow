package com.moe.starflow.library

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import com.moe.starflow.utils.LogCollector
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val SAF_TAG = "LibraryStorage"

/**
 * SAF 目录关系查询的**容错层**。
 *
 * ⚠️ 提供器对"已改名 / 已移动 / 已删除"的目录会抛 `FileNotFoundException`，
 * `DocumentsContract.isChildDocument` 再包一层 `RuntimeException("Failed to determine if …")`。
 * 这种「问不出来」**不能**当成「目录不能切换」：用户把 `导入文件` 改名成 `漫画导入` 之后，
 * 旧树再也解析不了，书库选择会直接卡死并把提供器原话怼到弹窗上（0.12.2 真机反馈）。
 * 关系未知 = 按「不嵌套」处理，让用户能继续选目录；真正的包含关系在可读目录上照常判定。
 */
internal object LibraryDocumentRelation {
    fun childOf(resolver: ContentResolver, parent: Uri, child: Uri): Boolean =
        childRelationOrFalse { DocumentsContract.isChildDocument(resolver, parent, child) }
}

/**
 * 容错语义的唯一实现（测试直接打这一层）：**关系问不出来 = 不嵌套**，并留一条 W 日志。
 */
internal inline fun childRelationOrFalse(crossinline query: () -> Boolean): Boolean =
    runCatching { query() }
        .onFailure { LogCollector.w(SAF_TAG, "无法判断书库目录嵌套，按不嵌套处理", it) }
        .getOrDefault(false)

/** Names are provider display names, never pieces of opaque document IDs. */
data class LibraryNode(val path: String, val directory: Boolean, val size: Long, val modifiedAt: Long = 0)

interface LibraryStorage {
    val locationId: String
    val displayPath: String
    fun list(path: String): List<LibraryNode>
    fun exists(path: String): Boolean
    fun mkdir(path: String)
    fun read(path: String): InputStream
    fun writeNew(path: String, mime: String = "application/octet-stream"): OutputStream
    fun delete(path: String)
    /** Rename within the same parent, preserving unreadable or malformed management entries. */
    fun rename(path: String, name: String): String = throw IOException("存储提供器不支持重命名，请重新选择可写目录")
    fun uri(path: String): Uri
    fun availableBytes(): Long?
    fun invalidateLookups() {}
}

internal fun safeLibraryPath(path: String): String {
    val normalized = path.replace('\\', '/')
    require(!normalized.startsWith('/') && normalized.split('/').none { it == ".." || it == "." }) {
        "Unsafe library path: $path"
    }
    require(!normalized.contains('\u0000')) { "Invalid library name" }
    return normalized.trimEnd('/')
}

/** Derive the suffix from the same canonical snapshots used for containment. Android's
 * external-storage aliases can change spelling between canonicalization calls. */
internal fun libraryRelativePath(base: String, path: String): String? {
    val prefix = base.trimEnd(File.separatorChar) + File.separator
    return if (path.startsWith(prefix)) safeLibraryPath(path.substring(prefix.length)) else null
}

class FileLibraryStorage(val root: File) : LibraryStorage {
    override val locationId = libraryHash("file:${root.canonicalPath}")
    override val displayPath get() = root.absolutePath
    fun file(path: String): File {
        val base = root.canonicalPath
        val result = File(root, safeLibraryPath(path))
        val canonical = result.canonicalPath
        require(canonical == base || libraryRelativePath(base, canonical) != null)
        // Keep the selected mount's spelling for IO; canonical paths only validate containment.
        return result
    }
    override fun list(path: String): List<LibraryNode> = file(path).let { directory ->
        (directory.listFiles() ?: throw IOException("无法枚举目录：${directory.path}"))
            .map { LibraryNode((path.trimEnd('/') + "/" + it.name).trimStart('/'), it.isDirectory, it.length(), it.lastModified()) }
    }
    override fun exists(path: String) = file(path).exists()
    override fun mkdir(path: String) {
        val target = file(path)
        if (!target.isDirectory && !target.mkdirs() && !target.isDirectory) throw IOException("无法创建目录：${target.path}")
    }
    override fun read(path: String) = file(path).inputStream()
    override fun writeNew(path: String, mime: String): OutputStream {
        val target = file(path)
        target.parentFile?.let { if (!it.isDirectory && !it.mkdirs() && !it.isDirectory) throw IOException("无法创建目录：$it") }
        if (!target.createNewFile()) throw IOException("文件已存在：$path")
        return target.outputStream()
    }
    override fun delete(path: String) {
        require(safeLibraryPath(path).isNotEmpty()) { "不能删除书库根目录" }
        val target = file(path)
        fun validate(at: File) {
            if (java.nio.file.Files.isSymbolicLink(at.toPath())) throw IOException("删除来源包含符号链接，已停止：${at.path}")
            val canonical = at.canonicalPath
            if (!canonical.startsWith(root.canonicalPath + File.separator)) throw IOException("删除来源越出书库：${at.path}")
            if (at.isDirectory) (at.listFiles() ?: throw IOException("无法枚举删除目录：${at.path}")).forEach(::validate)
        }
        if (target.exists()) {
            validate(target)
            if (!target.deleteRecursively()) throw IOException("无法删除：${target.path}")
        }
    }
    override fun uri(path: String) = Uri.fromFile(file(path))
    override fun rename(path: String, name: String): String {
        require(name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != "..")
        val safe = safeLibraryPath(path)
        require(safe.isNotEmpty())
        if (java.nio.file.Files.isSymbolicLink(File(root, safe).toPath())) throw IOException("管理文件是符号链接，请重新选择存储目录：$path")
        val destination = (safe.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }) + name
        val target = file(destination)
        if (target.exists() || !file(safe).renameTo(target)) throw IOException("无法替换书库管理文件：$path")
        return destination
    }
    override fun availableBytes(): Long? = runCatching { StatFs(root.path).availableBytes }.getOrNull()
}

class SafLibraryStorage(private val context: Context, val tree: Uri) : LibraryStorage {
    private val resolver get() = context.contentResolver
    private val treeId = DocumentsContract.getTreeDocumentId(tree)
    private val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
    override val locationId = libraryHash("saf:${tree.authority}:$treeId")
    @Volatile private var rootName: String? = null
    // URI encoding is a provider locator, not a useful path to show in the UI.
    override val displayPath: String get() = rootName ?: treeId
    private val documents = java.util.concurrent.ConcurrentHashMap<String, Uri>()
    override fun invalidateLookups() { documents.clear() }

    /** Locate an already-authorized source without interpreting provider document IDs as paths. */
    fun pathForDocument(document: Uri, importRoot: String): String? {
        if (document.authority != tree.authority) return null
        val id = runCatching { DocumentsContract.getDocumentId(document) }.getOrNull() ?: return null
        documents.entries.firstOrNull { DocumentsContract.getDocumentId(it.value) == id }?.let { return it.key }
        if (!exists(importRoot)) return null
        val sourceUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        if (!runCatching { DocumentsContract.isChildDocument(resolver, uri(importRoot), sourceUri) }.getOrDefault(false)) return null
        val seen = hashSetOf<String>()
        fun find(path: String, depth: Int): String? {
            if (depth > 6 || !seen.add(DocumentsContract.getDocumentId(uri(path)))) return null
            val nodes = list(path)
            nodes.firstOrNull { DocumentsContract.getDocumentId(uri(it.path)) == id }?.let { return it.path }
            return nodes.filter { it.directory && !it.path.substringAfterLast('/').startsWith('.') }
                .firstNotNullOfOrNull { find(it.path, depth + 1) }
        }
        return find(importRoot, 0)
    }
    /** 根目录是否还能被提供器解析：改名/移动/删除后返回 false，而不是抛异常。 */
    fun rootAvailable(): Boolean = runCatching { nameOf(rootUri) != null }.getOrDefault(false)

    fun overlaps(other: SafLibraryStorage): Boolean {
        if (tree.authority != other.tree.authority) return false
        if (locationId == other.locationId) return true
        return LibraryDocumentRelation.childOf(resolver, rootUri, other.rootUri) ||
            LibraryDocumentRelation.childOf(resolver, other.rootUri, rootUri)
    }

    fun requireImportOutsideDestination(sourceTree: Uri, kind: String) {
        if (sourceTree.authority != tree.authority) return
        val source = DocumentsContract.buildDocumentUriUsingTree(sourceTree, DocumentsContract.getTreeDocumentId(sourceTree))
        val destination = uri("${kind}_import")
        if (DocumentsContract.getDocumentId(source) == DocumentsContract.getDocumentId(destination)) {
            throw IOException("导入来源包含目标存储目录，已停止递归复制")
        }
        // 这里必须「问不出来就停」：放行可能把书库复制进它自己。但错误要能指导用户操作。
        val inside = runCatching { DocumentsContract.isChildDocument(resolver, source, destination) }
            .getOrElse {
                throw IOException(
                    "无法确认导入来源与书库目录的关系（目录可能已被移动或删除），请重新选择书库目录后重试",
                    it,
                )
            }
        if (inside) throw IOException("导入来源包含目标存储目录，已停止递归复制")
    }
    private data class Document(val uri: Uri, val name: String, val directory: Boolean, val size: Long, val modifiedAt: Long = 0)
    private fun children(parent: Uri): List<Document> {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val out = mutableListOf<Document>()
        val cursor = resolver.query(childUri, columns, null, null, null)
            ?: throw IOException("无法枚举目录：$parent")
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(1) ?: throw IOException("提供器未返回文件名称：$parent")
                if (name.isEmpty() || name.contains('/') || name.contains('\\') || name == "." || name == "..")
                    throw IOException("提供器返回了不可定位的名称：$name")
                out += Document(DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0)), name,
                    it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, if (it.isNull(3)) -1 else it.getLong(3), if (it.columnCount < 5 || it.isNull(4)) 0 else it.getLong(4))
            }
        }
        if (out.map { it.name }.toSet().size != out.size) throw IOException("目录有重名文件，无法可靠定位：$parent")
        return out
    }
    private fun nameOf(uri: Uri): String? = resolver.query(uri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
    private fun resolve(path: String, createDirectories: Boolean = false, fresh: Boolean = false): Uri? {
        var at = rootUri
        val safe = safeLibraryPath(path)
        if (!fresh) documents[safe]?.let { return it }
        val parts = safe.split('/').filter { it.isNotEmpty() }
        var prefix = ""
        for ((index, part) in parts.withIndex()) {
            prefix = (prefix + "/" + part).trimStart('/')
            val cached = documents[prefix]
            if (cached != null && (!fresh || index < parts.lastIndex)) { at = cached; continue }
            val child = children(at).singleOrNull { it.name == part }
            at = child?.uri ?: if (createDirectories) {
                val made = DocumentsContract.createDocument(resolver, at, DocumentsContract.Document.MIME_TYPE_DIR, part)
                    ?: throw IOException("无法创建目录：$part")
                if (nameOf(made) != part) throw IOException("提供器改写了目录名称：$part")
                made
            } else { documents.remove(prefix); return null }
            documents[prefix] = at
        }
        return at
    }
    override fun list(path: String): List<LibraryNode> {
        if (path.isEmpty()) rootName = nameOf(rootUri)
        return children(resolve(path) ?: throw IOException("目录不存在：$path"))
            .map {
                val childPath = (path.trimEnd('/') + "/" + it.name).trimStart('/')
                documents[childPath] = it.uri
                LibraryNode(childPath, it.directory, it.size, it.modifiedAt)
            }
    }
    override fun exists(path: String): Boolean = resolve(path, fresh = true) != null
    override fun mkdir(path: String) { resolve(path, true) }
    override fun read(path: String): InputStream = resolver.openInputStream(uri(path))
        ?: throw IOException("无法读取书库文件：$path")
    override fun writeNew(path: String, mime: String): OutputStream {
        val safe = safeLibraryPath(path)
        val parent = resolve(safe.substringBeforeLast('/', ""), true)!!
        val name = safe.substringAfterLast('/')
        if (children(parent).any { it.name == name }) throw IOException("文件已存在：$path")
        val made = DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: throw IOException("无法创建文件：$path")
        if (nameOf(made) != name) throw IOException("提供器改写了文件名称：$name")
        documents[safe] = made
        return resolver.openOutputStream(made, "w") ?: throw IOException("无法写入书库文件：$path")
    }
    override fun delete(path: String) {
        require(safeLibraryPath(path).isNotEmpty()) { "不能删除书库根目录" }
        val found = resolve(path) ?: return
        if (!DocumentsContract.deleteDocument(resolver, found)) throw IOException("无法删除书库文件：$path")
        documents.keys.removeAll { it == path || it.startsWith("$path/") }
    }
    override fun uri(path: String): Uri = resolve(path) ?: throw IOException("书库文件不存在：$path")
    override fun rename(path: String, name: String): String {
        require(name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != "..")
        val safe = safeLibraryPath(path)
        require(safe.isNotEmpty())
        val parent = safe.substringBeforeLast('/', "")
        if (children(resolve(parent) ?: throw IOException("目录不存在：$parent")).any { it.name == name })
            throw IOException("管理文件替换目标已存在：$name")
        val renamed = DocumentsContract.renameDocument(resolver, uri(safe), name)
            ?: throw IOException("无法替换书库管理文件：$path")
        if (nameOf(renamed) != name) throw IOException("提供器改写了管理文件名称：$name")
        documents.keys.removeAll { it == safe || it.startsWith("$safe/") }
        val destination = (if (parent.isEmpty()) "" else "$parent/") + name
        documents[destination] = renamed
        return destination
    }
    override fun availableBytes(): Long? {
        // Root capacity is optional. Never substitute the app's internal volume for a provider.
        return runCatching {
            val roots = DocumentsContract.buildRootsUri(tree.authority!!)
            resolver.query(roots, arrayOf(DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(0) == treeId && !cursor.isNull(1)) return@use cursor.getLong(1).takeIf { it >= 0 }
                }
                null
            }
        }.getOrNull()
    }
}

internal fun libraryHash(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
