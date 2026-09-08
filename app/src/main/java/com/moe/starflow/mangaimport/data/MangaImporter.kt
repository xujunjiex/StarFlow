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
 * 导入逻辑：SAF 源 → 复制进 app 内部存储目录（filesDir/manga_import）→ 解封面 → 产出 ImportedManga。
 *
 * 「导入即复制」策略彻底解除对源文件的依赖，源文件（SAF uri）用完即弃。
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

            val manga = importArchiveToFile(context, contentUri, id, name, title)
                ?: throw IllegalStateException("导入失败")

            ImportedMangaStore.add(context, manga)
            LogCollector.i(TAG, "导入压缩包完成: ${manga.title} (${manga.pageCount} 页)")
            manga
        }

    private fun importArchiveToFile(
        context: Context,
        contentUri: Uri,
        id: Long,
        name: String,
        title: String
    ): ImportedManga? {
        val destDir = File(StorageDirStore.root(context), id.toString()).apply { mkdirs() }
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
            addedAt = System.currentTimeMillis(),
            sizeBytes = archiveFile.length()
        )
    }

    // ===== 导入图片文件夹 =====

    /** 导入一个图片文件夹（ACTION_OPEN_DOCUMENT_TREE 返回的 treeUri，整个夹=一部）。 */
    suspend fun importDirectory(context: Context, treeUri: Uri): ImportedManga =
        withContext(Dispatchers.IO) {
            val id = nextId(context)
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IllegalStateException("无法读取目录: $treeUri")

            val manga = importDirectoryToFile(context, rootDoc, id)
                ?: throw IllegalStateException("导入失败")

            ImportedMangaStore.add(context, manga)
            LogCollector.i(TAG, "导入目录完成: ${manga.title} (${manga.pageCount} 页)")
            manga
        }

    private fun importDirectoryToFile(
        context: Context,
        rootDoc: DocumentFile,
        id: Long
    ): ImportedManga? {
        val destDir = File(StorageDirStore.root(context), id.toString()).apply { mkdirs() }
        val imageNames = mutableListOf<String>()
        val totalBytes = copyDocTreeToFile(context, rootDoc, destDir, imageNames)
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
            addedAt = System.currentTimeMillis(),
            sizeBytes = totalBytes
        )
    }

    // ===== 复制工具 =====

    /** 递归复制 DocumentFile 树到本地 File 目录，收集图片相对路径名并统计总字节数。 */
    private fun copyDocTreeToFile(
        context: Context,
        doc: DocumentFile,
        dest: File,
        imageNames: MutableList<String>
    ): Long {
        var total = 0L
        doc.listFiles().forEach { child ->
            if (child.isDirectory) {
                val sub = File(dest, child.name ?: "").apply { mkdirs() }
                total += copyDocTreeToFile(context, child, sub, imageNames)
            } else if (child.isFile && ArchivedMangaReader.isImageFile(child.name ?: "")) {
                val name = child.name ?: return@forEach
                val target = File(dest, name)
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
                total += target.length()
                imageNames.add(name)
            }
        }
        return total
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

    /** 目录封面：从本地图片文件解码后落盘缩略图。 */
    private fun extractThumbnailFromFile(context: Context, src: File, id: Long): String? {
        val bmp = BitmapFactory.decodeFile(src.absolutePath) ?: return null
        return extractThumbnail(context, bmp, id)
    }

    /** 用外部图片（用户自选）替换某部漫画封面，返回新封面绝对路径（同一 id 旧封面被清理）。 */
    fun replaceCover(context: Context, mangaId: Long, imageUri: Uri): String? {
        val bmp = context.contentResolver.openInputStream(imageUri)?.use { BitmapFactory.decodeStream(it) }
            ?: return null
        return extractThumbnail(context, bmp, mangaId)
    }

    private fun extractThumbnail(context: Context, bmp: Bitmap, id: Long): String? {
        val dir = coverDir(context).apply { mkdirs() }
        // 唯一命名 covers/<id>_<ts>.jpg：避免 Glide 按路径缓存导致重导后显示旧图
        dir.listFiles()?.filter { it.name.startsWith("${id}_") }?.forEach { it.delete() }
        val ts = System.currentTimeMillis()
        val out = File(dir, "${id}_$ts.jpg")
        val scaled = Bitmap.createScaledBitmap(bmp, COVER_WIDTH, COVER_WIDTH, true)
        FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (scaled !== bmp) scaled.recycle()
        return out.absolutePath
    }
}