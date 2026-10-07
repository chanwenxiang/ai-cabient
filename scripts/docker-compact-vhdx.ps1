#Requires -Version 5.1
<#
.SYNOPSIS
    压缩 docker_data.vhdx —— 让释放的空间真正还给宿主磁盘。

.DESCRIPTION
    Docker Desktop 的 WSL2 数据盘（docker_data.vhdx）是**只涨不缩**的：
    Docker 在 ext4 上删除文件只是把块标记为空闲，不会 punch hole 归还给宿主。
    所以即使 `docker-gc.ps1` 把 Docker 清到几 GB，vhdx 依然保持原来上百 GB。

    本脚本做两件事：
      1. 在 WSL 发行版内对 ext4 文件系统执行 `fstrim`（把空闲块告知虚拟磁盘）
      2. 把 vhdx 标记为 sparse（`wsl --manage <distro> --set-sparse true`），
         让未使用的块以稀疏形式表示，从而真正缩小宿主文件

    ⚠️ 本脚本会【停止 Docker Desktop】，当前所有容器会中断。
       执行前请确认没有正在跑的构建或服务。

.PARAMETER SkipTrim
    只做 --set-sparse，跳过 fstrim（trim 失败时的兜底）。

.PARAMETER Yes
    跳过交互确认（供自动化调用）。

.EXAMPLE
    .\scripts\docker-compact-vhdx.ps1
    .\scripts\docker-compact-vhdx.ps1 -Yes

.NOTES
    依据：settings-store.json 的 CustomWslDistroDir 指向数据盘位置。
    步骤来自 Docker Desktop 官方文档 "Disk image size" 章节。
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [switch]$SkipTrim,
    [switch]$Yes
)

$ErrorActionPreference = "Stop"

# PS 5.1 + native exe：stderr 的正常进度会被当终止错误
# ⚠️ 参数名绝不能叫 $Args —— PowerShell 自动变量，splat 会拿到整个位置参数列表。
# 成败判 $LASTEXITCODE，不判脚本退出码。
function Invoke-Native {
    param([string[]]$NativeArgList)
    if ($null -eq $NativeArgList -or $NativeArgList.Count -eq 0) {
        Write-Host "  ✗ Invoke-Native: 参数为空" -ForegroundColor Red
        return 1
    }
    $previousEap = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
    & @NativeArgList 2>&1 | ForEach-Object { Write-Host "  $_" }
        $code = $LASTEXITCODE
        return $code
    } finally {
        $ErrorActionPreference = $previousEap
    }
}

function Invoke-Checked {
    param([string]$What, [string[]]$NativeArgList)
    Write-Host "▶ $What" -ForegroundColor Cyan
    $code = Invoke-Native -NativeArgList $NativeArgList
    if ($code -ne 0) {
        Write-Host "  ✗ 退出码 $code" -ForegroundColor Yellow
    }
    return $code
}

# ── 0. 前置检查 ────────────────────────────────────────────
Write-Host "=== Docker vhdx 压缩 ===" -ForegroundColor Green

$settingsPath = Join-Path $env:APPDATA "Docker\settings-store.json"
if (-not (Test-Path $settingsPath)) {
    Write-Error "找不到 Docker settings-store.json（$settingsPath），无法定位数据盘"
}
$settings = Get-Content $settingsPath -Raw | ConvertFrom-Json
$distroDir = $settings.CustomWslDistroDir
if (-not $distroDir) {
    Write-Host "未设置 CustomWslDistroDir，使用默认路径" -ForegroundColor Yellow
    $distroDir = Join-Path $env:LOCALAPPDATA "Docker\wsl"
}
$vhdxPath = Join-Path $distroDir "disk\docker_data.vhdx"

if (-not (Test-Path $vhdxPath)) {
    Write-Error "找不到 docker_data.vhdx（$vhdxPath）"
}

$beforeGB = [math]::Round((Get-Item $vhdxPath).Length / 1GB, 2)
Write-Host "数据盘位置: $vhdxPath" -ForegroundColor Gray
Write-Host "压缩前大小: $beforeGB GB" -ForegroundColor Gray

# 读 ext4 内部真实用量（对照项：压缩后 Docker 侧用量不变，变的是 vhdx 文件大小）
Write-Host "`n提示：可先在容器内看 Docker 真实用量作对照：" -ForegroundColor Gray
Write-Host "  docker run --rm alpine df -h /" -ForegroundColor DarkGray

# ── 0.5 WSL 版本前置检查 ──────────────────────────────────
# wsl --set-sparse 需要 WSL ≥ 2.0；WSL 1 不支持，跑了也没用。
Write-Host "`n检查 WSL 版本..." -ForegroundColor Cyan
$wslVerRaw = & wsl --version 2>&1 | Out-String
if ($LASTEXITCODE -eq 0 -and $wslVerRaw -match 'Version:\s*(\d+)') {
    $wslMajor = [int]$Matches[1]
    Write-Host "  WSL 主版本: $wslMajor" -ForegroundColor Gray
    if ($wslMajor -lt 2) {
        Write-Host "  ⚠️ WSL 1 不支持 --set-sparse，压缩不会生效。" -ForegroundColor Yellow
        Write-Host "     请升级到 WSL 2（`wsl --update`）或改用 Docker Desktop 迁盘。" -ForegroundColor Yellow
    }
} else {
    Write-Host "  无法读取 WSL 版本（可能未安装或不在 PATH）" -ForegroundColor Yellow
    Write-Host "  请自行确认 WSL ≥ 2.0，否则 fstrim 之外的两步无效" -ForegroundColor Yellow
}

# ── 1. 确认 ────────────────────────────────────────────────
if (-not $Yes -and -not $SkipTrim) {
    $running = @(& docker ps --format "{{.Names}}" 2>$null)
    if ($running.Count -gt 0) {
        Write-Host "⚠️  以下容器正在运行，压缩会中断它们：" -ForegroundColor Yellow
        $running | ForEach-Object { Write-Host "    $_" -ForegroundColor Yellow }
    }
    Write-Host "`n本操作会停止 Docker Desktop。" -ForegroundColor Yellow
    $ans = Read-Host "确认继续？(输入 yes 继续，其他取消)"
    if ($ans -ne "yes") {
        Write-Host "已取消" -ForegroundColor Gray
        exit 0
    }
}

# ── 2. 停 Docker ───────────────────────────────────────────
Write-Host "`n【1/4】停止 Docker Desktop" -ForegroundColor Cyan
$dockerExe = "C:\Program Files\Docker\Docker\Docker Desktop.exe"
if (Get-Process -Name "Docker Desktop" -ErrorAction SilentlyContinue) {
    Write-Host "  正在关闭 Docker Desktop..."
    Stop-Process -Name "Docker Desktop" -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 5
}
Write-Host "  ✓ 已停止" -ForegroundColor Green

# ── 3. fstrim ──────────────────────────────────────────────
if (-not $SkipTrim) {
    Write-Host "`n【2/4】在 WSL 内执行 fstrim" -ForegroundColor Cyan
    # docker-desktop 是运行 Docker 的发行版名
    $code = Invoke-Checked "wsl -d docker-desktop -u root -- fstrim -av" `
        @("wsl", "-d", "docker-desktop", "-u", "root", "--", "fstrim", "-av")
    if ($code -ne 0) {
        Write-Host "  fstrim 失败（发行版名可能不同），可加 -SkipTrim 重试" -ForegroundColor Yellow
    }
} else {
    Write-Host "`n【2/4】fstrim —— 已按 -SkipTrim 跳过" -ForegroundColor Gray
}

# ── 4. set-sparse ──────────────────────────────────────────
Write-Host "`n【3/4】标记 vhdx 为 sparse" -ForegroundColor Cyan
$code = Invoke-Checked "wsl --manage docker-desktop --set-sparse true" `
    @("wsl", "--manage", "docker-desktop", "--set-sparse", "true")
if ($code -ne 0) {
    Write-Host "  ⚠️ --set-sparse 失败。这要求 WSL ≥ 2.0（Win10 21H2+ / Win11）。" -ForegroundColor Yellow
    Write-Host "     可用 Hyper-V 方案：Optimize-VHD -Mode Full（需管理员 + Hyper-V 模块）" -ForegroundColor Yellow
}

# ── 5. 回收 + 验证 ────────────────────────────────────────
Write-Host "`n【4/4】验证结果" -ForegroundColor Cyan
$afterGB = [math]::Round((Get-Item $vhdxPath).Length / 1GB, 2)
Write-Host "压缩前: $beforeGB GB" -ForegroundColor Gray
Write-Host "压缩后: $afterGB GB" -ForegroundColor Gray
$delta = [math]::Round($beforeGB - $afterGB, 2)
if ($delta -gt 0) {
    Write-Host "回收  : $delta GB 🎉" -ForegroundColor Green
} else {
    Write-Host "回收  : 0 GB —— 文件大小未变" -ForegroundColor Yellow
    Write-Host "        可能原因：① 未执行 fstrim ② WSL 版本过低 ③ 文件系统空闲块不足" -ForegroundColor Yellow
}

Write-Host "`n提示：现在可以重启 Docker Desktop" -ForegroundColor Gray
Write-Host "  Start-Process '$dockerExe'" -ForegroundColor DarkGray
