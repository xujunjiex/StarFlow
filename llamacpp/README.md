# :llamacpp —— 本地 GGUF 推理模块

本模块把 **llama.cpp 源码**编进 App（不再是预编译 `.so`），并提供一层 JNI 桥给 `:app` 使用。
原来只跑内置 Hy-MT2 的 `hymt2` 桥接与 4 个预编译 `.so` 已被它整体取代。

## 为什么不放在 `:app` 里编

`:app` 的顶层 CMake 是 **sentencepiece** 工程，它 `include_directories()` 了自己的 `src/`，
而 llama.cpp 也有 `common/common.h` —— 同一个 CMake 工程里两个 `common.h` 会撞头文件。
所以单独一个 Gradle 模块（同萌译的 `:llama`）。

## 首次拉源码（必需）

```powershell
pwsh -File llamacpp/setup-submodule.ps1
```

llama.cpp 的 `tools/ui` 有超过 260 字符的路径，Windows 默认长路径关闭时 `git submodule update`
会在 checkout 阶段失败（Filename too long）；脚本会开 `core.longpaths`、**只检出构建需要的目录**
并切到 pin 的提交。

## pin 在哪、为什么

- submodule pin = llama.cpp 官方仓库 PR **#22836** head（`ggml-cpu: add STQ1_0 ternary quantization
  with ARM NEON vec_dot kernel`），该提交里 `GGML_TYPE_STQ1_0 = 43`。
- 为什么要这个 PR：腾讯官方分发的 `Hy-MT2-1.8B-1.25Bit.gguf` 用私有量化类型（文件里张量类型号是 42），
  llama.cpp 官方 master 没有它；而且官方已经把 42 分给了自己的 `Q2_0`，所以这个类型必须落在 43。
- 完整补丁（374 行 / 17 文件）存在 `patches/0001-ggml-stq1_0-1.25bit.patch`，
  万一 PR 被关或我们要跟更新的 master，就改成「官方提交 + 打这个补丁」，步骤见 `patches/README.md`。

## 与模型文件的约定（重要）

官方那个 gguf 里写的是 **42**，我们的引擎认 **43**，因此 **App 在下载/导入完成后会把文件里的
42 改写成 43**（`com.moe.starflow.llamacpp.GgufTypeRetag`，224 处 4 字节原地改写，幂等，
并在文件旁留 `<file>.retagged` 标记记录改写后的 MD5）。

- 下载流水线完成校验后再打标（`ModelDownloadService.retagIfGguf`）；
- 重新校验时若有 `.retagged` 标记则按标记里的 MD5 比对，**否则会把改好的模型误判成损坏删掉**；
- 回归守卫：`GgufTypeRetagTest`（GGUF 是小端，`RandomAccessFile` 默认大端 —— 曾经踩过）。

## 编译开关（CMakeLists.txt）

| 开关 | 值 | 原因 |
|---|---|---|
| `LLAMA_BUILD_COMMON` | **ON** | 拿 minja（Jinja）：任意 GGUF 用自带对话模板渲染（成本：`.so` 从 3.15MB → 6.98MB） |
| `GGML_CPU_REPACK` | ON | Android ARM 关键优化 |
| `GGML_NATIVE` / `LLAMAFILE` / `OPENMP` / `LTO` / `CCACHE` | OFF | 交叉编译 / Android 上无意义或不稳 |
| `LLAMA_BUILD_{TESTS,EXAMPLES,TOOLS,SERVER}` | OFF | 只留库 |
| 链接选项 | `--gc-sections --strip-debug --exclude-libs,ALL` | `--strip-debug` 保留符号表（崩溃 backtrace 可读），不 strip 的话 `.so` 有 43.9MB |

实测（本机，NDK 25.2.9519653 / cmake 3.22.1 / ninja，-j8）：`llama` + `llama-common` 冷编约
**51 秒**；Gradle `:llamacpp:assembleDebug` 约 **1 分 13 秒**；产出 `.so` 约 **7MB**（含 Jinja）。

## JNI 接口

Kotlin 侧声明在 `:app` 的 `translationapi/llamacpp/LlamaCppNative.kt`，C++ 实现是
`src/main/cpp/llamacpp_bridge.cpp`，符号名 `Java_translationapi_llamacpp_LlamaCppNative_*`
（改函数名/参数必须两边同时改，否则运行期 `UnsatisfiedLinkError`）。

两条 prompt 通道：

- **Hy-MT2 通道**（`nativeTranslate*` / `nativeTranslateChat`）：prompt 由桥接按
  `[BOS]{指令}<sys_end><hy_User>{原文}<hy_Assistant>` 手工拼（vocab 里带 `hy_` 角色标记的模型自动走这条），
  前缀 KV 缓存 = 固定指令；
- **通用通道**（`nativeTranslateRaw*` + `nativeFormatChat`）：prompt 由 `:app` 用模型自带 Jinja 模板渲染好，
  桥接只做 tokenize → 前缀缓存 → 采样 → detokenize，前缀 KV 缓存 = 渲染结果里原文之前的部分。

运行时保留（相对老桥接未变）：前缀 KV 缓存、双 pinned 线程池、流式回调、warmUp、
崩溃处理器 + 统一日志，`use_mmap/use_mlock` → `load_mode=LLAMA_LOAD_MODE_NONE`，
取消改用上游 `llama_set_abort_callback`（能中断 prefill），删掉了手写 mlock 探测与 `n_batch` 钳位
（依据 2026-09 上游源码审计，见提交信息）。
