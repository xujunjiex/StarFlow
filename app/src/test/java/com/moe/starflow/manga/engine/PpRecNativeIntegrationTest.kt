package com.moe.starflow.manga.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.moe.starflow.manga.types.RecResult
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.FloatBuffer

/** Opt-in desktop ORT validation against the actual shipped model; no Android device is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PpRecNativeIntegrationTest {
    @Test fun pinnedOutputsMatchOrdinaryInferenceAcrossBatchSizesAndRepeatedCalls() {
        assumeTrue("requires desktop ONNX Runtime", java.lang.Boolean.getBoolean("starflow.ocr.nativeTest"))
        val model = File("src/main/assets/ppocrv6/rec_v6_small.onnx")
        val dict = listOf("blank") + File("src/main/assets/ppocrv6/ppocrv6_dict.txt")
            .readLines().filter { it.isNotEmpty() } + " "
        val env = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            env.createSession(model.absolutePath, options).use { session ->
                val images = listOf(96, 64, 2048, 96, 96, 64, 2048, 96).mapIndexed { index, width ->
                    Bitmap.createBitmap(width, 48, Bitmap.Config.ARGB_8888).apply {
                        val pixels = IntArray(width * height) { offset ->
                            val x = offset % width
                            val y = offset / width
                            Color.rgb((x * 17 + index * 19) % 256,
                                (y * 13 + index * 29) % 256, (x + y * 3) % 256)
                        }
                        setPixels(pixels, 0, width, 0, 0, width, height)
                    }
                }
                try {
                    val references = images.map { reference(env, session, it, dict) }
                    assertTrue("must exercise a single output rejected by the old 32 MiB working budget",
                        references.any { it.second * 2 > 32L * 1024 * 1024 })
                    val expected = references.map { it.first }
                    for (batch in listOf(3, 1, 2)) {
                        val actual = PpRecRecognizer.recognize(env, session, images, dict, batch, "PpRecNativeTest")
                        assertEquals(expected.map { it.text }, actual.map { it.text })
                        expected.indices.forEach { assertEquals(expected[it].score, actual[it].score, 0.001f) }
                    }
                } finally {
                    images.forEach { it.recycle() }
                }
            }
        }
    }

    private fun reference(env: OrtEnvironment, session: OrtSession, image: Bitmap, dict: List<String>): Pair<RecResult, Long> {
        val input = FloatBuffer.allocate(3 * image.width * image.height)
        for (channel in 0 until 3) for (y in 0 until image.height) for (x in 0 until image.width) {
            val component = (image.getPixel(x, y) shr (16 - channel * 8)) and 255
            input.put((component / 255f - 0.5f) / 0.5f)
        }
        input.flip()
        return OnnxTensor.createTensor(env, input, longArrayOf(1, 3, 48, image.width.toLong())).use { tensor ->
            session.run(mapOf("x" to tensor)).use { result ->
                val output = result[0] as OnnxTensor
                val shape = output.info.shape
                PpRecCtcDecoder.decode(output.floatBuffer, 0, shape[1].toInt(), shape[2].toInt(), dict) to
                    shape.fold(4L) { bytes, dimension -> bytes * dimension }
            }
        }
    }
}
