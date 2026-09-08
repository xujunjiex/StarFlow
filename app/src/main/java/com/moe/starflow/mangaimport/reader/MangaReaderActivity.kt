package com.moe.starflow.mangaimport.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.moe.starflow.R
import com.moe.starflow.databinding.ActivityMangaReaderBinding
import com.moe.starflow.databinding.ItemMangaReaderPageBinding
import com.moe.starflow.mangaimport.data.ArchivedMangaReader
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * 漫画阅读器（阶段一）：ViewPager2 翻页 + 缩放。
 * 完整形态框架（详情面板/三态切换/翻译按钮/断点续读）入口已占位，翻译功能后续接。
 *
 * 支持两种存储源：
 * - 默认 app 目录：localRoot 是文件路径，zip 用 ZipFile、目录用文件枚举
 * - 自定义 SAF 目录：localRoot 是 content:// uri，zip 用 ZipInputStream、目录用 DocumentFile
 */
class MangaReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MANGA_ID = "manga_id"
    }

    private lateinit var binding: ActivityMangaReaderBinding
    private lateinit var manga: ImportedManga

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMangaReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getLongExtra(EXTRA_MANGA_ID, -1L)
        manga = ImportedMangaStore.load(this).firstOrNull { it.id == id }
            ?: run { finish(); return }

        val isContent = manga.localRoot.startsWith("content://")
        val pageKeys = resolvePages(manga)
        binding.viewPager.adapter = PageAdapter(
            pageKeys,
            isArchive = manga.isArchive,
            isContentUri = isContent,
            archiveUri = if (manga.isArchive) manga.localRoot else null
        )
        // 断点续读：进入时恢复到上次页
        binding.viewPager.setCurrentItem(manga.lastReadPage.coerceIn(0, (pageKeys.size - 1).coerceAtLeast(0)), false)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // 断点续读：翻页回写
                ImportedMangaStore.update(applicationContext, manga.copy(lastReadPage = position))
            }
        })

        binding.btnToggleMode.setOnClickListener {
            Toast.makeText(this, getString(R.string.reader_toggle_placeholder), Toast.LENGTH_SHORT).show()
        }
        binding.btnTranslate.setOnClickListener {
            Toast.makeText(this, getString(R.string.reader_translate_placeholder), Toast.LENGTH_SHORT).show()
        }
    }

    /** 返回页标识列表：目录=图片路径/uri，zip=entry 名（自然排序）。 */
    private fun resolvePages(m: ImportedManga): List<String> {
        return if (m.isArchive) {
            if (m.localRoot.startsWith("content://")) {
                val out = mutableListOf<String>()
                contentResolver.openInputStream(Uri.parse(m.localRoot))?.use { input ->
                    java.util.zip.ZipInputStream(input.buffered()).use { zip ->
                        var e = zip.nextEntry
                        while (e != null) {
                            if (!e.isDirectory && ArchivedMangaReader.isImageFile(e.name)) out.add(e.name)
                            e = zip.nextEntry
                        }
                    }
                }
                ArchivedMangaReader.sortNaturally(out)
            } else {
                val out = mutableListOf<String>()
                ZipFile(File(m.localRoot)).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (!e.isDirectory && ArchivedMangaReader.isImageFile(e.name)) out.add(e.name)
                    }
                }
                ArchivedMangaReader.sortNaturally(out)
            }
        } else {
            if (m.localRoot.startsWith("content://")) {
                // 自定义 SAF 目录：DocumentFile 枚举图片，按自然排序返回每页 content uri
                val files = DocumentFile.fromTreeUri(this, Uri.parse(m.localRoot))?.listFiles()
                    ?.filter { it.isFile && ArchivedMangaReader.isImageFile(it.name ?: "") }
                    ?: emptyList()
                return ArchivedMangaReader.sortNaturally(files.map { it.name ?: "" })
                    .mapNotNull { name -> files.firstOrNull { it.name == name }?.uri?.toString() }
            } else {
                ArchivedMangaReader.listImageFilesInDir(File(m.localRoot))
                    .map { File(m.localRoot, it).absolutePath }
            }
        }
    }

    /** ViewPager2 页适配器：文件源读文件/ZipFile，content 源读流/DocumentFile。 */
    private class PageAdapter(
        private val pages: List<String>,
        private val isArchive: Boolean,
        private val isContentUri: Boolean,
        private val archiveUri: String?
    ) : RecyclerView.Adapter<PageAdapter.VH>() {

        private val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.Main
        )

        class VH(val binding: ItemMangaReaderPageBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemMangaReaderPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = pages.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.binding.zoomableImage.setImageBitmap(null)
            val context = holder.itemView.context
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { load(context, position) }
                if (bmp != null && holder.adapterPosition == position) {
                    holder.binding.zoomableImage.setImageBitmap(bmp)
                }
            }
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            scope.cancel()
            super.onDetachedFromRecyclerView(recyclerView)
        }

        private fun load(context: android.content.Context, position: Int): Bitmap? {
            val p = pages[position]
            return try {
                when {
                    isArchive && isContentUri && archiveUri != null -> {
                        // content zip：流式扫到目标 entry 解码
                        context.contentResolver.openInputStream(Uri.parse(archiveUri))?.use { input ->
                            java.util.zip.ZipInputStream(input.buffered()).use { zip ->
                                var e = zip.nextEntry
                                while (e != null) {
                                    if (e.name == p) {
                                        return@use BitmapFactory.decodeStream(zip) ?: return null
                                    }
                                    e = zip.nextEntry
                                }
                            }
                        }
                        null
                    }
                    isArchive && archiveUri != null -> {
                        // 文件 zip
                        ZipFile(File(archiveUri)).use { zip ->
                            val entry = zip.getEntry(p) ?: return null
                            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
                        }
                    }
                    isContentUri -> {
                        // content 目录：读单页 uri
                        context.contentResolver.openInputStream(Uri.parse(p))?.use { input ->
                            BitmapFactory.decodeStream(input)
                        }
                    }
                    else -> BitmapFactory.decodeFile(p)
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}