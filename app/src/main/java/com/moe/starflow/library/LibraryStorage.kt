package com.moe.starflow.library

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Names are provider display names, never pieces of opaque document IDs. */
data class LibraryNode(val path: String, val directory: Boolean, val size: Long)

interface LibraryStorage {
    val locationId: String
    val displayPath: String
    fun list(path: String): List<LibraryNode>
    fun exists(path: String): Boolean
    fun mkdir(path: String)
    fun read(path: String): InputStream
    fun writeNew(path: String, mime: String = "application/octet-stream"): OutputStream
    fun delete(path: String)
    fun uri(path: String): Uri
    fun availableBytes(): Long?
}

internal fun safeLibraryPath(path: String): String {
    val normalized = path.replace('\\', '/')
    require(!normalized.startsWith('/') && normalized.split('/').none { it == ".." || it == "." }) {
        "Unsafe library path: $path"
    }
    require(!normalized.contains('\u0000')) { "Invalid library name" }
    return normalized.trimEnd('/')
}

class FileLibraryStorage(val root: File) : LibraryStorage {
    override val locationId = libraryHash("file:${root.canonicalPath}")
    override val displayPath get() = root.absolutePath
    fun file(path: String): File {
        val result = File(root, safeLibraryPath(path)).canonicalFile
        require(result == root.canonicalFile || result.path.startsWith(root.canonicalPath + File.separator))
        return result
    }
    override fun list(path: String): List<LibraryNode> = file(path).let { directory ->
        (directory.listFiles() ?: throw IOException("无法枚举目录：${directory.path}"))
            .map { LibraryNode((path.trimEnd('/') + "/" + it.name).trimStart('/'), it.isDirectory, it.length()) }
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
    override fun availableBytes(): Long? = runCatching { StatFs(root.path).availableBytes }.getOrNull()
}

class SafLibraryStorage(private val context: Context, val tree: Uri) : LibraryStorage {
    private val resolver get() = context.contentResolver
    private val treeId = DocumentsContract.getTreeDocumentId(tree)
    private val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
    override val locationId = libraryHash("saf:${tree.authority}:$treeId")
    @Volatile private var rootName: String? = null
    override val displayPath: String get() = rootName?.let { "$it ($tree)" } ?: tree.toString()
    fun overlaps(other: SafLibraryStorage): Boolean {
        if (tree.authority != other.tree.authority) return false
        if (locationId == other.locationId) return true
        return DocumentsContract.isChildDocument(resolver, rootUri, other.rootUri) ||
            DocumentsContract.isChildDocument(resolver, other.rootUri, rootUri)
    }

    fun requireImportOutsideDestination(sourceTree: Uri, kind: String) {
        if (sourceTree.authority != tree.authority) return
        val source = DocumentsContract.buildDocumentUriUsingTree(sourceTree, DocumentsContract.getTreeDocumentId(sourceTree))
        val destination = uri("${kind}_import")
        if (DocumentsContract.getDocumentId(source) == DocumentsContract.getDocumentId(destination) ||
            DocumentsContract.isChildDocument(resolver, source, destination))
            throw IOException("导入来源包含目标存储目录，已停止递归复制")
    }
    private data class Document(val uri: Uri, val name: String, val directory: Boolean, val size: Long)
    private fun children(parent: Uri): List<Document> {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE)
        val out = mutableListOf<Document>()
        val cursor = resolver.query(childUri, columns, null, null, null)
            ?: throw IOException("无法枚举目录：$parent")
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(1) ?: throw IOException("提供器未返回文件名称：$parent")
                if (name.isEmpty() || name.contains('/') || name.contains('\\') || name == "." || name == "..")
                    throw IOException("提供器返回了不可定位的名称：$name")
                out += Document(DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0)), name,
                    it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, if (it.isNull(3)) -1 else it.getLong(3))
            }
        }
        if (out.map { it.name }.toSet().size != out.size) throw IOException("目录有重名文件，无法可靠定位：$parent")
        return out
    }
    private fun nameOf(uri: Uri): String? = resolver.query(uri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
    private fun resolve(path: String, createDirectories: Boolean = false): Uri? {
        var at = rootUri
        val parts = safeLibraryPath(path).split('/').filter { it.isNotEmpty() }
        for (part in parts) {
            val child = children(at).singleOrNull { it.name == part }
            at = child?.uri ?: if (createDirectories) {
                val made = DocumentsContract.createDocument(resolver, at, DocumentsContract.Document.MIME_TYPE_DIR, part)
                    ?: throw IOException("无法创建目录：$part")
                if (nameOf(made) != part) throw IOException("提供器改写了目录名称：$part")
                made
            } else return null
        }
        return at
    }
    override fun list(path: String): List<LibraryNode> {
        if (path.isEmpty()) rootName = nameOf(rootUri)
        return children(resolve(path) ?: throw IOException("目录不存在：$path"))
            .map { LibraryNode((path.trimEnd('/') + "/" + it.name).trimStart('/'), it.directory, it.size) }
    }
    override fun exists(path: String): Boolean = resolve(path) != null
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
        return resolver.openOutputStream(made, "w") ?: throw IOException("无法写入书库文件：$path")
    }
    override fun delete(path: String) {
        require(safeLibraryPath(path).isNotEmpty()) { "不能删除书库根目录" }
        val found = resolve(path) ?: return
        if (!DocumentsContract.deleteDocument(resolver, found)) throw IOException("无法删除书库文件：$path")
    }
    override fun uri(path: String): Uri = resolve(path) ?: throw IOException("书库文件不存在：$path")
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
