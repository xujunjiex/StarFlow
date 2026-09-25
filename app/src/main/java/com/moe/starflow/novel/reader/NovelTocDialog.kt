package com.moe.starflow.novel.reader

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.moe.starflow.R
import com.moe.starflow.data.NovelChapterStat
import com.moe.starflow.novel.model.NovelChapterMeta

/**
 * 章节目录。带**翻译状态徽章**（未翻 / 部分 / 已翻）与当前章高亮。
 *
 * 全书进度由这个面板承载 —— 底部进度条只表示**章内**位置。全书几千页时一像素代表好几页，
 * 拖动毫无精度，那个信息在这里用「哪些章已翻」表达更准。
 *
 * ⚠️ 配色**跟随阅读背景深浅**而不是全局主题（与阅读器面板同一约定）：深色背景读小说时
 * 弹出一个白底目录会刺眼。`dark` 由宿主按 `reader_background` 算好传进来。
 */
object NovelTocDialog {

    fun show(
        context: Context,
        chapters: List<NovelChapterMeta>,
        stats: Map<Int, NovelChapterStat>,
        currentChapter: Int,
        dark: Boolean = false,
        onPick: (Int) -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        val labelColor = if (dark) 0xFFE2E2E4.toInt() else 0xFF333333.toInt()

        val list = ListView(context)
        list.divider = ColorDrawable(if (dark) 0x1AFFFFFF else 0x11000000)
        list.dividerHeight = (1 * density).toInt().coerceAtLeast(1)
        list.adapter = object : BaseAdapter() {
            override fun getCount() = chapters.size
            override fun getItem(position: Int) = chapters[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val pad = (12 * density).toInt()
                    setPadding(pad, pad, pad, pad)
                }
                row.removeAllViews()
                row.setBackgroundColor(
                    if (position == currentChapter) {
                        if (dark) 0x3355AEEA else 0x22007AFF
                    } else {
                        Color.TRANSPARENT
                    }
                )

                val meta = chapters[position]
                val title = TextView(context).apply {
                    text = context.getString(R.string.novel_chapter_label, position + 1) +
                        if (meta.title.isNotBlank()) "　${meta.title}" else ""
                    textSize = 14f
                    setTextColor(if (position == currentChapter) 0xFF55AEEA.toInt() else labelColor)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val st = stats[position]
                val badgeText = when {
                    st == null || st.total == 0 || st.success == 0 -> context.getString(R.string.novel_chapter_unread)
                    st.success >= st.total -> context.getString(R.string.novel_chapter_done)
                    else -> context.getString(R.string.novel_chapter_partial, st.success, st.total)
                }
                val badge = TextView(context).apply {
                    text = badgeText
                    textSize = 12f
                    setPadding((6 * density).toInt(), 0, 0, 0)
                    setTextColor(
                        when {
                            st == null || st.total == 0 || st.success == 0 -> Color.GRAY
                            st.success >= st.total -> 0xFF34C759.toInt()
                            else -> 0xFFFF9F0A.toInt()
                        }
                    )
                }

                row.addView(title)
                row.addView(badge)
                return row
            }
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.novel_toc)
            .setView(list)
            .setNegativeButton(R.string.cancel, null)
            .create()
        list.setOnItemClickListener { _, _, position, _ ->
            onPick(position)
            dialog.dismiss()
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(if (dark) R.drawable.bg_dialog_dark else R.drawable.bg_dialog_white)
        if (dark) {
            // 标题栏（系统 TextView）在深色底上是深字，这里统一重着色
            recolor(dialog.window?.decorView, labelColor, list)
        }
        // ⚠️ ListView 在 AlertDialog 里会撑满窗口，必须显式限高。
        // 不能用 android:maxHeight（那不是 View 的属性，写在 XML 上静默失效）
        val dm = context.resources.displayMetrics
        dialog.window?.setLayout((dm.widthPixels * 0.88).toInt(), (dm.heightPixels * 0.7).toInt())
    }

    /** 深色底下的重着色；跳过 [skip] 子树（列表行自己按深浅上过色了）。 */
    private fun recolor(v: View?, color: Int, skip: View?) {
        if (v == null || v === skip) return
        if (v is TextView) v.setTextColor(color)
        if (v is ViewGroup) for (i in 0 until v.childCount) recolor(v.getChildAt(i), color, skip)
    }
}
