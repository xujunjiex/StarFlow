package com.moe.starflow.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import com.moe.starflow.mangaimport.data.MangaPageOrder
import com.moe.starflow.novel.data.NovelStore
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** One-way, resumable initialization. Legacy files and the global DB are never deleted. */
internal object LibraryMigration {
    fun migrate(context: Context, session: LibrarySession) {
        if (session.storage !is FileLibraryStorage) return
        val prefs = context.getSharedPreferences("library_locations", Context.MODE_PRIVATE)
        if (session.storage.root.path != prefs.getString("defaultPath", null)) return
        val catalog = session.database.catalog()
        if (catalog.meta("legacyMigrated") == "1") return
        val app = session.app
        val archive = catalog.meta("legacyArchive") ?: UUID.randomUUID().toString().also {
            catalog.meta(LibraryMeta("legacyArchive", it))
        }
        val path = ".starflow/legacy/$archive.json"
        if (session.recordArchive(path).isFile) {
            val valid = runCatching { JSONObject(session.recordArchive(path).readText(Charsets.UTF_8)) }.isSuccess
            if (!valid) session.recordArchive(path).delete() // Only this migration's unpublished backup.
        }
        if (!session.recordArchive(path).isFile) archive(app, session, path)
        val oldManga = ImportedMangaStore.loadLegacy(app)
        val oldNovels = NovelStore.loadLegacy(app)
        val sourceCounts = (oldManga.map { it.localRoot } + oldNovels.map { it.localRoot }).groupingBy { File(it).canonicalPath }.eachCount()
        oldManga.forEach { old ->
            if (catalog.book("manga", old.id) != null) return@forEach
            if (old.localRoot.startsWith("content://")) throw IOException("旧漫画来源仍为未迁移的授权目录：${old.title}")
            val original = legacyMangaSource(session, File(old.localRoot))
            val source = locate(session, "manga", old.id, original, (sourceCounts[File(old.localRoot).canonicalPath] ?: 0) > 1)
            val archiveSource = original != File(old.localRoot) || old.isArchive
            ImportedMangaStore.add(session.context(), old.copy(localRoot = source.path, isArchive = archiveSource, lastReadPage = 0,
                translatedPath = null, pageOrderVersion = MangaPageOrder.CURRENT_VERSION, pageOrderPlan = ""))
        }
        oldNovels.forEach { old ->
            if (catalog.book("novel", old.id) != null) return@forEach
            if (old.localRoot.startsWith("content://")) throw IOException("旧小说来源仍为未迁移的授权目录：${old.title}")
            val source = locate(session, "novel", old.id, File(old.localRoot), (sourceCounts[File(old.localRoot).canonicalPath] ?: 0) > 1)
            NovelStore.add(session.context(), old.copy(localRoot = source.path,
                lastReadChapter = 0, lastReadParaIndex = 0, lastReadPage = 0, lastReadAt = 0))
        }
        session.flush()
        check(session.saveState.value.pending == 0) { "旧资料尚未保存到书库" }
        splitArchive(app, session, path)
        session.persistMeta("legacyMigrated", "1")
    }
    private fun splitArchive(context: Context, session: LibrarySession, path: String) {
        val original = JSONObject(session.recordArchive(path).readText(Charsets.UTF_8))
        val records = original.getJSONObject("records")
        data class Owner(val kind: String, val id: Long, val stamp: String, val uuid: String)
        val owners = ImportedMangaStore.loadLegacy(context).map { Owner("manga", it.id, it.addedAt.toString(), session.uuid("manga", it.id)) } +
            NovelStore.loadLegacy(context).map { Owner("novel", it.id, it.addedAt.toString(), session.uuid("novel", it.id)) }
        val archives = owners.associate { it.uuid to JSONObject().put("format", 1).put("valid", false)
            .put("kind", it.kind).put("legacyId", it.id).put("records", JSONObject()) }.toMutableMap()
        val unassigned = JSONObject().put("format", 1).put("valid", false).put("records", JSONObject())
        records.keys().forEach { table ->
            val rows = records.getJSONArray(table)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val novel = table == "novel_paragraph_translation"
                val owner = owners.singleOrNull { it.kind == (if (novel) "novel" else "manga") &&
                    it.id == row.optLong(if (novel) "novelId" else "mangaId", -1) &&
                    it.stamp == row.optString(if (novel) "novelKey" else "mangaKey") }
                val target = (owner?.let { archives[it.uuid] } ?: unassigned).getJSONObject("records")
                val list = target.optJSONArray(table) ?: org.json.JSONArray().also { target.put(table, it) }
                list.put(row)
            }
        }
        fun save(at: String, body: JSONObject) {
            val target = session.recordArchive(at)
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            if (target.isFile && target.readBytes().contentEquals(bytes)) return
            target.parentFile!!.mkdirs()
            val pending = File(target.parentFile, target.name + ".migrating")
            try {
                pending.outputStream().use { it.write(bytes); it.fd.sync() }
                if (!pending.renameTo(target)) throw IOException("旧资料档案无法提交：$at")
            } finally { pending.delete() }
        }
        archives.forEach { (uuid, body) -> save(".starflow/legacy/$uuid/records.json", body) }
        if (unassigned.getJSONObject("records").length() > 0) save(".starflow/legacy/unassigned/records.json", unassigned)
        session.recordArchive(path).delete() // Per-book archives are complete; the original private DB and preferences remain intact.
    }

    private fun legacyMangaSource(session: LibrarySession, cached: File): File {
        if (!cached.name.startsWith(".starflow_scan_")) return cached
        val stamp = File(cached, ".source_stamp")
        val encoded = runCatching { stamp.readLines().lastOrNull() }.getOrNull() ?: return cached
        val original = runCatching { File(String(android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP), Charsets.UTF_8)).canonicalFile }
            .getOrNull() ?: throw IOException("旧压缩包来源定位损坏：${cached.path}")
        val base = session.workingRoot.canonicalPath + File.separator
        if (!original.path.startsWith(base)) throw IOException("旧压缩包来源越出书库：${original.path}")
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(original.path.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        if (cached.name != ".starflow_scan_$hash") throw IOException("旧压缩包来源定位不匹配：${cached.path}")
        return original // A missing original remains missing; stale extracted images cannot heal it.
    }
    private fun locate(session: LibrarySession, kind: String, id: Long, original: File, forceCopy: Boolean): File {
        val folder = if (kind == "manga") "manga_import" else "novel_import"
        val expected = File(session.workingRoot, folder)
        if (!forceCopy && original.canonicalPath.startsWith(expected.canonicalPath + File.separator)) return original
        val target = File(expected, "${session.uuid(kind, id)}/${original.name.ifEmpty { "source" }}")
        if (!original.exists()) return target // Keep an indexed placeholder at a portable expected location.
        if (original.isDirectory && target.canonicalPath.startsWith(original.canonicalPath + File.separator))
            throw IOException("旧来源包含迁移目标，已停止：${original.path}")
        if (!target.exists()) {
            val pending = File(target.parentFile, target.name + ".migration")
            target.parentFile?.mkdirs()
            if (pending.exists() && !pending.deleteRecursively()) throw IOException("无法清理旧迁移半成品：${pending.path}")
            try {
                if (original.isDirectory) original.copyRecursively(pending, overwrite = false)
                else original.copyTo(pending, overwrite = false)
                if (!pending.renameTo(target)) throw IOException("旧来源迁移无法提交：${target.path}")
            } catch (e: Exception) { pending.deleteRecursively(); throw e }
        }
        if (forceCopy && original.canonicalPath.startsWith(expected.canonicalPath + File.separator)) {
            val name = "legacySource:${session.relative(original)}"
            if (session.database.catalog().meta(name) != "1") session.persistMeta(name, "1")
        }
        return target
    }
    private fun archive(context: Context, session: LibrarySession, path: String) {
        val target = session.recordArchive(path).apply { parentFile!!.mkdirs() }
        target.bufferedWriter(Charsets.UTF_8).use { writer ->
            val head = JSONObject().put("format", 1).put("valid", false)
                .put("manga", context.getSharedPreferences("manga_import", 0).getString("imported_manga_list", null))
                .put("novel", context.getSharedPreferences("novel_shelf", 0).getString("imported_novel_list", null)).toString()
            writer.write(head.dropLast(1) + ",\"records\":{")
            val file = context.getDatabasePath("translation_history.db")
            var tableWritten = false
            if (file.isFile) SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                for (table in listOf("imported_page_translation", "imported_page_sr", "novel_paragraph_translation")) {
                    val exists = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table))
                        .use { it.moveToFirst() }
                    if (!exists) continue
                    if (tableWritten) writer.write(",")
                    tableWritten = true
                    writer.write(JSONObject.quote(table) + ":[")
                    db.rawQuery("SELECT * FROM $table", null).use { cursor ->
                        var first = true
                        while (cursor.moveToNext()) {
                            if (!first) writer.write(",")
                            first = false
                            val row = JSONObject()
                            cursor.columnNames.forEachIndexed { i, name -> row.put(name,
                                if (cursor.isNull(i)) JSONObject.NULL else cursor.getString(i)) }
                            writer.write(row.toString())
                        }
                    }
                    writer.write("]")
                }
            }
            writer.write("}}")
        }
        // A torn archive must never be mistaken for a successful migration on the next run.
        try { JSONObject(session.recordArchive(path).readText(Charsets.UTF_8)) }
        catch (e: Exception) { session.recordArchive(path).delete(); throw IOException("旧资料档案写入不完整", e) }
    }
}
