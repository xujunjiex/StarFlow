package com.moe.starflow.novel.reader

import android.content.Context
import androidx.appcompat.content.res.AppCompatResources
import com.moe.starflow.R
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 三态图标的**可加载**守卫。
 *
 * 为什么值得一条测试：矢量图标的 path 写错（多一个逗号、少一个命令）不会编译失败，
 * 真机上要么直接崩、要么画出一团空白 —— 而这三枚图标是阅读器里天天点的那颗按钮。
 *
 * ⚠️ 另一条**人工**约定（测不了，写在图标注释里）：三态图标不能跟翻译按钮的
 * `ic_reader_translate` 同形 —— 它们在同一个浮层组里并排，「译文」那枚曾经直接抄了
 * 翻译按钮的字形，用户看到的就是「图标重复了」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelDisplayIconTest {

    @Test
    fun `三态图标都能解析出来`() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val ids = mapOf(
            "译文" to R.drawable.ic_display_translated,
            "原文" to R.drawable.ic_display_original,
            "双语" to R.drawable.ic_display_bilingual,
        )
        for ((name, id) in ids) {
            assertNotNull("$name 图标加载失败（path 写错了？）", AppCompatResources.getDrawable(ctx, id))
        }
    }
}
