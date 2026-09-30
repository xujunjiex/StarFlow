cmake_minimum_required(VERSION 3.22.1)
project(starflow_sr CXX)

# ─────────────────────────────────────────────────────────────────────────────
# :sr —— 超分原生引擎（ncnn + Vulkan，源码自编）
#
# 为什么独立成模块（与 :llamacpp 同理）：
#   :app 的 CMakeLists 是 **sentencepiece** 的，把它和 ncnn 揉在一起会让两套
#   第三方头文件/编译选项互相污染。这里只产出 `libstarflow_sr.so`，
#   Kotlin 侧的引擎/模型管理仍在 :app（模块只暴露 JNI 门面 SrNcnnNative）。
#
# 为什么 ncnn **源码自编**而不是用官方预编译 SDK：
#   官方 `ncnn-<版本>-android-vulkan.zip` 是用**新版 NDK**编的，引用
#   `std::__ndk1::__libcpp_verbose_abort`，而本项目 NDK 25.2.9519653 的
#   libc++_shared.so 不导出该符号 → 链接期 undefined symbol。
#   自编可保证 ABI 严格一致，也能关掉用不到的部件（OpenMP/工具/示例）。
#
# ⚠️ ncnn-src/ 不入库（见 sr/.gitignore）：首次 clone 后跑 `pwsh sr/setup-ncnn.ps1`。
# ─────────────────────────────────────────────────────────────────────────────

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── ncnn：只编我们需要的那部分 ────────────────────────────────────────────────
set(NCNN_VULKAN ON CACHE BOOL "" FORCE)             # 核心：GPU 跑超分
set(NCNN_OPENMP OFF CACHE BOOL "" FORCE)            # 本模块只用 GPU 路径 → 省掉 libomp
set(NCNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_MOBILE_BENCHMARK OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_MOBILE_TEST OFF CACHE BOOL "" FORCE)
set(NCNN_DISABLE_RTTI OFF CACHE BOOL "" FORCE)      # 引擎用了多态/异常
set(NCNN_DISABLE_EXCEPTION OFF CACHE BOOL "" FORCE)
set(NCNN_SIMPLEOCV OFF CACHE BOOL "" FORCE)
set(NCNN_PLATFORM_API ON CACHE BOOL "" FORCE)       # Android AAsset / 硬件信息
set(NCNN_SHARED_LIB OFF CACHE BOOL "" FORCE)        # 静态链进 libstarflow_sr.so
# ⚠️ 变量名必须是 NCNN_SHARED_LIB：原来写的 NCNN_BUILD_SHARED_LIBS 在 ncnn 里**不存在**，
#    是个静默空操作（碰巧 NCNN_SHARED_LIB 默认就是 OFF 才没出事，改名后才是真的锁住）。
add_subdirectory(ncnn-src)

include_directories(${CMAKE_CURRENT_SOURCE_DIR}/engines)
include_directories(${CMAKE_CURRENT_BINARY_DIR})

find_library(log-lib log)
find_library(vulkan-lib vulkan)
find_library(android-lib android)

# .comp (GLSL) -> .comp.hex.h（纯 text->hex，与上游 nihui 的脚本一致）
# ⚠️ realcugan/realesrgan 用的是**已经生成好的** *.comp.hex.h（来自 koto 的构建产物），
#    不参与这里；只有 waifu2x/srmd 需要现生成。
macro(sr_add_shader SHADER_SRC)
    get_filename_component(SHADER_SRC_NAME_WE ${SHADER_SRC} NAME_WE)
    set(SHADER_COMP_HEADER ${CMAKE_CURRENT_BINARY_DIR}/${SHADER_SRC_NAME_WE}.comp.hex.h)
    add_custom_command(
        OUTPUT ${SHADER_COMP_HEADER}
        COMMAND ${CMAKE_COMMAND}
                -DSHADER_SRC=${CMAKE_CURRENT_SOURCE_DIR}/${SHADER_SRC}
                -DSHADER_COMP_HEADER=${SHADER_COMP_HEADER}
                -P "${CMAKE_CURRENT_SOURCE_DIR}/generate_shader_comp_header.cmake"
        DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/${SHADER_SRC}
        COMMENT "Preprocessing shader ${SHADER_SRC_NAME_WE}.comp")
    set_source_files_properties(${SHADER_COMP_HEADER} PROPERTIES GENERATED TRUE)
    list(APPEND SR_SHADER_HDRS ${SHADER_COMP_HEADER})
endmacro()

sr_add_shader(engines/waifu2x_preproc.comp)
sr_add_shader(engines/waifu2x_postproc.comp)
sr_add_shader(engines/waifu2x_preproc_tta.comp)
sr_add_shader(engines/waifu2x_postproc_tta.comp)
sr_add_shader(engines/srmd_preproc.comp)
sr_add_shader(engines/srmd_postproc.comp)
sr_add_shader(engines/srmd_preproc_tta.comp)
sr_add_shader(engines/srmd_postproc_tta.comp)

add_library(starflow_sr SHARED
    sr_jni.cpp
    engines/waifu2x.cpp
    engines/srmd.cpp
    engines/realcugan.cpp
    engines/realesrgan.cpp
    ${SR_SHADER_HDRS})

target_compile_options(starflow_sr PRIVATE
    -O2
    -fvisibility=hidden          # 只导出 JNIEXPORT 那几个符号
    -ffunction-sections -fdata-sections)

target_link_options(starflow_sr PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL       # 静态库符号不导出，显著减体积
    -Wl,--no-undefined)

target_link_libraries(starflow_sr
    ncnn
    ${log-lib}
    ${vulkan-lib}
    ${android-lib})
