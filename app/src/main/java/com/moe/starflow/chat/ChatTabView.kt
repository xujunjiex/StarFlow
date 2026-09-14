package com.moe.starflow.chat
import com.moe.starflow.translate.widget.*

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.moe.starflow.R
import com.moe.starflow.utils.CustomPreference
import translationapi.TranslatorFactory

/**
 * 对话 tab：顶部显示当前模型 + 问号按钮（模板），消息气泡列表，底部 Material 输入栏。
 * 问号按钮弹层需要 FragmentManager，通过 [onTemplateClick] 回调由宿主提供。
 */
class ChatTabView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private lateinit var viewModel: ChatHistoryViewModel
    private val adapter = MessageAdapter()
    private lateinit var input: EditText
    private lateinit var list: RecyclerView

    /** 问号按钮回调（宿主设置：弹模板弹层需要 FragmentManager） */
    var onTemplateClick: (() -> Unit)? = null

    init {
        orientation = LinearLayout.VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_chat_tab, this, true)
        list = findViewById(R.id.chatList)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        input = findViewById(R.id.chatInput)
        // 顶部显示当前模型（与翻译页 engineLabel 一致）
        findViewById<TextView>(R.id.chatModelDisplay).text =
            TranslatorFactory.engineLabel(context, CustomPreference.getInstance(context))
        findViewById<ImageButton>(R.id.chatTemplateButton).setOnClickListener { onTemplateClick?.invoke() }
        findViewById<MaterialButton>(R.id.chatSendButton).setOnClickListener {
            val text = input.text?.toString().orEmpty()
            if (text.isNotBlank()) {
                viewModel.sendMessage(text)
                input.setText("")
            }
        }
        findViewById<ImageButton>(R.id.chatClearButton).setOnClickListener {
            // 清空是破坏性操作：弹确认框防误触
            android.app.AlertDialog.Builder(context)
                .setMessage(context.getString(R.string.text_chat_clear_confirm))
                .setPositiveButton(context.getString(R.string.text_chat_clear)) { _, _ -> viewModel.clearAll() }
                .setNegativeButton(context.getString(R.string.user_cancel), null)
                .create().also { it.show(); it.window?.setBackgroundDrawableResource(R.drawable.dialog_background) }
        }
    }

    /** 消息更新 observer：命名引用以便 unbind 移除（observeForever 不自动移除，会泄漏 View） */
    private val messageObserver = androidx.lifecycle.Observer<List<ChatMessage>> { list ->
        adapter.submit(list)
        // 刷新当前模型显示（引擎切换后跟随）
        findViewById<TextView>(R.id.chatModelDisplay)?.text =
            TranslatorFactory.engineLabel(context, CustomPreference.getInstance(context))
        post { this.list.smoothScrollToPosition((adapter.itemCount - 1).coerceAtLeast(0)) }
    }

    fun bind(viewModel: ChatHistoryViewModel) {
        this.viewModel = viewModel
        viewModel.messages.observeForever(messageObserver)
    }

    fun unbind() {
        // observeForever 必须手动移除：否则 ViewModel（Activity 级）持有已销毁的 View 引用 → 泄漏 + 回调崩溃
        viewModel.messages.removeObserver(messageObserver)
    }

    /** 消息适配器：user 右对齐绿泡，assistant 左对齐白泡 */
    private class MessageAdapter : RecyclerView.Adapter<MessageAdapter.VH>() {
        private var items: List<ChatMessage> = emptyList()

        fun submit(list: List<ChatMessage>) { items = list; notifyDataSetChanged() }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val msg = items[position]
            holder.bubble.text = msg.content
            val lp = holder.bubble.layoutParams as LinearLayout.LayoutParams
            if (msg.role == ChatRole.USER) {
                lp.gravity = Gravity.END
                holder.bubble.setBackgroundResource(R.drawable.bg_chat_bubble_user)
                holder.bubble.setTextColor(0xFFFFFFFF.toInt())
            } else {
                lp.gravity = Gravity.START
                holder.bubble.setBackgroundResource(R.drawable.bg_chat_bubble_assistant)
                holder.bubble.setTextColor(
                    androidx.core.content.ContextCompat.getColor(holder.bubble.context, R.color.text_primary)
                )
            }
            holder.bubble.layoutParams = lp
        }

        class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
            val bubble: TextView = v.findViewById(R.id.messageBubble)
        }
    }
}
