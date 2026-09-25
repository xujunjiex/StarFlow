# 初始化 llama.cpp submodule（Windows 友好的方式）
#
# 背景：llama.cpp 的 tools/ui 目录里有超过 260 字符的路径，Windows 默认长路径关闭时
#       `git submodule update --init` 会在 checkout 阶段失败（Filename too long）。
#       而我们根本不需要 tools/examples/docs/tests，所以这里：
#         1) 开启 core.longpaths
#         2) 用 sparse-checkout 只检出构建需要的目录
#         3) 按 .gitmodules 里 pin 的提交 checkout
#
# 用法（在仓库根目录）：pwsh -File scripts/setup-llamacpp-submodule.ps1

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$subRel   = 'llamacpp/src/main/cpp/llama.cpp'
$subPath  = Join-Path $repoRoot $subRel
$url      = 'https://github.com/ggml-org/llama.cpp'

# 1) 目标提交：优先用索引里记录的 gitlink（submodule pin），拿不到就退回 .gitmodules + 手工指定
$pinned = (git -C $repoRoot ls-tree HEAD -- $subRel 2>$null)
if ($pinned) { $pinned = ($pinned -split '\s+')[2] }
if (-not $pinned) { throw "无法从 git 索引读出 $subRel 的 pin 提交，请先 git add 该 submodule" }
Write-Host "pin = $pinned"

git -C $repoRoot config core.longpaths true

# 2) 克隆 / 复用
if (-not (Test-Path (Join-Path $subPath '.git'))) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $subPath) | Out-Null
    git clone --depth 1 --no-checkout $url $subPath
} else {
    Write-Host "已存在，跳过 clone"
}
git -C $subPath config core.longpaths true
git -C $subPath config core.sparseCheckout true

# 只留构建需要的目录：llama.cpp 的 add_subdirectory 会用到 ggml/ src/ include/ common/ vendor/ cmake/
$sparse = Join-Path $subPath '.git/info/sparse-checkout'
@'
/*
!/tools/
!/examples/
!/docs/
!/tests/
'@ | Set-Content -Path $sparse -Encoding ASCII

# 3) 检出 pin 的提交（GitHub 支持按 SHA 直取）
git -C $subPath fetch --depth 1 origin $pinned
git -C $subPath checkout -q FETCH_HEAD

$head = git -C $subPath log -1 --format='%h %s'
Write-Host "submodule HEAD = $head"
Write-Host "完成。若上面 HEAD 不是你期望的提交，检查 .gitmodules / gitlink 是否被改动。"
