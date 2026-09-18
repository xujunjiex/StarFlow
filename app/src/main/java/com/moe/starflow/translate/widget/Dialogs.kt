/*
 * Copyright (C) 2024 murangogo
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with this library; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */

package com.moe.starflow.translate.widget
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.*
import com.moe.starflow.manga.*
import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import com.moe.starflow.R
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.ThemeManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.lang.Exception
import java.util.regex.Pattern

data class DialogResult(
    val dialog: AlertDialog,
    val listView: ListView,
    /**
     * 弹窗上下文（Service 场景是 `ThemeManager.dialogContext(service)`）。
     *
     * ⚠️ 调用方**必须**用它取字符串/查资源，不要用 service 自己的 —— 菜单里部分条目是建完之后
     * 用 `ctx` 拼出来再 `updateLabel()` 塞进去的（语言名、OCR 标签），service 自己那份取的是
     * 系统语言（App_Language=en 时会突然冒出中文）。
     */
    val context: Context,
    /** 弹窗窗口背景：按应用主题取的深浅圆角 drawable（`ThemeManager.dialogBackgroundRes`）。 */
    @DrawableRes val backgroundRes: Int
)

object Dialogs {
    /**
     * 游戏模式结果字号范围（sp）。
     * 悬浮窗填字号对话框校验 + 个性化页 summary 共用，strings 用占位符引用保持一致。
     */
    const val FONT_SIZE_MIN = 4f
    const val FONT_SIZE_MAX = 80f

    /**
     * 根据屏幕宽度 dp 计算菜单缩放因子。
     * 360dp → 0.825, 480dp+ → 1.0（不缩放）。
     */
    private fun calculateMenuScale(ctx: Context): Float {
        val dm = ctx.resources.displayMetrics
        val screenWidthDp = dm.widthPixels / dm.density
        return (0.3f + 0.7f * (screenWidthDp / 480f)).coerceIn(0.75f, 1.0f)
    }

    @SuppressLint("MissingInflatedId")
    fun menuDialog(ctx: Context, isAutoTranslating: Boolean, ocrEngineLabel: String = "", currentLangName: String = ""): DialogResult {
        // 动态构建菜单项
        val strItems = mutableListOf<String>()
        val imgItems = mutableListOf<Int>()

        // 0: 框选位置
        strItems.add(ctx.getString(R.string.game_crop_position))
        imgItems.add(R.drawable.crop_screen)
        // 1: 字体大小
        strItems.add(ctx.getString(R.string.game_font_size))
        imgItems.add(R.drawable.result_size)
        // 2: OCR 模型（动态标签）
        if (ocrEngineLabel.isNotEmpty()) {
            strItems.add(ctx.getString(R.string.game_ocr_engine_label) + "\n" + ocrEngineLabel)
            imgItems.add(R.drawable.ocr_engine)
        }
        // 3: 切换语言（动态标签）
        strItems.add(ctx.getString(R.string.game_switch_language) + "\n" + currentLangName)
        imgItems.add(R.drawable.language_switch)
        // 4: 翻译历史
        strItems.add(ctx.getString(R.string.game_translation_history))
        imgItems.add(R.drawable.ic_history)
        // 自动翻译
        if (isAutoTranslating) {
            strItems.add(ctx.getString(R.string.game_stop_auto))
            imgItems.add(R.drawable.stop_auto)
        } else {
            strItems.add(ctx.getString(R.string.game_start_auto))
            imgItems.add(R.drawable.start_auto)
        }
        // 关闭悬浮球
        strItems.add(ctx.getString(R.string.game_close_ball))
        imgItems.add(R.drawable.close_service)
        // 返回主界面
        strItems.add(ctx.getString(R.string.game_back_main))
        imgItems.add(R.drawable.back_home)

        val menuScale = calculateMenuScale(ctx)

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_floating_menu, null, false)
        applyCustomFontToTree(ctx, view)
        val img = view.findViewById<ImageView>(R.id.TitleIcon)
        val welcome = view.findViewById<TextView>(R.id.welcome)
        val lv = view.findViewById<ListView>(R.id.menu_list)
        lv.adapter = MenuDialogAdapter(ctx, strItems.toTypedArray(), imgItems.toTypedArray(), menuScale)
        if (isAutoTranslating) {
            welcome.text = ctx.getString(R.string.floating_ball_menu_1)
            img.setImageResource(R.drawable.fist_pump_star)
        } else {
            welcome.text = ctx.getString(R.string.floating_ball_menu_2)
            img.setImageResource(R.drawable.speed_star)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setView(view)
            .setCancelable(false)
            .setNegativeButton(R.string.user_cancel, null)
            .create()
        if (menuScale < 1f) {
            val iconSizePx = (55 * menuScale * ctx.resources.displayMetrics.density).toInt()
            img.layoutParams?.let { lp ->
                lp.width = iconSizePx
                lp.height = iconSizePx
                img.layoutParams = lp
            }
            welcome.textSize = 18f * menuScale
        }
        return DialogResult(dialog, lv, ctx, ThemeManager.dialogBackgroundRes(ctx))
    }

    data class HistoryItem(val time: String, val source: String, val translated: String)

    /**
     * UI 同步字体开关开启时，对 view 树递归应用自定义字体（Custom_Result_Font）。
     * Service 悬浮菜单/对话框用（Activity 已由 FontSync 的 LayoutInflater Factory2 覆盖）。
     * 开关关闭或无字体时为空操作。实现收敛到 utils/FontSync。
     */
    /** view 树套自定义字体；ctx 保留只为调用方语义完整（FontSync 用 view 自己的 context）。 */
    @Suppress("UNUSED_PARAMETER")
    fun applyCustomFontToTree(ctx: Context, view: View) {
        com.moe.starflow.utils.FontSync.applyToTree(view)
    }

    fun historyDialog(ctx: Context, items: List<HistoryItem>, onItemClick: (Int) -> Unit, onItemLongClick: ((Int) -> Unit)? = null): AlertDialog {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_floating_menu, null, false)
        applyCustomFontToTree(ctx, view)
        val img = view.findViewById<ImageView>(R.id.TitleIcon)
        val welcome = view.findViewById<TextView>(R.id.welcome)
        val lv = view.findViewById<ListView>(R.id.menu_list)
        welcome.text = ctx.getString(R.string.game_translation_history)
        img.setImageResource(R.drawable.ic_history)
        lv.adapter = HistoryListAdapter(ctx, items)
        lv.setOnItemClickListener { _, _, position, _ ->
            onItemClick(position)
        }
        if (onItemLongClick != null) {
            lv.setOnItemLongClickListener { _, _, position, _ ->
                onItemLongClick(position)
                true
            }
        }
        val dialog = AlertDialog.Builder(ctx)
            .setView(view)
            .setCancelable(true)
            .setNegativeButton(R.string.user_cancel, null)
            .create()
        return dialog
    }

    private class HistoryListAdapter(
        private val ctx: Context,
        private val items: List<HistoryItem>
    ) : BaseAdapter() {
        private val inflater = LayoutInflater.from(ctx)

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        @SuppressLint("ViewHolder")
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = inflater.inflate(R.layout.dialog_history_item, parent, false)
            // UI 同步字体：列表项惰性 inflate，Factory2 覆盖不到，这里定点应用
            com.moe.starflow.utils.FontSync.applyToTree(v)
            val item = items[position]
            v.findViewById<TextView>(R.id.history_time).text = item.time
            v.findViewById<TextView>(R.id.history_source).text = item.source
            v.findViewById<TextView>(R.id.history_translated).text = "→ " + item.translated
            return v
        }
    }

    fun mangaMenuDialog(
        ctx: Context,
        isAutoTranslating: Boolean,
        cropLabel: String,
        detModelLabel: String = ctx.getString(R.string.manga_det_mlkit),
        ocrEngineLabel: String = ctx.getString(R.string.manga_ocr_mlkit),
        currentLangName: String = ""
    ): DialogResult {
        val baseItems = if (isAutoTranslating) {
            ctx.resources.getStringArray(R.array.manga_menu_items_auto_on)
        } else {
            ctx.resources.getStringArray(R.array.manga_menu_items)
        }
        // 动态拼接当前模式
        val strlist = Array(baseItems.size) { i ->
            when (i) {
                0 -> "${baseItems[0]}\n$cropLabel"
                2 -> "${baseItems[2]}\n$detModelLabel"
                3 -> "${baseItems[3]}\n$ocrEngineLabel"
                4 -> "${baseItems[4]}\n$currentLangName"
                else -> baseItems[i]
            }
        }
        val imglist = if (isAutoTranslating) {
            arrayOf(
                R.drawable.crop_screen,
                R.drawable.result_size,
                R.drawable.model_manage,
                R.drawable.ocr_engine,
                R.drawable.language_switch,
                R.drawable.stop_auto,
                R.drawable.close_service,
                R.drawable.back_home
            )
        } else {
            arrayOf(
                R.drawable.crop_screen,
                R.drawable.result_size,
                R.drawable.model_manage,
                R.drawable.ocr_engine,
                R.drawable.language_switch,
                R.drawable.start_auto,
                R.drawable.close_service,
                R.drawable.back_home
            )
        }
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_manga_menu, null, false)
        applyCustomFontToTree(ctx, view)
        val img = view.findViewById<ImageView>(R.id.TitleIcon)
        val welcome = view.findViewById<TextView>(R.id.welcome)
        val lv = view.findViewById<ListView>(R.id.menu_list)
        lv.adapter = MenuDialogAdapter(ctx, strlist, imglist)
        if (isAutoTranslating) {
            welcome.text = ctx.getString(R.string.floating_ball_menu_1)
            img.setImageResource(R.drawable.star_cast)
        } else {
            welcome.text = ctx.getString(R.string.floating_ball_menu_2)
            img.setImageResource(R.drawable.manga_star)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setView(view)
            .setCancelable(false)
            .setNegativeButton(R.string.user_cancel, null)
            .create()
        return DialogResult(dialog, lv, ctx, ThemeManager.dialogBackgroundRes(ctx))
    }

    /**
     * 普通模式菜单（固定搭配，无独立检测器/OCR选择）
     */
    fun mangaMenuDialogSimple(
        ctx: Context,
        isAutoTranslating: Boolean,
        cropLabel: String,
        modelLabel: String,
        currentLangName: String = ""
    ): DialogResult {
        val baseItems = if (isAutoTranslating) {
            ctx.resources.getStringArray(R.array.manga_menu_items_simple_auto_on)
        } else {
            ctx.resources.getStringArray(R.array.manga_menu_items_simple)
        }
        val strlist = Array(baseItems.size) { i ->
            when (i) {
                0 -> "${baseItems[0]}\n$cropLabel"
                2 -> "${baseItems[2]}\n$modelLabel"
                3 -> "${baseItems[3]}\n$currentLangName"
                else -> baseItems[i]
            }
        }
        val imglist = if (isAutoTranslating) {
            arrayOf(
                R.drawable.crop_screen,
                R.drawable.result_size,
                R.drawable.model_manage,
                R.drawable.language_switch,
                R.drawable.stop_auto,
                R.drawable.close_service,
                R.drawable.back_home
            )
        } else {
            arrayOf(
                R.drawable.crop_screen,
                R.drawable.result_size,
                R.drawable.model_manage,
                R.drawable.language_switch,
                R.drawable.start_auto,
                R.drawable.close_service,
                R.drawable.back_home
            )
        }
        val menuScale = calculateMenuScale(ctx)

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_manga_menu, null, false)
        applyCustomFontToTree(ctx, view)
        val img = view.findViewById<ImageView>(R.id.TitleIcon)
        val welcome = view.findViewById<TextView>(R.id.welcome)
        val lv = view.findViewById<ListView>(R.id.menu_list)
        lv.adapter = MenuDialogAdapter(ctx, strlist, imglist, menuScale)
        if (isAutoTranslating) {
            welcome.text = ctx.getString(R.string.floating_ball_menu_3)
            img.setImageResource(R.drawable.sleepy_star)
        } else {
            welcome.text = ctx.getString(R.string.floating_ball_menu_2)
            img.setImageResource(R.drawable.wink_star)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setView(view)
            .setCancelable(false)
            .setNegativeButton(R.string.user_cancel, null)
            .create()
        if (menuScale < 1f) {
            val iconSizePx = (55 * menuScale * ctx.resources.displayMetrics.density).toInt()
            img.layoutParams?.let { lp ->
                lp.width = iconSizePx
                lp.height = iconSizePx
                img.layoutParams = lp
            }
            welcome.textSize = 18f * menuScale
        }
        return DialogResult(dialog, lv, ctx, ThemeManager.dialogBackgroundRes(ctx))
    }

    fun fontSizeDialog(ctx: Context, view: TextView?, onSizeSet: ((Float) -> Unit)?): AlertDialog {
        // Service 弹窗传进来的 ctx 已是 ThemeManager.dialogContext（调用方无须再套一层）
        val prefs = CustomPreference.getInstance(ctx)

        // 代码方式
//        val editText = EditText(context).apply {
//            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
//            filters = arrayOf(DecimalDigitsInputFilter())
//            hint = context.getString(R.string.font_size_hint, prefs.getFloat("Custom_Result_Font_Size", 16f).toString())
//
//            // 设置输入框的布局参数
//            val padding = TypedValue.applyDimension(
//                TypedValue.COMPLEX_UNIT_DIP,
//                16f,
//                resources.displayMetrics
//            ).toInt()
//
//            setPadding(padding, padding, padding, padding)
//        }
//
//        // 创建包含说明文字和输入框的布局
//        val layout = LinearLayout(context).apply {
//            orientation = LinearLayout.VERTICAL
//
//            // 设置线性布局的布局参数
//            val padding = TypedValue.applyDimension(
//                TypedValue.COMPLEX_UNIT_DIP,
//                16f,
//                resources.displayMetrics
//            ).toInt()
//
//            setPadding(padding, padding, padding, 0)
//
//            // 添加说明文字
//            addView(TextView(context).apply {
//                text = context.getString(R.string.font_size_float)
//                setPadding(padding, 0, padding, padding)
//            })
//
//            // 添加输入框
//            addView(editText)
//        }
        // 布局文件方式
        val layout = LayoutInflater.from(ctx).inflate(R.layout.dialog_message_edittext, null)
        applyCustomFontToTree(ctx, layout)
        layout.findViewById<TextView>(R.id.dialog_top_message).apply {
            text = ctx.getString(R.string.font_size_float) + "\n" +
                ctx.getString(R.string.font_size_range, FONT_SIZE_MIN.toInt().toString(), FONT_SIZE_MAX.toInt().toString())
        }
        val editText = layout.findViewById<EditText>(R.id.dialog_bottom_edittext).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            filters = arrayOf(DecimalDigitsInputFilter())
            hint = ctx.getString(R.string.font_size_hint, prefs.getFloat("Custom_Result_Font_Size", 16f).toString())
        }

        val res = AlertDialog.Builder(ctx)
            .setTitle(R.string.font_size_setting)
            .setView(layout)
            .setPositiveButton(R.string.save) { _, _ ->
                val sizeText = editText.text.toString()
                try {
                    val size = sizeText.toFloat()
                    // 字号范围限制：太小看不见，太大溢出屏幕
                    if (size in FONT_SIZE_MIN..FONT_SIZE_MAX) {
                        // ⚠️ 这里**不再自己写 prefs**：字号只有一个来源，由调用方经
                        // CustomFontSize / MangaFontSize 落盘（游戏与漫画是两套独立设置）
                        if (view != null){
                            MainScope().launch{
                                view.textSize = size
                            }
                        }
                        // 回调通知设置完成
                        onSizeSet?.invoke(size)
                    } else {
                        Toast.makeText(ctx, ctx.getString(R.string.font_size_invalid, FONT_SIZE_MIN.toInt().toString(), FONT_SIZE_MAX.toInt().toString()), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(ctx, ctx.getString(R.string.font_size_invalid, FONT_SIZE_MIN.toInt().toString(), FONT_SIZE_MAX.toInt().toString()), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.user_cancel, null)
            .create()
        return res
    }
}

// 输入过滤器
class DecimalDigitsInputFilter : InputFilter {
    private val pattern = Pattern.compile("[0-9]*\\.?[0-9]*")

    override fun filter(
        source: CharSequence,
        start: Int,
        end: Int,
        dest: Spanned,
        dstart: Int,
        dend: Int
    ): CharSequence? {
        val builder = StringBuilder(dest)
        builder.replace(dstart, dend, source.subSequence(start, end).toString())
        val resultString = builder.toString()

        return if (!pattern.matcher(resultString).matches()) {
            ""
        } else null
    }
}