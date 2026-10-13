package com.moe.starflow.manga.engine

import ai.onnxruntime.OrtException
import android.app.Application
import com.moe.starflow.R
import com.moe.starflow.utils.LogCollector
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PpRecRecognizerTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun initLog() {
        LogCollector.init(context)
        LogCollector.clear()
    }

    @Test fun javaAllocationFailureIsPersistedAndDoesNotEscapeAsAnError() {
        val original = OutOfMemoryError("failed to allocate simulated output")
        val error = assertThrows(OcrMemoryException::class.java) {
            PpRecRecognizer.withMemoryGuard(context, "PpRecTest") {
                PpRecBatching.guard("output allocation shape=[1,209,18385] bytes=15369860") { throw original }
            }
        }
        assertEquals(context.getString(R.string.error_ocr_memory), error.message)
        val log = LogCollector.readLogFile()
        assertTrue(log.contains("output allocation shape=[1,209,18385]"))
        assertTrue(log.contains("bytes=15369860"))
        assertTrue(log.contains("heapMax="))
        assertTrue(log.contains("OutOfMemoryError: failed to allocate simulated output"))
    }

    @Test fun nativeAllocationFailureIsPersistedWithOriginalOrtMessage() {
        val error = assertThrows(OcrMemoryException::class.java) {
            PpRecRecognizer.withMemoryGuard(context, "PpRecTest") {
                PpRecBatching.guard("recognition inference") {
                    throw OrtException("BFCArena: Failed to allocate memory for requested buffer of size 245917760")
                }
            }
        }
        assertEquals(context.getString(R.string.error_ocr_memory), error.message)
        val log = LogCollector.readLogFile()
        assertTrue(log.contains("recognition inference"))
        assertTrue(log.contains("245917760"))
        assertTrue(log.contains("OrtException"))
    }
}
