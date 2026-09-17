// hymt2_bridge.cpp — Hy-MT2 1.25-bit 本地翻译 JNI 桥
// 引擎: llama.cpp f8b355a9e (build 9521)，链接预编译 libllama.so
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <exception>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <algorithm>
#include <cstring>
#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <unistd.h>
#include <sched.h>
#include <fcntl.h>
#include <signal.h>
#include <pthread.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <unwind.h>
#include <dlfcn.h>
#include "llama.h"
#include "ggml.h"
#include "ggml-cpu.h"

#define TAG "HyMT2Bridge"

// ══════════════════════════════════════════════════════════════════════════════
// 统一坠机记录仪：SIGSEGV/SIGABRT 崩溃 backtrace 追加写入与 Java 层 LogCollector
// 同一个文件（<logs>/starflow.log，最近 300 行滚动、丢最旧）。崩溃块追加在文件末尾、
// 不覆盖旧日志——多次崩溃的记录都保留；滚动由 Java 侧维护（崩溃处理器受
// async-signal-safe 限制，只读 fd 追加写，不做读文件解析）。崩溃处理器只使用
// async-signal-safe 函数（open/write/snprintf），不拿锁、不 malloc。
// ══════════════════════════════════════════════════════════════════════════════

// 统一日志文件（nativeSetLogFile 设置；崩溃时只读 g_log_fd，覆盖写入）
static std::mutex g_log_mutex;
static std::string g_log_path;
static int g_log_fd = -1;

// 保存 libc 原始信号处置（debuggerd 通知链路）。崩溃时恢复后 re-raise，
// 让 debuggerd 走正常路径生成 tombstone（见 crash_handler）。
static struct sigaction g_old_sa[64];

// ══════════════════════════════════════════════════════════════════════════════
// 崩溃诊断上下文：translate 线程更新，crash_handler 只读（std::atomic 保证崩溃时
// 读到一致值，不锁）。崩溃块记录这些，让用户/开发者一次性定位（prompt 是否超长/
// 是否超 n_ctx / 生成到第几个 token / 前缀缓存状态）。
// ══════════════════════════════════════════════════════════════════════════════
struct CrashDiag {
    std::atomic<bool> in_translate{false};
    std::atomic<int> prompt_chars{0};    // 当前 prompt 字符数（含全部气泡编号文本）
    std::atomic<int> prompt_tokens{0};   // 分词后 token 数
    std::atomic<int> n_ctx{0};           // 模型 context 大小
    std::atomic<int> max_tokens{0};      // 最大生成 token
    std::atomic<int> gen_token{0};       // 生成循环已到第几个 token
    std::atomic<int> prefix_cache_valid{0};
    char prompt_preview[160];            // prompt 开头预览（写一次，崩溃时可能读到部分）
};
static CrashDiag g_diag;

static const char* sig_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS:  return "SIGBUS";
        case SIGFPE:  return "SIGFPE";
        case SIGILL:  return "SIGILL";
        case SIGSYS:  return "SIGSYS";
        default:      return "UNKNOWN";
    }
}

// 崩溃信号处理器：追加 backtrace 到日志文件后，re-raise 信号让 debuggerd 接管。
// ⚠️ 关键：绝不能用 _exit 退出——那会跳过 debuggerd，系统崩溃报告（tombstone / vivo·realme 的
// "服务与反馈"）就抓不到崩溃，用户无法上报日志。写盘后把 handler 重置为默认并重新抛信号，
// 让 Android 的 crash 捕获链路正常收尾。
struct crash_bt_ctx {
    int fd;
    int depth;
    unsigned long pcs[64];
};
static _Unwind_Reason_Code crash_unwind_cb(struct _Unwind_Context* uc, void* arg) {
    auto* c = static_cast<crash_bt_ctx*>(arg);
    if (c->depth >= 64) return _URC_END_OF_STACK;
    c->pcs[c->depth++] = _Unwind_GetIP(uc);
    return _URC_NO_REASON;
}

// SIGABRT 的 abort message（debuggerd 的 tombstone 里会显示）。通过 libc 内部符号
// __libc_get_abort_message 读取（android_set_abort_message 设置的诊断文本，如 ggml_abort
// 的"Internal error"、std::terminate 的 what()）。直接 dlsym，避免链接依赖。
static const char* android_abort_message() {
    typedef const char* (*get_abort_fn)();
    static void* sym = nullptr;
    if (sym == nullptr) {
        sym = dlsym(RTLD_DEFAULT, "__libc_get_abort_message");
    }
    if (sym == nullptr) return nullptr;
    auto fn = reinterpret_cast<get_abort_fn>(sym);
    return fn ? fn() : nullptr;
}

static void crash_handler(int sig, siginfo_t* info, void* /*ucontext*/) {
    const int fd = g_log_fd;
    char line[512];
    int n = 0;
    if (fd >= 0) {
        // 文件是"最近 300 行滚动"（Java 侧维护），崩溃块追加到末尾、不覆盖旧日志：
        // 多次崩溃的记录都保留；滚动（丢最旧）由 Java 侧下次启动时做（crash_handler
        // 内只允许 async-signal-safe 操作，不做读文件解析）。O_APPEND 下 write 天然追加。
        // 崩溃时间戳（time() 是 async-signal-safe）
        char tbuf[32];
        int tn = snprintf(tbuf, sizeof(tbuf), "time=%lld\n", (long long)time(nullptr));
        write(fd, tbuf, tn);
        // 崩溃线程信息（gettid + pthread_getname_np，均 async-signal-safe）
        {
            char tbuf2[96];
            char tname[64];
            memset(tname, 0, sizeof(tname));
            if (pthread_getname_np(pthread_self(), tname, sizeof(tname)) != 0) tname[0] = 0;
            int bn = snprintf(tbuf2, sizeof(tbuf2), "tid=%ld thread=[%s]\n",
                (long)syscall(SYS_gettid), tname);
            write(fd, tbuf2, bn);
        }
        n = snprintf(line, sizeof(line),
            "\n════════ NATIVE CRASH ════════\nsig=%d (%s) si_addr=%p\n",
            sig, sig_name(sig), (info && info->si_addr != nullptr) ? info->si_addr : nullptr);
        write(fd, line, n);
        // 崩溃时内存（进程 VmRSS + 系统 MemAvailable，判断是否 OOM/内存压力相关）
        {
            const int mfd = open("/proc/self/status", O_RDONLY | O_CLOEXEC);
            if (mfd >= 0) {
                char mbuf[4096];
                const ssize_t rd = read(mfd, mbuf, sizeof(mbuf) - 1);
                close(mfd);
                if (rd > 0) {
                    mbuf[rd] = 0;
                    char* p = strstr(mbuf, "VmRSS:");
                    if (p) {
                        char* eol = strchr(p, '\n');
                        size_t len = eol ? (size_t)(eol - p) : strlen(p);
                        write(fd, "mem: ", 5);
                        write(fd, p, len);
                        write(fd, "\n", 1);
                    }
                }
            }
            const int afd = open("/proc/meminfo", O_RDONLY | O_CLOEXEC);
            if (afd >= 0) {
                char mbuf[4096];
                const ssize_t rd = read(afd, mbuf, sizeof(mbuf) - 1);
                close(afd);
                if (rd > 0) {
                    mbuf[rd] = 0;
                    char* p = strstr(mbuf, "MemAvailable:");
                    if (p) {
                        char* eol = strchr(p, '\n');
                        size_t len = eol ? (size_t)(eol - p) : strlen(p);
                        write(fd, "memavail: ", 10);
                        write(fd, p, len);
                        write(fd, "\n", 1);
                    }
                }
            }
        }
        if (sig == SIGABRT) {
            const char* am = android_abort_message();
            if (am != nullptr && am[0] != '\0') {
                write(fd, "abort_message: ", 15);
                write(fd, am, strlen(am));
                write(fd, "\n", 1);
            }
        }
        // 翻译诊断（崩溃时读全局上下文，定位 prompt 超长/超 n_ctx/生成进度）
        n = snprintf(line, sizeof(line),
            "diag: in_translate=%d prompt_chars=%d prompt_tokens=%d n_ctx=%d max_tokens=%d gen_token=%d prefix_cache=%d\n",
            (int)g_diag.in_translate.load(), (int)g_diag.prompt_chars.load(),
            (int)g_diag.prompt_tokens.load(), (int)g_diag.n_ctx.load(),
            (int)g_diag.max_tokens.load(), (int)g_diag.gen_token.load(),
            (int)g_diag.prefix_cache_valid.load());
        write(fd, line, n);
        if (g_diag.prompt_chars.load() > 0) {
            write(fd, "prompt_preview: ", 16);
            write(fd, g_diag.prompt_preview, strnlen(g_diag.prompt_preview, sizeof(g_diag.prompt_preview)));
            write(fd, "\n", 1);
        }
        // backtrace（dladdr 符号化：函数名 + .so 名 + 函数内偏移，async-signal-safe，
        // 崩溃块直接可读，无需 PC 端 addr2line）
        crash_bt_ctx ctx;
        ctx.fd = fd;
        ctx.depth = 0;
        _Unwind_Backtrace(crash_unwind_cb, &ctx);
        for (int i = 0; i < ctx.depth; ++i) {
            char bt_line[256];
            Dl_info info;
            memset(&info, 0, sizeof(info));
            const bool has_sym = dladdr((void*)ctx.pcs[i], &info) != 0 && info.dli_sname != nullptr;
            const char* sym = has_sym ? info.dli_sname : "";
            const char* fname = info.dli_fname != nullptr ? info.dli_fname : "";
            const uintptr_t off = (has_sym && info.dli_saddr != nullptr)
                ? (uintptr_t)ctx.pcs[i] - (uintptr_t)info.dli_saddr : 0;
            int bn = snprintf(bt_line, sizeof(bt_line), "  #%02d pc=%016lx  %s+0x%zx  [%s]\n",
                i, ctx.pcs[i], sym, off, fname);
            // ⚠️ snprintf 返回**本该写入**的长度，截断时它 > sizeof(bt_line)（C++ 符号名 + split-APK
            // 的 /data/app/~~X==/.../*.so 路径轻松超 256）。直接拿它当 write 长度会越界读栈，
            // 轻则把无关栈内容写进日志，重则处理器自身再触发一次段错误 → 崩溃块和 re-raise 全丢。
            if (bn > 0) {
                size_t n = (size_t)bn;
                if (n > sizeof(bt_line) - 1) n = sizeof(bt_line) - 1;
                write(fd, bt_line, n);
            }
        }
        const char* end = "════════ END CRASH ════════\n";
        write(fd, end, strlen(end));
    }
    // 恢复 libc 原始的 debuggerd handler 再 re-raise，让 debuggerd / tombstone / 系统崩溃报告
    // 正常收尾。⚠️ 关键：
    // 1) Android 的 debuggerd 依赖 libc 安装的信号处理器（debuggerd_signal_handler，通过 socket
    //    通知 crash_dump 生成 tombstone），而不是内核 core_pattern。我们的 install_crash_handlers
    //    用 sigaction 覆盖了 libc handler，必须在此恢复它，否则 debuggerd 收不到通知、不产生
    //    tombstone（实测 SIG_DFL + raise 后 crash buffer 无任何 native 记录）。
    // 2) 必须先 sigprocmask(SIG_UNBLOCK)：信号处理器运行时被处理信号自动加入线程屏蔽集，
    //    直接 raise 会让信号 pending 不触发，走 _exit 正常退出（进程不像崩溃而死，像普通退出）。
    sigset_t set;
    sigemptyset(&set);
    sigaddset(&set, sig);
    sigprocmask(SIG_UNBLOCK, &set, nullptr);
    sigaction(sig, &g_old_sa[sig], nullptr);  // 恢复 libc 原始处置（debuggerd 通知链路）
    raise(sig);
    // raise 若仍未终止进程则兜底（正常不会到这）
    _exit(128 + sig);
}

// 安装崩溃信号处理器（幂等）。SA_ONSTACK + 备用栈：栈溢出（SIGSEGV on stack）时
// 处理器在备用栈上运行，不会因无栈空间再崩。
static void install_crash_handlers() {
    static std::once_flag once;
    std::call_once(once, [] {
        // 备用栈（8 页）：崩溃发生在耗尽栈时处理器仍有空间
        static char alt_stack[SIGSTKSZ * 4];
        static bool stack_set = false;
        if (!stack_set) {
            stack_t ss;
            memset(&ss, 0, sizeof(ss));
            ss.ss_sp = alt_stack;
            ss.ss_size = sizeof(alt_stack);
            if (sigaltstack(&ss, nullptr) == 0) stack_set = true;
        }
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_sigaction = crash_handler;
        sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
        sigemptyset(&sa.sa_mask);
        const int kSignals[] = { SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGSYS, SIGTRAP };
        for (int sig : kSignals) {
            // 保存 libc 原始处置（debuggerd 链路），崩溃时恢复用
            sigaction(sig, &sa, &g_old_sa[sig]);
        }
    });
}

// 统一日志入口：logcat（文件只保留崩溃 backtrace，普通 native 日志不落盘）
static void bridge_log(int level, const char* fmt, ...) {
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    __android_log_print(level, TAG, "%s", buf);
}

#define LOGI(...) bridge_log(ANDROID_LOG_INFO, __VA_ARGS__)
#define LOGW(...) bridge_log(ANDROID_LOG_WARN, __VA_ARGS__)
#define LOGE(...) bridge_log(ANDROID_LOG_ERROR, __VA_ARGS__)

// 串行化所有解码调用，防止并发访问同一 context（漫画模式多气泡并行翻译时排队）
static std::mutex g_mutex;

// 诊断用：相对毫秒时间
static long long now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

// 创建"全核 cpumask"的 llama 线程池：给每个 worker 显式设置允许全部在线核。
// 默认线程池 cpumask 全 0 = worker 继承 leader 的核限制，厂商调度器可能把翻译线程钉到部分核
// → worker 继承受限 mask 挤在少量核上超订。显式全核 cpumask 让 worker 能分散到所有核；
// 保持默认线程放置策略（worker 不被钉死单核，调度器可自由迁移）。
static ggml_threadpool_t create_pinned_pool(int n_threads) {
    if (n_threads < 1) n_threads = 1;
    struct ggml_threadpool_params tp = ggml_threadpool_params_default(n_threads);
    const int online = static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN));
    const int ncpu = (online > 0 && online <= GGML_MAX_N_THREADS) ? online : n_threads;
    for (int i = 0; i < ncpu; ++i) tp.cpumask[i] = true;
    return ggml_threadpool_new(&tp);
}

// 探测 mlock 能力：Android 普通 app 的 RLIMIT_MEMLOCK 通常为 0，mlock 大内存必然失败。
// 先锁一个小页探测，失败则直接不用 use_mlock —— 否则每次冷加载都会先带 mlock 读 440MB、
// 失败后再回退重读一遍（双重加载）。探测通过但大锁仍失败时由调用方回退重试。
static bool mlock_capable() {
    const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return false;
    void* p = mmap(nullptr, static_cast<size_t>(page_size), PROT_READ | PROT_WRITE,
                   MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (p == MAP_FAILED) return false;
    const bool ok = (mlock(p, static_cast<size_t>(page_size)) == 0);
    if (ok) munlock(p, static_cast<size_t>(page_size));
    munmap(p, static_cast<size_t>(page_size));
    return ok;
}

// logcat 单条日志上限约 1023 字符；超长字符串分块输出，避免被截断（用于完整打印 prompt/结果/token 序列）
static void log_chunked(const char* tag, const char* s, size_t len) {
    constexpr size_t CHUNK = 900;
    size_t off = 0;
    while (off < len) {
        size_t n = std::min(CHUNK, len - off);
        bridge_log(ANDROID_LOG_INFO, "%s%.*s", tag, static_cast<int>(n), s + off);
        off += n;
    }
}

// 把 C++ 异常转成 Java 异常抛出，避免 JNI 边界未捕获异常直接 abort 进程
static void throw_java_exception(JNIEnv* env, const char* msg) {
    jclass re = env->FindClass("java/lang/RuntimeException");
    if (re != nullptr) env->ThrowNew(re, msg);
}

// NewStringUTF 在 CheckJNI 下遇到非法 modified UTF-8 会 abort（模型特殊 token 的片段可能是非法字节）。
// 这里构造一份干净的 UTF-8：丢弃非法/过短/过长的字节序列与代理对编码，再转 Java String。
static jstring safe_new_string_utf8(JNIEnv* env, const char* s, size_t len) {
    std::string clean;
    clean.reserve(len);
    for (size_t i = 0; i < len;) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        int seq;
        if (c < 0x80) { clean.push_back(s[i]); ++i; continue; }
        else if ((c & 0xE0) == 0xC0) seq = 2;
        else if ((c & 0xF0) == 0xE0) seq = 3;
        else if ((c & 0xF8) == 0xF0) seq = 4;
        else { ++i; continue; }  // 非法起始字节
        if (i + seq > len) { ++i; continue; }  // 截断的序列
        // 校验后续续接字节
        bool ok = true;
        for (int k = 1; k < seq; ++k) {
            if ((static_cast<unsigned char>(s[i + k]) & 0xC0) != 0x80) { ok = false; break; }
        }
        // 拒绝过短编码（0xC0/0xC1、E0 后跟 <A0、F0 后跟 <90）与代理对（ED A0-BF）
        if (ok && seq == 2 && c < 0xC2) ok = false;
        if (ok && seq == 3 && (c == 0xE0 && (static_cast<unsigned char>(s[i+1]) & 0xE0) == 0x80)) ok = false;
        if (ok && seq == 3 && (c == 0xED && (static_cast<unsigned char>(s[i+1]) & 0xE0) == 0xA0)) ok = false;
        if (ok && seq == 4 && (c == 0xF0 && (static_cast<unsigned char>(s[i+1]) & 0xF0) == 0x80)) ok = false;
        if (ok && seq == 4 && c > 0xF4) ok = false;
        if (ok) { clean.append(s + i, seq); i += seq; }
        else { ++i; }
    }
    return env->NewStringUTF(clean.c_str());
}

struct HyMt2Handle {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    llama_token bos_token = 0;
    llama_token user_token = -1;
    llama_token asst_token = -1;
    llama_token eot_token = -1;
    // system 结束标记 <|hy_place▁holder▁no▁3|>（120021）：翻译指令放 system 段、原文放 user 段时的分隔符
    llama_token sys_token = -1;
    // 自定义线程池（全核 cpumask）：worker 不继承受限核 mask，可分散到所有核
    ggml_threadpool_t pool = nullptr;
    ggml_threadpool_t pool_batch = nullptr;
    // 中止标志：nativeAbort 置位，解码循环检查后提前退出（真正终止翻译进程）
    std::atomic<bool> abort{false};
    // 前缀 KV 缓存：固定翻译指令只解码一次进 KV，跨翻译复用，跳过重复 prefill。
    // prefix_key == 当前前缀字符串时 prefix_n > 0 表示 KV 的 [0, prefix_n) 已缓存前缀。
    std::string prefix_key;
    int32_t prefix_n = 0;
};

// 打开统一日志文件（mkdir + 安装崩溃处理器 + open fd）。幂等：已打开则不重开。
// ⚠️ fd 打开后进程生命周期内不再 close/reopen（崩溃处理器无锁读 g_log_fd，重开有 fd 复用竞态）。
// 返回 fd（<0 失败）。
static int ensure_log_file(const std::string& path) {
    // 先确保目录存在（mkdir(2) 替代 system()：不 spawn shell、不持锁、不碰信号处理器）
    const size_t slash = path.find_last_of('/');
    std::string dir;
    if (slash != std::string::npos) dir = path.substr(0, slash);
    if (!dir.empty()) {
        // 逐级 mkdir（app 私有目录已存在时通常一次成功；失败不致命，open 会暴露）
        size_t pos = 0;
        while (pos != std::string::npos) {
            pos = dir.find('/', pos + 1);
            std::string sub = (pos == std::string::npos) ? dir : dir.substr(0, pos);
            if (!sub.empty()) {
                if (mkdir(sub.c_str(), 0755) != 0 && errno != EEXIST) {
                    __android_log_print(ANDROID_LOG_WARN, TAG, "mkdir %s failed: %s", sub.c_str(), strerror(errno));
                    break;
                }
            }
        }
    }
    install_crash_handlers();
    std::lock_guard<std::mutex> lock(g_log_mutex);
    if (g_log_fd >= 0) return g_log_fd;  // 已打开过，不重开
    g_log_path = path;
    // 追加写入（O_APPEND）：Java 侧日志 + 崩溃块都追加，滚动由 Java 侧维护
    g_log_fd = open(path.c_str(), O_WRONLY | O_APPEND | O_CREAT | O_CLOEXEC, 0644);
    return g_log_fd;
}

// 设置统一日志文件（<logs>/starflow.log，与 Java 层 LogCollector 同一文件），
// 打开文件 + 安装崩溃信号处理器。
extern "C" JNIEXPORT void JNICALL
Java_translationapi_hymt2translation_HyMt2Native_nativeSetLogFile(
    JNIEnv* env, jclass, jstring jPath) {
    try {
        const char* path_c = env->GetStringUTFChars(jPath, nullptr);
        if (path_c == nullptr) return;
        std::string path(path_c);
        env->ReleaseStringUTFChars(jPath, path_c);

        if (ensure_log_file(path) < 0) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "open log file failed: %s", path.c_str());
            return;
        }
        // 日志放锁外：避免锁内打日志
        LOGI("nativeSetLogFile: %s", path.c_str());
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "nativeSetLogFile exception: %s", e.what());
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "nativeSetLogFile unknown exception");
    }
}

// 测试用：安装崩溃处理器 + 打开统一日志文件后触发 SIGSEGV，验证崩溃日志/backtrace 捕获链路。
// 即使从未初始化 Hy-MT2 引擎也能独立工作（ensure_log_file 会自开 fd）。
extern "C" JNIEXPORT void JNICALL
Java_translationapi_hymt2translation_HyMt2Native_nativeTriggerNativeCrash(
    JNIEnv* env, jclass, jstring jPath) {
    try {
        const char* path_c = env->GetStringUTFChars(jPath, nullptr);
        if (path_c == nullptr) return;
        std::string path(path_c);
        env->ReleaseStringUTFChars(jPath, path_c);
        ensure_log_file(path);
        LOGI("TEST: 触发 native SIGSEGV 崩溃（验证崩溃日志捕获链路）");
        // 空指针写触发 SIGSEGV → crash_handler 写 backtrace → re-raise → debuggerd/tombstone
        volatile int* p = nullptr;
        *p = 0xDEAD;
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "nativeTriggerNativeCrash exception: %s", e.what());
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "nativeTriggerNativeCrash unknown exception");
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_translationapi_hymt2translation_HyMt2Native_nativeInit(
    JNIEnv* env, jclass, jstring jModelPath, jint nThreads, jint nBatchThreads, jint nCtx) {
    try {
        install_crash_handlers();  // 保证即使未设目录，崩溃也走处理器的 fd 路径（fd<0 直接 _exit）
        std::lock_guard<std::mutex> lock(g_mutex);
        const char* path = env->GetStringUTFChars(jModelPath, nullptr);
        if (path == nullptr) return 0;

        // 防御性钳位：非法入参不进入引擎
        if (nCtx < 512) nCtx = 512;
        if (nThreads < 1) nThreads = 1;
        if (nBatchThreads < 1) nBatchThreads = 1;

        llama_model_params mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        // ⚠️ 必须 use_mmap=false：mmap 的权重页会被系统在内存压力下回收，导致解码时反复从存储读 440MB
        //    （实测 15-17s/次）。直接读入堆内存，加载慢一次但之后解码稳定快。
        mp.use_mmap = false;
        // use_mlock：把模型页锁进物理内存，防止系统在内存压力下把堆页换到 ZRAM（实测单 token 14s 慢 180 倍）。
        // 先探测 mlock 能力：非 root Android app 的 RLIMIT_MEMLOCK 通常为 0 → 直接跳过，避免带 mlock 读 440MB
        // 失败后再回退重读一遍（双重加载）。系统允许锁定才启用。
        mp.use_mlock = mlock_capable();
        if (!mp.use_mlock) LOGW("mlock unavailable, load without mlock");
        llama_model* model = llama_model_load_from_file(path, mp);
        if (model == nullptr && mp.use_mlock) {
            // 探测通过但大内存锁定失败 → 回退不锁定重试
            LOGE("mlock load failed, retry without mlock");
            mp.use_mlock = false;
            model = llama_model_load_from_file(path, mp);
        }
        env->ReleaseStringUTFChars(jModelPath, path);
        if (model == nullptr) { LOGE("llama_model_load_from_file failed"); return 0; }

        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = nCtx;
        cp.n_threads = nThreads;
        // 输入线程（prefill/批量解码）独立可配：Java 侧默认传设备全核（保持"读取用满全核"原策略），
        // 用户可在详情页调整对比速度。
        const unsigned hw_threads = std::thread::hardware_concurrency();
        cp.n_threads_batch = nBatchThreads;
        LOGI("nativeInit: threads=%d batch_threads=%d hw=%u", nThreads, cp.n_threads_batch, hw_threads);
        cp.n_batch = std::min<int>(nCtx, 2048);
        llama_context* ctx = llama_init_from_model(model, cp);
        if (ctx == nullptr) { LOGE("llama_init_from_model failed"); llama_model_free(model); return 0; }

        auto* h = new HyMt2Handle();
        h->model = model;
        h->ctx = ctx;
        h->vocab = llama_model_get_vocab(model);

        // 自定义线程池显式全核 mask，worker 不继承受限核（避免挤核超订）。
        // 已验证必要：注释掉（回到 llama 默认一次性线程池）后旧手机立即卡死（worker 每次新建被调度器塞中核）。
        h->pool = create_pinned_pool(nThreads);
        h->pool_batch = create_pinned_pool(nBatchThreads);
        if (h->pool != nullptr && h->pool_batch != nullptr) {
            llama_attach_threadpool(ctx, h->pool, h->pool_batch);
            LOGI("nativeInit: attached pinned threadpool gen=%d batch=%d", nThreads, nBatchThreads);
        } else {
            LOGW("nativeInit: pinned threadpool create failed, fallback to default");
            if (h->pool != nullptr) { ggml_threadpool_free(h->pool); h->pool = nullptr; }
            if (h->pool_batch != nullptr) { ggml_threadpool_free(h->pool_batch); h->pool_batch = nullptr; }
        }

        // 诊断 + 格式：读取模型要求的输入格式（chat template）与聊天特殊 token
        {
            char tbuf[2048];
            const int tn = llama_model_meta_val_str(model, "tokenizer.ggml.chat_template", tbuf, sizeof(tbuf));
            LOGI("chat_template[%d]=%s", tn, tn >= 0 ? tbuf : "(none)");
            h->bos_token = llama_vocab_bos(h->vocab);
            LOGI("bos=%d eos=%d vocab_n=%d", h->bos_token, llama_vocab_eos(h->vocab), llama_vocab_n_tokens(h->vocab));
            for (llama_token t = 0; t < llama_vocab_n_tokens(h->vocab); ++t) {
                const char* text = llama_vocab_get_text(h->vocab, t);
                if (text == nullptr) continue;
                if (strstr(text, "hy_User")) { if (h->user_token < 0) h->user_token = t; }
                else if (strstr(text, "hy_Assistant")) { if (h->asst_token < 0) h->asst_token = t; }
                else if (strstr(text, "hy_EOT")) { if (h->eot_token < 0) h->eot_token = t; }
                else if (strstr(text, "hy_place") && h->sys_token < 0) {
                    // system 结束标记 <|hy_place▁holder▁no▁3|>（120021）。token 文本用 U+2581 作分隔：
                    // "hy_place▁holder▁no▁3"。匹配 no▁3 且后续非数字（排除 no▁30 等）
                    const char* m = strstr(text, "no\xE2\x96\x81" "3");
                    if (m != nullptr) {
                        const char* after = m + 6;  // "no"(2) + U+2581(3) + "3"(1) = 6，指向 "3" 之后
                        if (!(*after >= '0' && *after <= '9')) h->sys_token = t;
                    }
                }
            }
            LOGI("chat special tokens: user=%d asst=%d eot=%d sys=%d",
                 h->user_token, h->asst_token, h->eot_token, h->sys_token);
        }

        // 预热：解码一个 token 跑一次全前向，把 440MB 权重页调入内存（首次解码会 mmap 缺页 ~15s）
        // 把这段耗时并入"模型加载中…"，避免首次翻译时才卡 15 秒
        const long long t_warm = now_ms();
        llama_token warm_tokens[4];
        const int32_t n_warm = llama_tokenize(h->vocab, "a", 1, warm_tokens, 4, false, false);
        if (n_warm > 0) {
            llama_batch wb = llama_batch_init(n_warm, 0, 1);
            wb.n_tokens = n_warm;
            for (int32_t i = 0; i < n_warm; ++i) {
                wb.token[i]     = warm_tokens[i];
                wb.pos[i]       = i;
                wb.n_seq_id[i]  = 1;
                wb.seq_id[i][0] = 0;
            }
            wb.logits[n_warm - 1] = true;
            if (llama_decode(h->ctx, wb) == 0) {
                LOGI("warmup decode ok (%lld ms)", now_ms() - t_warm);
            } else {
                LOGW("warmup decode failed");
            }
            llama_batch_free(wb);
            llama_memory_clear(llama_get_memory(h->ctx), false);
        }

        LOGI("model loaded: nCtx=%d nThreads=%d", nCtx, nThreads);
        return reinterpret_cast<jlong>(h);
    } catch (const std::exception& e) {
        LOGE("nativeInit exception: %s", e.what());
        throw_java_exception(env, e.what());
        return 0;
    } catch (...) {
        LOGE("nativeInit unknown exception");
        throw_java_exception(env, "unknown native exception");
        return 0;
    }
}

// 核心翻译实现。jCallback != nullptr 时，每生成一段译文就回调 onToken(累积的完整译文) 实现流式显示。
// jPrefix 传「固定指令前缀」字符串（不含待翻译文本）；同一前缀跨翻译复用 KV 缓存，跳过重复 prefill。
static jstring translate_impl(JNIEnv* env, jlong jHandle, jstring jPrompt, jstring jPrefix,
    jfloat temperature, jfloat topP, jint topK,
    jfloat repetitionPenalty, jint maxTokens, jobject jCallback) {
    try {
        const long long t_entry = now_ms();
        LOGI("translate: ENTER handle=%lld temp=%.3f top_p=%.3f top_k=%d rep=%.3f max_tokens=%d",
             jHandle, temperature, topP, topK, repetitionPenalty, maxTokens);

        std::lock_guard<std::mutex> lock(g_mutex);
        LOGI("translate: mutex acquired (+%lld ms)", now_ms() - t_entry);

        auto* h = reinterpret_cast<HyMt2Handle*>(jHandle);
        if (h == nullptr || h->ctx == nullptr) return env->NewStringUTF("");
        h->abort.store(false);  // 清除上次可能残留的中止标志

        llama_memory_t mem = llama_get_memory(h->ctx);

        const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
        if (prompt == nullptr) return env->NewStringUTF("");
        const size_t text_len = strlen(prompt);
        const char* prefix_cstr = (jPrefix != nullptr) ? env->GetStringUTFChars(jPrefix, nullptr) : nullptr;
        std::string prefix = (prefix_cstr != nullptr) ? std::string(prefix_cstr) : std::string();
        if (prefix_cstr != nullptr) env->ReleaseStringUTFChars(jPrefix, prefix_cstr);
        LOGI("translate: prompt chars=%zu preview=[%.100s]", text_len, prompt);
        // 崩溃诊断：记录本次翻译上下文（crash_handler 崩溃时读）
        g_diag.in_translate.store(true);
        g_diag.prompt_chars.store(static_cast<int>(text_len));
        g_diag.max_tokens.store(maxTokens);
        g_diag.n_ctx.store(static_cast<int>(llama_n_ctx(h->ctx)));
        g_diag.gen_token.store(0);
        snprintf(g_diag.prompt_preview, sizeof(g_diag.prompt_preview), "%.*s", (int)sizeof(g_diag.prompt_preview) - 1, prompt);
        // 把含 {source_text} 的尾部单独打出来，确认识别文本确实进了 prompt
        const size_t tail_len = std::min<size_t>(text_len, 80);
        LOGI("translate: prompt tail=[%s]", prompt + (text_len - tail_len));
        LOGI("translate: prefix chars=%zu prefix=[%.60s]", prefix.size(), prefix.c_str());
        log_chunked("translate: FULL PROMPT:\n", prompt, text_len);

        // 前缀缓存是否有效：前缀非空、已建缓存、且与本前缀一致
        const bool want_cache = !prefix.empty();
        const bool cache_valid = want_cache && h->prefix_n > 0 && h->prefix_key == prefix;

        // 分词辅助（缓冲按字符数 + 余量；若缓冲不足返回负的"需要数量"，则按需扩容重试）
        // ⚠️ f8b355a9e 版 llama_vocab::tokenize 内部是 std::string(text, text_len)，不处理 text_len=-1，
        //    传 -1 会抛 std::length_error 导致进程 abort。必须传真实字节长度。
        auto tokenize_str = [&](const char* s, int32_t len, bool add_special, std::vector<llama_token>& out) -> int32_t {
            std::vector<llama_token> buf((len < 0 ? 0 : len) + 16);
            for (int attempt = 0; attempt < 2; ++attempt) {
                int32_t n = llama_tokenize(h->vocab, s, len, buf.data(), static_cast<int32_t>(buf.size()), add_special, false);
                if (n < 0) {
                    const int32_t needed = -n;
                    if (needed <= static_cast<int32_t>(buf.size())) return n;
                    buf.resize(static_cast<size_t>(needed) + 16);
                    continue;
                }
                out.assign(buf.begin(), buf.begin() + n);
                return n;
            }
            return -1;
        };

        // 本次要解码的 token（不含已缓存的前缀；缓存未命中时 = 全量 prompt）
        std::vector<llama_token> prompt_tok;
        int32_t start_pos = 0;  // 本次解码的起始 KV 位置（缓存命中时 = 前缀长度）

        // 固定前缀（system 段指令，跨翻译不变）与变化部分（user 段原文）分开 tokenize。
        // 统一 add_special=false，BOS/角色标记全部手动加，不依赖引擎版本的 add_special 行为。
        const bool has_chat = (h->bos_token > 0 || h->user_token >= 0 || h->asst_token >= 0);
        // 固定前缀 token 数（KV 缓存量）：BOS + system 结束标记 + user 角色标记
        const int32_t chat_prefix = (h->bos_token > 0 ? 1 : 0) + (h->sys_token >= 0 ? 1 : 0) + (h->user_token >= 0 ? 1 : 0);
        const int32_t rest_len = static_cast<int32_t>(text_len - prefix.size());

        if (cache_valid) {
            // 缓存命中：只解码变化部分（rest + Assistant），前缀 KV 已在 [0, prefix_n)
            const int32_t nr = tokenize_str(prompt + prefix.size(), rest_len, false, prompt_tok);
            if (nr < 0) {
                LOGE("translate: rest tokenize failed (%d)", nr);
                env->ReleaseStringUTFChars(jPrompt, prompt);
                return env->NewStringUTF("");
            }
            // ⚠️ 缓存命中时 KV 前缀已含 [BOS][指令][sys_end][User]，不能再重复加这些角色标记
            if (has_chat && h->asst_token >= 0) prompt_tok.push_back(h->asst_token);
            start_pos = h->prefix_n;
            LOGI("translate: prefix cache HIT prefix_n=%d rest_tokens=%d", h->prefix_n, nr);
        } else {
            // 缓存未命中/禁用：全量 prefill（保持原有行为），清空 KV 后重建
            llama_memory_clear(mem, false);
            h->prefix_n = 0;  // KV 已清空，任何已缓存前缀失效
            start_pos = 0;

            std::vector<llama_token> prefix_tok, rest_tok;
            if (want_cache && rest_len >= 0) {
                // 指令（prefix）→ system 段；原文（rest）→ user 段
                const int32_t np = tokenize_str(prefix.c_str(), static_cast<int32_t>(prefix.size()), false, prefix_tok);
                const int32_t nr = tokenize_str(prompt + prefix.size(), rest_len, false, rest_tok);
                if (np < 0 || nr < 0) {
                    LOGE("translate: split tokenize failed np=%d nr=%d", np, nr);
                    env->ReleaseStringUTFChars(jPrompt, prompt);
                    return env->NewStringUTF("");
                }
                // 固定前缀：BOS + 指令 + sys_end + User，跨翻译复用 KV 跳过重复 prefill
                h->prefix_n = chat_prefix + static_cast<int32_t>(prefix_tok.size());
                h->prefix_key = prefix;
                LOGI("translate: prefix cache built prefix_n=%d prefix_tokens=%d sys_end=%d",
                     h->prefix_n, np, h->sys_token);
            } else {
                // 无前缀（模板不含 {source_text} 或缓存禁用）：全部放 user 段
                const int32_t n = tokenize_str(prompt, static_cast<int32_t>(text_len), false, rest_tok);
                if (n < 0) {
                    LOGE("translate: prompt tokenize failed (%d)", n);
                    env->ReleaseStringUTFChars(jPrompt, prompt);
                    return env->NewStringUTF("");
                }
                if (!want_cache) h->prefix_key.clear();
            }

            // ⚠️ 按模型 chat 模板构造输入:
            //   [BOS] + {指令} + <hy_place_holder_no_3>(system 结束) + <hy_User> + {原文} + <hy_Assistant>
            // 指令放 system 段（官方模板结构，BLEU 比全塞 user 高 5~13）；不包角色标记直接喂裸文本会退化输出垃圾
            if (has_chat) {
                if (h->bos_token > 0) prompt_tok.push_back(h->bos_token);
                prompt_tok.insert(prompt_tok.end(), prefix_tok.begin(), prefix_tok.end());
                if (h->sys_token >= 0) prompt_tok.push_back(h->sys_token);
                if (h->user_token >= 0) prompt_tok.push_back(h->user_token);
                prompt_tok.insert(prompt_tok.end(), rest_tok.begin(), rest_tok.end());
                if (h->asst_token >= 0) prompt_tok.push_back(h->asst_token);
            } else {
                prompt_tok.swap(rest_tok);
            }
        }
        env->ReleaseStringUTFChars(jPrompt, prompt);
        const int32_t n_tokens = static_cast<int32_t>(prompt_tok.size());
        g_diag.prompt_tokens.store(n_tokens);
        g_diag.prefix_cache_valid.store(cache_valid ? 1 : 0);
        // ⚠️ 超长 prompt 防御：实际 context 占用 = 前缀(KV 缓存 start_pos) + 本次输入。
        //   缓存命中时 n_tokens 只是变化部分，必须加 start_pos（前缀长度）才是真实占用，
        //   否则缓存命中会漏检放行超限 prompt（decode 时 llama 可能 ggml_abort 闪退）。
        //   这里直接返回错误标记，不喂给 llama——宁可翻译失败也不闪退。
        const int32_t ctx_n = static_cast<int32_t>(llama_n_ctx(h->ctx));
        if (start_pos + n_tokens >= ctx_n - 64) {
            LOGE("translate: prompt too long: %d tokens >= ctx %d - 64, aborting translate",
                 start_pos + n_tokens, ctx_n);
            g_diag.in_translate.store(false);
            return env->NewStringUTF("__PROMPT_TOO_LONG__");
        }
        // 本次输入实际占用上下文 [start_pos, start_pos + n_tokens)
        const int32_t total_prompt = start_pos + n_tokens;

        // 打印前几个 prompt token id（检查是否有 BOS / 特殊 token）
        {
            std::string ids;
            for (int k = 0; k < std::min<int>(n_tokens, 10); ++k) {
                if (k) ids += ",";
                ids += std::to_string(prompt_tok[k]);
            }
            LOGI("translate: input tokens=%d start_pos=%d total_ctx=%d first=[%s]",
                 n_tokens, start_pos, total_prompt, ids.c_str());
        }
        // 完整输入 token 序列（模型实际消费的 ID 列表），便于核对聊天格式
        {
            std::string ids;
            for (int32_t k = 0; k < n_tokens; ++k) {
                if (k) ids += ",";
                ids += std::to_string(prompt_tok[k]);
            }
            log_chunked("translate: FULL INPUT TOKENS: ", ids.c_str(), ids.size());
        }

        // 防御：空输入（无 token）时直接返回，避免下方 batch.logits[n_tokens-1] 越界写
        if (n_tokens <= 0) {
            LOGE("translate: empty prompt tokens");
            return env->NewStringUTF("");
        }

        const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(h->ctx));
        // 输出长度上限：翻译通常 ≈ 源长度，防止模型过度生成（实测短句翻出 300+ token）
        // effective = min(用户max_tokens, 源tokens*2+64, 上下文剩余)
        const int gen_cap = n_tokens * 2 + 64;
        const int max_tokens = std::max(0, std::min(static_cast<int>(maxTokens),
                                                    std::min(gen_cap, static_cast<int>(n_ctx - total_prompt))));
        if (max_tokens == 0) {
            LOGE("translate: prompt longer than context (%d)", n_ctx);
            return env->NewStringUTF("");
        }

        // 每次按当前采样参数重建采样链
        llama_sampler_chain_params sp = llama_sampler_chain_default_params();
        llama_sampler* smpl = llama_sampler_chain_init(sp);
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, repetitionPenalty, 0.0f, 0.0f));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

        // prompt 一次性解码（用显式位置数组，与 llama-bench 一致）
        // ⚠️ 不要用 llama_batch_get_one（pos=nullptr 自动跟踪）：f8b355a9e 实测单 token 解码慢 ~175×
        LOGI("translate: decoding input (%d tokens, start_pos=%d)...", n_tokens, start_pos);
        llama_batch batch = llama_batch_init(n_tokens, 0, 1);
        if (batch.token == nullptr) {
            LOGE("translate: batch alloc failed");
            llama_sampler_free(smpl);
            return env->NewStringUTF("");
        }
        batch.n_tokens = n_tokens;
        for (int32_t i = 0; i < n_tokens; ++i) {
            batch.token[i]    = prompt_tok[i];
            batch.pos[i]      = start_pos + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
        }
        // ⚠️ llama_batch_init 的 logits 数组默认全 false：必须为"需要采样"的最后一个 token 置 true，
        //    否则模型不算 logits，llama_sampler_sample 会 ggml_abort 崩溃。
        batch.logits[n_tokens - 1] = true;
        // 流式回调：解析 onPhase/onToken 方法；通知进入 prompt 解码阶段
        jmethodID onTokenId = nullptr;
        jmethodID onPhaseId = nullptr;
        if (jCallback != nullptr) {
            jclass cbClass = env->GetObjectClass(jCallback);
            onTokenId = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
            onPhaseId = env->GetMethodID(cbClass, "onPhase", "(Ljava/lang/String;)V");
            env->DeleteLocalRef(cbClass);
        }
        if (onPhaseId != nullptr) {
            jstring js = env->NewStringUTF("prefill");
            env->CallVoidMethod(jCallback, onPhaseId, js);
            env->DeleteLocalRef(js);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        // 每次 decode 紧前重新 pin：厂商 pinner 可能在 translate 处理途中把本线程重新钉回部分核，
        const long long t_decode0 = now_ms();
        if (llama_decode(h->ctx, batch) != 0) {
            LOGE("translate: prompt decode failed");
            llama_batch_free(batch);
            llama_sampler_free(smpl);
            return env->NewStringUTF("");
        }
        LOGI("translate: prompt decoded (+%lld ms), starting generation", now_ms() - t_decode0);

        std::string result;
        char piece_buf[512];
        int32_t n_cur = total_prompt;
        const long long t_gen0 = now_ms();
        bool hit_eog = false;
        int generated = 0;
        // 通知阶段：开始生成译文
        if (onPhaseId != nullptr) {
            jstring js = env->NewStringUTF("generate");
            env->CallVoidMethod(jCallback, onPhaseId, js);
            env->DeleteLocalRef(js);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        for (int i = 0; i < max_tokens; ++i) {
            g_diag.gen_token.store(i);  // 崩溃诊断：生成到第几个 token
            if ((i % 8) == 0) {
                LOGI("translate: gen progress token %d/%d (+%lld ms)", i, max_tokens, now_ms() - t_gen0);
            }
            // 中止检查：nativeAbort 置位后提前退出，真正终止翻译
            if (h->abort.load()) {
                LOGI("translate: ABORTED at token %d", i);
                break;
            }
            const long long t_s0 = now_ms();
            const llama_token id = llama_sampler_sample(smpl, h->ctx, -1);
            llama_sampler_accept(smpl, id);
            generated = i + 1;
            // 每个 token 记录解码信息（崩溃前最后 token 可定位问题）
            if ((i % 8) == 0 || i == 0) {
                LOGI("translate: sampled[%d] id=%d pos=%d", i, id, n_cur);
            }
            if (llama_vocab_is_eog(h->vocab, id)) {
                LOGI("translate: EOG at token %d (+%lld ms), token_id=%d", i, now_ms() - t_gen0, id);
                hit_eog = true;
                break;
            }
            const int32_t n_piece = llama_token_to_piece(
                h->vocab, id, piece_buf, static_cast<int32_t>(sizeof(piece_buf)), 0, false);
            if (n_piece > 0) {
                result.append(piece_buf, n_piece);
                // 流式显示：回调「当前完整译文」（累积），UI 逐段增长显示，而不是只发单个片段
                if (onTokenId != nullptr) {
                    const long long t_cb0 = now_ms();
                    jstring js = safe_new_string_utf8(env, result.c_str(), result.size());
                    if (js != nullptr) {
                        env->CallVoidMethod(jCallback, onTokenId, js);
                        env->DeleteLocalRef(js);
                        if (env->ExceptionCheck()) env->ExceptionClear();
                    }
                    if ((i % 4) == 0) LOGI("translate: gen[%d] sample=%lldms cb=%lldms", i, t_cb0 - t_s0, now_ms() - t_cb0);
                }
            }
            // 解码新 token（显式位置；logits 置 true 供下一轮采样）
            batch.n_tokens    = 1;
            batch.token[0]    = id;
            batch.pos[0]      = n_cur++;
            batch.n_seq_id[0] = 1;
            batch.seq_id[0][0] = 0;
            batch.logits[0]   = true;
            const long long t_d0 = now_ms();
            if (llama_decode(h->ctx, batch) != 0) { LOGE("translate: decode failed at step %d", i); break; }
            if ((i % 4) == 0) LOGI("translate: gen[%d] decode=%lldms", i, now_ms() - t_d0);
        }
        llama_batch_free(batch);
        llama_sampler_free(smpl);

        // 保留前缀 KV，只清掉本次生成的内容，供下次翻译复用；若裁剪失败则禁用缓存（下次全量 prefill）
        if (h->prefix_n > 0 && h->prefix_key == prefix) {
            if (llama_memory_seq_rm(mem, 0, h->prefix_n, -1)) {
                LOGI("translate: KV trimmed to prefix_n=%d", h->prefix_n);
            } else {
                h->prefix_n = 0;
                h->prefix_key.clear();
                LOGW("translate: KV trim failed, prefix cache disabled");
            }
        }

        log_chunked("translate: FULL RESULT:\n", result.c_str(), result.size());
        LOGI("translate: DONE generated=%d/%d eog=%d gen_elapsed=%lld ms total_elapsed=%lld ms result_len=%zu preview=[%.120s]",
             generated, max_tokens, hit_eog, now_ms() - t_gen0, now_ms() - t_entry, result.size(), result.c_str());
        g_diag.in_translate.store(false);  // 翻译结束，崩溃诊断复位
        return safe_new_string_utf8(env, result.c_str(), result.size());
    } catch (const std::exception& e) {
        g_diag.in_translate.store(false);
        LOGE("translate: C++ exception: %s", e.what());
        throw_java_exception(env, e.what());
        return nullptr;
    } catch (...) {
        LOGE("translate: unknown C++ exception");
        throw_java_exception(env, "unknown native exception");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_translationapi_hymt2translation_HyMt2Native_nativeTranslate(
    JNIEnv* env, jclass, jlong jHandle, jstring jPrompt, jstring jPrefix,
    jfloat temperature, jfloat topP, jint topK,
    jfloat repetitionPenalty, jint maxTokens) {
    return translate_impl(env, jHandle, jPrompt, jPrefix, temperature, topP, topK, repetitionPenalty, maxTokens, nullptr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_translationapi_hymt2translation_HyMt2Native_nativeTranslateStreaming(
    JNIEnv* env, jclass, jlong jHandle, jstring jPrompt, jstring jPrefix,
    jfloat temperature, jfloat topP, jint topK,
    jfloat repetitionPenalty, jint maxTokens, jobject jCallback) {
    return translate_impl(env, jHandle, jPrompt, jPrefix, temperature, topP, topK, repetitionPenalty, maxTokens, jCallback);
}

extern "C" JNIEXPORT void JNICALL
Java_translationapi_hymt2translation_HyMt2Native_nativeRelease(JNIEnv*, jclass, jlong jHandle) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto* h = reinterpret_cast<HyMt2Handle*>(jHandle);
        if (h == nullptr) return;
        if (h->ctx != nullptr) llama_free(h->ctx);  // 内部 detach threadpool（不 free pool）
        if (h->model != nullptr) llama_model_free(h->model);
        if (h->pool != nullptr) ggml_threadpool_free(h->pool);
        if (h->pool_batch != nullptr) ggml_threadpool_free(h->pool_batch);
        delete h;
        LOGI("model released");
    } catch (const std::exception& e) {
        LOGE("nativeRelease exception: %s", e.what());
    } catch (...) {
        LOGE("nativeRelease unknown exception");
    }
}

// 中止翻译：不抢互斥锁（translate_impl 持锁时无法获取），仅置位原子标志让解码循环提前退出
extern "C" JNIEXPORT void JNICALL
Java_translationapi_hymt2translation_HyMt2Native_nativeAbort(JNIEnv*, jclass, jlong jHandle) {
    auto* h = reinterpret_cast<HyMt2Handle*>(jHandle);
    if (h != nullptr) {
        h->abort.store(true);
        LOGI("abort requested");
    }
}

// 多轮对话：组装 [BOS]{system}<sys_end><hy_User>m1<hy_Assistant>m2...<hy_Assistant>，末尾 assistant 引导生成。
// system 段固定 → 前缀 KV 缓存只 prefill 一次，每轮只解新增消息。复用采样链/生成循环/abort 逻辑。
extern "C" JNIEXPORT jstring JNICALL
Java_translationapi_hymt2translation_HyMt2Native_nativeTranslateChat(
    JNIEnv* env, jclass, jlong jHandle, jstring jSystemPrompt,
    jintArray jRoles, jobjectArray jContents,
    jfloat temperature, jfloat topP, jint topK,
    jfloat repetitionPenalty, jint maxTokens, jobject jCallback) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto* h = reinterpret_cast<HyMt2Handle*>(jHandle);
        if (h == nullptr || h->ctx == nullptr) return env->NewStringUTF("");
        h->abort.store(false);

        const char* sys = env->GetStringUTFChars(jSystemPrompt, nullptr);
        if (sys == nullptr) return env->NewStringUTF("");
        std::string system_prompt(sys);
        env->ReleaseStringUTFChars(jSystemPrompt, sys);

        const jsize n_msgs = env->GetArrayLength(jRoles);
        std::vector<int> roles(static_cast<size_t>(n_msgs));
        env->GetIntArrayRegion(jRoles, 0, n_msgs, roles.data());
        std::vector<std::string> msgs(static_cast<size_t>(n_msgs));
        for (jsize i = 0; i < n_msgs; ++i) {
            auto js = static_cast<jstring>(env->GetObjectArrayElement(jContents, i));
            const char* cs = env->GetStringUTFChars(js, nullptr);
            msgs[static_cast<size_t>(i)] = cs ? cs : "";
            if (cs) env->ReleaseStringUTFChars(js, cs);
            env->DeleteLocalRef(js);
        }

        // 分词（add_special=false，特殊 token 手动加）
        auto tokenize_str = [&](const char* s, int32_t len, std::vector<llama_token>& out) -> int32_t {
            std::vector<llama_token> buf((len < 0 ? 0 : len) + 16);
            for (int attempt = 0; attempt < 2; ++attempt) {
                int32_t n = llama_tokenize(h->vocab, s, len, buf.data(), static_cast<int32_t>(buf.size()), false, false);
                if (n < 0) {
                    const int32_t needed = -n;
                    if (needed <= static_cast<int32_t>(buf.size())) return n;
                    buf.resize(static_cast<size_t>(needed) + 16);
                    continue;
                }
                out.assign(buf.begin(), buf.begin() + n);
                return n;
            }
            return -1;
        };

        // 组装 chat 序列: [BOS]{system}<sys_end> {role1}{m1}{role2}{m2}... {asst}
        std::vector<llama_token> seq;
        std::vector<llama_token> prefix_tok;
        tokenize_str(system_prompt.c_str(), static_cast<int32_t>(system_prompt.size()), prefix_tok);
        if (h->bos_token > 0) seq.push_back(h->bos_token);
        seq.insert(seq.end(), prefix_tok.begin(), prefix_tok.end());
        if (h->sys_token >= 0) seq.push_back(h->sys_token);
        // 固定前缀：BOS + system + sys_end + 第一条消息的 role token
        const int32_t chat_prefix = (h->bos_token > 0 ? 1 : 0) + (h->sys_token >= 0 ? 1 : 0) + (h->user_token >= 0 ? 1 : 0);
        const int32_t prefix_n = chat_prefix + static_cast<int32_t>(prefix_tok.size());

        for (jsize i = 0; i < n_msgs; ++i) {
            const llama_token role_tok = (roles[static_cast<size_t>(i)] == 0) ? h->user_token : h->asst_token;
            if (role_tok >= 0) seq.push_back(role_tok);
            std::vector<llama_token> toks;
            if (tokenize_str(msgs[static_cast<size_t>(i)].c_str(),
                    static_cast<int32_t>(msgs[static_cast<size_t>(i)].size()), toks) < 0) {
                return env->NewStringUTF("");
            }
            seq.insert(seq.end(), toks.begin(), toks.end());
        }
        if (h->asst_token >= 0) seq.push_back(h->asst_token);

        // 前缀 KV 缓存：system 段固定 → 每轮只 prefill 新增消息
        llama_memory_t mem = llama_get_memory(h->ctx);
        const bool cache_valid = h->prefix_n > 0 && h->prefix_key == system_prompt && h->prefix_n == prefix_n;
        std::vector<llama_token> prompt_tok;
        int32_t start_pos = 0;
        if (cache_valid) {
            start_pos = h->prefix_n;
            prompt_tok.assign(seq.begin() + prefix_n, seq.end());
        } else {
            llama_memory_clear(mem, false);
            h->prefix_n = 0;
            h->prefix_key = system_prompt;
            h->prefix_n = prefix_n;
            prompt_tok = seq;
        }

        const int32_t n_tokens = static_cast<int32_t>(prompt_tok.size());
        if (n_tokens <= 0) return env->NewStringUTF("");
        const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(h->ctx));
        const int gen_cap = n_tokens * 2 + 64;
        const int max_tok = std::max(0, std::min(static_cast<int>(maxTokens),
                                                 std::min(gen_cap, static_cast<int>(n_ctx - start_pos - n_tokens))));
        if (max_tok == 0) return env->NewStringUTF("");

        // 采样链（与 translate_impl 一致）
        llama_sampler_chain_params sp = llama_sampler_chain_default_params();
        llama_sampler* smpl = llama_sampler_chain_init(sp);
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, repetitionPenalty, 0.0f, 0.0f));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

        llama_batch batch = llama_batch_init(n_tokens, 0, 1);
        if (batch.token == nullptr) { llama_sampler_free(smpl); return env->NewStringUTF(""); }
        batch.n_tokens = n_tokens;
        for (int32_t i = 0; i < n_tokens; ++i) {
            batch.token[i] = prompt_tok[i];
            batch.pos[i] = start_pos + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
        }
        batch.logits[n_tokens - 1] = true;

        jmethodID onTokenId = nullptr, onPhaseId = nullptr;
        if (jCallback != nullptr) {
            jclass cbClass = env->GetObjectClass(jCallback);
            onTokenId = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
            onPhaseId = env->GetMethodID(cbClass, "onPhase", "(Ljava/lang/String;)V");
            env->DeleteLocalRef(cbClass);
        }
        auto notify_phase = [&](const char* p) {
            if (onPhaseId != nullptr) {
                jstring js = env->NewStringUTF(p);
                env->CallVoidMethod(jCallback, onPhaseId, js);
                env->DeleteLocalRef(js);
                if (env->ExceptionCheck()) env->ExceptionClear();
            }
        };

        notify_phase("prefill");
        if (llama_decode(h->ctx, batch) != 0) {
            llama_batch_free(batch);
            llama_sampler_free(smpl);
            return env->NewStringUTF("");
        }

        notify_phase("generate");
        std::string result;
        char piece_buf[512];
        int32_t n_cur = start_pos + n_tokens;
        for (int i = 0; i < max_tok; ++i) {
            if (h->abort.load()) break;
            const llama_token id = llama_sampler_sample(smpl, h->ctx, -1);
            llama_sampler_accept(smpl, id);
            if (llama_vocab_is_eog(h->vocab, id)) break;
            const int32_t n_piece = llama_token_to_piece(h->vocab, id, piece_buf,
                static_cast<int32_t>(sizeof(piece_buf)), 0, false);
            if (n_piece > 0) {
                result.append(piece_buf, n_piece);
                if (onTokenId != nullptr) {
                    jstring js = safe_new_string_utf8(env, result.c_str(), result.size());
                    if (js != nullptr) {
                        env->CallVoidMethod(jCallback, onTokenId, js);
                        env->DeleteLocalRef(js);
                        if (env->ExceptionCheck()) env->ExceptionClear();
                    }
                }
            }
            batch.n_tokens = 1;
            batch.token[0] = id;
            batch.pos[0] = n_cur++;
            batch.n_seq_id[0] = 1;
            batch.seq_id[0][0] = 0;
            batch.logits[0] = true;
            if (llama_decode(h->ctx, batch) != 0) break;
        }
        llama_batch_free(batch);
        llama_sampler_free(smpl);

        // 保留 system 前缀 KV，裁掉本次生成内容
        if (h->prefix_n > 0 && h->prefix_key == system_prompt) {
            llama_memory_seq_rm(mem, 0, h->prefix_n, -1);
        }
        return safe_new_string_utf8(env, result.c_str(), result.size());
    } catch (const std::exception& e) {
        LOGE("translateChat: C++ exception: %s", e.what());
        throw_java_exception(env, e.what());
        return nullptr;
    } catch (...) {
        LOGE("translateChat: unknown C++ exception");
        throw_java_exception(env, "unknown native exception");
        return nullptr;
    }
}
