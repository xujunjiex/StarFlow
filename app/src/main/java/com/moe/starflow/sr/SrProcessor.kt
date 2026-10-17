package com.moe.starflow.sr

import android.content.Context
import android.graphics.Bitmap
import androidx.preference.PreferenceManager
import com.moe.starflow.manga.OcrLock
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** The only page execution path. OCR keeps priority; requests retain their configuration. */
object SrProcessor {
    private val inFlight=ConcurrentHashMap<String,Job>()
    private fun key(id: Long,page: Int,book: String)="$id:$book:$page"
    fun isRunning(id: Long,page: Int,book: String): Boolean=inFlight.containsKey(key(id,page,book))
    fun isBusy(): Boolean=inFlight.isNotEmpty()
    fun isStopping(id: Long,page: Int,book: String): Boolean=inFlight[key(id,page,book)]?.isCancelled==true
    fun cancelPage(id: Long,page: Int,book: String) {inFlight[key(id,page,book)]?.cancel()}
    fun cancelManga(id: Long,book: String) {inFlight.filterKeys { it.startsWith("$id:$book:") }.values.forEach { it.cancel() }}

    suspend fun stopManga(id: Long, book: String) {
        val jobs = inFlight.filterKeys { it.startsWith("$id:$book:") }.values.toList()
        jobs.forEach { it.cancel() }
        jobs.joinAll()
    }
    suspend fun preflight(context: Context,request: SrRequest): SuperResolutionEngines.EnginePick = withContext(Dispatchers.IO) {
        var token=0L
        var heartbeat: Job?=null
        try {
            while(token==0L) {ensureActive();if(!OcrLock.isRunning && !OcrLock.hasOcrDemand())token=OcrLock.acquire(currentCoroutineContext()[Job], "super resolution");if(token==0L)delay(50)}
            heartbeat=OcrLock.startHeartbeat(token)
            SuperResolutionEngines.obtain(context,PreferenceManager.getDefaultSharedPreferences(context),request)
        } finally {heartbeat?.cancel();if(token!=0L)OcrLock.release(token)}
    }
    suspend fun enhanceAndStore(context: Context,mangaId: Long,page: Int,src: Bitmap,mangaKey: String,
        request: SrRequest=SrRequest.capture(context,PreferenceManager.getDefaultSharedPreferences(context)),
        runId: Long=SrRequest.nextRunId.incrementAndGet(),execution: JSONObject=request.describe(),
        expectedGeneration: Long=SrStore.generation(mangaId,page,mangaKey)): SrOutcome = withContext(Dispatchers.IO) {
        val begin=System.nanoTime()
        val k=key(mangaId,page,mangaKey)
        val job=currentCoroutineContext()[Job]!!
        execution.put("runId",runId).put("srcWidth",src.width).put("srcHeight",src.height)
        // The generation belongs to the decoded source. A page deletion between decode and
        // engine registration must invalidate this request instead of letting it adopt the new generation.
        if(SrStore.generation(mangaId,page,mangaKey)!=expectedGeneration)
            return@withContext SrOutcome.fail(SrFailReason.CANCELLED)
        if(inFlight.putIfAbsent(k,job)!=null)return@withContext SrOutcome.fail(SrFailReason.BUSY)
        // Pair with deletePage's generation bump followed by cancelPage: if deletion landed
        // just before registration, reject here; if it lands after, cancelPage sees this job.
        if(SrStore.generation(mangaId,page,mangaKey)!=expectedGeneration) {
            inFlight.remove(k,job)
            return@withContext SrOutcome.fail(SrFailReason.CANCELLED)
        }
        var feed: Bitmap=src
        var output: Bitmap?=null
        var engine: NcnnSrEngine?=null
        var token=0L
        var heartbeat: Job?=null
        var stage="waiting"
        fun elapsed(since: Long)=(System.nanoTime()-since)/1_000_000
        try {
            val prefs=PreferenceManager.getDefaultSharedPreferences(context)
            if(!SrSettings.isEnabledForReader(prefs))return@withContext SrOutcome.fail(SrFailReason.DISABLED)
            if(src.width<=0 || src.height<=0)return@withContext SrOutcome.fail(SrFailReason.PAGE_LOAD_FAILED)
            while(token==0L) {
                ensureActive()
                if(!OcrLock.isRunning && !OcrLock.hasOcrDemand())token=OcrLock.acquire(currentCoroutineContext()[Job], "super resolution")
                if(token==0L)delay(50)
            }
            // The detached heartbeat is cancelled only after native processing exits.
            heartbeat=OcrLock.startHeartbeat(token)
            execution.put("waitMs",elapsed(begin))
            stage="initialization"
            var time=System.nanoTime()
            val pick=SuperResolutionEngines.obtain(context,prefs,request,retain=true)
            execution.put("initMs",elapsed(time))
            engine=pick.engine as? NcnnSrEngine
            val selected=engine ?: return@withContext SrOutcome.fail(pick.reason ?: SrFailReason.ENGINE_INIT_FAILED,pick.detail)
            execution.put("native",selected.diagnostics)
            ensureActive()
            stage="preprocessing";time=System.nanoTime()
            val plan=SrDownscale.plan(src.width,src.height,maxInputMegapixels=request.options.maxInputMegapixels)
            if(plan!=null) {
                feed=SrDownscale.apply(src,plan)
                if(feed.width!=plan.width || feed.height!=plan.height)
                    return@withContext SrOutcome.fail(SrFailReason.OUT_OF_MEMORY,"input resize failed; lower input MP threshold")
            }
            execution.put("inputWidth",feed.width).put("inputHeight",feed.height).put("preprocessMs",elapsed(time))
            ensureActive()
            stage="inference";time=System.nanoTime()
            output=selected.upscaleCancellable(feed,request.options.tileSleepMs,runId)
            execution.put("processingMs",elapsed(time)).put("inferenceMs",selected.diagnostics.optLong("inferenceMs"))
                .put("rgbPackMs",selected.diagnostics.optLong("rgbPackMs")).put("rgbUnpackMs",selected.diagnostics.optLong("rgbUnpackMs"))
                .put("native",selected.diagnostics)
            ensureActive()
            val product=output ?: return@withContext SrOutcome.fail(
                if(selected.failureCode == -100)SrFailReason.OUT_OF_MEMORY else SrFailReason.INFERENCE_FAILED,selected.lastFailDetail())
            if(product.width.toLong()!=feed.width.toLong()*selected.scale || product.height.toLong()!=feed.height.toLong()*selected.scale)
                return@withContext SrOutcome.fail(SrFailReason.INFERENCE_FAILED,"output dimensions do not match actual model input")
            execution.put("outputWidth",product.width).put("outputHeight",product.height)
            stage="saving";time=System.nanoTime()
            execution.put("totalMs",elapsed(begin)+execution.optLong("decodeMs"))
            val saved=SrStore.save(context,mangaId,page,product,request.model!!.name,mangaKey,
                request.fingerprint,execution,expectedGeneration) {job.isActive}
            execution.put("saveMs",elapsed(time))
            if(!saved) {ensureActive();return@withContext SrOutcome.fail(SrFailReason.SAVE_FAILED,"atomic output commit failed")}
            SrOutcome.stored()
        } catch(e: CancellationException) {throw e}
          catch(e: OutOfMemoryError) {SrOutcome.fail(SrFailReason.OUT_OF_MEMORY,"$stage: memory allocation failed")}
          catch(t: Throwable) {SrOutcome.fail(SrFailReason.EXCEPTION,"$stage: ${t.javaClass.simpleName}: ${t.message}")}
        finally {
            execution.put("stage",stage).put("totalMs",elapsed(begin)+execution.optLong("decodeMs"))
            heartbeat?.cancel()
            if(token!=0L)OcrLock.release(token)
            engine?.releaseLease()
            if(feed!==src && !feed.isRecycled)feed.recycle()
            output?.let {if(it!==src && !it.isRecycled)it.recycle()}
            inFlight.remove(k,job)
        }
    }
}
