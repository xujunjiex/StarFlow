/*
 * Copyright (C) 2024 murangogo
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with this library; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */

package translationapi.nllbtranslation

import android.content.Context
import com.moe.starflow.R
import com.moe.starflow.translate.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** NLLB is a sentence translator; it intentionally has no conversational context. */
class NLLBTranslation(context: Context) : TranslationTextAPI {
    private val ctx = context.applicationContext
    private val ready = CountDownLatch(1)
    @Volatile private var initializationError: Exception? = null
    private val requests = ConcurrentHashMap.newKeySet<LocalTranslationRequest>()
    private val core = TranslationCore(ctx, object : InitializationListener {
        override fun onInitializationComplete() { ready.countDown() }
        override fun onInitializationError(e: Exception) {
            initializationError = e
            ready.countDown()
        }
    })

    override fun getTranslation(
        text: String, sourceLanguage: String, targetLanguage: String,
        callback: (TranslationResult) -> Unit,
    ) { startTranslation(text, sourceLanguage, targetLanguage, {}, {}, callback) }

    override fun startTranslation(
        text: String, sourceLanguage: String, targetLanguage: String,
        onPhase: (String) -> Unit, onPartial: (String) -> Unit,
        callback: (TranslationResult) -> Unit,
    ): TranslationRequest {
        val request = LocalTranslationRequest(callback)
        requests.add(request)
        request.start(onFinished = { requests.remove(request) }) { task ->
            check(ready.await(120, TimeUnit.SECONDS)) { ctx.getString(R.string.initialization_not_complete) }
            initializationError?.let { throw it }
            task.ensureActive()
            onPhase("generate")
            val worker = Thread.currentThread()
            val textOut = task.abortable(abort = { worker.interrupt() }) {
                core.translateBlocking(text, CustomLocale(sourceLanguage), CustomLocale(targetLanguage)) { task.reportProgress() }
            }
            task.ensureActive()
            TranslationResult.Success(textOut)
        }
        return request
    }

    override fun cancelTranslation() { requests.toList().forEach { it.cancel() } }
    override fun release() { cancelTranslation() }
}
