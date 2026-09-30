# 安装 Gradle 机器级拦截器（L1）
#
# 把 guard/gradle-init/device-guard.gradle 装到 ~/.gradle/init.d/。
# 装完后 **所有 Gradle 项目、所有调用方** 都会在任务图里拦截
# 含 uninstall / 以 connected 开头 / managedDevice 的任务。
#
# ⚠️ 为什么必须在 ~/.gradle/init.d/ 而不是项目内：
#    项目内的规则会被 git clean / 换分支 / 重新 clone 清掉；
#    init.d 是机器级的，甩不掉 —— 这正是防止"下次又忘了"的关键。
#
# 用法：pwsh guard/install-gradle-guard.ps1

$ErrorActionPreference = 'Stop'
$src = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'gradle-init/device-guard.gradle'
$dstDir = Join-Path $env:USERPROFILE '.gradle/init.d'
$dst = Join-Path $dstDir 'device-guard.gradle'

if (-not (Test-Path $src)) { throw "找不到 $src" }
New-Item -ItemType Directory -Force -Path $dstDir | Out-Null

if (Test-Path $dst) {
    $a = (Get-FileHash $src).Hash; $b = (Get-FileHash $dst).Hash
    if ($a -eq $b) { Write-Host "已是最新（$dst）"; exit 0 }
    Copy-Item $dst "$dst.bak" -Force
    Write-Host "旧的已备份到 $dst.bak"
}
Copy-Item $src $dst -Force
Write-Host "已安装: $dst"
Write-Host ""
Write-Host "验证（应看到 BLOCKED）："
Write-Host "  .\gradlew.bat :app:connectedCheck --dry-run"
