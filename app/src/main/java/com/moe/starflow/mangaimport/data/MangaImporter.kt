package com.moe.starflow.mangaimport.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * 导入逻辑：SAF 源 → 复制进存储目录 → 解封面 → 产出 ImportedManga。
 *
 * 存储目录支持两种（见 [StorageDirStore]）：
 * - 默认 app 目录 → 复制为 File，localRoot 存绝对路径
 * - 自定义 SAF 目录 → 复制进 DocumentFile 树，localRoot 存 content:// uri（阅读器据此分流读）
 *
 * 「导入即复制」策略彻底解除对源文件的依赖。
 */
object MangaImporter {

    private const val TAG = "MangaImporter"

    private const val COVER_WIDTH = 300

    fun nextId(context: Context): Long =
        (ImportedMangaStore.load(context).maxOfOrNull { it.id } ?: 0L) + 1L

    private fun coverDir(context: Context): File =
        File(context.filesDir, "covers")

    // ===== 导入压缩包 zip/cbz =====

    /** 导入一个 zip/cbz 压缩包（ACTION_OPEN_DOCUMENT 返回的 contentUri）。 */
    suspend fun importArchive(context: Context, contentUri: Uri): ImportedManga =
        withContext(Dispatchers.IO) {
            val id = nextId(context)
            val name = DocumentFile.fromSingleUri(context, contentUri)?.name
                ?: contentUri.lastPathSegment
                ?: "漫画$id"
            val title = name.substringBeforeLast('.', name)

            val manga = when (val root = StorageDirStore.currentRoot(context)) {
                is PickedRoot.FileRoot -> importArchiveToFile(
                    context, root.file, contentUri, id, name, title
                )
                is PickedRoot.DocRoot -> importArchiveToDoc(
                    context, root.doc, contentUri, id, name, title
                )
                null -> null
            }
                ?: throw IllegalStateException("存储目录无效")

            ImportedMangaStore.add(context, manga)
            LogCollector.i(TAG, "导入压缩包完成: ${manga.title} (${manga.pageCount} 页)")
            manga
        }

    private fun importArchiveToFile(
        context: Context,
        root: File,
        contentUri: Uri,
        id: Long,
        name: String,
        title: String
    ): ImportedManga? {
        val destDir = File(root, id.toString()).apply { mkdirs() }
        val archiveFile = File(destDir, name)
        context.contentResolver.openInputStream(contentUri)?.use { input ->
            FileOutputStream(archiveFile).use { output -> input.copyTo(output) }
        } ?: return null

        val pageNames = listZipImageEntries(archiveFile)
        val coverPath = extractCoverFromZipFile(context, archiveFile, pageNames, id)
        return ImportedManga(
            id = id,
            title = title,
            localRoot = archiveFile.absolutePath,
            isArchive = true,
            coverPath = coverPath,
            pageCount = pageNames.size,
            addedAt = System.currentTimeMillis()
        )
    }

    private fun importArchiveToDoc(
        context: Context,
        rootDoc: DocumentFile,
        contentUri: Uri,
        id: Long,
        name: String,
        title: String
    ): ImportedManga? {
        val destDir = rootDoc.createDirectory(id.toString()) ?: return null
        val archiveDoc = destDir.createFile("application/octet-stream", name) ?: return null
        context.contentResolver.openInputStream(contentUri)?.use { input ->
            context.contentResolver.openOutputStream(archiveDoc.uri)?.use { output ->
                input.copyTo(output)
            }
        } ?: return null

        val pageNames = listZipImageEntriesFromDoc(context, archiveDoc.uri)
        val coverPath = extractCoverFromZipDoc(context, archiveDoc.uri, pageNames, id)
        return ImportedManga(
            id = id,
            title = title,
            localRoot = archiveDoc.uri.toString(),
            isArchive = true,
            coverPath = coverPath,
            pageCount = pageNames.size,
            addedAt = System.currentTimeMillis()
        )
    }

    // ===== 导入图片文件夹 =====

    /** 导入一个图片文件夹（ACTION_OPEN_DOCUMENT_TREE 返回的 treeUri，整个夹=一部）。 */
    suspend fun importDirectory(context: Context, treeUri: Uri): ImportedManga =
        withContext(Dispatchers.IO) {
            val id = nextId(context)
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IllegalStateException("无法读取目录: $treeUri")

            val manga = when (val root = StorageDirStore.currentRoot(context)) {
                is PickedRoot.FileRoot -> importDirectoryToFile(
                    context, root.file, rootDoc, id
                )
                is PickedRoot.DocRoot -> importDirectoryToDoc(
                    context, root.doc, rootDoc, id
                )
                null -> null
            }
                ?: throw IllegalStateException("存储目录无效")

            ImportedMangaStore.add(context, manga)
            LogCollector.i(TAG, "导入目录完成: ${manga.title} (${manga.pageCount} 页)")
            manga
        }

    private fun importDirectoryToFile(
        context: Context,
        root: File,
        rootDoc: DocumentFile,
        id: Long
    ): ImportedManga? {
        val destDir = File(root, id.toString()).apply { mkdirs() }
        val imageNames = mutableListOf<String>()
        copyDocTreeToFile(context, rootDoc, destDir, imageNames)
        val pages = ArchivedMangaReader.sortNaturally(imageNames)
        val coverPath = pages.firstOrNull()
            ?.let { File(destDir, it) }
            ?.let { extractThumbnailFromFile(context, it, id) }

        return ImportedManga(
            id = id,
            title = rootDoc.name ?: "漫画$id",
            localRoot = destDir.absolutePath,
            isArchive = false,
            coverPath = coverPath,
            pageCount = pages.size,
            addedAt = System.currentTimeMillis()
        )
    }

    private fun importDirectoryToDoc(
        context: Context,
        destRootDoc: DocumentFile,
        rootDoc: DocumentFile,
        id: Long
    ): ImportedManga? {
        val destDir = destRootDoc.createDirectory(id.toString()) ?: return null
        val imageNames = mutableListOf<String>()
        copyDocTreeToDoc(context, rootDoc, destDir, imageNames)
        val pages = ArchivedMangaReader.sortNaturally(imageNames)
        val coverPath = pages.firstOrNull()
            ?.let { destDir.findFile(it) }
            ?.let { extractThumbnailFromDoc(context, it.uri, id) }

        return ImportedManga(
            id = id,
            title = rootDoc.name ?: "漫画$id",
            localRoot = destDir.uri.toString(),
            isArchive = false,
            coverPath = coverPath,
            pageCount = pages.size,
            addedAt = System.currentTimeMillis()
        )
    }

    // ===== 复制工具 =====

    /** 递归复制 DocumentFile 树到本地 File 目录，收集图片相对路径名。 */
    private fun copyDocTreeToFile(
        context: Context,
        doc: DocumentFile,
        dest: File,
        imageNames: MutableList<String>
    ) {
        doc.listFiles().forEach { child ->
            if (child.isDirectory) {
                val sub = File(dest, child.name ?: "").apply { mkdirs() }
                copyDocTreeToFile(context, child, sub, imageNames)
            } else if (child.isFile && ArchivedMangaReader.isImageFile(child.name ?: "")) {
                val name = child.name ?: return@forEach
                val target = File(dest, name)
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
                imageNames.add(name)
            }
        }
    }

    /** 递归复制 DocumentFile 树到另一个 DocumentFile 目录，收集图片相对路径名。 */
    private fun copyDocTreeToDoc(
        context: Context,
        doc: DocumentFile,
        dest: DocumentFile,
        imageNames: MutableList<String>
    ) {
        doc.listFiles().forEach { child ->
            if (child.isDirectory) {
                val sub = dest.createDirectory(child.name ?: "") ?: return@forEach
                copyDocTreeToDoc(context, child, sub, imageNames)
            } else if (child.isFile && ArchivedMangaReader.isImageFile(child.name ?: "")) {
                val name = child.name ?: return@forEach
                val target = dest.createFile("image/*", name) ?: return@forEach
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    context.contentResolver.openOutputStream(target.uri)?.use { output ->
                        input.copyTo(output)
                    }
                }
                imageNames.add(name)
            }
        }
    }

    // ===== zip 页枚举 =====

    private fun listZipImageEntries(archive: File): List<String> {
        val out = mutableListOf<String>()
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (!e.isDirectory && ArchivedMangaReader.isImageFile(e.name)) out.add(e.name)
            }
        }
        return ArchivedMangaReader.sortNaturally(out)
    }

    private fun listZipImageEntriesFromDoc(context: Context, archiveUri: Uri): List<String> {
        val out = mutableListOf<String>()
        context.contentResolver.openInputStream(archiveUri)?.use { input ->
            java.util.zip.ZipInputStream(input.buffered()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (!e.isDirectory && ArchivedMangaReader.isImageFile(e.name)) out.add(e.name)
                    e = zip.nextEntry
                }
            }
        }
        return ArchivedMangaReader.sortNaturally(out)
    }

    // ===== 封面提取 =====

    private fun extractCoverFromZipFile(
        context: Context,
        archive: File,
        pages: List<String>,
        id: Long
    ): String? {
        val first = pages.firstOrNull() ?: return null
        return ZipFile(archive).use { zip ->
            val entry = zip.getEntry(first) ?: return null
            zip.getInputStream(entry).use { input ->
                val bmp = BitmapFactory.decodeStream(input) ?: return null
                extractThumbnail(context, bmp, id)
            }
        }
    }

    private fun extractCoverFromZipDoc(
        context: Context,
        archiveUri: Uri,
        pages: List<String>,
        id: Long
    ): String? {
        val first = pages.firstOrNull() ?: return null
        context.contentResolver.openInputStream(archiveUri)?.use { input ->
            java.util.zip.ZipInputStream(input.buffered()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (e.name == first) {
                        val bmp = BitmapFactory.decodeStream(zip) ?: return null
                        return extractThumbnail(context, bmp, id)
                    }
                    e = zip.nextEntry
                }
            }
        }
        return null
    }

    /** 目录封面：从本地图片文件解码后落盘缩略图。 */
    private fun extractThumbnailFromFile(context: Context, src: File, id: Long): String? {
        val bmp = BitmapFactory.decodeFile(src.absolutePath) ?: return null
        return extractThumbnail(context, bmp, id)
    }

    /** 目录封面：从 SAF 图片流解码后落盘缩略图。 */
    private fun extractThumbnailFromDoc(context: Context, imageUri: Uri, id: Long): String? {
        context.contentResolver.openInputStream(imageUri)?.use { input ->
            val bmp = BitmapFactory.decodeStream(input) ?: return null
            return extractThumbnail(context, bmp, id)
        }
        return null
    }

    private fun extractThumbnail(context: Context, bmp: Bitmap, id: Long): String? {
        val dir = coverDir(context).apply { mkdirs() }
        val out = File(dir, "$id.jpg")
        val scaled = Bitmap.createScaledBitmap(bmp, COVER_WIDTH, COVER_WIDTH, true)
        FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (scaled !== bmp) scaled.recycle()
        return out.absolutePath
    }
}