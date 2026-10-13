package com.moe.starflow.library

import org.json.JSONObject
import java.io.IOException

internal data class LibraryCommit(val sequence: Long, val token: String, val parent: String,
    val operation: JSONObject)

/** A marker is accepted only after its immutable payload has been verified. */
internal class LibraryJournal(private val storage: LibraryStorage) {
    private val verified = mutableMapOf<String, LibraryCommit>()

    fun chain(incompleteTokens: Set<String> = emptySet()): List<LibraryCommit> {
        val nodes = storage.list(".starflow/catalog").filter { it.path.endsWith(".commit.json") }
        val visible = nodes.mapTo(hashSetOf()) { it.path }
        verified.keys.retainAll(visible)
        val commits = nodes.mapNotNull { node -> verified[node.path] ?: read(node.path)?.also { verified[node.path] = it } }
        val anchor = commits.filter { it.operation.optString("component") == "checkpoint" }.maxByOrNull { it.sequence }
        val history = anchor?.operation?.getJSONArray("history")?.let { values ->
            (0 until values.length()).mapTo(hashSetOf()) { values.getString(it) }
        }.orEmpty()
        if (anchor != null && commits.any { it.sequence < anchor.sequence && it.token !in history })
            throw IOException("目录记录版本冲突；已停止覆盖，本地未保存数据保留")
        val eligible = if (anchor == null) commits else commits.filter { it.sequence >= anchor.sequence }
        val result = mutableListOf<LibraryCommit>()
        var parent = ""
        var sequence = 0L
        if (anchor != null) { result += anchor; parent = anchor.token; sequence = anchor.sequence }
        while (true) {
            val next = eligible.filter { it.parent == parent }
            if (next.size > 1) throw IOException("目录记录版本冲突；已停止覆盖，本地未保存数据保留")
            val commit = next.singleOrNull() ?: break
            if (commit.sequence != sequence + 1) throw IOException("书库提交顺序损坏；已停止覆盖")
            result += commit
            parent = commit.token
            sequence = commit.sequence
        }
        if (eligible.size != result.size) throw IOException("书库提交链损坏；已停止覆盖")
        // A partial next write is harmless with an older complete state. A root consisting
        // exclusively of damaged published markers cannot be silently treated as an empty library.
        if (nodes.any { node -> incompleteTokens.none { node.path.contains(it) } } && result.isEmpty()) throw IOException("书库提交记录损坏；已停止覆盖")
        return result
    }

    private fun read(path: String): LibraryCommit? = runCatching {
        val marker = JSONObject(storage.read(path).bufferedReader().use { it.readText() })
        val bytes = storage.read(safeLibraryPath(marker.getString("payload"))).use { it.readBytes() }
        if (LibraryCodec.digest(bytes) != marker.getString("digest")) return null
        val body = JSONObject(String(bytes, Charsets.UTF_8))
        require(body.getString("token") == marker.getString("token"))
        if (body.optString("component") == "checkpoint") {
            require(body.getLong("sequence") + 1 == marker.getLong("sequence"))
            val history = body.getJSONArray("history")
            require(history.length() > 0 && history.getString(history.length() - 1) == marker.getString("parent"))
        }
        LibraryCommit(marker.getLong("sequence"), marker.getString("token"), marker.getString("parent"), body)
    }.getOrNull()
}
