package com.moe.moetranslator.manga

import android.graphics.Bitmap
import android.graphics.Rect
import com.moe.moetranslator.bridge.OCRBridge
import com.moe.moetranslator.bridge.TextBlockInfo
import com.moe.moetranslator.utils.LogCollector

/**
 * 统一检测桥接层。
 *
 * 支持三种检测器：ML Kit、DBNet、CTD
 * 支持三种 OCR 引擎：ML Kit、manga-ocr、48px_ctc
 */
object DetectionBridge {

    private const val TAG = "DetectionBridge"

    private const val BATCH_SIZE = 16

    /**
     * 使用 DBNet 检测文字区域，然后用指定引擎识别文字。
     *
     * 流程对齐 manga-image-translator：
     * Detection → OCR（逐个 box，批量编码）→ Textline Merge（几何+文字合并）
     */
    suspend fun detectWithDBNet(
        bitmap: Bitmap,
        language: String,
        ocrEngine: OcrEngine
    ): List<TextBlockInfo> {
        try {
            // Step 1: DBNet 检测文字区域（QuadBox，保留旋转信息）
            LogCollector.d(TAG, "使用 DBNet 检测文字区域...")
            val rawQuadBoxes = DBNetDetector.detectQuadBoxes(bitmap)
            if (rawQuadBoxes.isEmpty()) {
                LogCollector.d(TAG, "DBNet 未检测到文字区域")
                return emptyList()
            }
            LogCollector.d(TAG, "DBNet 检测到 ${rawQuadBoxes.size} 个文字区域")

            // Step 2: 逐个 box OCR（批量编码，对齐 manga-image-translator 的 OCR 在合并之前）
            val textQuadBoxes = recognizeQuadBoxes(bitmap, rawQuadBoxes, language, ocrEngine)
            LogCollector.d(TAG, "OCR 完成: ${rawQuadBoxes.size} 个 box → ${textQuadBoxes.size} 个有文字的 box")

            if (textQuadBoxes.isEmpty()) {
                LogCollector.d(TAG, "OCR 未识别到任何文字")
                return emptyList()
            }

            // Step 3: 合并（有文字信息，能做更好的判断，对齐 merge_bboxes_text_region）
            val mergedGroups = BoxMerger.merge(textQuadBoxes)
            LogCollector.d(TAG, "box 合并: ${textQuadBoxes.size} → ${mergedGroups.size} 个文本行")

            // Step 4: 构建结果（合并后的文字拼接）
            val results = mutableListOf<TextBlockInfo>()
            for (group in mergedGroups) {
                val rect = computeBoundingRect(group)
                val combinedText = group.joinToString("") { it.text }
                if (combinedText.isNotBlank()) {
                    results.add(TextBlockInfo(
                        text = combinedText,
                        boundingBox = rect,
                        cornerPoints = null
                    ))
                }
                LogCollector.d(TAG, "合并结果: rect=$rect, text='$combinedText'")
            }

            LogCollector.d(TAG, "DBNet 检测完成，共 ${results.size} 个文字块")
            return results

        } catch (e: Exception) {
            LogCollector.e(TAG, "DBNet 检测失败", e)
            throw e
        }
    }

    /**
     * 对每个 QuadBox 裁剪并识别。
     *
     * 对齐 manga-image-translator 的 OCR 阶段：
     * - 每个 box 独立识别（不做合并）
     * - 跳过空白识别结果
     */
    private suspend fun recognizeQuadBoxes(
        bitmap: Bitmap,
        quadBoxes: List<QuadBox>,
        language: String,
        ocrEngine: OcrEngine
    ): List<QuadBox> {
        // 透视变换裁剪（对齐 Python get_transformed_region）
        // 使用 QuadBox.direction（由 sort_pnts 确定），不再使用启发式判断
        val croppedBitmaps = quadBoxes.mapIndexed { idx, qb ->
            val crop = qb.getTransformedRegion(bitmap, qb.direction, 48)
            LogCollector.d(TAG, "Crop[$idx]: AABB=${qb.aabb.width()}x${qb.aabb.height()}, cropSize=${crop.width}x${crop.height}, direction=${qb.direction}")
            crop
        }

        try {
            val result = mutableListOf<QuadBox>()

            when (ocrEngine) {
                OcrEngine.CTC_48PX -> {
                    if (CtcOcrRecognizer.isInitialized) {
                        val textProbPairs = recognizeCtcOcrBatchedWithProb(croppedBitmaps)
                        for (i in quadBoxes.indices) {
                            val (text, prob) = textProbPairs[i]
                            if (text.isNotBlank() && prob >= 0.5f) {
                                result.add(QuadBox(ptsInput = quadBoxes[i].pts, text = text, prob = prob))
                            }
                        }
                    } else {
                        LogCollector.w(TAG, "48px_ctc 未初始化，回退到 ML Kit")
                        for (i in croppedBitmaps.indices) {
                            val text = try { OCRBridge.recognizeText(language, croppedBitmaps[i]) }
                                catch (e: Exception) { LogCollector.e(TAG, "ML Kit 识别失败", e); "" }
                            if (text.isNotBlank()) {
                                result.add(QuadBox(ptsInput = quadBoxes[i].pts, text = text, prob = quadBoxes[i].prob))
                            }
                        }
                    }
                }
                OcrEngine.MANGA_OCR -> {
                    val texts = if (MangaOcrRecognizer.isInitialized) {
                        recognizeMangaOcrBatched(croppedBitmaps)
                    } else {
                        LogCollector.w(TAG, "manga-ocr 未初始化，回退到 ML Kit")
                        croppedBitmaps.map { cropped ->
                            try { OCRBridge.recognizeText(language, cropped) }
                            catch (e: Exception) { LogCollector.e(TAG, "ML Kit 识别失败", e); "" }
                        }
                    }
                    for (i in quadBoxes.indices) {
                        val text = texts[i].trim()
                        if (text.isNotBlank()) {
                            result.add(QuadBox(ptsInput = quadBoxes[i].pts, text = text, prob = quadBoxes[i].prob))
                        }
                    }
                }
                OcrEngine.MLKIT -> {
                    for (i in croppedBitmaps.indices) {
                        val text = try { OCRBridge.recognizeText(language, croppedBitmaps[i]) }
                            catch (e: Exception) { LogCollector.e(TAG, "ML Kit 识别失败", e); "" }
                        if (text.isNotBlank()) {
                            result.add(QuadBox(ptsInput = quadBoxes[i].pts, text = text, prob = quadBoxes[i].prob))
                        }
                    }
                }
            }
            return result

        } finally {
            // 释放裁剪的图片
            for (cropped in croppedBitmaps) {
                if (cropped !== bitmap) cropped.recycle()
            }
        }
    }

    /**
     * 使用 manga-ocr 批量识别。
     */
    private suspend fun recognizeMangaOcrBatched(bitmaps: List<Bitmap>): List<String> {
        val results = mutableListOf<String>()
        for (chunk in bitmaps.chunked(BATCH_SIZE)) {
            results.addAll(MangaOcrRecognizer.recognizeBatch(chunk))
        }
        return results
    }

    /**
     * 使用 48px_ctc 批量识别。
     */
    private suspend fun recognizeCtcOcrBatched(bitmaps: List<Bitmap>): List<String> {
        val results = mutableListOf<String>()
        for (chunk in bitmaps.chunked(BATCH_SIZE)) {
            results.addAll(CtcOcrRecognizer.recognizeBatch(chunk))
        }
        return results
    }

    /**
     * 使用 48px_ctc 批量识别，返回文字和概率。
     */
    private suspend fun recognizeCtcOcrBatchedWithProb(bitmaps: List<Bitmap>): List<Pair<String, Float>> {
        val results = mutableListOf<Pair<String, Float>>()
        for (chunk in bitmaps.chunked(BATCH_SIZE)) {
            results.addAll(CtcOcrRecognizer.recognizeBatchWithProb(chunk))
        }
        return results
    }

    private fun computeBoundingRect(group: List<QuadBox>): Rect {
        var left = Int.MAX_VALUE; var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE; var bottom = Int.MIN_VALUE
        for (qb in group) {
            val aabb = qb.aabb
            left = minOf(left, aabb.left)
            top = minOf(top, aabb.top)
            right = maxOf(right, aabb.right)
            bottom = maxOf(bottom, aabb.bottom)
        }
        return Rect(left, top, right, bottom)
    }

    /**
     * 使用 CTD 检测文字区域，然后用指定引擎识别文字。
     */
    suspend fun detectWithCTD(
        bitmap: Bitmap,
        language: String,
        ocrEngine: OcrEngine
    ): List<TextBlockInfo> {
        try {
            // Step 1: CTD 检测文字区域
            LogCollector.d(TAG, "使用 CTD 检测文字区域...")
            val quadBoxes = CTDDetector.detectQuadBoxes(bitmap)
            if (quadBoxes.isEmpty()) {
                LogCollector.d(TAG, "CTD 未检测到文字区域")
                return emptyList()
            }
            LogCollector.d(TAG, "CTD 检测到 ${quadBoxes.size} 个文字区域")

            // Step 2: 调试打印每个 QuadBox 的 AABB 尺寸
            for ((idx, qb) in quadBoxes.withIndex()) {
                val aabb = qb.aabb
                LogCollector.d(TAG, "QuadBox[$idx]: AABB=${aabb.width()}x${aabb.height()}, prob=${String.format("%.3f", qb.prob)}")
            }
            // Step 3: OCR（MLKit 需要扩大检测框，其他引擎直接使用原始框）
            val ocrBoxes = if (ocrEngine == OcrEngine.MLKIT) {
                // ML Kit 有 32px 最小尺寸限制，扩大检测框
                val EXPAND_RATIO = 3.0f
                quadBoxes.mapIndexed { idx, qb ->
                    val aabb = qb.aabb
                    val cx = (aabb.left + aabb.right) / 2f
                    val cy = (aabb.top + aabb.bottom) / 2f
                    val ew = aabb.width() * EXPAND_RATIO
                    val eh = aabb.height() * EXPAND_RATIO
                    val newLeft = (cx - ew / 2).toInt().coerceAtLeast(0)
                    val newTop = (cy - eh / 2).toInt().coerceAtLeast(0)
                    val newRight = (cx + ew / 2).toInt().coerceAtMost(bitmap.width)
                    val newBottom = (cy + eh / 2).toInt().coerceAtMost(bitmap.height)
                    LogCollector.d(TAG, "Expand[$idx]: ${aabb.width()}x${aabb.height()} → ${newRight - newLeft}x${newBottom - newTop}")
                    QuadBox.fromRect(android.graphics.Rect(newLeft, newTop, newRight, newBottom))
                }
            } else {
                quadBoxes
            }
            val textQuadBoxes = recognizeQuadBoxes(bitmap, ocrBoxes, language, ocrEngine)
            LogCollector.d(TAG, "OCR 完成: ${quadBoxes.size} → ${textQuadBoxes.size} 个有文字的区域")

            if (textQuadBoxes.isEmpty()) {
                LogCollector.d(TAG, "OCR 未识别到任何文字")
                return emptyList()
            }

            // Step 4: BoxMerger 合并相邻 box（对齐 manga-image-translator 的 textline_merge）
            val mergedGroups = BoxMerger.merge(textQuadBoxes)
            LogCollector.d(TAG, "box 合并: ${textQuadBoxes.size} → ${mergedGroups.size} 个文本行")

            // Step 5: 构建结果（合并后的文字拼接）
            val results = mutableListOf<TextBlockInfo>()
            for (group in mergedGroups) {
                val rect = computeBoundingRect(group)
                val combinedText = group.joinToString("") { it.text }
                if (combinedText.isNotBlank()) {
                    results.add(TextBlockInfo(
                        text = combinedText,
                        boundingBox = rect,
                        cornerPoints = null
                    ))
                }
                LogCollector.d(TAG, "CTD 合并结果: rect=$rect, text='$combinedText'")
            }

            LogCollector.d(TAG, "CTD 检测完成，共 ${results.size} 个文字块")
            return results

        } catch (e: Exception) {
            LogCollector.e(TAG, "CTD 检测失败", e)
            throw e
        }
    }

    /**
     * CTD + manga-ocr 独立流程
     * CTD 检测 → 按 Y 分组 + 组内合并 → 扩展宽度 → manga-ocr 识别
     */
    suspend fun detectWithCTDManga(
        bitmap: Bitmap,
        language: String
    ): List<TextBlockInfo> {
        try {
            // Step 1: CTD 简化检测，只返回 AABB 列表
            LogCollector.d(TAG, "使用 CTD(简化) 检测文字区域...")
            val rects = CTDDetector.detectRectsSimple(bitmap)
            if (rects.isEmpty()) {
                LogCollector.d(TAG, "CTD(简化) 未检测到文字区域")
                return emptyList()
            }
            LogCollector.d(TAG, "CTD(简化) 检测到 ${rects.size} 个文字区域")
            for ((idx, rect) in rects.withIndex()) {
                LogCollector.d(TAG, "CTD(简化) 检测[$idx]: rect=[${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom}](${rect.width()}x${rect.height()})")
            }

            // 临时对比：同时运行 ML Kit 检测
            LogCollector.d(TAG, "========== CTD vs ML Kit 对比日志 ==========")
            val mlKitBlocks = OCRBridge.recognizeWithLocation(language, bitmap)
            LogCollector.d(TAG, "ML Kit 检测到 ${mlKitBlocks.size} 个文字区域")
            for ((idx, block) in mlKitBlocks.withIndex()) {
                val rect = block.boundingBox
                LogCollector.d(TAG, "ML Kit 检测[$idx]: rect=[${rect?.left}, ${rect?.top}, ${rect?.right}, ${rect?.bottom}](${rect?.width()}x${rect?.height()}), text='${block.text}'")
            }
            LogCollector.d(TAG, "========== 对比日志结束 ==========")

            // Step 2: 按 Y 分组，组内按 X 合并相邻框
            val mergedRects = mergeRectsByRowThenCol(rects)
            LogCollector.d(TAG, "CTD(简化) 合并: ${rects.size} → ${mergedRects.size} 个区域")
            for ((idx, rect) in mergedRects.withIndex()) {
                LogCollector.d(TAG, "CTD(简化) 合并后[$idx]: rect=[${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom}](${rect.width()}x${rect.height()})")
            }

            // Step 3: 只扩展宽度（不扩展高度，保持行的独立性）
            val EXPAND_WIDTH = 2.0f
            val expandedRects = mergedRects.map { rect ->
                val cx = (rect.left + rect.right) / 2f
                val ew = rect.width() * EXPAND_WIDTH
                android.graphics.Rect(
                    (cx - ew / 2).toInt().coerceAtLeast(0),
                    rect.top,
                    (cx + ew / 2).toInt().coerceAtMost(bitmap.width),
                    rect.bottom
                )
            }
            LogCollector.d(TAG, "CTD(简化) 扩展后: ${expandedRects.size} 个框")
            for ((idx, rect) in expandedRects.withIndex()) {
                LogCollector.d(TAG, "CTD(简化) 扩展[$idx]: rect=[${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom}](${rect.width()}x${rect.height()})")
            }

            // Step 4: 裁剪图片并批量识别
            val croppedBitmaps = expandedRects.map { rect -> cropBitmap(bitmap, rect) }

            // Step 5: manga-ocr 批量识别
            val texts = MangaOcrRecognizer.recognizeBatch(croppedBitmaps)

            // Step 6: 构建结果
            val results = mutableListOf<TextBlockInfo>()
            for (i in expandedRects.indices) {
                val text = texts[i].trim()
                if (text.isNotBlank()) {
                    val rect = expandedRects[i]
                    results.add(TextBlockInfo(
                        text = text,
                        boundingBox = rect,
                        cornerPoints = null
                    ))
                    LogCollector.d(TAG, "CTD(简化) 识别结果[$i]: rect=[${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom}](${rect.width()}x${rect.height()}), text='$text'")
                }
            }

            // 释放裁剪的图片
            for (cropped in croppedBitmaps) {
                if (cropped !== bitmap) cropped.recycle()
            }

            LogCollector.d(TAG, "CTD(简化) + manga-ocr 完成，共 ${results.size} 个文字块")
            return results

        } catch (e: Exception) {
            LogCollector.e(TAG, "CTD(简化) + manga-ocr 失败", e)
            throw e
        }
    }

    /**
     * 按 Y 分组，组内按 X 合并相邻框
     * 1. 按 top 排序
     * 2. Y 范围重叠（或 gap ≤ 阈值）视为同一行
     * 3. 同行的框再按 X 方向合并相邻的
     */
    private fun mergeRectsByRowThenCol(rects: List<android.graphics.Rect>): List<android.graphics.Rect> {
        if (rects.isEmpty()) return emptyList()
        if (rects.size == 1) return rects

        val sorted = rects.sortedBy { it.top }
        val rows = mutableListOf<MutableList<android.graphics.Rect>>()
        var currentRow = mutableListOf<android.graphics.Rect>()
        var currentRowBottom = sorted[0].bottom

        val Y_GAP_THRESHOLD = 50 // Y 间隙超过 50px 视为不同行

        for (rect in sorted) {
            val gap = rect.top - currentRowBottom
            if (gap <= Y_GAP_THRESHOLD) {
                currentRow.add(rect)
                currentRowBottom = maxOf(currentRowBottom, rect.bottom)
            } else {
                if (currentRow.isNotEmpty()) rows.add(currentRow)
                currentRow = mutableListOf(rect)
                currentRowBottom = rect.bottom
            }
        }
        if (currentRow.isNotEmpty()) rows.add(currentRow)

        // 组内按 X 合并
        val result = mutableListOf<android.graphics.Rect>()
        for (row in rows) {
            val sortedRow = row.sortedBy { it.left }
            var merged: android.graphics.Rect? = null
            for (rect in sortedRow) {
                if (merged == null) {
                    merged = rect
                } else {
                    val gap = rect.left - merged.right
                    if (gap <= 20) { // X 间隙 ≤ 20px 合并
                        merged = android.graphics.Rect(
                            merged.left,
                            minOf(merged.top, rect.top),
                            maxOf(merged.right, rect.right),
                            maxOf(merged.bottom, rect.bottom)
                        )
                    } else {
                        result.add(merged)
                        merged = rect
                    }
                }
            }
            if (merged != null) result.add(merged)
        }
        return result
    }

    /**
     * 使用 ML Kit 检测+识别（或混合模式）。
     */
    suspend fun detectWithMLKit(
        bitmap: Bitmap,
        language: String,
        ocrEngine: OcrEngine
    ): List<TextBlockInfo> {
        return if (ocrEngine == OcrEngine.MLKIT) {
            LogCollector.d(TAG, "使用 ML Kit 检测 + 识别")
            val blocks = OCRBridge.recognizeWithLocation(language, bitmap)
            for ((idx, block) in blocks.withIndex()) {
                val rect = block.boundingBox
                LogCollector.d(TAG, "ML Kit 检测结果[$idx]: rect=[${rect?.left}, ${rect?.top}, ${rect?.right}, ${rect?.bottom}], text='${block.text}'")
            }
            blocks
        } else {
            LogCollector.d(TAG, "使用 ML Kit 检测 + $ocrEngine 识别")
            val blocks = MangaOcrBridge.recognizeWithLocation(bitmap, language)
            for ((idx, block) in blocks.withIndex()) {
                val rect = block.boundingBox
                LogCollector.d(TAG, "ML Kit + $ocrEngine 检测结果[$idx]: rect=[${rect?.left}, ${rect?.top}, ${rect?.right}, ${rect?.bottom}], text='${block.text}'")
            }
            blocks
        }
    }

    private fun cropBitmap(bitmap: Bitmap, rect: Rect): Bitmap {
        val padding = 10
        val left = maxOf(0, rect.left - padding)
        val top = maxOf(0, rect.top - padding)
        val right = minOf(bitmap.width, rect.right + padding)
        val bottom = minOf(bitmap.height, rect.bottom + padding)

        val width = right - left
        val height = bottom - top

        if (width <= 0 || height <= 0) {
            LogCollector.w(TAG, "裁剪区域无效: left=$left, top=$top, right=$right, bottom=$bottom")
            return bitmap
        }

        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }
}
