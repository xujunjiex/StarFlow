package com.moe.starflow.sr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.moe.starflow.library.LibrarySessions
import com.moe.starflow.library.LibrarySources
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Immutable image revisions are published through the captured library commit journal. */
object SrStore {
    const val UNKNOWN_MODEL = "-"
    val version = MutableStateFlow(0L)
    private val pages = ConcurrentHashMap<String,List<String>>()
    private val generations = ConcurrentHashMap<String,Long>()
    private val checked = ConcurrentHashMap<String,Pair<String,Boolean>>()
    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    private fun book(id: Long,key: String) = "${id}_${hash(key)}"
    fun bindPages(id: Long,key: String,sourceKeys: List<String>) { pages[book(id,key)]=sourceKeys }
    private fun portable(key: String) = key.substringAfterLast(':', key)
    private fun stem(id: Long,page: Int,key: String): String = book(id,portable(key))+"_"+hash(pages[book(id,key)]?.getOrNull(page) ?: "page:$page")
    private fun generationKey(id: Long,page: Int,key: String) = key + "/" + stem(id,page,key)
    private fun libraryKey(key: String): Boolean {
        val parts = key.split(':')
        return parts.size == 3 && parts[0].isNotBlank() && parts[1] == "manga" &&
            parts[2].matches(Regex("[0-9a-fA-F-]{36}"))
    }
    private fun session(ctx: Context, key: String): com.moe.starflow.library.LibrarySession {
        require(libraryKey(key)) { "超分结果必须关联到带 UUID 的书库漫画" }
        return LibrarySessions.forLocation(ctx, key.substringBefore(':'))
    }
    private fun dir(ctx: Context,key: String): File {
        require(libraryKey(key)) { "超分结果必须关联到带 UUID 的书库漫画" }
        SrStoragePaths.modelDir(ctx) // Also performs the one-time move of old sibling diagnostics/cache files.
        return session(ctx,key).local(".starflow/books/${portable(key)}/sr").apply { mkdirs() }
    }
    private fun marker(ctx: Context,id: Long,page: Int,key: String) = File(dir(ctx,key),stem(id,page,key)+".json")
    private fun read(file: File): JSONObject? = runCatching {
        LibrarySources.file(file).bufferedReader().use { JSONObject(it.readText()) }
    }.getOrNull()
    private fun image(ctx: Context,m: JSONObject,key: String): File? {
        val name=m.optString("image")
        if(name.isEmpty() || File(name).name != name || !name.endsWith(".webp"))return null
        return runCatching { LibrarySources.file(File(dir(ctx,key),name)) }.getOrNull()
    }
    private fun valid(ctx: Context,m: JSONObject,key: String): Boolean {
        if(key.isEmpty() || m.optString("key") != portable(key))return false
        val f=image(ctx,m,key) ?: return false
        if(!f.isFile || f.length()==0L)return false
        val signature="${f.absolutePath}|${f.length()}|${f.lastModified()}|${m.optInt("w")}|${m.optInt("h")}"
        return checked[f.absolutePath]?.takeIf { it.first==signature }?.second ?: run {
            runCatching {
                val o=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeFile(f.absolutePath,o)
                o.outWidth>0 && o.outWidth==m.optInt("w") && o.outHeight==m.optInt("h")
            }.getOrDefault(false).also { checked[f.absolutePath]=signature to it }
        }
    }
    fun bookHasArtifacts(ctx: Context,key: String): Boolean =
        if (!libraryKey(key)) false else session(ctx,key).database.catalog().allMeta().any {
            it.name.startsWith("asset:.starflow/books/${portable(key)}/sr/") && it.value.endsWith(".json")
        }
    fun exists(ctx: Context,id: Long,page: Int,key: String): Boolean =
        read(marker(ctx,id,page,key))?.let { valid(ctx,it,key) } ?: false
    fun existsForSource(ctx: Context, id: Long, key: String, sourcePageKey: String): Boolean {
        val name = book(id, portable(key)) + "_" + hash(sourcePageKey) + ".json"
        return read(File(dir(ctx, key), name))?.let { valid(ctx, it, key) } ?: false
    }
    fun modelOf(ctx: Context,id: Long,page: Int,key: String): String? = infoOf(ctx,id,page,key)?.model
    fun storedModelOrNull(ctx: Context,id: Long,page: Int,key: String): String? = modelOf(ctx,id,page,key)
    data class StoredInfo(val model: String,val width: Int,val height: Int,val scale: Int,val savedAtMs: Long,val bytes: Long,
        val fingerprint: String="",val revision: String="",val executionJson: String?=null)
    fun infoOf(ctx: Context,id: Long,page: Int,key: String): StoredInfo? {
        if (!libraryKey(key)) return null
        val m=read(marker(ctx,id,page,key)) ?: return null
        if(!valid(ctx,m,key))return null
        return StoredInfo(m.optString("model",UNKNOWN_MODEL),m.optInt("w"),m.optInt("h"),m.optInt("scale",2),
            m.optLong("ts"),image(ctx,m,key)!!.length(),m.optString("fp"),m.optString("revision"),m.optJSONObject("execution")?.toString())
    }
    fun generation(id: Long,page: Int,key: String): Long = generations[generationKey(id,page,key)] ?: 0L

    @Synchronized fun save(ctx: Context,mangaId: Long,page: Int,bitmap: Bitmap,modelName: String,mangaKey: String,
        fingerprint: String="",execution: JSONObject?=null,
        expectedGeneration: Long=generation(mangaId,page,mangaKey),canCommit: () -> Boolean={true}): Boolean {
        if(!libraryKey(mangaKey) || bitmap.width<=0 || bitmap.height<=0)return false
        val name=stem(mangaId,page,mangaKey)
        if(generation(mangaId,page,mangaKey)!=expectedGeneration || !canCommit())return false
        val revision=UUID.randomUUID().toString()
        val img=File(dir(ctx,mangaKey),"${name}_$revision.webp")
        val temp=File(dir(ctx,mangaKey),"${img.name}.tmp")
        val manifest=marker(ctx,mangaId,page,mangaKey)
        var committed=false
        var imagePublished=false
        val saveBegin=System.nanoTime()
        try {
            @Suppress("DEPRECATION")
            val format=if(Build.VERSION.SDK_INT>=30)Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            FileOutputStream(temp).use { out ->
                if(!bitmap.compress(format,90,out))return false
                out.fd.sync()
            }
            if(!temp.renameTo(img))return false
            val saveElapsed=(System.nanoTime()-saveBegin)/1_000_000
            execution?.put("saveMs",saveElapsed)?.put("totalMs",execution.optLong("totalMs")+saveElapsed)
            val m=JSONObject().put("key",portable(mangaKey)).put("pageKey",pages[book(mangaId,mangaKey)]?.getOrNull(page))
                .put("model",modelName).put("fp",fingerprint).put("scale",2)
                .put("revision",revision).put("image",img.name).put("w",bitmap.width).put("h",bitmap.height)
                .put("ts",System.currentTimeMillis()).put("execution",execution)
            if(!valid(ctx,m,mangaKey) || !canCommit() || generation(mangaId,page,mangaKey)!=expectedGeneration)return false
            val pending=File(dir(ctx,mangaKey),"${manifest.name}.$revision.tmp")
            try {
                FileOutputStream(pending).use { out -> out.write(m.toString().toByteArray());out.fd.sync() }
                if(!canCommit() || generation(mangaId,page,mangaKey)!=expectedGeneration)return false
                val owner=session(ctx,mangaKey)
                owner.assertWritable(mangaKey)
                owner.publishArtifact(img)
                imagePublished=true
                owner.assertWritable(mangaKey)
                val previous = read(manifest)?.optString("image")?.takeIf { it.isNotEmpty() && File(it).name == it }
                    ?.let { File(dir(ctx,mangaKey), it) }
                owner.publishRevision(manifest,pending,listOfNotNull(previous))
            } finally {pending.delete()}
            committed=true
            pruneUnreferencedArtifacts(ctx,mangaKey)
            version.value+=1
            return valid(ctx,m,mangaKey)
        } catch(t: Throwable) {
            com.moe.starflow.utils.LogCollector.e("SrStore","save failed: ${t.message}",t)
            return false
        } finally {
            temp.delete()
            val durablePending = runCatching {
                session(ctx,mangaKey).database.catalog().pending().any { it.body.contains(img.name) }
            }.getOrDefault(true)
            if(!committed && !imagePublished && !durablePending)img.delete()
        }
    }
    fun load(ctx: Context,id: Long,page: Int,key: String): Bitmap? {
        if (!libraryKey(key)) return null
        val mf=marker(ctx,id,page,key);val m=read(mf) ?: return null
        if(!valid(ctx,m,key))return null
        val f=image(ctx,m,key) ?: return null
        val bmp=runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
        if(bmp==null) {
            synchronized(this) {
                if(read(mf)?.optString("revision") == m.optString("revision")) {
                    generations.merge(generationKey(id,page,key),1L,Long::plus)
                    mf.delete();f.delete();version.value+=1
                }
            }
        }
        return bmp
    }
    @Synchronized fun deletePage(ctx: Context,id: Long,page: Int,key: String): Boolean {
        if (!libraryKey(key)) return false
        generations.merge(generationKey(id,page,key),1L,Long::plus)
        if (runCatching { session(ctx,key).flush() }.isFailure) return false
        val mf=marker(ctx,id,page,key);val m=read(mf)
        val f=m?.let { image(ctx,it,key) }
        val ok=runCatching {
            f?.let { session(ctx,key).deleteArtifact(it) }
            session(ctx,key).deleteArtifact(mf)
            true
        }.getOrDefault(false)
        if(ok){mf.delete();version.value+=1}
        return ok && !mf.exists()
    }
    @Synchronized fun deleteManga(ctx: Context,id: Long,key: String) {
        if (!libraryKey(key)) return
        val prefix=book(id,portable(key))+"_"
        generations.keys.filter { it.startsWith(key + "/" + prefix) }.forEach { generations.merge(it,1L,Long::plus) }
        // Include in-flight pages that have not yet written a file.
        pages[book(id,key)]?.indices?.forEach { generations.merge(generationKey(id,it,key),1L,Long::plus) }
        val owner = session(ctx,key)
        owner.database.catalog().allMeta().filter { it.name.startsWith("asset:.starflow/books/${portable(key)}/sr/") && it.value.isNotEmpty() }
            .forEach { owner.deleteArtifact(owner.local(it.name.removePrefix("asset:"))) }
        version.value+=1
    }
    @Synchronized fun clear(ctx: Context): Int {
        val owner = LibrarySessions.current(ctx)
        var count = 0
        owner.books("manga").filterNot { it.deleting }.forEach { item ->
            val key = "${owner.locationId}:manga:${item.uuid}"
            if (bookHasArtifacts(ctx, key)) count++
            deleteManga(ctx, item.id, key)
        }
        checked.clear(); version.value += 1
        return count
    }
    fun totalBytes(ctx: Context): Long {
        val owner = LibrarySessions.current(ctx)
        fun bytes(path: String): Long = if (!owner.storage.exists(path)) 0L else
            owner.storage.list(path).sumOf { if (it.directory) bytes(it.path) else it.size.coerceAtLeast(0) }
        return owner.books("manga").sumOf { bytes(".starflow/books/${it.uuid}/sr") }
    }
    private fun pruneUnreferencedArtifacts(ctx: Context,key: String) {
        if (libraryKey(key) && com.moe.starflow.library.BookRecordLocks.exporting(com.moe.starflow.library.BookRef(session(ctx,key).locationId, "manga", portable(key)))) return
        val directory=dir(ctx,key)
        // Interrupted temporary writes and unreferenced immutable images have no owners.
        val owned=directory.listFiles()?.filter { it.name.endsWith(".json") }?.mapNotNull { read(it)?.optString("image") }?.toSet().orEmpty()
        directory.listFiles()?.filter { (it.name.endsWith(".tmp") || it.name.endsWith(".webp") && it.name !in owned) }?.forEach { it.delete() }
        checked.keys.removeAll { !File(it).isFile }
    }
}
