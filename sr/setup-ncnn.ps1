# :sr 模块的 ncnn 依赖准备脚本（**源码方式**）
#
# 为什么不用官方预编译 SDK：
#   官方 `ncnn-<版本>-android-vulkan.zip` 是**用新版 NDK 编的**，引用了
#   `std::__ndk1::__libcpp_verbose_abort`，而本项目的 NDK 25.2.9519653 的
#   libc++_shared.so **不导出**该符号 → 链接期 undefined symbol。
#   硬用一个 shim 补上属于掩盖 ABI 不匹配（可能还有其它新 libc++ 符号），
#   所以这里跟 :llamacpp 一样**源码自编**，ABI 严格一致。
#
# 顺带收益：可以关掉 OpenMP（本模块只用 GPU 路径），省掉 libomp 依赖与体积。
#
# ⚠️ 版本写死（不是 latest）：ncnn 的算子实现会变，换版本必须重跑真机回归
#    （超分输出正确性 + .so 体积）。
# ⚠️ ncnn-src/ 不入库（见 sr/.gitignore）。
#
# 用法：pwsh sr/setup-ncnn.ps1

$ErrorActionPreference = 'Stop'

$NcnnVersion = '20260526'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$cpp = Join-Path $root 'src/main/cpp'
$src = Join-Path $cpp 'ncnn-src'

if (Test-Path (Join-Path $src 'CMakeLists.txt')) {
    Write-Host "ncnn 源码已就位（$src），跳过克隆。"
    exit 0
}

Write-Host "克隆 Tencent/ncnn @ $NcnnVersion（含 glslang 子模块，体积较大）..."

# 长路径（Windows）与子模块一起配好，否则 checkout 会失败
git -c core.longpaths=true clone --depth 1 --branch $NcnnVersion `
    https://github.com/Tencent/ncnn.git $src
if ($LASTEXITCODE -ne 0) { throw "git clone 失败" }

Push-Location $src
try {
    # Vulkan 后端需要 glslang；只拉这一个子模块
    git -c core.longpaths=true submodule update --init --recursive --depth 1 glslang
    if ($LASTEXITCODE -ne 0) {
        Write-Host "浅拉 glslang 失败，回退到完整拉取..."
        git -c core.longpaths=true submodule update --init --recursive glslang
        if ($LASTEXITCODE -ne 0) { throw "glslang 子模块拉取失败" }
    }
} finally {
    Pop-Location
}

Write-Host "`n完成。ncnn 源码：$src"
Write-Host "下一步：./gradlew :sr:assembleDebug（CMake 会 add_subdirectory 一起编）"
