package com.moe.starflow.manga.debug
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.state.*
import com.moe.starflow.manga.render.*
import com.moe.starflow.manga.merge.*

import com.moe.starflow.manga.engine.*
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Size
import android.view.View
import android.widget.ScrollView
import com.moe.starflow.manga.types.MLKitDebugResult
import com.moe.starflow.manga.types.MergeParams
import com.moe.starflow.manga.types.OcrResult
import com.moe.starflow.manga.types.RTDetrV2DebugResult
import com.moe.starflow.manga.types.TextDirection
import com.moe.starflow.manga.types.TextRegionGroup
import com.moe.starflow.utils.CustomPreference

/**
 * 调试渲染函数（纯函数）：输入截图 + 检测/识别结果，输出带调试框的 bitmap。
 * 无状态，不依赖任何服务字段；依赖全部通过参数传入。
 */
object MangaDebugOverlays {

    fun renderRTDetrV2DebugOverlay(bitmap: Bitmap, debugResult: RTDetrV2DebugResult): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(result)

        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 28f
            isAntiAlias = true
            setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
        }

        val fillPaint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.FILL
        }

        val strokePaint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 4f
        }

        // 0: bubble（无文字气泡）— 红色
        for ((idx, b) in debugResult.emptyBubbles.withIndex()) {
            fillPaint.color = android.graphics.Color.argb(50, 255, 0, 0)
            strokePaint.color = android.graphics.Color.RED
            strokePaint.strokeWidth = 2f
            canvas.drawRect(b.rect.left.toFloat(), b.rect.top.toFloat(), b.rect.right.toFloat(), b.rect.bottom.toFloat(), fillPaint)
            canvas.drawRect(b.rect.left.toFloat(), b.rect.top.toFloat(), b.rect.right.toFloat(), b.rect.bottom.toFloat(), strokePaint)
            textPaint.color = android.graphics.Color.RED
            canvas.drawText("bubble[$idx] ${String.format("%.0f%%", b.confidence * 100)}", b.rect.left.toFloat() + 4, b.rect.top.toFloat() + 24, textPaint)
        }

        // 1: text_bubble（气泡内文字）— 绿色
        for ((idx, b) in debugResult.textBubbles.withIndex()) {
            fillPaint.color = android.graphics.Color.argb(60, 0, 255, 0)
            strokePaint.color = android.graphics.Color.GREEN
            strokePaint.strokeWidth = 4f
            canvas.drawRect(b.rect.left.toFloat(), b.rect.top.toFloat(), b.rect.right.toFloat(), b.rect.bottom.toFloat(), fillPaint)
            canvas.drawRect(b.rect.left.toFloat(), b.rect.top.toFloat(), b.rect.right.toFloat(), b.rect.bottom.toFloat(), strokePaint)
            textPaint.color = android.graphics.Color.GREEN
            canvas.drawText("text_bubble[$idx] ${String.format("%.0f%%", b.confidence * 100)}", b.rect.left.toFloat() + 4, b.rect.top.toFloat() + 24, textPaint)
        }

        // 2: text_free（自由文字）— 蓝色
        for ((idx, b) in debugResult.textFree.withIndex()) {
            fillPaint.color = android.graphics.Color.argb(60, 0, 100, 255)
            strokePaint.color = android.graphics.Color.CYAN
            strokePaint.strokeWidth = 4f
            canvas.drawRect(b.rect.left.toFloat(), b.rect.top.toFloat(), b.rect.right.toFloat(), b.rect.bottom.toFloat(), fillPaint)
            canvas.drawRect(b.rect.left.toFloat(), b.rect.top.toFloat(), b.rect.right.toFloat(), b.rect.bottom.toFloat(), strokePaint)
            textPaint.color = android.graphics.Color.CYAN
            canvas.drawText("text_free[$idx] ${String.format("%.0f%%", b.confidence * 100)}", b.rect.left.toFloat() + 4, b.rect.top.toFloat() + 24, textPaint)
        }

        // 最终提交给OCR的区域 — 黄色粗框（最上层）
        for ((idx, rect) in debugResult.finalRegions.withIndex()) {
            strokePaint.color = android.graphics.Color.YELLOW
            strokePaint.strokeWidth = 6f
            canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), strokePaint)
            textPaint.color = android.graphics.Color.YELLOW
            textPaint.textSize = 28f
            canvas.drawText("OCR[$idx]", rect.left.toFloat() + 4, rect.bottom.toFloat() - 8, textPaint)
        }

        return result
    }

    fun renderMLKitDebugOverlay(original: Bitmap, result: MLKitDebugResult): Bitmap {
        val mutableBitmap = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(mutableBitmap)

        // 块级框（绿色）
        val blockPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.GREEN
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }

        // 行级框（黄色）
        val linePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.YELLOW
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }

        // 元素级框（红色）
        val elementPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.RED
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1f
            isAntiAlias = true
        }

        // 四角点圆点画笔
        val blockPointPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.GREEN
            style = android.graphics.Paint.Style.FILL
        }
        val linePointPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.YELLOW
            style = android.graphics.Paint.Style.FILL
        }
        val elementPointPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.RED
            style = android.graphics.Paint.Style.FILL
        }

        // 绘制每个块（只画框和角点，不画文字）
        for (block in result.textBlocks) {
            // 块边界框（绿色）+ 四角点
            block.blockRect?.let { rect ->
                canvas.drawRect(rect, blockPaint)
            }
            block.blockCorners?.let { corners ->
                for (pt in corners) {
                    canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 5f, blockPointPaint)
                }
            }

            // 行（黄色）+ 四角点
            for (line in block.lines) {
                line.lineRect?.let { rect ->
                    canvas.drawRect(rect, linePaint)
                }
                line.lineCorners?.let { corners ->
                    for (pt in corners) {
                        canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 3f, linePointPaint)
                    }
                }

                // 元素（红色）+ 四角点
                for (element in line.elements) {
                    element.elementRect?.let { rect ->
                        canvas.drawRect(rect, elementPaint)
                    }
                    element.elementCorners?.let { corners ->
                        for (pt in corners) {
                            canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 2f, elementPointPaint)
                        }
                    }
                }
            }
        }

        return mutableBitmap
    }

    fun renderPPOcrV5DebugWithMerge(
        bitmap: Bitmap,
        ocrResult: OcrResult,
        mergedRegions: List<TextRegionGroup>,
        debugDet: PPOcrV5Engine.DebugDetResult? = null,
        textScoreThresh: Float
    ): Bitmap {
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(output)

        // 原始检测框（绿色，细线）
        val rawPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.GREEN
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }

        // 合并区域框（青色，粗线）
        val mergedPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.CYAN
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }

        // 合并区域半透明填充
        val mergedFillPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(30, 0, 255, 255)
            style = android.graphics.Paint.Style.FILL
        }

        // 文字标签画笔
        val labelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 24f
            isAntiAlias = true
            setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
        }

        // 气泡编号标签画笔（绿色框上方，小号避免遮挡）
        val memberLabelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.YELLOW
            textSize = 22f
            isAntiAlias = true
            setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
        }

        // ① 绘制每个气泡的原始检测框（绿色）+ 气泡编号（与日志 canMerge [i] 一致）
        //    ⚠️ 不能用 ocrResult.boxes 编号：ocrResultToTextLines 过滤了空白/低分 box，
        //    导致 boxes 索引 ≠ merge 输入的 regions 索引。用 memberIndices 保证对齐。
        for (region in mergedRegions) {
            val memberIds = if (region.memberIndices.isNotEmpty()) region.memberIndices
            else (0 until region.members.size).toList()
            for ((k, memberId) in memberIds.withIndex()) {
                val member = region.members.getOrNull(k) ?: continue
                val r = member.quad.aabb
                canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), rawPaint)
                // 气泡编号（绿色框上方，与 canMerge [i] 对齐）
                canvas.drawText("[$memberId]", r.left.toFloat(), r.top.toFloat() - 6f, memberLabelPaint)
            }
        }

        // ② 绘制合并区域框（青色）+ 标签
        for ((idx, region) in mergedRegions.withIndex()) {
            val r = region.rect
            val hasTilt = kotlin.math.abs(region.angle) > 0.5f

            canvas.save()
            if (hasTilt) {
                canvas.rotate(region.angle, region.center.x, region.center.y)
            }
            canvas.drawRect(r, mergedFillPaint)
            canvas.drawRect(r, mergedPaint)

            // 标签：组编号 + 组内成员原始索引（与日志「merge: 组N 成员[i,j]」对应）
            val dirLabel = if (region.direction == TextDirection.VERTICAL_RL || region.direction == TextDirection.VERTICAL_LR) "V" else "H"
            val angleStr = if (hasTilt) " ∠${String.format("%.0f°", region.angle)}" else ""
            val memberStr = if (region.memberIndices.isNotEmpty()) {
                region.memberIndices.joinToString(",") { it.toString() }
            } else {
                (0 until region.texts.size).joinToString(",")
            }
            val label = "[$idx]$dirLabel ×${region.texts.size}{$memberStr}$angleStr"
            canvas.drawText(label, r.left.toFloat(), r.top.toFloat() - 6f, labelPaint)
            canvas.restore()
        }

        // ③ 绘制被丢弃的选区（红色虚线）
        if (debugDet != null && debugDet.discardedBoxes.isNotEmpty()) {
            val discPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.RED
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f
                isAntiAlias = true
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 4f), 0f)
            }
            val discLabelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.RED
                textSize = 20f
                isAntiAlias = true
                setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
            }
            for (i in debugDet.discardedBoxes.indices) {
                val box = debugDet.discardedBoxes[i]
                canvas.drawLine(box[0], box[1], box[2], box[3], discPaint)
                canvas.drawLine(box[2], box[3], box[4], box[5], discPaint)
                canvas.drawLine(box[4], box[5], box[6], box[7], discPaint)
                canvas.drawLine(box[6], box[7], box[0], box[1], discPaint)
                // 标签：分数 + 原因
                val score = debugDet.discardedScores.getOrElse(i) { 0f }
                val reason = debugDet.discardedReasons.getOrElse(i) { "" }
                val label = "✗${String.format("%.2f", score)} $reason"
                canvas.drawText(label, box[0], box[1] - 4f, discLabelPaint)
            }
        }

        // ④ 绘制被识别置信度丢弃的选区（橙色虚线）
        if (ocrResult.recDebug != null && ocrResult.recDebug.discardedBoxes.isNotEmpty()) {
            val recDiscPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(255, 165, 0) // 橙色
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f
                isAntiAlias = true
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f, 2f, 4f), 0f)
            }
            val recDiscFillPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(40, 255, 165, 0)
                style = android.graphics.Paint.Style.FILL
            }
            val recDiscLabelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(255, 165, 0)
                textSize = 18f
                isAntiAlias = true
                setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
            }
            for (i in ocrResult.recDebug.discardedBoxes.indices) {
                val box = ocrResult.recDebug.discardedBoxes[i]
                // 半透明填充
                val path = android.graphics.Path().apply {
                    moveTo(box[0], box[1])
                    lineTo(box[2], box[3])
                    lineTo(box[4], box[5])
                    lineTo(box[6], box[7])
                    close()
                }
                canvas.drawPath(path, recDiscFillPaint)
                canvas.drawPath(path, recDiscPaint)
                // 标签：根据丢弃原因显示
                val reason = ocrResult.recDebug.discardedReasons.getOrElse(i) { "score" }
                val label = if (reason == "score") {
                    val score = ocrResult.recDebug.discardedScores.getOrElse(i) { 0f }
                    "✗${String.format("%.2f", score)}<${String.format("%.2f", textScoreThresh)}"
                } else {
                    "✗内容:$reason"
                }
                canvas.drawText(label, box[0], box[1] - 4f, recDiscLabelPaint)
            }
        }

        return output
    }

    fun renderPPOcrV6DebugWithMerge(
        bitmap: Bitmap,
        ocrResult: OcrResult,
        mergedRegions: List<TextRegionGroup>,
        debugDet: PPOcrV6Engine.DebugDetResult? = null,
        textScoreThresh: Float
    ): Bitmap {
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(output)

        // 原始检测框（绿色，细线）
        val rawPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.GREEN
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }

        // 合并区域框（青色，粗线）
        val mergedPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.CYAN
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }

        // 合并区域半透明填充
        val mergedFillPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(30, 0, 255, 255)
            style = android.graphics.Paint.Style.FILL
        }

        // 文字标签画笔
        val labelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 24f
            isAntiAlias = true
            setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
        }

        // 气泡编号标签画笔（绿色框上方，小号避免遮挡）
        val memberLabelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.YELLOW
            textSize = 22f
            isAntiAlias = true
            setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
        }

        // ① 绘制每个气泡的原始检测框（绿色）+ 气泡编号（与日志 canMerge [i] 一致）
        //    ⚠️ 不能用 ocrResult.boxes 编号：ocrResultToTextLines 过滤了空白/低分 box，
        //    导致 boxes 索引 ≠ merge 输入的 regions 索引。用 memberIndices 保证对齐。
        for (region in mergedRegions) {
            val memberIds = if (region.memberIndices.isNotEmpty()) region.memberIndices
            else (0 until region.members.size).toList()
            for ((k, memberId) in memberIds.withIndex()) {
                val member = region.members.getOrNull(k) ?: continue
                val r = member.quad.aabb
                canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), rawPaint)
                // 气泡编号（绿色框上方，与 canMerge [i] 对齐）
                canvas.drawText("[$memberId]", r.left.toFloat(), r.top.toFloat() - 6f, memberLabelPaint)
            }
        }

        // ② 绘制合并区域框（青色）+ 标签
        for ((idx, region) in mergedRegions.withIndex()) {
            val r = region.rect
            val hasTilt = kotlin.math.abs(region.angle) > 0.5f

            canvas.save()
            if (hasTilt) {
                canvas.rotate(region.angle, region.center.x, region.center.y)
            }
            canvas.drawRect(r, mergedFillPaint)
            canvas.drawRect(r, mergedPaint)

            // 标签：组编号 + 组内成员原始索引（与日志「merge: 组N 成员[i,j]」对应）
            val dirLabel = if (region.direction == TextDirection.VERTICAL_RL || region.direction == TextDirection.VERTICAL_LR) "V" else "H"
            val angleStr = if (hasTilt) " ∠${String.format("%.0f°", region.angle)}" else ""
            val memberStr = if (region.memberIndices.isNotEmpty()) {
                region.memberIndices.joinToString(",") { it.toString() }
            } else {
                (0 until region.texts.size).joinToString(",")
            }
            val label = "[$idx]$dirLabel ×${region.texts.size}{$memberStr}$angleStr"
            canvas.drawText(label, r.left.toFloat(), r.top.toFloat() - 6f, labelPaint)
            canvas.restore()
        }

        // ③ 绘制被丢弃的选区（红色虚线）
        if (debugDet != null && debugDet.discardedBoxes.isNotEmpty()) {
            val discPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.RED
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f
                isAntiAlias = true
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 4f), 0f)
            }
            val discLabelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.RED
                textSize = 20f
                isAntiAlias = true
                setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
            }
            for (i in debugDet.discardedBoxes.indices) {
                val box = debugDet.discardedBoxes[i]
                canvas.drawLine(box[0], box[1], box[2], box[3], discPaint)
                canvas.drawLine(box[2], box[3], box[4], box[5], discPaint)
                canvas.drawLine(box[4], box[5], box[6], box[7], discPaint)
                canvas.drawLine(box[6], box[7], box[0], box[1], discPaint)
                // 标签：分数 + 原因
                val score = debugDet.discardedScores.getOrElse(i) { 0f }
                val reason = debugDet.discardedReasons.getOrElse(i) { "" }
                val label = "✗${String.format("%.2f", score)} $reason"
                canvas.drawText(label, box[0], box[1] - 4f, discLabelPaint)
            }
        }

        // ④ 绘制被识别置信度丢弃的选区（橙色虚线）
        if (ocrResult.recDebug != null && ocrResult.recDebug.discardedBoxes.isNotEmpty()) {
            val recDiscPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(255, 165, 0) // 橙色
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f
                isAntiAlias = true
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f, 2f, 4f), 0f)
            }
            val recDiscFillPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(40, 255, 165, 0)
                style = android.graphics.Paint.Style.FILL
            }
            val recDiscLabelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(255, 165, 0)
                textSize = 18f
                isAntiAlias = true
                setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
            }
            for (i in ocrResult.recDebug.discardedBoxes.indices) {
                val box = ocrResult.recDebug.discardedBoxes[i]
                // 半透明填充
                val path = android.graphics.Path().apply {
                    moveTo(box[0], box[1])
                    lineTo(box[2], box[3])
                    lineTo(box[4], box[5])
                    lineTo(box[6], box[7])
                    close()
                }
                canvas.drawPath(path, recDiscFillPaint)
                canvas.drawPath(path, recDiscPaint)
                // 标签：根据丢弃原因显示
                val reason = ocrResult.recDebug.discardedReasons.getOrElse(i) { "score" }
                val label = if (reason == "score") {
                    val score = ocrResult.recDebug.discardedScores.getOrElse(i) { 0f }
                    "✗${String.format("%.2f", score)}<${String.format("%.2f", textScoreThresh)}"
                } else {
                    "✗内容:$reason"
                }
                canvas.drawText(label, box[0], box[1] - 4f, recDiscLabelPaint)
            }
        }

        return output
    }

    /**
     * 为 debug 图片添加框选外区域遮罩。
     * cropRect 为 null（全屏模式）时直接返回原 bitmap。
     */
    fun applyCropDimming(debugBitmap: Bitmap, cropRect: RectF?, screenSize: Size): Bitmap {
        if (cropRect == null) return debugBitmap

        val screenW = screenSize.width
        val screenH = screenSize.height

        // 创建全屏 bitmap
        val fullBitmap = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(fullBitmap)

        // 绘制半透明黑色背景（全屏）
        val dimPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(150, 0, 0, 0)
        }
        canvas.drawRect(0f, 0f, screenW.toFloat(), screenH.toFloat(), dimPaint)

        // 将 debug bitmap 绘制到框选区域
        val crop = cropRect!!
        val srcRect = android.graphics.Rect(0, 0, debugBitmap.width, debugBitmap.height)
        val dstRect = android.graphics.Rect(
            crop.left.toInt(),
            crop.top.toInt(),
            crop.right.toInt(),
            crop.bottom.toInt()
        )
        canvas.drawBitmap(debugBitmap, srcRect, dstRect, null)

        return fullBitmap
    }

    /** 创建调试信息面板 view */
    fun createInfoPanelView(context: Context, lines: List<String>, scrollable: Boolean = false, maxHeight: Int = 0): View {
        val tv = android.widget.TextView(context).apply {
            text = lines.joinToString("\n")
            setTextColor(android.graphics.Color.WHITE)
            textSize = if (scrollable) 11f else 13f
            setPadding(24, 16, 24, 16)
            setBackgroundColor(android.graphics.Color.argb(200, 0, 0, 0))
            com.moe.starflow.utils.FontSync.apply(this)
        }

        return if (scrollable) {
            // 调用方必须传 maxHeight（scrollable 面板没有备用高度逻辑）
            require(maxHeight > 0) { "createInfoPanelView: scrollable 面板必须传 maxHeight" }
            MaxHeightScrollView(context, maxHeight).apply {
                addView(tv)
                isVerticalScrollBarEnabled = true
            }
        } else {
            tv
        }
    }

    /** 创建展开/折叠按钮（onToggle 必传，行为由调用方决定） */
    @SuppressLint("SetTextI18n")
    fun createToggleButton(context: Context, onToggle: () -> Unit): android.widget.TextView {
        return android.widget.TextView(context).apply {
            text = "▼"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(android.graphics.Color.argb(180, 0, 0, 0))
            com.moe.starflow.utils.FontSync.apply(this)
            setOnClickListener {
                onToggle()
            }
        }
    }

    /**
     * 构建 PP-OCRv5/v6 调试信息行（V5/V6 共用，参数化 prefs key 与 header 差异）。
     * detDiscard* 从各自引擎的 DebugDetResult 提取（字段相同但类型不同，故传列表）。
     * 原实现：MangaFloatingService.showPPOcrV5DebugResultOverlay / showPPOcrV6DebugResultOverlay。
     */
    fun buildPPOcrInfoLines(
        headerLines: List<String>,
        ocrResult: OcrResult,
        mergedRegions: List<TextRegionGroup>,
        detDiscardBoxes: List<FloatArray>,
        detDiscardScores: List<Float>,
        detDiscardReasons: List<String>,
        prefs: CustomPreference,
        detBoxKey: String,
        detBoxDefault: Float,
        unclipKey: String,
        textKey: String,
        textDefault: Float
    ): List<String> {
        return buildList {
            addAll(headerLines)
            add("图例: 绿=检测框  青=合并区  红虚线=检测分数低  橙虚线=识别/内容丢弃")
            val curBox = prefs.getFloat(detBoxKey, detBoxDefault)
            val curUnclip = prefs.getFloat(unclipKey, 1.6f)
            val curText = prefs.getFloat(textKey, textDefault)
            add("参数: box_thresh=${String.format("%.2f", curBox)}  unclip=${String.format("%.1f", curUnclip)}  text_score=${String.format("%.2f", curText)}")
            val curGap = prefs.getFloat("merge_discard_gap", MergeParams.DISCARD_CONNECTION_GAP_DEFAULT)
            add("合并参数（对齐 manga-image-translator）:")
            add("  距离门控 = ${String.format("%.1f", curGap)} (×字号, AABB距离超过则拒绝合并)")
            add("  其他参数: 字号比AA=2.0/Tilted=0.25  角度差Tilted=15°  长宽比=1.3")
            add("耗时: det=${String.format("%.2f", ocrResult.elapseList.getOrElse(0) { 0f })}s  " +
                "cls=${String.format("%.2f", ocrResult.elapseList.getOrElse(2) { 0f })}s  " +
                "rec=${String.format("%.2f", ocrResult.elapseList.getOrElse(3) { 0f })}s  " +
                "总=${String.format("%.2f", ocrResult.elapseList.getOrElse(4) { 0f })}s")
            add("━━━ 合并结果 ━━━")
            for ((idx, region) in mergedRegions.withIndex()) {
                val dirLabel = if (region.direction == TextDirection.VERTICAL_RL || region.direction == TextDirection.VERTICAL_LR) "竖排" else "横排"
                val srcCount = region.texts.size
                val merged = region.texts.joinToString("｜")
                val r = region.rect
                val angleStr = if (kotlin.math.abs(region.angle) > 0.5f) " ∠${String.format("%.1f°", region.angle)}" else ""
                add("【$idx】$dirLabel ×$srcCount$angleStr [${r.left},${r.top},${r.right},${r.bottom}]")
                add("    $merged")
            }
            add("")
            add("━━━ 原始识别 ━━━")
            for (i in ocrResult.texts.indices) {
                val text = ocrResult.texts[i]
                val score = ocrResult.scores.getOrElse(i) { 0f }
                val box = ocrResult.boxes.getOrNull(i)
                val boxStr = if (box != null && box.size >= 8) {
                    "[${box[0].toInt()},${box[1].toInt()} → ${box[4].toInt()},${box[5].toInt()}]"
                } else ""
                val angleStr = if (box != null && box.size >= 8) {
                    val topDx = box[2] - box[0]
                    val topDy = box[3] - box[1]
                    val ang = kotlin.math.atan2(topDy, topDx) * 180f / Math.PI.toFloat()
                    val finalAng = if (kotlin.math.abs(ang) <= 3f) 0f else ang
                    if (kotlin.math.abs(finalAng) > 0.5f) " ∠${String.format("%.1f°", finalAng)}" else ""
                } else ""
                add("[$i] ${String.format("%.2f", score)}$angleStr $boxStr \"$text\"")
            }
            if (detDiscardBoxes.isNotEmpty()) {
                add("")
                add("━━━ 被检测丢弃选区 (${detDiscardBoxes.size}) ━━━")
                for (i in detDiscardBoxes.indices) {
                    val box = detDiscardBoxes[i]
                    val score = detDiscardScores.getOrElse(i) { 0f }
                    val reason = detDiscardReasons.getOrElse(i) { "" }
                    add("✗[$i] ${String.format("%.2f", score)} [${box[0].toInt()},${box[1].toInt()}→${box[4].toInt()},${box[5].toInt()}] $reason")
                }
            }
            val recDisc = ocrResult.recDebug
            if (recDisc != null && recDisc.discardedBoxes.isNotEmpty()) {
                add("")
                add("━━━ 被识别/内容丢弃选区 (${recDisc.discardedBoxes.size}) ━━━")
                for (i in recDisc.discardedBoxes.indices) {
                    val box = recDisc.discardedBoxes[i]
                    val score = recDisc.discardedScores.getOrElse(i) { 0f }
                    val text = recDisc.discardedTexts.getOrElse(i) { "" }
                    val reason = recDisc.discardedReasons.getOrElse(i) { "score" }
                    val preview = text.take(20).ifEmpty { "(空)" }
                    if (reason == "score") {
                        add("✗[$i] 分数${String.format("%.2f", score)} [${box[0].toInt()},${box[1].toInt()}→${box[4].toInt()},${box[5].toInt()}] \"$preview\"")
                    } else {
                        add("✗[$i] 内容:$reason [${box[0].toInt()},${box[1].toInt()}→${box[4].toInt()},${box[5].toInt()}] \"$preview\"")
                    }
                }
            }
        }
    }

    /** 构建 ML Kit 调试信息行（原 MangaFloatingService.showMLKitDebugResultOverlay） */
    fun buildMLKitInfoLines(result: MLKitDebugResult): List<String> {
        return buildList {
            add("ML Kit 调试模式 | 块: ${result.textBlocks.size}  行: ${result.totalLines}  元素: ${result.totalElements} | 语言: ${result.detectedLanguage ?: "未知"}")
            add("绿=块  黄=行  红=元素")
            add("")
            for ((i, block) in result.textBlocks.withIndex()) {
                val text = block.blockText.take(30).replace("\n", " ")
                add("B${i}: \"$text\" ${block.language ?: ""}")
            }
        }
    }

    /** 构建 RT-DETR-V2 调试信息行（原 MangaFloatingService.showRTDetrV2DebugResultOverlay） */
    fun buildRTDetrInfoLines(debugResult: RTDetrV2DebugResult, keepTextFree: Boolean): List<String> {
        return listOf(
            "🟢 绿色 = text_bubble（${debugResult.textBubbles.size}）",
            "🔵 蓝色 = text_free（${debugResult.textFree.size}）${if (keepTextFree) "保留" else "丢弃"}",
            "🔴 红色 = bubble（${debugResult.emptyBubbles.size}）压缩15%",
            "🟡 黄色 = 最终提交OCR（${debugResult.finalRegions.size}）"
        )
    }

    /** 限制最大高度的 ScrollView */
    class MaxHeightScrollView(context: Context, private val maxHeightPx: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val limitSpec = android.view.View.MeasureSpec.makeMeasureSpec(maxHeightPx, android.view.View.MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, limitSpec)
        }
    }
}
