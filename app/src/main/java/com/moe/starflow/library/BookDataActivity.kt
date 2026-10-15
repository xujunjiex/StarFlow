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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.moe.starflow.BaseActivity
import com.moe.starflow.R
import com.moe.starflow.mangaimport.data.MangaChapter
import com.moe.starflow.mangaimport.data.MangaSourceIndex
import com.moe.starflow.utils.AppNotice
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Inspect internal records without opening the original, including missing books. */
class BookDataActivity : BaseActivity() {
    companion object {
        private const val PAGE_SIZE = 100
        fun open(context: Context, ref: BookRef, runtimePages: Int? = null) {
            context.startActivity(Intent(context, BookDataActivity::class.java).putExtra("location", ref.locationId)
                .putExtra("kind", ref.kind).putExtra("uuid", ref.uuid).putExtra("runtimePages", runtimePages ?: -1))
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
    private var offset = 0
    private var overview = JSONObject()
    private var refreshJob: Job? = null
    private var pageJob: Job? = null
    private var updatingSelector = false
    private var sourceExpanded = false
    private var detailSheet: BottomSheetDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
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
        updateSelector(); refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("type", type); outState.putBoolean("sourceExpanded", sourceExpanded)
        super.onSaveInstanceState(outState)
    }

    private fun showActions(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.book_data_refresh)
            menu.add(0, 2, 1, R.string.book_records_export).isEnabled = overview.has("book")
            menu.add(0, 3, 2, R.string.book_data_raw).isEnabled = overview.has("book")
            menu.add(0, 4, 3, R.string.book_data_storage_help)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> refresh()
                    2 -> try { BookRecordExport.start(this@BookDataActivity, ref) } catch (e: Exception) { report(e) }
                    3 -> showJson(overview.toString(2))
                    4 -> showText(getString(R.string.book_data_storage_help), getString(R.string.library_record_storage_hint))
                }; true
            }
            show()
        }
    }

    private fun refresh() {
        refreshJob?.cancel(); pageJob?.cancel()
        refreshJob = lifecycleScope.launch {
            var lease: java.io.Closeable? = null
            try {
                lease = LibrarySessions.inspectionLease()
                overview = BookData.snapshot(this@BookDataActivity, ref, false)
                renderSummary(); updateSelector(); offset = 0; page()
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
        book.optString("description").takeIf { it.isNotBlank() && it != "null" }?.let { description ->
            summary.addView(text(description))
        }
        card(summary) { body ->
            val chapters = if (book.has("chapterCount")) book.optInt("chapterCount") else MangaChapter.listFromJson(book.optString("chapters")).size
            field(body, fieldLabel("chapterCount"), chapters.toString())
            if (ref.kind == "manga") field(body, fieldLabel("pageCount"), value(book, "pageCount"))
            else field(body, getString(R.string.book_data_layout_pages), intent.getIntExtra("runtimePages", -1)
                .takeIf { it >= 0 }?.toString() ?: getString(R.string.book_data_unknown))
            val readAt = book.optLong("lastReadAt")
            field(body, getString(R.string.book_data_progress), if (readAt <= 0) getString(R.string.book_data_not_read)
                else if (ref.kind == "manga") getString(R.string.book_data_manga_progress, book.optInt("lastReadChapter") + 1, book.optInt("lastReadPage") + 1)
                else getString(R.string.book_data_novel_progress, book.optInt("lastReadChapter") + 1, book.optInt("lastReadParaIndex") + 1))
            if (readAt > 0) field(body, fieldLabel("lastReadAt"), date(readAt))
            field(body, fieldLabel("addedAt"), date(book.optLong("addedAt")))
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
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val title = recordTitle(requestedType, row, start + i)
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
