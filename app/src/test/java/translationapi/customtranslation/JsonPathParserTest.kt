package translationapi.customtranslation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 自定义翻译 API 的 JSON 响应路径（对象属性 + 数组下标）。
 *
 * 回归背景：这条路径曾被写成「按 '.' 切分、只认 JSONObject」，于是上游一直支持的
 * `data.list[0].text` 直接解析失败 —— 自定义图片/文本接口只要把译文放在数组里就用不了。
 */
@RunWith(RobolectricTestRunner::class)
class JsonPathParserTest {

    private val root = JSONObject(
        """
        {
          "code": 0,
          "data": {
            "translation": "你好世界",
            "list": [ {"text": "第一段"}, {"text": "第二段"} ],
            "items": [ [ {"value": "矩阵"} ] ]
          },
          "empty": null,
          "scalar": "just-a-string"
        }
        """.trimIndent()
    )

    @Test
    fun `reads nested object value`() {
        assertEquals("你好世界", JsonPathParser.parse(root, "data.translation"))
    }

    @Test
    fun `reads array index inside object path`() {
        // 上游文档里的典型写法：data.list[0].text
        assertEquals("第一段", JsonPathParser.parse(root, "data.list[0].text"))
        assertEquals("第二段", JsonPathParser.parse(root, "data.list[1].text"))
    }

    @Test
    fun `reads chained indexes`() {
        assertEquals("矩阵", JsonPathParser.parse(root, "data.items[0][0].value"))
    }

    @Test
    fun `json null is returned as JSONObject NULL`() {
        val value = JsonPathParser.valueOf(root, "empty")
        assertTrue("null 节点要能识别出来（调用方据此报空结果）", value === JSONObject.NULL)
    }

    @Test
    fun `missing key fails with a located message`() {
        val error = runCatching { JsonPathParser.valueOf(root, "data.missing") }.exceptionOrNull()
        assertTrue("缺键要抛 IllegalStateException 且指出位置", error is IllegalStateException)
        assertTrue(error!!.message.orEmpty().contains("data"))
    }

    @Test
    fun `array index on a non array fails`() {
        val error = runCatching { JsonPathParser.valueOf(root, "data.translation[0]") }.exceptionOrNull()
        assertTrue("对非数组取下标要失败", error is IllegalStateException)
    }

    @Test
    fun `index out of bounds fails`() {
        val error = runCatching { JsonPathParser.valueOf(root, "data.list[9].text") }.exceptionOrNull()
        assertTrue("越界要抛 IndexOutOfBoundsException", error is IndexOutOfBoundsException)
    }

    @Test
    fun `malformed path fails`() {
        assertTrue(
            "空段（a..b）是非法路径",
            runCatching { JsonPathParser.valueOf(root, "data..translation") }.exceptionOrNull()
                is IllegalArgumentException,
        )
        assertTrue(
            "缺右括号是非法路径",
            runCatching { JsonPathParser.valueOf(root, "data.list[0") }.exceptionOrNull()
                is IllegalArgumentException,
        )
    }
}
