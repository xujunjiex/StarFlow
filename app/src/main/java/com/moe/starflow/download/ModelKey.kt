package com.moe.starflow.download
import com.moe.starflow.translate.widget.*

/**
 * 所有可下载模型的统一标识。
 *
 * 命名规则：
 * - 单文件模型：单一枚举值（RT_DETR_V2、PP_OCR_V5_DET 等）
 * - 多文件模型：`*_GROUP` 后缀（MANGA_OCR_GROUP、NLLB_GROUP），由 Repository 展开成多个 FileInfo
 *
 * 用作 JSON 配置 `model_key` 字段值（`ModelKey.valueOf(keyStr)`），错误键名会被跳过。
 */
enum class ModelKey(val stableId: Int) {
    /** RT-DETR-V2 漫画气泡/文字检测模型（单文件 ~11MB） */
    RT_DETR_V2(1001),

    /** manga-ocr 识别组（encoder.onnx + decoder.onnx + vocab.txt） */
    MANGA_OCR_GROUP(1009),

    /** PP-OCRv5 文字检测模型（单文件 ~4.6MB） */
    PP_OCR_V5_DET(1002),

    /** PP-OCRv5 中文识别模型（rec_zh.onnx + rec_zh_dict.txt） */
    PP_OCR_V5_REC_ZH(1003),

    /** PP-OCRv5 英文识别模型（rec_en.onnx + rec_en_dict.txt） */
    PP_OCR_V5_REC_EN(1004),

    /** PP-OCRv5 韩文识别模型（rec_ko.onnx + rec_ko_dict.txt） */
    PP_OCR_V5_REC_KO(1005),

    /** PP-OCRv5 俄文识别模型（rec_ru.onnx + rec_ru_dict.txt） */
    PP_OCR_V5_REC_RU(1006),

    /** PP-OCRv6 medium 检测模型（单文件 ~60MB） */
    PP_OCR_V6_MEDIUM_DET(1007),

    /** PP-OCRv6 medium 识别模型（单文件 ~74MB） */
    PP_OCR_V6_MEDIUM_REC(1008),

    /** NLLB 翻译模型组（NLLB_encoder.onnx + NLLB_decoder.onnx + sentencepiece_bpe.model） */
    NLLB_GROUP(2010),

    /** Hy-MT2 本地翻译模型（Hy-MT2-1.8B-1.25Bit.gguf 单文件 ~440MB） */
    HY_MT2_GROUP(2011),

    /**
     * Hy-MT2 1.8B **Q4_K_M**（标准量化，单文件 ~1.08GB）—— 官方 HuggingFace 仓库直接下载。
     * 与 1.25-bit 一样是「可下载的内置模型」，不打进 APK；标准量化无需重打标。
     */
    HY_MT2_Q4_KM(2012);
}