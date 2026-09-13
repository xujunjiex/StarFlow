package com.moe.starflow.mangaimport.reader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.R
import com.moe.starflow.data.ImportedPageTranslation

/** 翻译面板每页一行：P{n} + 状态徽章（成功/失败/翻译中/未翻译）+ 失败原因 + 详情/跳页。 */
class ReaderPageStateAdapter(
    private val onJump: (Int) -> Unit,
    private val onDetail: (Int) -> Unit,
) : RecyclerView.Adapter<ReaderPageStateAdapter.VH>() {

    var rows: List<ImportedPageTranslation> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    class VH(item: View) : RecyclerView.ViewHolder(item)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_translate_page_state, parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = holder.itemView
        val row = rows[position]
        val context = item.context

        item.findViewById<TextView>(R.id.tv_page_label).text = "P${row.pageIndex + 1}"
        val badge = item.findViewById<TextView>(R.id.tv_state_badge)
        val stateStrRes = when (row.state) {
            ImportedPageTranslation.STATE_TRANSLATING -> R.string.reader_translate_state_translating
            ImportedPageTranslation.STATE_SUCCESS -> R.string.reader_translate_state_success
            ImportedPageTranslation.STATE_FAILED -> R.string.reader_translate_state_failed
            else -> R.string.reader_translate_state_idle
        }
        badge.setText(context.getString(stateStrRes))
        badge.setBackgroundResource(
            when (row.state) {
                ImportedPageTranslation.STATE_SUCCESS -> R.drawable.bg_state_success
                ImportedPageTranslation.STATE_FAILED -> R.drawable.bg_state_failed
                ImportedPageTranslation.STATE_TRANSLATING -> R.drawable.bg_state_translating
                else -> R.drawable.bg_state_idle
            }
        )

        val msg = item.findViewById<TextView>(R.id.tv_fail_message)
        msg.text = row.failMessage
        msg.visibility = if (!row.failMessage.isNullOrBlank()) View.VISIBLE else View.GONE

        item.findViewById<TextView>(R.id.btn_row_detail).setText(context.getString(R.string.reader_translate_row_detail))
        item.setOnClickListener { onJump(row.pageIndex) }
        item.findViewById<View>(R.id.btn_row_detail).setOnClickListener { onDetail(row.pageIndex) }
    }
}