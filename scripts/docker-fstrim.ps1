#Requires -Version 5.1
<#
.SYNOPSIS
    只做 fstrim —— Docker 必须处于运行状态（ext4 挂着才能交还空闲块）。

.DESCRIPTION
    为什么单独拆一个脚本（2026-10-07 实测教训）：

    上一版docker-disk-rescue.ps1 的执行顺序是
        停 Docker → wsl --shutdown → compact
    结果只回收 4.75GB（124.09 → 119.34）。用 fsutil file queryextents 取证：
        vhdx 逻辑 119.34GB，其中 80.92GB 是「NTFS 已分配的连续块」，
        VCN 完全连续 ⇒ 没有空洞可压。

    根因：ext4 只有执行过 fstrim 才知道哪些块真空了。
    不 fstrim 时 ext4 认为那些块仍在用 → vhdx 必须为其保留空间
    → NTFS 只能实打实分配 → compact 无从下手。

    而 ext4 卸载后（wsl --shutdown 之后）就无法 fstrim 了，
    所以 fstrim 必须在【Docker 运行中】单独做。

    本脚本：Docker 运行时执行，成功后你再去跑 docker-disk-rescue.ps1 做 compact。

.PARAMETER Distro
    WSL 发行版名，默认 docker-desktop。

.EXAMPLE
    # 顺序
    docker-up.ps1                                    # 或手动启动 Docker Desktop
    powershell -ExecutionPolicy Bypass -File .\scripts\docker-fstrim.ps1
    powershell -ExecutionPolicy Bypass -File .\scripts\docker-disk-rescue.ps1

.NOTES
    ⚠ 本脚本【不需要】管理员权限即可跑 fstrim 与 set-sparse
      （实测 wsl 命令本身不提权），但需要 Docker 处于运行状态。
#>
[CmdletBinding()]
param(
    [string]$Distro = "docker-desktop"
)

$ErrorActionPreference = "Continue"

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

Write-Host "=== Docker fstrim（ext4 交还空闲块）===" -ForegroundColor Green

# ── 前置检查：Docker 必须运行 ────────────────────────────
Write-Host "`n检查 Docker 是否运行..." -ForegroundColor Cyan
$dockerUp = $false
try {
    $null = & docker version --format "{{.Server.Version}}" 2>$null
    if ($LASTEXITCODE -eq 0) { $dockerUp = $true }
} catch {}

if (-not $dockerUp) {
    Write-Host "  ✗ Docker 未运行" -ForegroundColor Red
    Write-Host ""
    Write-Host "fstrim 必须 ext4 挂载着才能做，Docker 停了就不行。" -ForegroundColor Yellow
    Write-Host "请先启动 Docker Desktop 或跑 .\docker-up.ps1，等 `docker ps` 出结果后再跑本脚本。" -ForegroundColor Yellow
    exit 1
}
Write-Host "  ✓ Docker 正在运行" -ForegroundColor Green

# ── 1. 取当前基线 ───────────────────────────────────────
Write-Host "`n【1/3】压缩前基线" -ForegroundColor Cyan
$settings = Get-Content "$env:APPDATA\Docker\settings-store.json" -Raw | ConvertFrom-Json
$distroDir = $settings.CustomWslDistroDir
if (-not $distroDir) { $distroDir = Join-Path $env:LOCALAPPDATA "Docker\wsl" }
$vhdx = Join-Path $distroDir "disk\docker_data.vhdx"
$beforeGB = 0
if (Test-Path $vhdx) {
    $beforeGB = [math]::Round((Get-Item $vhdx).Length / 1GB, 2)
    Write-Host "  vhdx 大小: $beforeGB GB" -ForegroundColor Gray
}
$eFreeBefore = [math]::Round((Get-PSDrive E).Free / 1GB, 2)
Write-Host "  E 盘剩余 : $eFreeBefore GB" -ForegroundColor Gray

# ext4 内部真实用量（这才是 fstrim 后能压缩掉的理论上限）
$ext4Line = & docker run --rm alpine df -h / 2>&1 | Select-Object -Last 1
if ($ext4Line -match '\s+([\d.]+)G\s+') {
    $ext4GB = [double]$Matches[1]
    Write-Host "  ext4 内部真实用量: $ext4GB GB" -ForegroundColor Gray
    if ($beforeGB -gt 0) {
        $theoretical = [math]::Round($beforeGB - $ext4GB, 2)
        Write-Host "  ⇒ 理论可回收上限:约 $theoretical GB（vhdx 减ext4）" -ForegroundColor Yellow
    }
}

# ── 2. fstrim ───────────────────────────────────────────
Write-Host "`n【2/3】fstrim —— 让 ext4 交还空闲块" -ForegroundColor Cyan
Write-Host "  wsl -d $Distro -u root -- /sbin/fstrim -av" -ForegroundColor DarkGray
$code = Invoke-Native @("wsl", "-d", $Distro, "-u", "root", "--", "/sbin/fstrim", "-av")
if ($code -eq 0) {
    Write-Host "  ✓ fstrim 完成" -ForegroundColor Green
} else {
    Write-Host "  ✗ fstrim 退出码 $code" -ForegroundColor Yellow
    if ($code -eq 1) {
        Write-Host "    常见原因：① ext4 未挂载 ② Docker 刚启动还没就绪" -ForegroundColor DarkGray
        Write-Host "    等 30 秒再跑一次本脚本" -ForegroundColor DarkGray
    }
    Write-Host ""
    Write-Host "  试试发行版名 docker-desktop-data（老版本 Docker）：" -ForegroundColor DarkGray
    Write-Host "    .\scripts\docker-fstrim.ps1 -Distro docker-desktop-data" -ForegroundColor Yellow
}

# ── 3. set-sparse ───────────────────────────────────────
Write-Host "`n【3/3】set-sparse —— 让 vhdx 稀疏化" -ForegroundColor Cyan
Write-Host "  wsl --manage $Distro --set-sparse true" -ForegroundColor DarkGray
$code2 = Invoke-Native @("wsl", "--manage", $Distro, "--set-sparse", "true")
if ($code2 -eq 0) {
    Write-Host "  ✓ set-sparse 完成" -ForegroundColor Green
} else {
    Write-Host "  ✗ set-sparse 退出码 $code2（需 WSL ≥ 2.0）" -ForegroundColor Yellow
    Write-Host "    查版本： wsl --version" -ForegroundColor DarkGray
}

# ── 复查 ────────────────────────────────────────────────
Write-Host "`n=== 结果 ===" -ForegroundColor Green
if (Test-Path $vhdx) {
    $afterGB = [math]::Round((Get-Item $vhdx).Length / 1GB, 2)
    $attr = (Get-Item $vhdx).Attributes
    $sparse = ($attr -band [IO.FileAttributes]::SparseFile) -ne 0
    Write-Host "  vhdx 大小 : $beforeGB GB  →  ${afterGB} GB" -ForegroundColor Gray
    Write-Host "  稀疏标志  : $(if($sparse){'已启用 ✓'}else{'未启用'})" -ForegroundColor Gray
    Write-Host "  ⚠ fstrim+set-sparse 只打标记，文件大小通常不变。" -ForegroundColor DarkGray
    Write-Host "    真正缩小要靠下一步 compact。" -ForegroundColor DarkGray
} else { Write-Host "  找不到 vhdx" -ForegroundColor Yellow }

Write-Host ""
Write-Host "下一步（这才会真正缩小 vhdx）：" -ForegroundColor Cyan
Write-Host "  .\scripts\docker-disk-rescue.ps1" -ForegroundColor Yellow
Write-Host ""
Write-Host "  完整顺序：Docker 运行 → 本脚本 → docker-disk-rescue.ps1" -ForegroundColor Gray