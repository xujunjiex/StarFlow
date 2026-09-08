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
 * 导入逻辑：SAF uri → 复制进 app 目录 → 解封面 → 产出 ImportedManga。
 * 「导入即复制」策略彻底解除 SAF 授权失效依赖。
 */
object MangaImporter {

    private const val TAG = "MangaImporter"

    private const val COVER_WIDTH = 300

    fun nextId(context: Context): Long =
        (ImportedMangaStore.load(context).maxOfOrNull { it.id } ?: 0L) + 1L

    private fun importRootDir(context: Context): File =
        StorageDirStore.rootDir(context)

    private fun coverDir(context: Context): File =
        File(context.filesDir, "covers")

    /** 导入一个 zip/cbz 压缩包（ACTION_OPEN_DOCUMENT 返回的 contentUri）。 */
    suspend fun importArchive(context: Context, contentUri: Uri): ImportedManga =
        withContext(Dispatchers.IO) {
            val id = nextId(context)
            val destDir = File(importRootDir(context), id.toString()).apply { mkdirs() }
            val name = DocumentFile.fromSingleUri(context, contentUri)?.name
                ?: contentUri.lastPathSegment
                ?: "漫画$id"
            val title = name.substringBeforeLast('.', name)
            val archiveFile = File(destDir, name)
            context.contentResolver.openInputStream(contentUri)?.use { input ->
                FileOutputStream(archiveFile).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法读取压缩包: $name")

            val pageNames = listZipImageEntries(archiveFile)
            val coverPath = extractCoverFromZip(context, archiveFile, pageNames, id)

            ImportedManga(
                id = id,
                title = title,
                localRoot = archiveFile.absolutePath,
                isArchive = true,
                coverPath = coverPath,
                pageCount = pageNames.size,
                addedAt = System.currentTimeMillis()
            ).also {
                ImportedMangaStore.add(context, it)
                LogCollector.i(TAG, "导入压缩包完成: $title (${pageNames.size} 页)")
            }
        }

    /** 导入一个图片文件夹（ACTION_OPEN_DOCUMENT_TREE 返回的 treeUri，整个夹=一部）。 */
    suspend fun importDirectory(context: Context, treeUri: Uri): ImportedManga =
        withContext(Dispatchers.IO) {
            val id = nextId(context)
            val destDir = File(importRootDir(context), id.toString()).apply { mkdirs() }
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IllegalStateException("无法读取目录: $treeUri")

            val imageNames = mutableListOf<String>()
            copyDocTree(context, rootDoc, destDir, imageNames)
            val pages = ArchivedMangaReader.sortNaturally(imageNames)
            val coverPath = pages.firstOrNull()
                ?.let { File(destDir, it) }
                ?.let { extractThumbnail(context, it, id) }

            ImportedManga(
                id = id,
                title = rootDoc.name ?: "漫画$id",
                localRoot = destDir.absolutePath,
                isArchive = false,
                coverPath = coverPath,
                pageCount = pages.size,
                addedAt = System.currentTimeMillis()
            ).also {
                ImportedMangaStore.add(context, it)
                LogCollector.i(TAG, "导入目录完成: ${it.title} (${pages.size} 页)")
            }
        }

    /** 递归复制 DocumentFile 树到 dest，收集图片相对路径名。 */
    private fun copyDocTree(
        context: Context,
        doc: DocumentFile,
        dest: File,
        imageNames: MutableList<String>
    ) {
        doc.listFiles().forEach { child ->
            if (child.isDirectory) {
                val sub = File(dest, child.name ?: "").apply { mkdirs() }
                copyDocTree(context, child, sub, imageNames)
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

    private fun extractCoverFromZip(
        context: Context,
        archive: File,
        pages: List<String>,
        id: Long
    ): String? {
        val first = pages.firstOrNull() ?: return null
        ZipFile(archive).use { zip ->
            val entry = zip.getEntry(first) ?: return null
            zip.getInputStream(entry).use { input ->
                val bmp = BitmapFactory.decodeStream(input) ?: return null
                return extractThumbnail(context, bmp, id)
            }
        }
    }

    /** 目录封面：从图片文件解码后落盘缩略图。 */
    private fun extractThumbnail(context: Context, src: File, id: Long): String? {
        val bmp = BitmapFactory.decodeFile(src.absolutePath) ?: return null
        return extractThumbnail(context, bmp, id)
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
