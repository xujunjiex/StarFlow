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
    HY_MT2_Q4_KM(2012),

    // ══════════════════════════════════════════════════════════════════
    // 超分（SR）模型组 —— 单文件 ONNX，全部走下载（不打进 APK）
    //
    // 一族一个「等级」条目：AnimeJaNai HD V3.1 有 4 个等级
    // （均衡/性能 × 标准/锐化），另加 SD 紧凑档。
    // 运行时由 `SuperResolutionEngine` 按 [stableId] 反查；模型管理页「超分」Tab
    // 与 OCR 用同一套下载/状态/浏览器机制（见 ModelManagementFragment）。
    // ══════════════════════════════════════════════════════════════════

    /** AnimeJaNai HD V3.1 均衡档（2x，SPANF3 b8f64，fp16，~1.9MB） */
    SR_ANIMEJANAI_HD_BALANCED(3010),

    /** AnimeJaNai HD V3.1 性能档（2x，SPANF3 b5f48，fp16，~0.7MB，最快） */
    SR_ANIMEJANAI_HD_PERFORMANCE(3011),

    /** AnimeJaNai HD V3.1 锐化·均衡档（2x，输出更锐，改动原图更多） */
    SR_ANIMEJANAI_HD_SHARP1_BALANCED(3012),

    /** AnimeJaNai HD V3.1 锐化·性能档（2x，最快 + 更锐） */
    SR_ANIMEJANAI_HD_SHARP1_PERFORMANCE(3013),

    /** AnimeJaNai SD 紧凑档（2x，低清源向，实测最慢且最弱，默认不推荐） */
    SR_ANIMEJANAI_SD_COMPACT(3014),

    // ── waifu2x（官方模型集的 ONNX 版，`deepghs/waifu2x_onnx`）─────────────────
    // 这些是**同一条流水线的第二个模型族**：与 AnimeJaNai 是不同架构（cunet / SwinIR），
    // 「等级」这一维在这里是**降噪强度**（n0 不降噪 → n3 强力降噪），不是画质档。
    // 本地实测：cunet 一族是质量最高的（缩回 PSNR ≈ 41，比 AnimeJaNai 的 34 高 7 分）。

    // ── ncnn + Vulkan 超分（waifu2x / SRMD / Real-CUGAN / Real-ESRGAN）──────────
    // 引擎在 :sr 模块（ncnn 源码自编），模型走下载、不内置；
    // 引擎参数（family/scale/noise/prepad）取自 downloadinfo.json，不在这里硬编码。

    // waifu2x upconv_7 · 动漫（waifu2x，共 5 档）
    /** waifu2x upconv_7 · 动漫 · 不降噪 · 2x，1.06MB */
    SR_W2X_UP7_ANIME_M1(3040),
    /** waifu2x upconv_7 · 动漫 · 轻度降噪 · 2x，1.06MB */
    SR_W2X_UP7_ANIME_N0(3041),
    /** waifu2x upconv_7 · 动漫 · 中度降噪 · 2x，1.06MB */
    SR_W2X_UP7_ANIME_N1(3042),
    /** waifu2x upconv_7 · 动漫 · 强力降噪 · 2x，1.06MB */
    SR_W2X_UP7_ANIME_N2(3043),
    /** waifu2x upconv_7 · 动漫 · 极强降噪 · 2x，1.06MB */
    SR_W2X_UP7_ANIME_N3(3044),

    // waifu2x upconv_7 · 照片（waifu2x，共 5 档）
    /** waifu2x upconv_7 · 照片 · 不降噪 · 2x，1.06MB */
    SR_W2X_UP7_PHOTO_M1(3050),
    /** waifu2x upconv_7 · 照片 · 轻度降噪 · 2x，1.06MB */
    SR_W2X_UP7_PHOTO_N0(3051),
    /** waifu2x upconv_7 · 照片 · 中度降噪 · 2x，1.06MB */
    SR_W2X_UP7_PHOTO_N1(3052),
    /** waifu2x upconv_7 · 照片 · 强力降噪 · 2x，1.06MB */
    SR_W2X_UP7_PHOTO_N2(3053),
    /** waifu2x upconv_7 · 照片 · 极强降噪 · 2x，1.06MB */
    SR_W2X_UP7_PHOTO_N3(3054),

    // waifu2x cunet（waifu2x，共 5 档）
    /** waifu2x cunet · 不降噪 · 2x，2.65MB */
    SR_W2X_CUNET_M1(3020),
    /** waifu2x cunet · 轻度降噪 · 2x，2.65MB */
    SR_W2X_CUNET_N0(3021),
    /** waifu2x cunet · 中度降噪 · 2x，2.65MB */
    SR_W2X_CUNET_N1(3022),
    /** waifu2x cunet · 强力降噪 · 2x，2.65MB */
    SR_W2X_CUNET_N2(3023),
    /** waifu2x cunet · 极强降噪 · 2x，2.65MB */
    SR_W2X_CUNET_N3(3024),

    // SRMD（srmd，共 2 档）
    /** SRMD · 2x，2.89MB */
    SR_SRMD_X2(3060),
    /** SRMD · 2x，2.89MB */
    SR_SRMD_NF_X2(3061),

    // Real-CUGAN（realcugan，共 5 档）
    /** Real-CUGAN · 2x，2.46MB */
    SR_REALCUGAN_CONSERVATIVE(3070),
    /** Real-CUGAN · 2x，2.46MB */
    SR_REALCUGAN_DENOISE1X(3071),
    /** Real-CUGAN · 2x，2.46MB */
    SR_REALCUGAN_DENOISE2X(3072),
    /** Real-CUGAN · 2x，2.46MB */
    SR_REALCUGAN_DENOISE3X(3073),
    /** Real-CUGAN · 2x，2.46MB */
    SR_REALCUGAN_NODENOISE(3074),

    // Real-ESRGAN（realesrgan，共 1 档）
    /** Real-ESRGAN · 4x，17.09MB */
    SR_REALESRGAN_ANIME6B(3080);
}