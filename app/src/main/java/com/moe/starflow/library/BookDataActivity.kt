package com.moe.starflow.library

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import com.moe.starflow.mangaimport.data.ComicMetadata
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.moe.starflow.BaseActivity
import com.moe.starflow.R
import com.moe.starflow.library.ui.BookMetadataChips
import com.moe.starflow.mangaimport.data.ComicMetadataParser
import com.moe.starflow.mangaimport.data.MangaChapter
import com.moe.starflow.mangaimport.data.mangaChapterLabel
import com.moe.starflow.mangaimport.data.MangaSourceIndex
import com.moe.starflow.utils.AppNotice
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Inspect internal records without opening the original, including missing books. */
class BookDataActivity : BaseActivity() {
    companion object {
        private const val PAGE_SIZE = 100
        fun open(context: Context, ref: BookRef, runtimePages: Int? = null, chapterNumber: Int = -1, currentPage: Int = -1) {
            context.startActivity(Intent(context, BookDataActivity::class.java).putExtra("location", ref.locationId)
                .putExtra("kind", ref.kind).putExtra("uuid", ref.uuid).putExtra("runtimePages", runtimePages ?: -1)
                .putExtra("chapterNumber", chapterNumber).putExtra("currentPage", currentPage))
        }
    }
    private lateinit var ref: BookRef
    private lateinit var summary: LinearLayout
    private lateinit var records: LinearLayout
    private lateinit var selector: ChipGroup
    private lateinit var recordHeading: TextView
    private lateinit var range: TextView
    private lateinit var pager: LinearLayout
    private lateinit var previous: MaterialButton
    private lateinit var next: MaterialButton
    private lateinit var categories: List<Pair<String, Int>>
    private var type = "translation"
    /** Owned by this Activity; refresh and destruction cancel the source read. */
    private var metadataJob: Job? = null
    private data class MetadataResult(val work: Result<ComicMetadata?>, val chapter: Result<ComicMetadata?>)
    private var metadataKey: String? = null
    private var metadataResult: MetadataResult? = null
    private var metadataRenderer: ((MetadataResult) -> Unit)? = null
    private var initialPage = -1
    private var initialStoredPage: Int? = null
    private var initialReadAt: Long? = null
    private lateinit var scroll: ScrollView
    private var offset = 0
    private var overview = JSONObject()
    private var refreshJob: Job? = null
    private var pageJob: Job? = null
    private var updatingSelector = false
    private var sourceExpanded = false
    private var detailSheet: BottomSheetDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialPage = savedInstanceState?.getInt("initialPage", -1) ?: intent.getIntExtra("currentPage", -1)
        initialStoredPage = savedInstanceState?.getInt("initialStoredPage", -1)?.takeIf { it >= 0 }
        initialReadAt = savedInstanceState?.getLong("initialReadAt", -1)?.takeIf { it >= 0 }
        ref = BookRef(intent.getStringExtra("location").orEmpty(), intent.getStringExtra("kind").orEmpty(), intent.getStringExtra("uuid").orEmpty())
        categories = if (ref.kind == "manga") listOf("translation" to R.string.book_record_translation_ocr,
            "sr" to R.string.book_data_sr, "source" to R.string.book_data_sources)
        else listOf("paragraph" to R.string.book_data_paragraph)
        type = savedInstanceState?.getString("type")?.takeIf { key -> categories.any { it.first == key } } ?: categories.first().first
        sourceExpanded = savedInstanceState?.getBoolean("sourceExpanded") ?: false
        val root = column().apply { setBackgroundColor(color(R.color.surface)) }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(icon(R.drawable.ic_reader_back, R.string.book_data_back) { finish() })
        toolbar.addView(text(getString(R.string.book_data_title), 18f), LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(icon(R.drawable.ic_reader_more, R.string.book_data_actions) { showActions(it) })
        root.addView(toolbar)
        val content = column().apply { setPadding(dp(16), dp(8), dp(16), dp(24)) }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(content); scroll = this }, LinearLayout.LayoutParams(-1, 0, 1f))
        summary = column().also(content::addView)
        recordHeading = text(getString(R.string.book_data_records), 18f).apply { setPadding(0, dp(20), 0, dp(4)) }.also(content::addView)
        selector = ChipGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
            .also { content.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        if (categories.size == 1) selector.visibility = View.GONE
        range = text("").also(content::addView)
        records = column().also(content::addView)
        pager = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        previous = smallButton(R.string.book_data_previous) { offset = (offset - PAGE_SIZE).coerceAtLeast(0); page() }
        next = smallButton(R.string.book_data_next) { offset += PAGE_SIZE; page() }
        pager.addView(previous); pager.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)); pager.addView(next)
        content.addView(pager)
        setContentView(root); applySystemBarsPadding(root, true, true)
        summary.addView(text(getString(R.string.loading)))
        updateSelector()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    val (session, _) = withContext(Dispatchers.IO) { BookData.resolve(this@BookDataActivity, ref) }
                    combine(session.database.catalog().observeBook(ref.uuid).distinctUntilChanged(), session.saveState) { row, _ -> row }
                        .collectLatest {
                        if (it == null || it.deleting) {
                            metadataJob?.cancel(); refreshJob?.cancel(); pageJob?.cancel()
                            metadataKey = null; metadataResult = null; metadataRenderer = null
                            overview = JSONObject(); summary.removeAllViews(); records.removeAllViews()
                            summary.addView(text(getString(R.string.book_reference_expired)))
                            return@collectLatest
                        }
                        refresh(resetRecords = !overview.has("book"), forceMetadata = false)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { report(e) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("initialPage", initialPage)
        outState.putInt("initialStoredPage", initialStoredPage ?: -1)
        outState.putLong("initialReadAt", initialReadAt ?: -1)
        outState.putString("type", type); outState.putBoolean("sourceExpanded", sourceExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        metadataJob?.cancel(); refreshJob?.cancel(); pageJob?.cancel()
        metadataKey = null; metadataResult = null; metadataRenderer = null
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (overview.has("book")) refresh(forceMetadata = true)
    }

    private fun showActions(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.book_data_refresh)
            menu.add(0, 2, 1, R.string.book_records_export).isEnabled = overview.has("book")
            menu.add(0, 3, 2, R.string.book_data_raw).isEnabled = overview.has("book")
            menu.add(0, 4, 3, R.string.book_data_storage_help)
            menu.add(0, 5, 4, R.string.library_retry_save)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> refresh()
                    2 -> try { BookRecordExport.start(this@BookDataActivity, ref) } catch (e: Exception) { report(e) }
                    3 -> showJson(overview.toString(2))
                    4 -> showText(getString(R.string.book_data_storage_help), getString(R.string.library_record_storage_hint))
                    5 -> lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) { BookData.resolve(this@BookDataActivity, ref).first.flush() }
                            refresh(resetRecords = false, forceMetadata = false)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { report(e) }
                    }
                }; true
            }
            show()
        }
    }

    private fun refresh(resetRecords: Boolean = false, forceMetadata: Boolean = true) {
        refreshJob?.cancel()
        if (forceMetadata) { metadataJob?.cancel(); metadataKey = null; metadataResult = null }
        if (resetRecords) pageJob?.cancel()
        refreshJob = lifecycleScope.launch {
            var lease: java.io.Closeable? = null
            try {
                lease = LibrarySessions.inspectionLease()
                val previousIndex = overview.optJSONObject("book")?.optString("sourceIndexPath")
                overview = BookData.snapshot(this@BookDataActivity, ref, false)
                val book = overview.getJSONObject("book")
                val storedPage = book.optInt("lastReadPage")
                val readAt = book.optLong("lastReadAt")
                if (initialStoredPage == null) { initialStoredPage = storedPage; initialReadAt = readAt }
                else if (initialStoredPage != storedPage || initialReadAt != readAt) initialPage = -1
                if (ref.kind == "manga") withContext(Dispatchers.IO) {
                    val (session, _) = BookData.resolve(this@BookDataActivity, ref)
                    try {
                        MangaSourceIndex.load(session, book.optString("sourceIndexPath"))?.let {
                            book.put("chapters", MangaChapter.listToJson(it.chapters))
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { com.moe.starflow.utils.LogCollector.w("BookData", "章节索引暂不可读：${e.message}") }
                }
                ensureActive()
                val y = scroll.scrollY
                renderSummary(); updateSelector()
                scroll.post { scroll.scrollTo(0, y) }
                if (resetRecords) { offset = 0; page() }
                else if (forceMetadata || previousIndex != book.optString("sourceIndexPath")) page()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { report(e) }
            finally { lease?.close() }
        }
    }

    private fun renderSummary() {
        summary.removeAllViews()
        val book = overview.getJSONObject("book")
        summary.addView(text(value(book, "title"), 22f))
        summary.addView(text(getString(R.string.book_data_source_state, availabilityLabel()), 14f).apply {
            setTextColor(color(if (overview.optString("availability") == "AVAILABLE") R.color.text_secondary else R.color.red))
        })
        val chapter = resolveCurrentChapter(book)
        if (ref.kind == "manga") {
            renderMetadata(book, chapter)
            card(summary) { body -> bookFacts(body, book) }
        } else {
            book.optString("description").takeIf { it.isNotBlank() && it != "null" }?.let { description ->
                summary.addView(text(description))
            }
            book.optJSONArray("tags")?.takeIf { it.length() > 0 }?.let { array ->
                val values = (0 until array.length()).mapNotNull { index ->
                    array.optString(index).takeIf(String::isNotBlank)
                }
                BookMetadataChips.addRow(summary, getString(R.string.book_meta_tags_label), values) { copyTag(it) }
            }
            card(summary) { body -> bookFacts(body, book) }
        }
        val pending = overview.optJSONArray("pendingWrites")?.length() ?: 0
        summary.addView(text(getString(if (pending == 0) R.string.book_data_saved else R.string.book_data_pending_files, pending)).apply {
            setTextColor(color(R.color.text_secondary))
        })
        listOf("saveError", "sourceIssue", "deletionError").map { value(overview, it, "") }.filter { it.isNotBlank() }.distinct().forEach {
            summary.addView(text(it).apply { setTextColor(color(R.color.red)) })
        }
        if (overview.optBoolean("deleting")) summary.addView(text(getString(R.string.book_data_deleting)))
        card(summary) { body ->
            val heading = text("", 16f).apply {
                setTextIsSelectable(false); minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; isClickable = true; isFocusable = true
            }
            body.addView(heading)
            val details = column().also(body::addView)
            fun expanded() {
                heading.text = getString(if (sourceExpanded) R.string.book_data_source_collapse else R.string.book_data_source_expand)
                details.visibility = if (sourceExpanded) View.VISIBLE else View.GONE
            }
            heading.setOnClickListener { sourceExpanded = !sourceExpanded; expanded() }
            expanded()
            details.addView(text(getString(R.string.book_data_copy_hint), 12f).apply { setTextColor(color(R.color.text_secondary)) })
            field(details, "UUID", ref.uuid)
            field(details, getString(R.string.book_data_library_label), value(overview, "library"))
            field(details, getString(R.string.book_data_path_label), value(overview, "source"))
            details.addView(smallButton(R.string.book_open_folder) { openFolderLocation() })
            value(book, "format", "").takeIf { it.isNotBlank() }?.let { field(details, fieldLabel("format"), it) }
            if (ref.kind == "manga") {
                val source = overview.optJSONObject("sourceSummary")
                if (source != null) {
                    field(details, fieldLabel("missingPages"), source.optInt("missingPages").toString())
                    field(details, fieldLabel("changedPages"), source.optInt("pagesRequiringReview").toString())
                }
                value(book, "sourceIndexPath", "").takeIf { it.isNotBlank() }?.let { field(details, fieldLabel("sourceIndexPath"), it) }
            }
        }
    }

    private fun total(key: String): Int = if (key == "source") overview.optJSONObject("sourceSummary")?.optInt("knownPages") ?: 0
        else overview.optJSONObject("counts")?.optInt(key) ?: 0

    private fun updateSelector() {
        if (categories.size == 1) recordHeading.text = getString(R.string.book_data_category_count, getString(categories.first().second), total(type))
        updatingSelector = true
        selector.removeAllViews()
        categories.forEach { (key, label) ->
            selector.addView(Chip(this).apply {
                id = View.generateViewId(); isCheckable = true; isChecked = key == type
                text = getString(R.string.book_data_category_count, getString(label), total(key))
                setOnClickListener { if (!updatingSelector && type != key) { type = key; offset = 0; page() } }
            })
        }
        updatingSelector = false
    }

    private fun page() {
        pageJob?.cancel()
        val requestedType = type; val start = offset
        records.removeAllViews(); range.setText(R.string.loading); pager.visibility = View.GONE
        pageJob = lifecycleScope.launch {
            var lease: java.io.Closeable? = null
            try {
                lease = LibrarySessions.inspectionLease()
                val rows = if (requestedType == "source") withContext(Dispatchers.IO) {
                    val (session, book) = BookData.resolve(this@BookDataActivity, ref)
                    val index = MangaSourceIndex.load(session, JSONObject(book.body).optString("sourceIndexPath"))
                    JSONArray(index?.pages.orEmpty().drop(start).take(PAGE_SIZE).map { it.json().put("sourceChanged", it.path in index?.problems.orEmpty()) })
                } else BookData.recordPage(this@BookDataActivity, ref, requestedType, start)
                ensureActive()
                if (requestedType != type || start != offset) return@launch
                range.text = if (rows.length() == 0) getString(R.string.book_data_no_records)
                    else getString(R.string.book_data_range, start + 1, start + rows.length(), maxOf(total(type), start + rows.length()))
                val chapterList = if (ref.kind == "manga") {
                    MangaChapter.listFromJson(overview.optJSONObject("book")?.optString("chapters").orEmpty())
                } else emptyList()
                var lastChapter = Int.MIN_VALUE
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val title = recordTitle(requestedType, row, start + i)
                    if (ref.kind == "manga" && requestedType != "source") {
                        val chapter = chapterList.firstOrNull { it.containsPage(row.optInt("pageIndex", -1)) }
                        if (chapter != null && chapter.number != lastChapter) {
                            lastChapter = chapter.number
                            records.addView(text(mangaChapterLabel(this@BookDataActivity, chapter), 13f).apply {
                                setTextColor(color(R.color.primary))
                                setPadding(dp(8), dp(12), dp(8), dp(2))
                            })
                        }
                    }
                    val item = column().apply { setPadding(dp(8), dp(12), dp(8), dp(12)) }
                    val heading = LinearLayout(this@BookDataActivity).apply { gravity = Gravity.CENTER_VERTICAL }
                    heading.addView(text(title, 15f), LinearLayout.LayoutParams(0, -2, 1f))
                    heading.addView(smallButton(R.string.reader_translate_row_detail) { showRecord(requestedType, title, row) })
                    item.addView(heading)
                    item.addView(text(recordPreview(requestedType, row)).apply {
                        maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setTextColor(color(R.color.text_secondary))
                    })
                    records.addView(item)
                    records.addView(View(this@BookDataActivity).apply { setBackgroundColor(color(R.color.divider)) }, LinearLayout.LayoutParams(-1, dp(1)))
                }
                previous.isEnabled = start > 0
                next.isEnabled = start + rows.length() < total(type) && rows.length() == PAGE_SIZE
                pager.visibility = if (previous.isEnabled || next.isEnabled) View.VISIBLE else View.GONE
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { range.setText(R.string.book_data_load_failed); report(e) }
            finally { lease?.close() }
        }
    }

    /** Actual reader page, then persisted progress, then the first chapter. */
    private fun resolveCurrentChapter(book: JSONObject): MangaChapter? {
        if (ref.kind != "manga") return null
        val list = MangaChapter.listFromJson(book.optString("chapters"))
        if (list.isEmpty()) return null
        val preferred = if (initialStoredPage == book.optInt("lastReadPage")) intent.getIntExtra("chapterNumber", -1) else -1
        return list.firstOrNull { initialPage >= 0 && it.containsPage(initialPage) }
            ?: list.firstOrNull { initialPage < 0 && !intent.hasExtra("currentPage") && it.number == preferred }
            ?: list.firstOrNull { it.containsPage(book.optInt("lastReadPage", -1)) }
            ?: list.first()
    }

    private fun renderMetadata(book: JSONObject, chapter: MangaChapter?) {
        val bookHolder = column().also(summary::addView)
        val chapters = MangaChapter.listFromJson(book.optString("chapters"))
        val chapterRows = if (chapter != null && chapters.size > 1) {
            var rows: LinearLayout? = null
            card(summary) { body ->
                body.addView(text(getString(R.string.book_chapter_meta_title) + " · " + mangaChapterLabel(this, chapter), 16f))
                rows = column().also(body::addView)
                rows!!.addView(text(getString(R.string.loading), 12f))
                field(body, getString(R.string.book_meta_pages_label), chapter.pageCount.toString())
                body.addView(smallButton(R.string.book_open_folder) { openFolderLocation(chapter) })
            }
            rows
        } else null
        val key = listOf(ref.key, overview.optString("source"), book.optString("sourceIndexPath"), chapter).toString()
        fun render(result: MetadataResult) {
            bookHolder.removeAllViews()
            val sourceMeta = result.work.getOrNull()
            val description = if (!book.isNull("descriptionOverride")) book.optString("descriptionOverride") else book.optString("description")
            val meta = (sourceMeta ?: ComicMetadata()).copy(summary = null,
                tags = sourceMeta?.tags?.ifEmpty { storedTags(book) } ?: storedTags(book))
            if (sourceMeta != null || description.isNotBlank() || meta.tags.isNotEmpty()) card(bookHolder) { body ->
                body.addView(text(getString(R.string.book_meta_book_label), 16f))
                if (description.isNotBlank()) body.addView(text(description))
                BookMetadataChips.addRows(body, meta) { copyTag(it) }
            }
            if (result.work.isFailure) bookHolder.addView(text(getString(R.string.book_chapter_meta_unreadable), 12f))
            chapterRows?.let { rows ->
                rows.removeAllViews()
                val chapterMeta = result.chapter.getOrNull()
                if (chapterMeta != null) BookMetadataChips.addRows(rows, chapterMeta.copy(pageCount = null)) { copyTag(it) }
                else rows.addView(text(getString(if (result.chapter.isFailure) R.string.book_chapter_meta_unreadable else R.string.book_chapter_meta_empty), 12f))
            }
        }
        metadataRenderer = ::render
        if (key == metadataKey) {
            render(metadataResult ?: MetadataResult(Result.success(null), Result.success(null)))
            if (metadataResult == null) chapterRows?.apply { removeAllViews(); addView(text(getString(R.string.loading), 12f)) }
            return
        }
        metadataJob?.cancel()
        metadataKey = key
        metadataResult = null
        // The stored effective description is available even while the source is loading/missing.
        render(MetadataResult(Result.success(null), Result.success(null)))
        chapterRows?.apply { removeAllViews(); addView(text(getString(R.string.loading), 12f)) }
        metadataJob = lifecycleScope.launch {
            var lease: java.io.Closeable? = null
            try {
                lease = LibrarySessions.inspectionLease()
                var readRecord: Pair<LibrarySession, LibraryBook>? = null
                val result = withContext(Dispatchers.IO) {
                    val (session, record) = BookData.resolve(this@BookDataActivity, ref)
                    if (record.source != overview.optString("source") ||
                        JSONObject(record.body).optString("sourceIndexPath") != book.optString("sourceIndexPath"))
                        throw CancellationException("Metadata source changed")
                    readRecord = session to record
                    val source = session.local(record.source)
                    LibrarySources.acquire(source).use {
                        val index = MangaSourceIndex.load(session, book.optString("sourceIndexPath"))
                        fun read(block: () -> ComicMetadata?): Result<ComicMetadata?> = try { Result.success(block()) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { Result.failure(e) }
                        MetadataResult(read { BookMetadataReader.bookMetadata(source, chapters, index) },
                            read { if (chapterRows != null && chapter != null) BookMetadataReader.chapterMetadata(source, chapter, index) else null })
                    }
                }
                ensureActive()
                if (metadataKey != key) return@launch
                if (result.work.isSuccess) readRecord?.let { (session, record) ->
                    withContext(Dispatchers.IO) { MangaDescriptions.cacheSource(session, record, result.work.getOrNull()) }
                }
                ensureActive()
                if (metadataKey == key) { metadataResult = result; metadataRenderer?.invoke(result) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (metadataKey == key) {
                    val result = MetadataResult(Result.failure(e), Result.failure(e))
                    metadataResult = result; metadataRenderer?.invoke(result)
                }
            } finally { lease?.close() }
        }
    }

    private fun storedTags(book: JSONObject): List<String> = book.optJSONArray("tags")?.let { array ->
        (0 until array.length()).map { array.optString(it) }.filter(String::isNotBlank)
    }.orEmpty()

    /** 书级事实：章节数 / 页数（本章 / 全书）/ 进度（本章）/ 导入时间。 */
    private fun bookFacts(body: LinearLayout, book: JSONObject) {
        val chapters = if (book.has("chapterCount")) book.optInt("chapterCount")
        else MangaChapter.listFromJson(book.optString("chapters")).size
        field(body, fieldLabel("chapterCount"), chapters.toString())
        if (ref.kind == "manga") {
            field(body, getString(R.string.book_meta_pages_label), value(book, "pageCount"))
        } else {
            field(body, getString(R.string.book_data_layout_pages), intent.getIntExtra("runtimePages", -1)
                .takeIf { it >= 0 }?.toString() ?: getString(R.string.book_data_unknown))
        }
        val readAt = book.optLong("lastReadAt")
        val readingPage = initialPage.takeIf { it >= 0 } ?: book.optInt("lastReadPage")
        field(body, getString(R.string.book_data_progress), if (readAt <= 0 && (ref.kind != "manga" || readingPage <= 0 && initialPage < 0)) getString(R.string.book_data_not_read)
            else if (ref.kind == "manga") MangaChapter.listFromJson(book.optString("chapters"))
                .firstOrNull { it.containsPage(readingPage) }?.let { chapter ->
                getString(R.string.import_read_progress_chapter, mangaChapterLabel(this, chapter),
                    (readingPage - chapter.startPage + 1).coerceAtLeast(1), chapter.pageCount)
            } ?: getString(R.string.book_data_manga_progress, book.optInt("lastReadChapter") + 1, readingPage + 1)
            else getString(R.string.book_data_novel_progress, book.optInt("lastReadChapter") + 1, book.optInt("lastReadParaIndex") + 1))
        if (readAt > 0) field(body, fieldLabel("lastReadAt"), date(readAt))
        field(body, fieldLabel("addedAt"), date(book.optLong("addedAt")))
    }
    /** 打开作品所在文件夹：**交给系统文件管理器**（SAF 用 tree 文档 URI；本地路径用 ExternalStorageProvider）。 */
    private fun openFolderLocation(chapter: MangaChapter? = null) {
        val source = overview.optString("source")
        if (source.isBlank()) return
        fun launch(uri: Uri): Boolean = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            true
        }.getOrDefault(false)
        val book = overview.optJSONObject("book") ?: return
        lifecycleScope.launch {
            try {
                val uris = withContext(Dispatchers.IO) {
                    LibrarySessions.inspectionLease().use {
                        val session = BookData.resolve(this@BookDataActivity, ref).first
                        val index = MangaSourceIndex.load(session, book.optString("sourceIndexPath"))
                        val target = chapter?.let { BookMetadataReader.chapterSource(session.local(source), it, index) }
                            ?: session.local(source)
                        val path = session.relative(target)
                        val parent = path.substringBeforeLast('/', "")
                        val node = session.storage.list(parent).firstOrNull { it.path == path }
                        val directory = if (node?.directory == true) path else parent
                        listOfNotNull(session.storage.uri(directory).takeIf { it.scheme == "content" },
                            externalDocumentsUri(session.local(directory)))
                    }
                }
                if (uris.none(::launch)) AppNotice.show(this@BookDataActivity, source)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { report(e) }
        }
    }

    private fun externalDocumentsUri(file: File): Uri? {
        val path = file.absolutePath
        val primary = "/storage/emulated/0/"
        return when {
            path.startsWith(primary) -> DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:" + path.removePrefix(primary),
            )
            path.startsWith("/storage/") -> {
                val volume = path.removePrefix("/storage/").substringBefore('/')
                if (volume.isBlank()) null else DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", volume + ":" + path.substringAfter("/storage/" + volume + "/"),
                )
            }
            else -> null
        }
    }
    private fun recordTitle(key: String, row: JSONObject, position: Int): String {
        val address = if (key == "paragraph") getString(R.string.book_data_novel_progress, row.optInt("chapterIndex") + 1, row.optInt("paraIndex") + 1)
            else getString(R.string.book_data_page_number, if (key == "source") position + 1 else row.optInt("pageIndex") + 1)
        val status = if (key == "source") getString(when {
            row.optBoolean("missing") || overview.optString("availability") == "MISSING" -> R.string.book_source_missing
            row.optBoolean("sourceChanged") -> R.string.book_source_changed
            else -> R.string.book_source_available
        })
            else value(row, "stateLabel")
        return "$address · $status"
    }

    private fun recordPreview(key: String, row: JSONObject): String = when (key) {
        "source" -> listOf(value(row, "path", ""), value(row, "entry", "")).filter { it.isNotBlank() }.distinct().joinToString(" / ")
        "sr" -> listOf(value(row, "modelName", ""), getString(when {
            row.isNull("artifactAvailable") -> R.string.book_data_artifact_unknown
            row.optBoolean("artifactAvailable") -> R.string.book_data_artifact_available
            else -> R.string.book_data_artifact_missing
        }), value(row, "failMessage", "")).filter { it.isNotBlank() }.joinToString(" · ")
        else -> sequenceOf("failMessage", "translatedText", "sourceText").map { value(row, it, "").trim() }
            .firstOrNull { it.isNotBlank() }?.take(300) ?: getString(R.string.book_data_no_text)
    }

    private fun copyTag(value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("tag", value))
        AppNotice.show(this, getString(R.string.book_tag_copied))
    }

    private fun showRecord(type: String, title: String, row: JSONObject) {
        showText(title, BookRecordDetails(this).format(type, row, overview.optString("availability") == "MISSING"), row.toString(2))
    }

    private fun showJson(value: String) = showText(getString(R.string.book_data_raw), value, monospace = true)
    private fun showText(title: String, value: CharSequence, raw: String? = null, monospace: Boolean = false) {
        detailSheet?.dismiss()
        detailSheet = BookDataSheet.create(this, title, value, raw, monospace).also { it.show() }
    }

    private fun field(parent: LinearLayout, label: String, value: String) {
        // A card's consecutive fields share one TextView, allowing selection across fields.
        val existing = (parent.getChildAt(parent.childCount - 1) as? TextView)?.takeIf { it.tag == "book-data-fields" }
        val target = existing ?: text("").apply { tag = "book-data-fields"; parent.addView(this) }
        val content = SpannableStringBuilder(target.text)
        if (content.isNotEmpty()) content.append("\n\n")
        val start = content.length
        content.append(label)
        content.setSpan(ForegroundColorSpan(color(R.color.text_secondary)), start, content.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        content.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, content.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        content.append('\n').append(value)
        target.text = content
    }
    private fun card(parent: LinearLayout, fill: (LinearLayout) -> Unit) {
        val body = column().apply { setPadding(dp(14), dp(4), dp(14), dp(12)) }
        fill(body)
        parent.addView(MaterialCardView(this).apply {
            radius = dp(12).toFloat(); cardElevation = 0f; strokeWidth = dp(1); strokeColor = color(R.color.divider)
            setCardBackgroundColor(color(R.color.surface)); addView(body)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }
    private fun icon(drawable: Int, description: Int, action: (View) -> Unit) = AppCompatImageButton(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)); setPadding(dp(12), dp(12), dp(12), dp(12))
        setImageResource(drawable); imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.text_primary))
        contentDescription = getString(description)
        val background = android.util.TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true)
        setBackgroundResource(background.resourceId); setOnClickListener(action)
    }
    private fun smallButton(label: Int, action: () -> Unit) = MaterialButton(this).apply {
        backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        setTextColor(color(R.color.primary)); strokeWidth = 0; elevation = 0f; stateListAnimator = null
        setText(label); setOnClickListener { action() }
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Float = 14f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color(R.color.text_primary)); setPadding(0, dp(4), 0, dp(4))
        setTextIsSelectable(true)
        com.moe.starflow.utils.FontSync.apply(this)
    }
    private fun value(data: JSONObject, key: String, fallback: String = getString(R.string.book_data_unknown)): String =
        if (!data.has(key) || data.isNull(key)) fallback else data.get(key).toString().ifBlank { fallback }
    private fun date(time: Long) = if (time > 0) android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", time).toString() else getString(R.string.book_data_unknown)
    private fun fieldLabel(key: String): String {
        val keys = resources.getStringArray(R.array.book_data_detail_keys)
        val index = keys.indexOf(key)
        return if (index >= 0) resources.getStringArray(R.array.book_data_detail_labels)[index] else key
    }
    private fun availabilityLabel() = getString(when (overview.optString("availability")) {
        "AVAILABLE" -> R.string.book_source_available; "MISSING" -> R.string.book_source_missing
        "PARTIAL" -> R.string.book_source_partial; "CHANGED" -> R.string.book_source_changed
        else -> R.string.book_source_unreadable
    })
    override fun onDestroy() {
        detailSheet?.dismiss(); detailSheet = null
        super.onDestroy()
    }
    private fun report(error: Exception) { AppNotice.show(this, error.message ?: getString(R.string.book_data_load_failed), AppNotice.Style.ERROR) }
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
