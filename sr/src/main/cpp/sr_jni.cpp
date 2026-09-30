// :sr 的 JNI 门面 —— 超分引擎（ncnn + Vulkan）
//
// Kotlin 侧只有 `SrNcnnNative.kt` 一个门面，引擎/模型管理都在 :app 里。
//
// ## 统一接口
// **四个引擎族共用同一个 handle**，用 `family` 区分：
//   WAIFU2X / SRMD / REALCUGAN / REALESRGAN
// 调用序列：create → process* → release。
// ⚠️ handle 是**永不复用的递增 ID**，不是指针（防 ABA，见 g_live 的说明）。
//
// ## 像素契约（⚠️ 改动前必读）
//   · 输入：**紧凑 RGB8**，长度 w*h*3，由 Kotlin 侧从 Bitmap 拆出来
//   · 输出：**紧凑 RGB8**，长度 (w*scale)*(h*scale)*3
//   · 用 DirectByteBuffer 直传，**不用 jnigraphics/Bitmap**：Bitmap 是 RGBA，
//     多一个 alpha 通道会被引擎一起算（浪费 1/3 算力）；RGB 也避免依赖 libjnigraphics。
//
// ## ⚠️ 输出不要用 outMat.to_pixels()
//   引擎产出的 outimage 是 **elempack=3 的 uchar Mat**，ncnn 的 to_pixels 不支持
//   elempack=3 → 直接段错误（本地踩过）。各引擎在 Android 分支写的就是 PIXEL_RGB
//   （waifu2x.cpp:519 / srmd / realcugan / realesrgan 同构），紧凑字节直接拷即可。
//
// ## ⚠️ RealCUGAN 的 tile 决定走哪条网络分支（血泪）
//   `RealCUGAN::process()` 首行 `syncgap_needed = tilesize < max(w,h)`：
//     · 为真 → 走 process_se_*（SE 模型的**真实**通路，慢一些）
//     · 为假 → 走 classic 非-SE 分支（对 models-pro 会产出全 0 的常量图）
//   本地实测：`--tile 2000`（比图大）时，13 个模型"跑"出 100 ms 的**假数据**。
//   所以这里照官方 main.cpp 的口径给 tile 兜底（≤0 时按显存自动选），
//   并且**每次都校验输出**（非空 + 非常量），有问题直接返回 false。
//
// ## ⚠️ ncnn::create_gpu_instance() 只能调一次
//    用 std::once_flag 守住；重复调用会崩。

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "gpu.h"
#include "net.h"

#include "realcugan.h"
#include "realesrgan.h"
#include "srmd.h"
#include "waifu2x.h"

#define LOG_TAG "SrNcnn"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kFamilyWaifu2x = 0;
constexpr int kFamilySrmd = 1;
constexpr int kFamilyRealCugan = 2;
constexpr int kFamilyRealEsrgan = 3;

/** 一个引擎实例。family 决定用哪个成员。 */
struct SrHandle {
    int family = -1;
    Waifu2x* w2x = nullptr;
    SRMD* srmd = nullptr;
    RealCUGAN* cugan = nullptr;
    RealESRGAN* esrgan = nullptr;
    /** ⚠️ 同一实例可能被多处并发调用（阅读器预取 + 截图链路），串行化保护 ncnn 分配器 */
    std::mutex lock;
    /**
     * ⚠️ **生命周期保护**（改动前必读）
     *
     * `release()` 可能在与 `process()` 并发时被调用（用户切模型 / 退出阅读器）。
     * 若直接 `delete h`，在途的 `process()` 会解引用已释放的内存 → **native 段错误**。
     *
     * 完整机制在文件下方（[g_live] / [acquireHandle]）：**全局锁内的「ID 存活查表 + 在途计数」**。
     * 这里只是计数本身：
     *   · process 经 acquireHandle 登记（`users++`），用完 `users--`
     *   · release 先摘牌，再短等 `users` 归零；等不到就**故意泄漏**（记日志）
     * 宁可泄漏一个几 MB 的 net，也不制造野指针 —— 泄漏只发生在「正好在推理时切模型」，属罕见路径。
     */
    std::atomic<int> users{0};
};

std::once_flag g_gpuOnce;
int g_gpuCount = 0;
int g_heapMb = 0;

/**
 * ⚠️ **存活登记表 + 在途计数**（改动前必读，这里有两个不同的问题）
 *
 * **问题一：野指针。** 只有 `users` 计数不够 —— `process()` 拿到的是地址，
 * 它要做的第一件事就是 `h->users.fetch_add(...)`，而此刻 `release()` 可能已经
 * `delete h` 了。「读个 dead 标记」同样得先碰那块内存，所以**标记救不了自己**。
 * （Kotlin 侧 `handle = 0L` 也挡不住：在途的 upscale 可能早已把非零 handle 读进局部变量。）
 * ⇒ 把「还在世吗」与「登记在途」放进**同一把锁**里做。
 *
 * **问题二：ABA。** 若 handle 就是裸指针，`delete` 后下一次 `new` **可能复用同一地址**：
 *      T1 读到 handle=X 后被切走 → T2 release(X) 摘牌并 delete → T3 create 的 new 又落在 X 并登牌
 *      → T1 调 process(X) **命中新引擎**，拿 T1 的图去跑 T3 的模型 = 静默跑错模型。
 * ⇒ handle 改成**永不复用的递增 ID**，调用方拿到的从来不是地址，ABA 从根上不存在。
 *
 * 于是调用序列是：create 返回 ID（ID → SrHandle*）→ process(ID) 锁内查表 + 在途登记
 * → release(ID) 锁内摘牌，等在途归零再 delete。摘牌后不可能再有新的登记，
 * 因此「归零 → delete」是安全的。
 */
std::mutex g_liveLock;
std::map<jlong, SrHandle*> g_live;
jlong g_nextHandleId = 0;

/**
 * 锁内按 ID 查表并登记在途。
 * @return 存活则返回句柄且已 `users++`（调用方务必配对 `users--`）；否则返回 nullptr，
 *         此时**绝不可**再碰任何句柄内存。
 */
SrHandle* acquireHandle(jlong id) {
    std::lock_guard<std::mutex> g(g_liveLock);
    auto it = g_live.find(id);
    if (it == g_live.end()) return nullptr;
    it->second->users.fetch_add(1, std::memory_order_acq_rel);
    return it->second;
}

void ensureGpu() {
    std::call_once(g_gpuOnce, [] {
        ncnn::create_gpu_instance();
        g_gpuCount = ncnn::get_gpu_count();
        if (g_gpuCount > 0) g_heapMb = (int)ncnn::get_gpu_device(0)->get_heap_budget();
        LOGI("ncnn gpu instance created, gpuCount=%d heap=%dMB", g_gpuCount, g_heapMb);
    });
}

/** 与 nihui 各工具 main.cpp 一致的 tile 兜底策略（显存越大块越大） */
int autoTile() {
    if (g_gpuCount <= 0) return 400;
    if (g_heapMb >= 1900) return 400;
    if (g_heapMb >= 550) return 300;
    if (g_heapMb >= 190) return 200;
    return 100;
}

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

/** 引擎产出统一是紧凑 uchar RGB；校验布局再拷，避免布局变化后静默出错图 */
bool copyOut(const ncnn::Mat& out, unsigned char* dst, size_t need) {
    if (!out.data || out.elembits() != 8 || out.elemsize != 3 || out.elempack != 3) {
        LOGE("unexpected out format: bits=%d size=%zu pack=%d data=%p",
             out.elembits(), out.elemsize, out.elempack, out.data);
        return false;
    }
    const size_t have = (size_t)out.total() * (size_t)out.elempack;
    if (have < need) {
        LOGE("out too small: have=%zu need=%zu", have, need);
        return false;
    }
    memcpy(dst, out.data, need);
    return true;
}

/**
 * ⚠️ 输出健全性校验：**空跑必须暴露出来**。
 * 之前踩过的坑就是「网络分支选错 → process() 正常返回 0 但图全是 0」，
 * 结果 13 个模型都"成功"跑出假数据。这里至少拦掉「全空」和「只有一两个灰度值」两种。
 */
bool looksValid(const unsigned char* p, size_t n) {
    bool seen[256] = {false};
    int distinct = 0;
    for (size_t i = 0; i < n; i++) {
        unsigned char v = p[i];
        if (!seen[v]) {
            seen[v] = true;
            // 提前退出：正常图扫几十~几百字节就凑够 3 个灰度级，没必要扫满整张（最大 30MB）。
            // `distinct > 2` 必然蕴含「存在非零值」，所以它就是原判据的等价充分条件。
            if (++distinct > 2) return true;
        }
    }
    if (distinct <= 1) { LOGE("output is all zero — wrong network branch?"); return false; }
    LOGE("output is near-constant (%d levels) — wrong branch?", distinct);
    return false;
}

}  // namespace

extern "C" {

/** 建实例。成功返回 handle（非 0），失败返回 0。 */
JNIEXPORT jlong JNICALL
Java_com_moe_starflow_sr_ncnn_SrNcnnNative_create(
        JNIEnv* env, jclass, jint family, jstring paramPath, jstring binPath, jint gpuId) {
    ensureGpu();
    const std::string param = jstr(env, paramPath);
    const std::string bin = jstr(env, binPath);

    int gpuid = gpuId;
    if (gpuid >= 0 && g_gpuCount == 0) {
        LOGI("no vulkan device, fallback to CPU");
        gpuid = -1;
    }

    auto* h = new SrHandle();
    h->family = family;

    switch (family) {
        case kFamilyWaifu2x: {
            auto* e = new Waifu2x(gpuid, /*tta*/ false, /*num_threads*/ 2);
            if (e->load(param, bin) != 0) { LOGE("waifu2x load failed: %s", param.c_str()); delete e; delete h; return 0; }
            h->w2x = e;
            break;
        }
        case kFamilySrmd: {
            auto* e = new SRMD(gpuid, /*tta*/ false);
            if (e->load(param, bin) != 0) { LOGE("srmd load failed: %s", param.c_str()); delete e; delete h; return 0; }
            h->srmd = e;
            break;
        }
        case kFamilyRealCugan: {
            auto* e = new RealCUGAN(gpuid, /*tta*/ false, /*num_threads*/ 2);
            if (e->load(param, bin) != 0) { LOGE("realcugan load failed: %s", param.c_str()); delete e; delete h; return 0; }
            h->cugan = e;
            break;
        }
        case kFamilyRealEsrgan: {
            auto* e = new RealESRGAN(gpuid, /*tta*/ false);
            if (e->load(param, bin) != 0) { LOGE("realesrgan load failed: %s", param.c_str()); delete e; delete h; return 0; }
            h->esrgan = e;
            break;
        }
        default:
            LOGE("unknown family %d", family);
            delete h;
            return 0;
    }

    // 登牌：分配一个**永不复用**的 ID，调用方拿到的是 ID 而不是地址（消 ABA）
    jlong id;
    {
        std::lock_guard<std::mutex> g(g_liveLock);
        id = ++g_nextHandleId;
        g_live[id] = h;
    }
    LOGI("engine ready family=%d gpu=%d heap=%dMB id=%lld (%s)",
         family, gpuid, g_heapMb, (long long)id, param.c_str());
    return id;
}

/**
 * 处理一张图。
 *
 * @param inBuf  DirectByteBuffer，紧凑 RGB8，长度 w*h*3
 * @param outBuf DirectByteBuffer，紧凑 RGB8，长度 (w*scale)*(h*scale)*3
 * @param noise  语义随 family 变：waifu2x = 降噪档 -1~3；SRMD = 退化强度 -1~10；其余忽略
 * @param tileSize ≤0 时按显存自动选（与官方各 main.cpp 一致）
 */
JNIEXPORT jboolean JNICALL
Java_com_moe_starflow_sr_ncnn_SrNcnnNative_process(
        JNIEnv* env, jclass, jlong handle, jobject inBuf, jobject outBuf,
        jint w, jint ht, jint scale, jint noise, jint prepadding, jint tileSize) {
    if (handle == 0 || w <= 0 || ht <= 0 || scale <= 0) return JNI_FALSE;

    // ── 生命周期：**必须在全局锁内**完成「存活判定 + 在途登记」 ──
    // 不能先 fetch_add 再判存活：那时句柄可能已被 release 释放，自增本身就踩了野内存。
    auto* h = acquireHandle(handle);
    if (!h) return JNI_FALSE;
    struct UserGuard {
        SrHandle* h;
        ~UserGuard() { h->users.fetch_sub(1, std::memory_order_acq_rel); }
    } guard{h};

    auto* in = static_cast<unsigned char*>(env->GetDirectBufferAddress(inBuf));
    auto* out = static_cast<unsigned char*>(env->GetDirectBufferAddress(outBuf));
    if (!in || !out) { LOGE("buffer is not direct"); return JNI_FALSE; }

    std::lock_guard<std::mutex> engineLock(h->lock);

    const int ow = w * scale;
    const int oh = ht * scale;
    const size_t need = (size_t)ow * (size_t)oh * 3u;
    if (tileSize <= 0) tileSize = autoTile();

    ncnn::Mat inMat(w, ht, in, (size_t)3u, 3);
    // 引擎直接往 outimage.data 写（用完即弃），所以给一块自己的缓冲，
    // process() 之后再校验并拷进 Java 的 DirectBuffer。
    std::vector<unsigned char> tmp(need, 0);
    ncnn::Mat outMat(ow, oh, tmp.data(), (size_t)3u, 3);

    int rc = -1;
    switch (h->family) {
        case kFamilyWaifu2x:
            h->w2x->noise = noise;
            h->w2x->scale = scale;
            h->w2x->tilesize = tileSize;
            h->w2x->prepadding = prepadding;
            rc = h->w2x->process(inMat, outMat);
            break;
        case kFamilySrmd:
            h->srmd->noise = noise;
            h->srmd->scale = scale;
            h->srmd->tilesize = tileSize;
            h->srmd->prepadding = prepadding;
            rc = h->srmd->process(inMat, outMat);
            break;
        case kFamilyRealCugan:
            h->cugan->noise = noise;
            h->cugan->scale = scale;
            h->cugan->tilesize = tileSize;
            h->cugan->prepadding = prepadding;
            h->cugan->syncgap = 3;   // 与 koto 一致的省显存分段；tile<max 时生效
            rc = h->cugan->process(inMat, outMat);
            break;
        case kFamilyRealEsrgan:
            h->esrgan->scale = scale;
            h->esrgan->tilesize = tileSize;
            h->esrgan->prepadding = prepadding;
            rc = h->esrgan->process(inMat, outMat);
            break;
        default:
            return JNI_FALSE;
    }

    if (rc != 0) { LOGE("process failed rc=%d family=%d", rc, h->family); return JNI_FALSE; }

    // ⚠️ 先把产物拷出来再校验：outMat.data 可能已被引擎换成自己的内存
    if (outMat.data && outMat.data != tmp.data()) {
        LOGE("engine replaced outimage buffer — 校验前先取样");
    }
    if (!copyOut(outMat, out, need)) return JNI_FALSE;
    if (!looksValid(out, need)) return JNI_FALSE;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_moe_starflow_sr_ncnn_SrNcnnNative_release(JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return;

    // 先「摘牌」：erase 之后 acquireHandle 一律失败，不可能再有新的在途登记
    SrHandle* h;
    {
        std::lock_guard<std::mutex> g(g_liveLock);
        auto it = g_live.find(handle);
        if (it == g_live.end()) return;   // 不在世 = 重复释放，直接忽略
        h = it->second;
        g_live.erase(it);
    }

    // 等在途推理退出（最多 ~3s）。等不到就**泄漏**，绝不 delete 一个可能还在用的实例。
    for (int i = 0; i < 300 && h->users.load(std::memory_order_acquire) > 0; ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    if (h->users.load(std::memory_order_acquire) > 0) {
        LOGE("release 时仍有推理在途，**故意泄漏** handle(id=%lld) 以避免 use-after-free",
             (long long)handle);
        return;
    }
    {
        std::lock_guard<std::mutex> guard(h->lock);
        delete h->w2x;    h->w2x = nullptr;
        delete h->srmd;   h->srmd = nullptr;
        delete h->cugan;  h->cugan = nullptr;
        delete h->esrgan; h->esrgan = nullptr;
    }
    delete h;
}

/** Vulkan 设备名（诊断用）。无可用 GPU 时返回 "CPU" */
JNIEXPORT jstring JNICALL
Java_com_moe_starflow_sr_ncnn_SrNcnnNative_gpuName(JNIEnv* env, jclass) {
    ensureGpu();
    if (g_gpuCount <= 0) return env->NewStringUTF("CPU");
    const ncnn::GpuInfo& info = ncnn::get_gpu_info(0);
    return env->NewStringUTF(info.device_name());
}

JNIEXPORT jint JNICALL
Java_com_moe_starflow_sr_ncnn_SrNcnnNative_gpuCount(JNIEnv*, jclass) {
    ensureGpu();
    return g_gpuCount;
}

/** GPU 可用显存（MB）。用于展示与 tile 选择；无 GPU 时 0。 */
JNIEXPORT jint JNICALL
Java_com_moe_starflow_sr_ncnn_SrNcnnNative_heapBudgetMb(JNIEnv*, jclass) {
    ensureGpu();
    return g_heapMb;
}

/** 与原生侧一致的 tile 兜底值，便于设置页展示诊断信息 */
JNIEXPORT jint JNICALL
Java_com_moe_starflow_sr_ncnn_SrNcnnNative_autoTileSize(JNIEnv*, jclass) {
    ensureGpu();
    return autoTile();
}

}  // extern "C"
