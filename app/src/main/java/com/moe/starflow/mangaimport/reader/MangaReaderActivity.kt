package com.moe.starflow.mangaimport.reader

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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

        val pageKeys = resolvePages(manga)
        binding.viewPager.adapter = PageAdapter(
            pageKeys,
            isArchive = manga.isArchive,
            archivePath = if (manga.isArchive) manga.localRoot else null
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

    /** 返回页标识列表：目录=图片绝对路径，zip=entry 名。 */
    private fun resolvePages(m: ImportedManga): List<String> {
        return if (m.isArchive) {
            val out = mutableListOf<String>()
            ZipFile(File(m.localRoot)).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (!e.isDirectory && ArchivedMangaReader.isImageFile(e.name)) out.add(e.name)
                }
            }
            ArchivedMangaReader.sortNaturally(out)
        } else {
            ArchivedMangaReader.listImageFilesInDir(File(m.localRoot))
                .map { File(m.localRoot, it).absolutePath }
        }
    }

    /** ViewPager2 页适配器：目录页读文件，zip 页流式解单 entry。 */
    private class PageAdapter(
        private val pages: List<String>,
        private val isArchive: Boolean,
        private val archivePath: String?
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
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { load(position) }
                if (bmp != null && holder.adapterPosition == position) {
                    holder.binding.zoomableImage.setImageBitmap(bmp)
                }
            }
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            scope.cancel()
            super.onDetachedFromRecyclerView(recyclerView)
        }

        private fun load(position: Int): android.graphics.Bitmap? {
            val p = pages[position]
            return try {
                if (isArchive && archivePath != null) {
                    ZipFile(File(archivePath)).use { zip ->
                        val entry = zip.getEntry(p) ?: return null
                        zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
                    }
                } else {
                    BitmapFactory.decodeFile(p)
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
