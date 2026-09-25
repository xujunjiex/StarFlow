# ============================================================================
#  :llamacpp —— llama.cpp 源码自编 + 我们的 JNI 桥
#
#  两件事：
#    1. 把 llama.cpp 整个工程作为子项目编进来（add_subdirectory）
#    2. 编出 libllamacpp.so —— Kotlin 侧 System.loadLibrary("llamacpp") 加载的桥
#
#  ⚠️ 必须放在独立 Gradle 模块里编：:app 的顶层 CMake 是 sentencepiece 工程，
#     它 include_directories() 了 src/（里面有 sentencepiece 的 common.h），
#     而 llama.cpp 也有 common/common.h —— 同处一个 CMake 工程会撞头文件。
#
#  ⚠️ 不要加 "-Wl,--strip-all"：崩溃处理器要靠符号表把地址翻成函数名，
#     strip 掉之后 starflow.log 里的 backtrace 只剩地址。
# ============================================================================

cmake_minimum_required(VERSION 3.22.1)
project(llamacpp-android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

# ----------------------------------------------------------------------------
#  llama.cpp / ggml 关键开关（必须在 add_subdirectory 之前 FORCE，防上游改默认值）
# ----------------------------------------------------------------------------

# 静态链接，最终只输出一个 libllamacpp.so
set(BUILD_SHARED_LIBS  OFF CACHE BOOL "" FORCE)

# ggml：Android 是交叉编译，绝不能让 ggml 跑 -march=native
set(GGML_NATIVE        OFF CACHE BOOL "" FORCE)
# Bionic 的 OpenMP 支持不稳；llama.cpp 自带线程池够用（我们还显式挂了 pinned pool）
set(GGML_OPENMP        OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE     OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE        OFF CACHE BOOL "" FORCE)
set(GGML_LTO           OFF CACHE BOOL "" FORCE)
# CPU repack：Android ARM 关键优化（Q4_0 → Q4_X_X 运行时转换），保持开启
set(GGML_CPU_REPACK    ON  CACHE BOOL "" FORCE)
# backend 全部静态链进来，不做 dynamic load
set(GGML_BACKEND_DL    OFF CACHE BOOL "" FORCE)

# llama
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
# ⭐ common 库必须开：minja（Jinja）对话模板渲染 / sampler 都在里面，
#   这是"任意 GGUF 都能正确拼 prompt"的关键（见 patches/README.md）
set(LLAMA_BUILD_COMMON   ON  CACHE BOOL "" FORCE)
set(LLAMA_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)
set(LLAMA_LLGUIDANCE     OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)

# ----------------------------------------------------------------------------
#  拉入 llama.cpp（submodule，pin 见 patches/README.md）
# ----------------------------------------------------------------------------
add_subdirectory(llama.cpp build-llama)

# ----------------------------------------------------------------------------
#  我们的 JNI 桥
# ----------------------------------------------------------------------------
add_library(llamacpp SHARED
    llamacpp_bridge.cpp
)

target_include_directories(llamacpp PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/common
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include
)

target_link_libraries(llamacpp PRIVATE
    llama          # libllama.a
    llama-common   # libllama-common.a —— minja 模板 / sampler（target 名是 llama-common）
    ggml           # libggml.a（内部已链 ggml-base / ggml-cpu）
    android        # __android_log_print
    log
)

target_link_options(llamacpp PRIVATE
    "-Wl,--gc-sections"
    # 去掉 DWARF：NDK 工具链默认带 -g，不 strip 的话 .so 有 43.9MB；
    # --strip-debug 保留 .symtab（3.9MB，崩溃 backtrace 还能用 nm/addr2line 还原函数名），
    # 比 --strip-all（3.15MB、符号全无）只多 0.8MB → 选 strip-debug。
    "-Wl,--strip-debug"
    "-Wl,--exclude-libs,ALL"
)

set_target_properties(llamacpp PROPERTIES
    C_VISIBILITY_PRESET   hidden
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)

message(STATUS "[llamacpp] ABI=${ANDROID_ABI} build=${CMAKE_BUILD_TYPE} STL=${ANDROID_STL}")
