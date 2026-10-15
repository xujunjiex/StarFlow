package com.moe.starflow.library

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.format.Formatter
import androidx.core.content.ContextCompat
import com.moe.starflow.R
import com.moe.starflow.novel.translate.NovelImagePayloadCodec
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Presentation only. The full unchanged record remains available through the sheet menu. */
internal class BookRecordDetails(private val context: Context) {
    private val text = SpannableStringBuilder()
    private val used = mutableSetOf<String>()
    private val names = buildMap {
        putAll(context.resources.getStringArray(R.array.book_data_detail_keys)
            .zip(context.resources.getStringArray(R.array.book_data_detail_labels)))
        putAll(context.resources.getStringArray(R.array.book_record_extra_keys)
            .zip(context.resources.getStringArray(R.array.book_record_extra_labels)))
    }
    private fun string(id: Int) = context.getString(id)
    private fun unknown() = string(R.string.book_data_unknown)
    private fun label(key: String) = names[key] ?: key.replace(Regex("([a-z])([A-Z])"), "$1 $2")
    private fun present(row: JSONObject, key: String) = row.has(key) && !row.isNull(key) && row.get(key).toString().isNotBlank()
    private fun storedText(row: JSONObject, key: String) = if (row.isNull(key)) "" else row.optString(key)
    private fun styled(value: String, color: Int, heading: Boolean = false) {
        val start = text.length
        text.append(value)
        text.setSpan(ForegroundColorSpan(ContextCompat.getColor(context, color)), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (heading) {
            text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(RelativeSizeSpan(1.1f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    private fun section(id: Int) {
        if (text.isNotEmpty()) text.append("\n\n")
        styled(string(id), R.color.primary, true); text.append('\n')
    }
    private fun entry(name: String, value: String) {
        if (text.isNotEmpty() && text.last() != '\n') text.append('\n')
        styled(name, R.color.text_secondary); text.append("\n").append(value).append('\n')
    }
    private fun value(value: Any?, key: String = ""): String = when {
        value == null || value == JSONObject.NULL -> unknown()
        value is Boolean -> string(if (value) R.string.book_data_yes else R.string.book_data_no)
        key == "performanceMode" && value is Number && value.toInt() in 0..2 -> string(when (value.toInt()) {
            1 -> R.string.sr_opt_perf_50; 2 -> R.string.sr_opt_perf_30; else -> R.string.sr_opt_perf_90
        })
        key == "tileRequested" && value is Number && value.toInt() == 0 -> string(R.string.sr_opt_tile_auto)
        key in setOf("tile", "tileRequested") && value is Number -> "$value px"
        key == "dir" && value is String && value in setOf("VERTICAL_RL", "VERTICAL_LR", "HORIZONTAL") ->
            string(when (value) {
                "VERTICAL_RL" -> R.string.book_record_vertical_rl
                "VERTICAL_LR" -> R.string.book_record_vertical_lr
                else -> R.string.book_record_horizontal
            })
        key == "sourcePageKey" && value is String -> runCatching {
            val parts = JSONArray(value)
            require(parts.length() == 3)
            listOf("path", "kind", "entry").mapIndexed { index, name -> "${label(name)}：${parts.getString(index)}" }.joinToString("\n")
        }.getOrDefault(value)
        key in setOf("updatedAtMs", "updatedAt", "modifiedAt", "startedAtMs", "finishedAtMs") && value is Number ->
            if (value.toLong() > 0) android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", value.toLong()).toString() else unknown()
        key.endsWith("Ms") && value is Number -> context.getString(R.string.book_record_seconds,
            String.format(Locale.getDefault(), "%.3f", value.toDouble() / 1000))
        key in setOf("bytes", "srcBytes", "outBytes") && value is Number ->
            if (value.toLong() < 0 || key != "bytes" && value.toLong() == 0L) unknown()
            else "${Formatter.formatFileSize(context, value.toLong())} (${value.toLong()} B)"
        else -> value.toString().ifBlank { unknown() }
    }
    private fun fields(row: JSONObject, keys: List<String>) {
        keys.forEach { key ->
            used += key
            if (present(row, key)) entry(label(key), value(row.get(key), key))
        }
    }

    fun format(type: String, row: JSONObject, bookMissing: Boolean = false): CharSequence {
        section(R.string.book_record_overview)
        if (present(row, "stateLabel")) entry(string(R.string.book_record_status), row.getString("stateLabel"))
        used += listOf("state", "stateLabel")
        if (type == "source") {
            entry(string(R.string.book_record_status), string(when {
                row.optBoolean("missing") || bookMissing -> R.string.book_source_missing
                row.optBoolean("sourceChanged") -> R.string.book_source_changed
                else -> R.string.book_source_available
            }))
            fields(row, listOf("path", "kind", "chapter", "entry", "bytes", "modifiedAt", "missing", "sourceMissing", "sourceChanged"))
        } else {
            fields(row, listOf("translatorName", "ocrModel", "modelName", "sourceLang", "targetLang", "updatedAtMs", "updatedAt"))
            if (present(row, "failCode") || present(row, "failMessage")) {
                section(R.string.book_record_failure)
                fields(row, listOf("failCode", "failMessage"))
            }
            if (type == "sr") superResolution(row) else translatedText(type, row)
        }
        val identity = listOf("mangaId", "mangaKey", "novelId", "novelKey", "pageIndex", "chapterIndex", "paraIndex", "splitVersion", "sourcePageKey")
        if (identity.any { present(row, it) }) {
            section(R.string.book_record_identity); fields(row, identity)
        }
        val remaining = row.keys().asSequence().filter { it !in used && present(row, it) }.sorted().toList()
        if (remaining.isNotEmpty()) {
            section(R.string.book_record_other)
            remaining.forEach { key -> structured(label(key), row.get(key), key) }
        }
        return text
    }

    /** Preserve multiline OCR and the stored block numbers rather than pairing by list position. */
    private fun indexed(source: String): Map<Int, String> {
        if (source.isBlank()) return emptyMap()
        val matches = Regex("(?m)^\\[(\\d+)] ?").findAll(source).toList()
        if (matches.isEmpty() || source.substring(0, matches.first().range.first).isNotBlank()) return mapOf(1 to source)
        val result = linkedMapOf<Int, String>()
        matches.forEachIndexed { index, match ->
            val number = match.groupValues[1].toIntOrNull() ?: return mapOf(1 to source)
            val body = source.substring(match.range.last + 1, matches.getOrNull(index + 1)?.range?.first ?: source.length).trimEnd()
            result[number] = listOfNotNull(result[number], body).joinToString("\n")
        }
        return result
    }
    private fun textPair(number: Int, original: String?, translated: String?) {
        styled(context.getString(R.string.book_record_block, number), R.color.primary, true); text.append('\n')
        entry(string(R.string.book_record_original), original?.takeIf { it.isNotBlank() } ?: unknown())
        entry(string(R.string.book_record_translated), translated?.takeIf { it.isNotBlank() } ?: unknown())
        text.append('\n')
    }
    private fun translatedText(type: String, row: JSONObject) {
        used += listOf("sourceText", "translatedText", "bubbleRects", "imagePayload")
        val payload = NovelImagePayloadCodec.decode(storedText(row, "imagePayload"))
        section(R.string.book_record_text)
        if (payload != null) {
            entry(label("ocrModel"), payload.ocrEngine.ifBlank { unknown() })
            if (payload.sourceLang.isNotBlank() && payload.sourceLang != row.optString("sourceLang")) entry(label("sourceLang"), payload.sourceLang)
            if (payload.targetLang.isNotBlank() && payload.targetLang != row.optString("targetLang")) entry(label("targetLang"), payload.targetLang)
            payload.blocks.forEachIndexed { index, block ->
                textPair(index + 1, block.original, block.translated)
                entry(string(R.string.book_record_region), "(${block.left}, ${block.top}) – (${block.right}, ${block.bottom})")
                entry(label("fs"), block.fontSize.toString())
                entry(label("dir"), string(if (block.vertical) R.string.book_record_vertical_rl else R.string.book_record_horizontal))
            }
        } else if (type == "paragraph") {
            textPair(1, storedText(row, "sourceText"), storedText(row, "translatedText"))
        } else {
            val originals = indexed(storedText(row, "sourceText"))
            val translations = indexed(storedText(row, "translatedText"))
            val keys = (originals.keys + translations.keys).sorted()
            if (keys.isEmpty()) text.append(string(R.string.book_data_no_text)).append('\n')
            keys.forEach { index -> textPair(index, originals[index], translations[index]) }
        }
        if (present(row, "bubbleRects")) {
            section(R.string.book_record_regions)
            val regions = runCatching { JSONArray(row.getString("bubbleRects")) }.getOrNull()
            if (regions == null) text.append(string(R.string.book_record_unreadable))
            else for (i in 0 until regions.length()) {
                structured(context.getString(R.string.book_record_block, i + 1), regions.get(i))
            }
        }
        if (present(row, "imagePayload") && payload == null) {
            section(R.string.book_record_regions); text.append(string(R.string.book_record_unreadable))
        }
    }
    private fun superResolution(row: JSONObject) {
        section(R.string.book_record_images)
        fields(row, listOf("artifactAvailable"))
        for ((prefix, title) in listOf("src" to R.string.book_record_source_image, "out" to R.string.book_record_output_image)) {
            val width = row.optInt("${prefix}Width"); val height = row.optInt("${prefix}Height")
            entry(string(title), if (width > 0 && height > 0)
                "$width × $height px\n${value(row.opt("${prefix}Bytes"), "${prefix}Bytes")}" else unknown())
            used += listOf("${prefix}Width", "${prefix}Height", "${prefix}Bytes")
        }
        fields(row, listOf("startedAtMs", "finishedAtMs"))
        used += "executionJson"
        if (!present(row, "executionJson")) return
        val execution = runCatching { JSONObject(row.getString("executionJson")) }.getOrNull()
        if (execution == null) {
            section(R.string.book_record_parameters); text.append(string(R.string.book_record_unreadable)); return
        }
        val keys = execution.keys().asSequence().toList()
        val timing = listOf("totalMs", "decodeMs", "waitMs", "initMs", "preprocessMs", "rgbPackMs", "inferenceMs", "rgbUnpackMs", "saveMs")
        if (timing.any { execution.has(it) }) {
            section(R.string.book_record_timings)
            timing.filter { execution.has(it) }.forEach { key -> entry(label(key), value(execution.get(key), key)) }
        }
        val parameters = keys.filter { it !in timing && it != "native" }
        if (parameters.isNotEmpty()) {
            section(R.string.book_record_parameters)
            parameters.forEach { key -> structured(label(key), execution.get(key), key) }
        }
        execution.optJSONObject("native")?.let { native ->
            section(R.string.book_record_actual)
            native.keys().forEach { key -> structured(label(key), native.get(key), key) }
        }
    }
    private fun structured(name: String, data: Any, key: String = "", depth: Int = 0) {
        when (data) {
            is JSONObject -> {
                styled("${"  ".repeat(depth)}$name", R.color.text_secondary, true); text.append('\n')
                data.keys().forEach { child -> structured(label(child), data.get(child), child, depth + 1) }
            }
            is JSONArray -> {
                styled("${"  ".repeat(depth)}$name", R.color.text_secondary, true); text.append('\n')
                for (i in 0 until data.length()) structured("${i + 1}", data.get(i), key, depth + 1)
            }
            else -> entry("${"  ".repeat(depth)}$name", value(data, key))
        }
    }
}
