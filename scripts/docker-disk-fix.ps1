#Requires -Version 5.1
<#
.SYNOPSIS
    一键体检 + 修复 Docker 磁盘膨胀（交互式，每步都问确认）

.DESCRIPTION
    本脚本不需要手动跑 docker-compact-vhdx.ps1，它自己会：
      1. 测出ext4 真实用量 / vhdx 占盘 / 宿主盘剩余三个数字
      2. 清空回收站（白捡的 2.9GB）
      3. 压缩 vhdx（fstrim + set-sparse）
      4. 复查并报告

    ⚠️ 会停止 Docker Desktop，当前容器中断。
       postgres / redis 重启 Docker 后会自动恢复。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\scripts\docker-disk-fix.ps1

.NOTES
    背景：vhdx 是固定分配的动态虚拟磁盘，ext4 删文件不 punch hole，
    所以 `docker system df` 变小 ≠ Windows 盘释放。必须 fstrim + set-sparse。
#>
[CmdletBinding()]
param(
    [switch]$SkipRecycleBin,
    [switch]$SkipCompact,
    [switch]$Yes
)

$ErrorActionPreference = "Stop"

function Invoke-Native {
    param([string[]]$NativeArgList)
    if ($null -eq $NativeArgList -or $NativeArgList.Count -eq 0) { return 1 }
    $prev = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
    & @NativeArgList 2>&1 | ForEach-Object { Write-Host "  $_" }
        return $LASTEXITCODE
    } finally { $ErrorActionPreference = $prev }
}

function Write-Step { param([string]$T) Write-Host "`n▶ $T" -ForegroundColor Cyan }
function Write-Ok { param([string]$T) Write-Host "  ✓ $T" -ForegroundColor Green }
function Write-Warn2 { param([string]$T) Write-Host "  ⚠ $T" -ForegroundColor Yellow }

# ══════════════════════════════════════════════════════════
# Step 1: 体检 —— 三个数字对照
# ══════════════════════════════════════════════════════════
Write-Step "Step 1/5 体检：三个数字对照"

# vhdx 路径
$settingsPath = Join-Path $env:APPDATA "Docker\settings-store.json"
if (-not (Test-Path $settingsPath)) {
    Write-Host "找不到 Docker 设置文件，无法定位数据盘" -ForegroundColor Red
    exit 1
}
$settings = Get-Content $settingsPath -Raw | ConvertFrom-Json
$distroDir = $settings.CustomWslDistroDir
if (-not $distroDir) { $distroDir = Join-Path $env:LOCALAPPDATA "Docker\wsl" }
$vhdxPath = Join-Path $distroDir "disk\docker_data.vhdx"

if (-not (Test-Path $vhdxPath)) {
    Write-Host "找不到 docker_data.vhdx：$vhdxPath" -ForegroundColor Red
    exit 1
}

$vhdxGB = [math]::Round((Get-Item $vhdxPath).Length / 1GB, 2)
Write-Host "  vhdx 文件    : $vhdxGB GB   ($vhdxPath)"

$attr = (Get-Item $vhdxPath).Attributes
$isSparse = ($attr -band [IO.FileAttributes]::SparseFile) -ne 0
Write-Host "  稀疏标志: $(if($isSparse){'已启用'}else{'未启用'})"

# 宿主盘剩余
$drive = (Get-Item $vhdxPath).PSDrive.Name
$drv = Get-PSDrive $drive
$freeGB = [math]::Round($drv.Free / 1GB, 1)
$usedGB = [math]::Round($drv.Used / 1GB, 1)
$totalGB = [math]::Round(($drv.Free + $drv.Used) / 1GB, 1)
Write-Host "  宿主盘 $drive`: 剩余 ${freeGB}GB / 共 ${totalGB}GB（已用 ${usedGB}GB）"

# ext4 内部真实用量
$ext4Line = & docker run --rm alpine df -h / 2>&1 | Select-Object -Last 1
if ($ext4Line -match '\s+([\d.]+)G\s+') {
    $ext4UsedGB = [double]$Matches[1]
    Write-Host "  ext4 内部真实用量: ${ext4UsedGB} GB"
    $hole = [math]::Round($vhdxGB - $ext4UsedGB, 2)
    Write-Host "  ⇒ 空洞（ext4 已释放但 Windows 没拿回）: $hole GB" -ForegroundColor Yellow
} else {
    $ext4UsedGB = $null
    Write-Warn2 "无法读取 ext4 用量（Docker 可能没启动）"
}

if ($freeGB -lt 20) {
    Write-Host "`n  🔴 宿主盘只剩 ${freeGB}GB —— 这是紧急状态" -ForegroundColor Red
}

# ══════════════════════════════════════════════════════════
# Step 2: 清空回收站
# ══════════════════════════════════════════════════════════
Write-Step "Step 2/5 清空回收站"
if ($SkipRecycleBin) {
    Write-Warn2 "已按 -SkipRecycleBin 跳过"
} else {
    $recycle = 0
    try {
        $recycle = (Get-ChildItem "$drive\`\$RECYCLE.BIN" -Recurse -File -Force -ErrorAction SilentlyContinue |
                    Measure-Object Length -Sum).Sum
    } catch {}
    if ($recycle -gt 0) {
        Write-Host "  回收站占用: $([math]::Round($recycle/1GB,2)) GB"
        if ($Yes) {
            Clear-RecycleBin -Force -ErrorAction SilentlyContinue
            Write-Ok "已清空"
        } else {
            $a = Read-Host "  清空回收站？(yes 继续)"
            if ($a -eq "yes") {
                Clear-RecycleBin -Force -ErrorAction SilentlyContinue
                Write-Ok "已清空"
            } else { Write-Warn2 "已跳过" }
        }
    } else {
        Write-Host "  回收站本来就是空的"
    }
}

# ══════════════════════════════════════════════════════════
# Step 3: 停Docker
# ══════════════════════════════════════════════════════════
Write-Step "Step 3/5 停止 Docker Desktop"
$running = @(& docker ps --format "{{.Names}}" 2>$null)
if ($running.Count -gt 0) {
    Write-Host "  以下容器正在运行，压缩会中断它们：" -ForegroundColor Yellow
    $running | ForEach-Object { Write-Host "    $_" -ForegroundColor Yellow }
}
if (-not $Yes) {
    $a = Read-Host "`n  继续？(yes 继续)"
    if ($a -ne "yes") { Write-Host "已取消" -ForegroundColor Gray; exit 0 }
}
if (Get-Process -Name "Docker Desktop" -ErrorAction SilentlyContinue) {
    Stop-Process -Name "Docker Desktop" -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 6
}
Write-Ok "Docker Desktop 已停止"

# ══════════════════════════════════════════════════════════
# Step 4: fstrim + set-sparse
# ══════════════════════════════════════════════════════════
Write-Step "Step 4/5 压缩 vhdx（fstrim + set-sparse）"

if ($SkipCompact) {
    Write-Warn2 "已按 -SkipCompact 跳过"
} else {
    $wslVer = & wsl --version 2>&1 | Out-String
    if ($LASTEXITCODE -eq 0 -and $wslVer -match 'Version:\s*(\d+)') {
        if ([int]$Matches[1] -lt 2) {
            Write-Warn2 "WSL 版本 < 2.0，--set-sparse 不支持，压缩会无效。请先 'wsl --update'"
        }
    } else {
        Write-Warn2 "读不到 WSL 版本，继续尝试（--set-sparse 需 WSL ≥ 2.0）"
    }

    Write-Host "`n  [1/2] fstrim —— 让 ext4 把空闲块标记为可回收"
    $c1 = Invoke-Native @("wsl", "-d", "docker-desktop", "-u", "root", "--", "fstrim", "-av")
    if ($c1 -eq 0) { Write-Ok "fstrim 完成" } else { Write-Warn2 "fstrim 退出码 $c1（发行版名可能不同，不影响下一步）" }

    Write-Host "`n  [2/2] set-sparse —— 让 vhdx 稀疏化，空闲块真正还给 NTFS"
    $c2 = Invoke-Native @("wsl", "--manage", "docker-desktop", "--set-sparse", "true")
    if ($c2 -eq 0) { Write-Ok "set-sparse 完成" } else {
        Write-Warn2 "set-sparse 退出码 $c2"
        Write-Host"     可能原因：WSL < 2.0，或需要管理员权限" -ForegroundColor DarkGray
    }
}

# ══════════════════════════════════════════════════════════
# Step 5: 复查
# ══════════════════════════════════════════════════════════
Write-Step "Step 5/5 复查"
$afterGB = [math]::Round((Get-Item $vhdxPath).Length / 1GB, 2)
$attr2 = (Get-Item $vhdxPath).Attributes
$sparse2 = ($attr2 -band [IO.FileAttributes]::SparseFile) -ne 0
$drv2 = Get-PSDrive $drive
$free2 = [math]::Round($drv2.Free / 1GB, 1)

Write-Host "  vhdx 大小    : $vhdxGB GB  →  ${afterGB}GB"
Write-Host "  稀疏标志      : $(if($isSparse){'未启用'}else{'未启用'})  →  $(if($sparse2){'已启用 ✓'}else{'仍未启用'})"
Write-Host "  宿主盘剩余    : ${freeGB}GB  →  ${free2}GB"
$delta = [math]::Round($vhdxGB - $afterGB, 2)
Write-Host "  回收          : $delta GB" -ForegroundColor $(if ($delta -gt 0) { 'Green' } else { 'Yellow' })

if ($delta -le 0) {
    Write-Host "`n  ⚠ 文件大小没变。常见原因：" -ForegroundColor Yellow
    Write-Host "    ① fstrim 没成功（发行版名不是 docker-desktop）" -ForegroundColor DarkGray
    Write-Host "    ② WSL < 2.0，不支持 --set-sparse" -ForegroundColor DarkGray
    Write-Host "    ③ 需要管理员权限重试" -ForegroundColor DarkGray
    Write-Host "    ④ 替代方案：Docker Desktop → Settings → Resources → Advanced →" -ForegroundColor DarkGray
    Write-Host "      Disk image location 把数据盘搬到 D 盘（会重建数据盘）" -ForegroundColor DarkGray
}

Write-Host "`n  现在可以重启 Docker Desktop：" -ForegroundColor Gray
Write-Host "    Start-Process 'C:\Program Files\Docker\Docker\Docker Desktop.exe'" -ForegroundColor DarkGray
Write-Host "  postgres / redis 会自动恢复（Docker 有 restart policy）。" -ForegroundColor DarkGray