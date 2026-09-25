package com.moe.starflow.novel.reader

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
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
 * 全书进度由这个面板承载 —— 进度条只表示**章内**位置。全书几千页时一像素代表好几页，
 * 拖动毫无精度，那个信息在这里用「哪些章已翻」表达更准。
 */
object NovelTocDialog {

    fun show(
        context: Context,
        chapters: List<NovelChapterMeta>,
        stats: Map<Int, NovelChapterStat>,
        currentChapter: Int,
        onPick: (Int) -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        val list = ListView(context)
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
                    if (position == currentChapter) 0x22007AFF else Color.TRANSPARENT
                )

                val meta = chapters[position]
                val title = TextView(context).apply {
                    text = context.getString(R.string.novel_chapter_label, position + 1) +
                        if (meta.title.isNotBlank()) "　${meta.title}" else ""
                    textSize = 14f
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
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        // ⚠️ ListView 在 AlertDialog 里会撑满窗口，必须显式限高。
        // 不能用 android:maxHeight（那不是 View 的属性，写在 XML 上静默失效）
        val dm = context.resources.displayMetrics
        dialog.window?.setLayout((dm.widthPixels * 0.88).toInt(), (dm.heightPixels * 0.7).toInt())
    }
}
