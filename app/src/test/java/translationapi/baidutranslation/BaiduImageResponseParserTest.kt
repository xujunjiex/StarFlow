package translationapi.baidutranslation

import com.moe.starflow.translate.ImageApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BaiduImageResponseParserTest {

    @Test
    fun `content rect maps to block coordinates`() {
        val payload = BaiduImageResponseParser.parse(
            """
            {"error_code":"0","data":{
              "sumDst":"你好\n世界",
              "content":[
                {"rect":"10 20 100 40","src":"こんにちは","dst":"你好","lineCount":1},
                {"rect":"200 30 50 120","src":"世界","dst":"世界","lineCount":2}
              ]
            }}
            """.trimIndent(),
            imageWidth = 400,
            imageHeight = 300,
        )

        assertEquals("你好\n世界", payload.text)
        assertEquals(2, payload.blocks.size)
        val first = payload.blocks[0]
        assertEquals(10, first.left)
        assertEquals(20, first.top)
        assertEquals(110, first.right)
        assertEquals(60, first.bottom)
        assertEquals("你好", first.translatedText)
        assertEquals(false, first.vertical)
        assertEquals(40f, first.fontSize, 0.01f)

        val second = payload.blocks[1]
        assertTrue(second.vertical)
        assertEquals(25f, second.fontSize, 0.01f)
    }

    @Test
    fun `sumDst fallback uses block text`() {
        val payload = BaiduImageResponseParser.parse(
            """
            {"data":{"content":[
              {"rect":"0 0 10 10","src":"a","dst":"甲","lineCount":1},
              {"rect":"0 20 10 10","src":"b","dst":"乙","lineCount":1}
            ]}}
            """.trimIndent(),
            100,
            100,
        )
        assertEquals("甲\n乙", payload.text)
    }

    @Test
    fun `api error is surfaced with provider code and stage`() {
        val error = assertThrows(ImageApiException::class.java) {
            BaiduImageResponseParser.parse(
                """{"error_code":"54001","error_msg":"invalid sign"}""",
                100,
                100,
            )
        }
        assertEquals("provider", error.stage)
        assertEquals("54001", error.providerCode)
        assertTrue(error.message!!.contains("54001"))
    }

    @Test
    fun `missing data is surfaced`() {
        assertThrows(IOException::class.java) {
            BaiduImageResponseParser.parse("""{"error_code":"0"}""", 100, 100)
        }
    }
}
