package translationapi.tencentcloud

import com.moe.starflow.translate.ImageApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TencentImageResponseParserTest {

    @Test
    fun `text only response falls back to plain text`() {
        val payload = TencentImageResponseParser.parse(
            """
            {"Response":{"ImageRecord":{"Value":[
              {"SourceText":"こんにちは","TargetText":"你好"},
              {"SourceText":"世界","TargetText":"世界"}
            ]}}}
            """.trimIndent(),
            400,
            300,
        )
        assertEquals("你好\n世界", payload.text)
        assertTrue(payload.blocks.isEmpty())
    }

    @Test
    fun `line points produce a block`() {
        val payload = TencentImageResponseParser.parse(
            """
            {"Response":{"ImageRecord":{"Value":[
              {"SourceText":"abc","TargetText":"甲乙","Line":[
                {"Points":[{"X":10,"Y":20},{"X":110,"Y":20},{"X":110,"Y":60},{"X":10,"Y":60}]}
              ]}
            ]}}}
            """.trimIndent(),
            400,
            300,
        )
        assertEquals(1, payload.blocks.size)
        val block = payload.blocks.single()
        assertEquals(10, block.left)
        assertEquals(20, block.top)
        assertEquals(110, block.right)
        assertEquals(60, block.bottom)
        assertEquals("甲乙", block.translatedText)
        assertEquals(40f, block.fontSize, 0.01f)
    }

    @Test
    fun `normalized line points fall back to text instead of a 1px box`() {
        val payload = TencentImageResponseParser.parse(
            """
            {"Response":{"ImageRecord":{"Value":[
              {"SourceText":"abc","TargetText":"甲乙","Line":[
                {"Points":[{"X":0,"Y":0},{"X":1,"Y":0},{"X":1,"Y":1},{"X":0,"Y":1}]}
              ]}
            ]}}}
            """.trimIndent(),
            400,
            300,
        )
        assertTrue(payload.blocks.isEmpty())
        assertEquals("甲乙", payload.text)
    }

    @Test
    fun `api error is surfaced with provider code and stage`() {
        val error = assertThrows(ImageApiException::class.java) {
            TencentImageResponseParser.parse(
                """{"Response":{"Error":{"Code":"AuthFailure","Message":"bad key"}}}""",
                100,
                100,
            )
        }
        assertEquals("provider", error.stage)
        assertEquals("AuthFailure", error.providerCode)
        assertTrue(error.message!!.contains("AuthFailure"))
    }

    @Test
    fun `empty image record is surfaced as parse failure`() {
        val error = assertThrows(ImageApiException::class.java) {
            TencentImageResponseParser.parse("""{"Response":{"ImageRecord":{"Value":[]}}}""", 100, 100)
        }
        assertEquals("parse", error.stage)
    }
}
