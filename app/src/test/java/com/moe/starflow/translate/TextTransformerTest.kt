package com.moe.starflow.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户自定义「翻译前/后处理」脚本的守卫（纯 JVM，Rhino 是纯 Java 实现，无需 Robolectric）：
 * 预置模板、正常替换、语法错误与超时的兜底（都必须**按原文返回**，不能把翻译链路搞挂）。
 *
 * 改 [TextTransformer] 或 [TextTransformScripts] 之前先看这里锁的是什么。
 */
class TextTransformerTest {

    // ───────────────────── 预置模板 ─────────────────────

    @Test
    fun identityScriptReturnsInputUnchanged() {
        val input = "hello world"
        assertEquals(input, TextTransformer.apply(TextTransformScripts.IDENTITY_SCRIPT, input))
        // 预置模板第一条就是「什么都不做」
        assertEquals(TextTransformScripts.IDENTITY_SCRIPT, TextTransformScripts.PRESET_SCRIPTS.first())
    }

    @Test
    fun presetScriptsAndNamesStayAligned() {
        // 预置模板正文与显示名一一对应（设置页按下标取，错位会张冠李戴）
        assertEquals(
            TextTransformScripts.PRESET_SCRIPTS.size,
            TextTransformScripts.PRESET_NAME_RES.size
        )
    }

    @Test
    fun everyPresetPassesSyntaxCheck() {
        TextTransformScripts.PRESET_SCRIPTS.forEach { script ->
            assertNull("预置模板必须能通过语法检查：$script", TextTransformer.validate(script))
        }
    }

    @Test
    fun blankScriptFallsBackToIdentity() {
        assertEquals(TextTransformScripts.IDENTITY_SCRIPT, TextTransformScripts.normalize(null))
        assertEquals(TextTransformScripts.IDENTITY_SCRIPT, TextTransformScripts.normalize(""))
        assertEquals(TextTransformScripts.IDENTITY_SCRIPT, TextTransformScripts.normalize("   "))
        assertEquals("return s.trim();", TextTransformScripts.normalize("return s.trim();"))
    }

    // ───────────────────── 正常处理 ─────────────────────

    @Test
    fun scriptCanRewriteTheString() {
        assertEquals("a b", TextTransformer.apply("return s.replace(/\\s+/g, ' ');", "a   \n b"))
        assertEquals("X", TextTransformer.apply("return s.toUpperCase();", "x"))
        // 预置的「半角标点转全角」
        assertEquals("你好，世界！", TextTransformer.apply(TextTransformScripts.PRESET_SCRIPTS[3], "你好,世界!"))
    }

    // ───────────────────── 失败兜底 ─────────────────────

    @Test
    fun syntaxErrorReturnsInput() {
        val input = "原文"
        assertEquals(input, TextTransformer.apply("return s.", input))
        assertNotNull("语法错误必须能被 validate 抓到", TextTransformer.validate("return s."))
        assertNull(TextTransformer.validate("return s;"))
    }

    @Test
    fun scriptThrowingReturnsInput() {
        assertEquals("abc", TextTransformer.apply("throw new Error('boom');", "abc"))
        assertEquals("abc", TextTransformer.apply("return s.nope();", "abc"))
    }

    @Test
    fun hostJavaAccessIsBlocked() {
        // safe 作用域 + ClassShutter 全拒：脚本拿不到宿主 Java 类 → 抛错 → 按原文返回
        assertEquals("abc", TextTransformer.apply("return new java.lang.String(s);", "abc"))
    }

    @Test
    fun missingReturnValueFallsBackToInput() {
        // 忘了写 return（拿到 undefined）或写了 return null：不能把字面量 "undefined"/"null"
        // 当作译文喂给引擎 —— 那会原样上屏并写进历史。
        assertEquals("abc", TextTransformer.apply("s.trim();", "abc"))
        assertEquals("abc", TextTransformer.apply("return null;", "abc"))
        // 但正常的空字符串返回值仍然生效（用户主动清空是有意义的）
        assertEquals("", TextTransformer.apply("return '';", "abc"))
    }

    @Test
    fun runawayScriptIsAbortedAndFallsBack() {
        // 十亿次空转：没有超时保护的话这条要跑很久。用有界循环而不是 while(true)，
        // 这样即使超时保护失效（观察者没回调）测试也一定会结束，只会断言失败。
        val script = "var i = 0; while (i < 1000000000) { i = i + 1; } return 'done';"
        val start = System.currentTimeMillis()
        val out = TextTransformer.apply(script, "abc")
        val elapsed = System.currentTimeMillis() - start
        assertEquals("超时必须按原文返回", "abc", out)
        assertTrue("超时保护没有生效，实测 ${elapsed}ms", elapsed < 5_000)
    }
}
