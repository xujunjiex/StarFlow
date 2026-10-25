package com.moe.starflow.mangaimport.translate

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.preference.PreferenceManager
import com.moe.starflow.R
import com.moe.starflow.data.ImportedPageSr
import com.moe.starflow.mangaimport.data.MangaChapter
import com.moe.starflow.manga.OcrLock
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.mangaimport.reader.ReaderPageSource
import com.moe.starflow.sr.SrDisplayBase
import com.moe.starflow.sr.SrFailReason
import com.moe.starflow.sr.SrModelManager
import com.moe.starflow.sr.SrOutcome
import com.moe.starflow.sr.SrRequest
import org.json.JSONObject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import com.moe.starflow.sr.SrProcessor
import com.moe.starflow.sr.SrSettings
import com.moe.starflow.sr.SrStore
import com.moe.starflow.sr.SuperResolutionEngines
import com.moe.starflow.sr.anime4k.Anime4kMode
import com.moe.starflow.translate.batch.ChapterJobState
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import com.moe.starflow.mangaimport.translate.ReaderTranslationController.SrAction
import com.moe.starflow.mangaimport.translate.ReaderTranslationController.SrClickResult
import com.moe.starflow.mangaimport.translate.ReaderTranslationController.SrChapterJob

/**
 * Book-owned super-resolution work, records and display bases. The facade owns the page source
 * and UI binding; this component borrows them through Source/Host and never closes the source.
 * This component recycles its decoded execution inputs; borrowed OCR inputs stay caller-owned.
 * Export/preview copies belong to their callers. Cached display bases are borrowed by views
 * and are only evicted, never recycled while a view may retain them.
 */
internal class ReaderSuperResolution(
    private val context: Context,
    private val manga: ImportedManga,
    private val scope: CoroutineScope,
    private val source: Source,
    private val host: Host,
    private val backend: Backend = ProcessorBackend(context, manga),
) {
    interface Source {
        val pageSource: ReaderPageSource
        fun loadFull(page: Int): Bitmap?
        fun isAvailable(page: Int): Boolean
    }

    interface Host {
        val uiAttached: Boolean
        suspend fun onBaseChanged(page: Int)
        fun evictPageRenders(page: Int)
        fun clearWebtoonCache()
        suspend fun evictAllRendersForChapter()
        fun onVisual()
        fun onSrNotice(text: String, isError: Boolean)
        fun onSrProgress(page: Int, running: Boolean)
    }

    /** Native processing boundary; scheduling, records and bitmap ownership stay here. */
    interface Backend {
        /** Null means ready; a failure preserves the processor's localized reason. */
        suspend fun preflight(request: SrRequest): SrOutcome?
        suspend fun enhanceAndStore(
            page: Int, src: Bitmap, request: SrRequest, runId: Long,
            execution: JSONObject, expectedGeneration: Long,
        ): SrOutcome
    }

    private class ProcessorBackend(private val context: Context, private val manga: ImportedManga) : Backend {
        override suspend fun preflight(request: SrRequest): SrOutcome? {
            val pick = SrProcessor.preflight(context, request)
            return if (pick.engine == null) {
                SrOutcome.fail(pick.reason ?: SrFailReason.ENGINE_INIT_FAILED, pick.detail)
            } else null
        }

        override suspend fun enhanceAndStore(
            page: Int, src: Bitmap, request: SrRequest, runId: Long,
            execution: JSONObject, expectedGeneration: Long,
        ): SrOutcome = SrProcessor.enhanceAndStore(
            context, manga.id, page, src, manga.translationKey, request, runId, execution, expectedGeneration,
        )
    }

    companion object {
        private const val TAG = "ReaderTranslate"
        private const val SR_BASE_CACHE_KB = 48 * 1024
    }

    /** Borrowed by rendering callers; the cache retains ownership of the bitmap. */
    data class SrBase(val bitmap: Bitmap, val scale: Float, val sig: String)

    private val mangaKey = manga.translationKey
    private val srDao = com.moe.starflow.library.LibrarySessions.current(context).importedPageSrDao()
    private val srRows = MutableStateFlow<Map<Int, ImportedPageSr>>(emptyMap())
    // Initial loading and writes share a lock so a stale query cannot replace a completed run.
    private val srRowsMutex = Mutex()
    // Deletion, registration and completion must agree on the captured storage generation.
    private val srLifecycleMutex = Mutex()
    private val appPrefs get() = PreferenceManager.getDefaultSharedPreferences(context)
    private val ownSource get() = source.pageSource
    private fun loadFull(page: Int) = source.loadFull(page)
    private fun sourcePageAvailable(page: Int) = source.isAvailable(page)
    private fun chapterStartPage(chapter: Int) = ownSource.chapters.getOrNull(chapter)?.startPage ?: 0
    private fun chapterLabel(chapter: Int) = ownSource.chapters.getOrNull(chapter)?.let {
        com.moe.starflow.mangaimport.data.mangaChapterLabel(context, it)
    }.orEmpty()

    // ========== 超分 / 增强「显示底图」（v2，2026-10） ==========

    private val srBaseLru = object : LruCache<Int, SrBase>(SR_BASE_CACHE_KB) {
        override fun sizeOf(key: Int, value: SrBase) = value.bitmap
            .let { it.allocationByteCount.coerceAtLeast(it.rowBytes * it.height) } / 1024
    }

    private val srVisualByPage = ConcurrentHashMap<Int, Boolean>()

    private val srModelByPage = ConcurrentHashMap<Int, String>()

    fun isSrVisualOn(pageIndex: Int): Boolean = srVisualByPage[pageIndex] ?: true

    fun baseSig(pageIndex: Int): String {
        if(!isSrVisualOn(pageIndex))return "o"
        if(SrSettings.isEnabledForReader(appPrefs)) {
            val info=SrStore.infoOf(context,manga.id,pageIndex,mangaKey) ?: return "o"
            return "s:${info.model}:${info.revision}"
        }
        return if(Anime4kMode.isEnabled(appPrefs)) "a:${Anime4kMode.fromPrefs(appPrefs).id}" else "o"
    }

    suspend fun srBaseFor(pageIndex: Int, space: Bitmap, spaceWidth: Int): SrBase? {
        if (pageIndex < 0 || spaceWidth <= 0) return null

        // ── ① 先回答「**这一页到底有没有超分结果**」——它与"现在显示哪张底图"是**两件事** ──
        // ⚠️ 真机反馈：「点击切换回原图，整个超分的组件都没有了」。
        //    根因就在这里：以前把「用户切回原图」和「结果不存在」都当成 `sig == "o"`，
        //    一并 `srModelByPage.remove(...)` → `hasSrResult` 变 false → 右下角整个超分组消失，
        //    于是**再也切不回超分图**（按钮没了）。
        //    现在：结果的存在性只看**文件**（IO 一次 stat+读标记），显示态由 `isSrVisualOn` 单独管。
        val storedModel = withContext(Dispatchers.IO) {
            SrStore.storedModelOrNull(context, manga.id, pageIndex, manga.translationKey)
        }
        if (storedModel == null) {
            srModelByPage.remove(pageIndex)
            srBaseLru.remove(pageIndex)
        } else {
            srModelByPage[pageIndex] = storedModel
        }

        // ── ② 显示态：用户把这一页切回原图 → 不取底图（但上面那句仍记着"结果还在"）──
        if (!isSrVisualOn(pageIndex)) return null

        val sig = baseSig(pageIndex)
        if (sig == "o") {
            srBaseLru.remove(pageIndex)
            return null
        }
        srBaseLru.get(pageIndex)?.let { if (it.sig == sig) return it }

        if (sig.startsWith("s:")) {
            val stored = withContext(Dispatchers.IO) {
                SrStore.load(context, manga.id, pageIndex, manga.translationKey)
            }
            // ⚠️ 记的是**标记文件里的模型**（不是 sig 里那个）：换模型后 `srActionOf` 要能看出
            //    "这一页是别的模型超的 → 该重新超分"，拿 sig 当答案永远等于"已经是当前模型"。
            if (stored == null) {
                // 文件刚被系统/用户清掉（`SrStore` 以"文件存在"为真值）→ 本次回落原图
                LogCollector.d(TAG, "超分底图读取失败，回落原图 page=$pageIndex")
                return null
            }
            val scale = stored.width.toFloat() / spaceWidth
            // 输出即使比原图像素尺寸小也照常显示；这里只拦截无法计算底图倍率的无效尺寸。
            if (!SrDisplayBase.isUsableBase(stored.width, spaceWidth)) {
                LogCollector.w(
                    TAG,
                    "超分底图尺寸无效 ${stored.width}x${stored.height} vs 坐标宽 $spaceWidth" +
                        " page=$pageIndex → 回落原图",
                )
                stored.recycle()
                return null
            }
            if(sig!=baseSig(pageIndex)) {stored.recycle();return null}
            return SrBase(stored, scale, sig).also { srBaseLru.put(pageIndex, it) }
        }

        // Anime4K：同分辨率增强，输出尺寸 == 输入尺寸 → baseScale 恒 1
        // ⚠️ 失败**只记日志、不弹提示**：这是被动显示增强（用户没点任何东西），
        //    在翻页过程中弹提示纯属打扰；真要报也是等用户主动点超分时那条路径去报。
        val enhanced = withContext(Dispatchers.IO) {
            val r = SuperResolutionEngines.enhanceWithAnime4k(context, appPrefs, space)
            if (!r.ok) LogCollector.d(TAG, "Anime4K 增强未产出 page=$pageIndex: ${r.reason} ${r.detail ?: ""}")
            r.bitmap
        } ?: return null
        if(sig!=baseSig(pageIndex)) {enhanced.recycle();return null}
        return SrBase(enhanced, 1f, sig).also { srBaseLru.put(pageIndex, it) }
    }

    suspend fun warmSrBase(pageIndex: Int) {
        val sig = baseSig(pageIndex)
        srBaseLru.get(pageIndex)?.let { if (it.sig == sig) return }
        if (sig == "o") {
            // ⚠️ 与 [srBaseFor] 的同名分支**必须一致**：签名退化成 "o"（超分关了 / 文件被删 /
            //    Anime4K 让位）时要把旧签名那份**清掉**。只 return 不清的话，
            //    `cachedDisplayBitmap` 会一直取到这份过期底图 → 屏幕上挂着早已失效的超分图。
            srBaseLru.remove(pageIndex)
            return
        }
        withContext(Dispatchers.IO) {
            val src = loadFull(pageIndex) ?: return@withContext
            try {
                srBaseFor(pageIndex, src, src.width)
            } finally {
                if (!src.isRecycled) src.recycle()
            }
        }
    }

    suspend fun srPreviewFor(pageIndex: Int, targetWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (targetWidth <= 0 || !isSrVisualOn(pageIndex)) return@withContext null
        if (SrStore.storedModelOrNull(context, manga.id, pageIndex, manga.translationKey) == null) return@withContext null
        // 已经预热过就直接缩它（省一次解码）
        val cached = srBaseLru.get(pageIndex)?.bitmap
        val full = cached ?: SrStore.load(context, manga.id, pageIndex, manga.translationKey)
            ?: return@withContext null
        // ⚠️ 早退这条**返回的就是 full 本身**，所以回收**不能**塞在 finally 里 ——
        //    finally 先于 return 执行，`cached == null`（本次现解码）时会把要交出去的位图
        //    回收掉，调用方（调色面板预览格）`setImageBitmap` 后下一帧就崩
        //    `Canvas: trying to use a recycled bitmap`。
        //    改为显式回收「被替换掉的那份」，早退那份的所有权交给调用方。
        if (full.width <= targetWidth) return@withContext if(cached != null) full.copy(Bitmap.Config.ARGB_8888,false) else full
        val h = (full.height.toLong() * targetWidth / full.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(full, targetWidth, h, true)
        // 只有"本次现解码的、且确实被换掉的那份"才回收（缓存里那份归缓存）
        if (scaled !== full && cached == null && !full.isRecycled) full.recycle()
        return@withContext scaled
    }

    private suspend fun onBaseChanged(pageIndex: Int) {
        srBaseLru.remove(pageIndex)
        host.onBaseChanged(pageIndex)
    }


    // ========== 右下角超分按钮（每页三重语义） ==========


    fun srActionOf(pageIndex: Int): SrAction {
        if (!SrSettings.isEnabledForReader(appPrefs)) return SrAction.HIDDEN
        if (SrProcessor.isRunning(manga.id, pageIndex, mangaKey)) return SrAction.BUSY
        val active = SrModelManager.getActiveKey(appPrefs)?.name
        if (active == null || srModelByPage[pageIndex] != active) return SrAction.ENHANCE
        return if (isSrVisualOn(pageIndex)) SrAction.SHOW_ORIGINAL else SrAction.SHOW_SR
    }

    fun hasSrResult(pageIndex: Int): Boolean = srModelByPage.containsKey(pageIndex)

    fun srBaseOn(pageIndex: Int): Boolean = isSrVisualOn(pageIndex)

    suspend fun toggleSrBase(pageIndex: Int): SrAction {
        if (!hasSrResult(pageIndex)) return srActionOf(pageIndex)
        val nowOn = isSrVisualOn(pageIndex)
        srVisualByPage[pageIndex] = !nowOn
        onBaseChanged(pageIndex)
        return srActionOf(pageIndex)
    }

    suspend fun deleteSrResult(pageIndex: Int, dropRecord: Boolean = false): Boolean {
        srLifecycleMutex.lock()
        try {
            // Invalidate storage first, then cancel. SrProcessor checks the captured generation
            // after registering itself, closing the gap where cancellation used to miss a new job.
            srAttemptIds.remove(pageIndex)
            autoSrJobs.remove(pageIndex)?.cancel()
            val deleted = withContext(Dispatchers.IO) { SrStore.deletePage(context, manga.id, pageIndex, mangaKey) }
            SrProcessor.cancelPage(manga.id,pageIndex,mangaKey)
            if(!deleted) {
                upsertSrRow(pageIndex) {it.copy(state=ImportedPageSr.STATE_FAILED,failCode="DELETE_FAILED",failMessage=context.getString(R.string.sr_fail_delete),finishedAtMs=System.currentTimeMillis())}
                withContext(Dispatchers.Main){host.onSrNotice(context.getString(R.string.sr_fail_delete),true)}
                return false
            }
            srArtifacts.remove(pageIndex)
            srBaseLru.remove(pageIndex)
            srModelByPage.remove(pageIndex)
            // 没有超分结果了 → 自然回落原图（留着 true 也没用，但没有结果时 baseSig 本来就是 o）
            srVisualByPage.remove(pageIndex)
            host.evictPageRenders(pageIndex)
            host.clearWebtoonCache()
            // 记录同步：面板行内的「删除」要连记录一起清掉（否则删完文件还留着一行「完成」）；
            // 右下角那枚删除按钮走默认（记录退回「未超分」，面板上仍能看到"这页超过、现在没了"）。
            if (dropRecord) {
                srRowsMutex.withLock {
                    srRows.update { it - pageIndex }
                    runCatching { srDao.deletePage(manga.id, mangaKey, pageIndex) }
                        .onFailure { LogCollector.e(TAG, "删除超分记录失败 page=$pageIndex", it) }
                    srVersion.value += 1
                }
            } else {
                upsertSrRow(pageIndex) {
                    it.copy(state = ImportedPageSr.STATE_IDLE, failCode = null, failMessage = null)
                }
            }
            withContext(Dispatchers.Main) { if (host.uiAttached) host.onVisual() }
            LogCollector.d(TAG, "删除超分结果 page=$pageIndex ok=$deleted dropRecord=$dropRecord")
            return deleted
        } finally {
            srLifecycleMutex.unlock()
        }
    }

    suspend fun clearChapterSr(chapter: MangaChapter, chapterIndex: Int) = withContext(Dispatchers.IO) {
        for(p in chapter.startPage..chapter.endPage) {ensureActive();deleteSrResult(p,dropRecord=true)}
        host.evictAllRendersForChapter()
    }



    suspend fun onSrButtonClicked(pageIndex: Int): SrClickResult {
        val action = srActionOf(pageIndex)
        when (action) {
            SrAction.HIDDEN -> return SrClickResult.Failed(context.getString(R.string.sr_fail_disabled))
            SrAction.BUSY -> return SrClickResult.Failed(context.getString(R.string.sr_fail_busy))
            else -> {
                val outcome = enhancePage(pageIndex)
                if (!outcome.ok) return SrClickResult.Failed(outcome.message(context))
            }
        }
        onBaseChanged(pageIndex)
        return SrClickResult.Done(srActionOf(pageIndex))
    }

    private suspend fun enhancePage(pageIndex: Int, request: SrRequest=SrRequest.capture(context,appPrefs)): SrOutcome {
        if (!sourcePageAvailable(pageIndex)) return SrOutcome.fail(SrFailReason.PAGE_LOAD_FAILED)
        if (!SrSettings.isEnabledForReader(appPrefs)) return SrOutcome.fail(SrFailReason.DISABLED)
        val decodeStarted=System.currentTimeMillis()
        val expectedGeneration=captureSrStoreGeneration(pageIndex)
        val src = loadSrSource(pageIndex,request,decodeStarted,expectedGeneration) ?: return SrOutcome.fail(
            runCatching {SrFailReason.valueOf(srRows.value[pageIndex]?.failCode.orEmpty())}.getOrDefault(SrFailReason.PAGE_LOAD_FAILED))
        val outcome = try {
            runSr(pageIndex, src,request,(System.currentTimeMillis()-decodeStarted).coerceAtLeast(0L),expectedGeneration)
        } finally {
            // 原图是本次现解码的（`loadFull` 纯解码）→ 用完必须回收
            if (!src.isRecycled) src.recycle()
        }
        return outcome
    }

    private val srAttemptIds=ConcurrentHashMap<Int,Long>()
    private val srArtifacts=ConcurrentHashMap<Int,SrStore.StoredInfo>()
    private var srStoreObserver: Job?=null
    private val autoSrJobs=ConcurrentHashMap<Int,Job>()
    private fun bindSrPages() {
        SrStore.bindPages(manga.id,mangaKey,(0 until ownSource.size).map { ownSource.key(it) ?: "page:$it" })
    }
    private fun captureSrStoreGeneration(page: Int): Long {
        bindSrPages()
        return SrStore.generation(manga.id,page,mangaKey)
    }
    private suspend fun refreshSrArtifacts() = withContext(Dispatchers.IO) {
        if(srRows.value.isEmpty() && srModelByPage.isEmpty() && !SrStore.bookHasArtifacts(context,mangaKey))return@withContext
        bindSrPages()
        val available=(0 until ownSource.size).associateWith { SrStore.infoOf(context,manga.id,it,mangaKey) }.filterValues { it!=null }
        srRowsMutex.withLock {
            val orphanRows=available.filterKeys { it !in srRows.value }.mapValues { (p,info) ->
                ImportedPageSr(manga.id,p,ImportedPageSr.STATE_SUCCESS,modelName=info!!.model,
                    outWidth=info.width,outHeight=info.height,outBytes=info.bytes,finishedAtMs=info.savedAtMs,
                    mangaKey=mangaKey,sourcePageKey=ownSource.key(p),executionJson=info.executionJson)
            }
            if(orphanRows.isNotEmpty())srRows.update {it+orphanRows}
        }
        val oldPages=srArtifacts.keys.toSet()
        srArtifacts.clear()
        available.forEach { (p,info) -> srArtifacts[p]=info!!;srModelByPage[p]=info.model }
        (oldPages-available.keys).forEach { p -> srModelByPage.remove(p);srBaseLru.remove(p);host.evictPageRenders(p) }
        srVersion.value+=1
        withContext(Dispatchers.Main) {if(host.uiAttached)host.onVisual()}
    }
    suspend fun srChapterAvailability(pages: List<Int>,request: SrRequest): Triple<Int,Int,Int> {
        bindSrPages()
        refreshSrArtifacts()
        var current=0;var old=0;var other=0
        for(p in pages) {
            val info=SrStore.infoOf(context,manga.id,p,mangaKey) ?: continue
            if(info.model!=request.model?.name)other++ else if(info.fingerprint!=request.fingerprint)old++ else current++
        }
        return Triple(current,old,other)
    }
    private fun observeSrStore() {
        if(srStoreObserver?.isActive==true)return
        srStoreObserver=scope.launch(Dispatchers.IO) {SrStore.version.collect {refreshSrArtifacts()} }
    }
    private suspend fun runSr(page: Int,src: Bitmap,request: SrRequest=SrRequest.capture(context,appPrefs),decodeMs: Long=0L,
        expectedGeneration: Long): SrOutcome {
        if (!sourcePageAvailable(page)) return SrOutcome.fail(SrFailReason.PAGE_LOAD_FAILED)
        bindSrPages()
        val runId=SrRequest.nextRunId.incrementAndGet()
        val started=System.currentTimeMillis()
        val execution=request.describe().put("runId",runId).put("startedAtMs",started).put("decodeMs",decodeMs)
        var outcome=SrOutcome.fail(SrFailReason.CANCELLED)
        var registered=false
        try {
            srLifecycleMutex.lock()
            try {
                if(SrStore.generation(manga.id,page,mangaKey)!=expectedGeneration)
                    return SrOutcome.fail(SrFailReason.CANCELLED)
                if(srAttemptIds.putIfAbsent(page,runId)!=null)return SrOutcome.fail(SrFailReason.BUSY)
                registered=true
                markSrRunning(page,src,started,request,execution)
            } finally {
                srLifecycleMutex.unlock()
            }
            outcome=withSrRunningNotice(page) {
                backend.enhanceAndStore(page, src, request, runId, execution, expectedGeneration)
            }
            return outcome
        } catch(e: CancellationException) {
            val committed=SrStore.infoOf(context,manga.id,page,mangaKey)?.executionJson?.let { JSONObject(it).optLong("runId")==runId } == true
            if(committed)outcome=SrOutcome.stored()
            throw e
        } finally {
            if(registered)withContext(NonCancellable) {
                srLifecycleMutex.lock()
                try {
                    if(srAttemptIds[page]==runId) {
                        markSrFinished(page,outcome,started,execution)
                        val info=SrStore.infoOf(context,manga.id,page,mangaKey)
                        if(info!=null) {srArtifacts[page]=info;srModelByPage[page]=info.model}
                        if(outcome.ok)srVisualByPage[page]=true
                        srVersion.value+=1
                        srAttemptIds.remove(page,runId)
                        if(outcome.ok)onBaseChanged(page)
                    }
                } finally {
                    srLifecycleMutex.unlock()
                }
            }
        }
    }
    private suspend fun loadSrSource(page: Int,request: SrRequest,started: Long,expectedGeneration: Long): Bitmap? {
        if (!sourcePageAvailable(page)) return null
        var decoded: Bitmap?=null
        try {
            withContext(Dispatchers.IO) {ensureActive();decoded=loadFull(page);ensureActive()}
            if(decoded==null)recordSrLoadFailure(page,request,started,expectedGeneration=expectedGeneration)
            return decoded
        } catch(e: CancellationException) {decoded?.let {if(!it.isRecycled)it.recycle()};throw e}
          catch(e: OutOfMemoryError) {recordSrLoadFailure(page,request,started,"decoding: memory allocation failed",SrFailReason.OUT_OF_MEMORY,expectedGeneration);return null}
          catch(e: Exception) {recordSrLoadFailure(page,request,started,"${e.javaClass.simpleName}: ${e.message}",expectedGeneration=expectedGeneration);return null}
    }
    private suspend fun recordSrLoadFailure(page: Int,request: SrRequest,started: Long,detail: String?=null,
        reason: SrFailReason=SrFailReason.PAGE_LOAD_FAILED,expectedGeneration: Long) {
        val elapsed=(System.currentTimeMillis()-started).coerceAtLeast(0L)
        srLifecycleMutex.lock()
        try {
            if(SrStore.generation(manga.id,page,mangaKey)!=expectedGeneration)return
            upsertSrRow(page) {it.copy(state=ImportedPageSr.STATE_FAILED,modelName=request.model?.name,
                failCode=reason.name,failMessage=context.getString(reason.messageRes)+(detail?.let { "\n$it" } ?: ""),
                startedAtMs=started,finishedAtMs=System.currentTimeMillis(),sourcePageKey=ownSource.key(page),
                executionJson=request.describe().put("totalMs",elapsed).put("decodeMs",elapsed).put("stage","decoding").toString())}
        } finally {
            srLifecycleMutex.unlock()
        }
    }

    // ========== 超分记录（面板的「超分记录」列表） ==========

    private suspend fun upsertSrRow(page: Int, expectedRunId: Long? = null, transform: (ImportedPageSr) -> ImportedPageSr) {
        if (!sourcePageAvailable(page)) return
        srRowsMutex.withLock {
            if(expectedRunId!=null && srAttemptIds[page]!=expectedRunId)return@withLock
            val old = srRows.value[page] ?: ImportedPageSr(
                mangaId = manga.id, pageIndex = page,
                state = ImportedPageSr.STATE_IDLE, mangaKey = mangaKey,
            )
            val row = transform(old).copy(
                mangaId = manga.id, pageIndex = page, mangaKey = mangaKey,
                updatedAtMs = System.currentTimeMillis(),
            )
            srRows.update { it + (page to row) }
            srVersion.value += 1
            try {
                srDao.upsert(row)
            } catch (e: Exception) {
                LogCollector.e(TAG, "写超分记录失败 page=$page", e)
            }
        }
    }

    private suspend fun markSrRunning(page: Int, src: Bitmap, startedAtMs: Long, request: SrRequest, execution: JSONObject) = upsertSrRow(page,execution.optLong("runId")) {
        it.copy(
            state = ImportedPageSr.STATE_RUNNING,
            startedAtMs = startedAtMs,
            finishedAtMs = 0L,
            modelName = request.model?.name,
            outWidth = 0, outHeight = 0, outBytes = 0,
            sourcePageKey = ownSource.key(page),
            executionJson = execution.toString(),
            srcWidth = src.width, srcHeight = src.height,
            srcBytes = srcBytesOf(page),
            // 上一轮的失败原因不能留在「进行中」这一行上，否则面板会显示"超分中 + 上次的报错"
            failCode = null, failMessage = null,
        )
    }

    private suspend fun markSrFinished(page: Int, outcome: SrOutcome, startedAtMs: Long, execution: JSONObject) = upsertSrRow(page,execution.optLong("runId")) { old ->
        val now = System.currentTimeMillis()
        if (outcome.ok) {
            // 尺寸/模型/字节都从**磁盘上的真实产物**读回来（标记 + 文件本身就是真值），
            // 不从内存里的 bitmap 猜 —— 两处读会漂移。
            val info = SrStore.infoOf(context, manga.id, page, manga.translationKey)
            old.copy(
                state = ImportedPageSr.STATE_SUCCESS,
                failCode = null, failMessage = null,
                modelName = execution.optString("model"),
                executionJson = execution.toString(),
                outWidth = info?.width ?: 0, outHeight = info?.height ?: 0, outBytes = info?.bytes ?: 0L,
                startedAtMs = if (old.startedAtMs > 0) old.startedAtMs else startedAtMs,
                finishedAtMs = now,
            )
        } else {
            old.copy(
                state = if(outcome.reason==SrFailReason.CANCELLED)ImportedPageSr.STATE_CANCELLED else ImportedPageSr.STATE_FAILED,
                modelName = execution.optString("model"),
                executionJson = execution.toString(),
                failCode = outcome.reason?.name,
                failMessage = outcome.message(context),
                finishedAtMs = now,
            )
        }
    }

    private fun srcBytesOf(page: Int): Long =
        runCatching { ownSource.pageBytes(page) }.getOrDefault(0L)

    fun srRecords(): List<ImportedPageSr> = srRows.value.values.map { row ->
        val artifact=srArtifacts[row.pageIndex]
        val details=runCatching { JSONObject(if(row.state==ImportedPageSr.STATE_IDLE && artifact!=null) artifact.executionJson ?: "{}" else row.executionJson ?: "{}") }.getOrDefault(JSONObject())
        if(artifact!=null) {
            details.put("artifactModel",artifact.model).put("artifactWidth",artifact.width).put("artifactHeight",artifact.height)
                .put("oldConfig",artifact.fingerprint!=SrRequest.capture(context,appPrefs).fingerprint)
        }
        row.copy(state=if(row.state==ImportedPageSr.STATE_RUNNING && (_srChapterJob.value?.stopping==true || SrProcessor.isStopping(manga.id,row.pageIndex,mangaKey)))ImportedPageSr.STATE_STOPPING
                else if(row.state==ImportedPageSr.STATE_SUCCESS && artifact==null)ImportedPageSr.STATE_RESULT_MISSING
                else if(row.state==ImportedPageSr.STATE_IDLE && artifact!=null)ImportedPageSr.STATE_SUCCESS else row.state,
            executionJson=details.toString())
    }.sortedBy { it.pageIndex }

    fun srSuccessCount(from: Int, to: Int): Int =
        srArtifacts.keys.count { it in from..to }

    fun srPages(): Set<Int> =
        srArtifacts.keys.toSet()

    val srVersion = MutableStateFlow(0L)

    private suspend fun <T> withSrRunningNotice(page: Int, block: suspend () -> T): T {
        withContext(Dispatchers.Main) { host.onSrProgress(page, true) }
        try {
            return block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { host.onSrProgress(page, false) }
        }
    }

    private fun srAlreadyCurrent(page: Int): Boolean {
        val request=SrRequest.capture(context,appPrefs)
        return request.model!=null && SrStore.infoOf(context,manga.id,page,mangaKey)?.fingerprint==request.fingerprint
    }

    private fun launchAutoSr(page: Int, block: suspend () -> Unit): Job? {
        if(autoSrJobs[page]?.isActive==true)return null
        val task=scope.launch(Dispatchers.IO,start=CoroutineStart.LAZY) {block()}
        val previous=autoSrJobs.putIfAbsent(page,task)
        if(previous!=null) {task.cancel();return null}
        task.invokeOnCompletion {autoSrJobs.remove(page,task)}
        task.start()
        return task
    }
    fun startAutoSr(page: Int): Job? {
        if (!SrSettings.isAutoEnabledForReader(appPrefs)) return null
        if (!host.uiAttached) return null
        // 该页已经是"当前模型超的" → 没什么可做（换过模型 / 手动点过都会走到这）
        if (srAlreadyCurrent(page)) return null
        val request=SrRequest.capture(context,appPrefs)
        return launchAutoSr(page) {
            val outcome = enhancePage(page,request)
            if (outcome.ok) {
                LogCollector.d(TAG, "翻译时自动超分完成 page=$page")
                onBaseChanged(page)
            } else {
                // ⚠️ 自动超分失败**必须告诉用户原因**（只记日志 = 用户看到"开了超分却没效果"，
                //    只能自己猜是没选模型还是图太大）；呈现方式由宿主决定（状态浮层/Toast）。
                val msg = outcome.message(context)
                LogCollector.d(TAG, "翻译时自动超分未产出 page=$page: $msg")
                withContext(Dispatchers.Main) { host.onSrNotice(msg, true) }
            }
        }
    }


    private val _srChapterJob = MutableStateFlow<SrChapterJob?>(null)
    val srChapterJob: StateFlow<SrChapterJob?> get() = _srChapterJob
    private val _srChapterJobs = MutableStateFlow<List<SrChapterJob>>(emptyList())
    val srChapterJobs: StateFlow<List<SrChapterJob>> get() = _srChapterJobs

    private val srChapterQueue = SrChapterQueue<SrRequest>(
        scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
        prepare = { request ->
            // 到章节真正执行时才加载模型；排队不抢当前章的原生锁。
            val failure = backend.preflight(request)
            if (failure != null) {
                val message = failure.message(context)
                withContext(Dispatchers.Main) { if (host.uiAttached) host.onSrNotice(message, true) }
                throw IllegalStateException(message)
            } else true
        },
        processPage = { page, request ->
            // 一页一张图，只有轮到本章才解码；请求参数保持提交时的快照。
            val expectedGeneration = captureSrStoreGeneration(page)
            val decodeStarted = System.currentTimeMillis()
            val src = loadSrSource(page, request, decodeStarted, expectedGeneration)
            if (src != null) {
                try {
                    runSr(page, src, request, (System.currentTimeMillis() - decodeStarted).coerceAtLeast(0L), expectedGeneration)
                } finally {
                    if (!src.isRecycled) src.recycle()
                }
            }
        },
        onFailure = { chapterIndex, page, error ->
            LogCollector.e(TAG, "章节超分异常 chapter=$chapterIndex page=$page", error)
            if (page == null) {
                val job = com.moe.starflow.translate.batch.ActiveChapterJob(
                    kind = com.moe.starflow.translate.batch.TranslationJobKind.MANGA,
                    operation = com.moe.starflow.translate.batch.ChapterJobOperation.UPSCALE,
                    bookId = manga.id, bookTitle = manga.title, chapterIndex = chapterIndex,
                    chapterLabel = runCatching { chapterLabel(chapterIndex) }.getOrDefault("")
                        .ifBlank { context.getString(R.string.manga_chapter_label, chapterIndex + 1) },
                    startPage = runCatching { chapterStartPage(chapterIndex) }.getOrDefault(0),
                    total = 0, done = 0, state = ChapterJobState.DONE,
                    libraryLocationId = manga.libraryLocationId, bookUuid = manga.bookUuid,
                )
                com.moe.starflow.translate.batch.ChapterTaskNotificationAccess.reportFailure(
                    context, job, error.message ?: error.javaClass.simpleName,
                )
            }
        },
        onChanged = { jobs ->
            val snapshot = jobs.map { SrChapterJob(it.chapterIndex, it.done, it.total, it.page, it.stopping, it.queued, it.currentPage) }
            _srChapterJob.value = snapshot.firstOrNull { !it.queued }
            _srChapterJobs.value = snapshot
            srVersion.value += 1
        },
    )

    @Synchronized
    fun startSrChapterJob(chapterIndex: Int, pages: List<Int>, request: SrRequest = SrRequest.capture(context, appPrefs)): Boolean {
        return srChapterQueue.submit(chapterIndex, pages, request)
    }

    fun cancelSrChapterJob(chapterIndex: Int? = null) {
        srChapterQueue.cancel(chapterIndex)
    }


    fun maybeStartAutoSr(
        page: Int,
        bitmap: Bitmap,
    ): Job? {
        if (!host.uiAttached) return null
        if (!SrSettings.isAutoEnabledForReader(appPrefs)) return null
        if (!SrSettings.isEnabledForReader(appPrefs)) return null
        if (bitmap.isRecycled) return null
        // ⚠️ **同一模型已经超过就不重超**（用户口径 2026-10：「不判模型、无条件重跑一遍并覆盖
        //    落盘不行」）。这一句以前漏了 —— 章节批量每页都白跑一次超分，还要抢 OcrLock，
        //    把 OCR 串行一路卡住。判据与手动/自动路径**同源**（[srAlreadyCurrent]）。
        if (srAlreadyCurrent(page)) return null
        // ⚠️ 这里**不再自己预判**"模型下没下载/选没选"：判断集中在 `SuperResolutionEngines`，
        //    它才能给出**具体原因**。预判成 return null 的话用户什么都看不到。
        val request=SrRequest.capture(context,appPrefs)
        val expectedGeneration=captureSrStoreGeneration(page)
        return launchAutoSr(page) {
            // ⚠️ 走 runSr（不是直接调 SrProcessor）：它顺带挂「正在超分第 N 页…」的进行中提示。
            //    整章批量时**每页都会发一对**（用户口径 2026-10：「每一页都提示」）。
            val outcome = runSr(page, bitmap,request,expectedGeneration=expectedGeneration)
            if (outcome.ok) {
                // 底图换了 → 该页渲染缓存 + 底图缓存一起作废，并刷新上屏
                onBaseChanged(page)
                LogCollector.d(TAG, "翻译时自动超分完成 page=$page")
            } else {
                // ⚠️ 必须把**具体原因**告诉用户（以前只记日志，用户只看到"开了超分却没效果"）
                val msg = outcome.message(context)
                LogCollector.d(TAG, "翻译时自动超分未产出 page=$page: $msg")
                withContext(Dispatchers.Main) { host.onSrNotice(msg, true) }
            }
        }
    }

    suspend fun load() {
        srRowsMutex.withLock {
            try {
                if(srAttemptIds.isEmpty() && !SrProcessor.isBusy())srDao.resetRunning(manga.id, mangaKey)
            } catch (e: Exception) {
                LogCollector.e(TAG, "resetRunning(sr) failed", e)
            }
            srRows.value = srDao.forManga(manga.id, mangaKey).associateBy { it.pageIndex }
            srVersion.value += 1
        }
        refreshSrArtifacts()
        observeSrStore()
    }

    fun invalidateBaseCache() = srBaseLru.evictAll()

    fun evictPageCache(page: Int) {
        srBaseLru.remove(page)
        srVisualByPage.remove(page)
        srModelByPage.remove(page)
    }

    /** Memory-only lookup; stale base revisions must not be returned to an adapter. */
    fun cachedBase(page: Int): Bitmap? = srBaseLru.get(page)?.let {
        if (it.sig == baseSig(page)) it.bitmap else null
    }

    /** Independent storage copy, including when the display enhancement switch is off. */
    fun loadForExport(page: Int): Bitmap? = SrStore.load(context, manga.id, page, mangaKey)

    fun cancelPageTasks(): List<Job> = autoSrJobs.values.toList().also { tasks ->
        tasks.forEach { it.cancel() }
    }

    suspend fun cancelChaptersAndJoin() = srChapterQueue.cancelAllAndJoin()

    fun stopPageWork() {
        srStoreObserver?.cancel()
        cancelPageTasks()
        SrProcessor.cancelManga(manga.id, mangaKey)
    }
}
