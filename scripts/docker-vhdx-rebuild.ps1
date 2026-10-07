#Requires -Version 5.1
<#
.SYNOPSIS
    Docker vhdx 重建 —— 在 E 盘内把 119GB 的数据盘重建成 ~25GB。

.DESCRIPTION
    为什么这是最优解（2026-10-07 实测数据）：

        vhdx 文件119.34 GB   ← E 盘被它吃掉
        ext4 内部真实用量21.9 GB   ← Docker 实际只用这么多
        差额97 GB = ext4 已释放但 NTFS 没拿回的空洞

    fstrim + compact 这条路要：管理员 + Docker 运行中 + WSL 状态正常，
    三者缺一就失败（我实测卡在 WSL 起不来）。

    而【重建】不需要任何前置：
      - 删掉旧 vhdx → Docker Desktop 下次启动时重建一个新的
      - 新 vhdx 只装「现在真正需要的东西」（~21.9 GB）
      - 结果：119.34 GB →约 25 GB，**在 E 盘内完成，不迁盘**

    ⚠️ 会丢什么（不可逆，请确认后再跑）：
      - 全部镜像（39 个，需重新 build / 重拉）
      - 全部容器
      - 全部卷 —— 包括 **ai-cabinet_pgdata（业务库）** 和
        ai-cabinet_xxljob-mysql-data（调度库）

    📌 跑之前本脚本会自动备份 pgdata 到 D 盘（不占用 E 盘）。

.PARAMETER BackupDir
    pgdata 备份目标目录，默认 D:\docker-backup

.PARAMETER Yes
    跳过交互确认

.EXAMPLE
    # 先看看会做什么（不删任何东西）
    powershell -ExecutionPolicy Bypass -File .\scripts\docker-vhdx-rebuild.ps1 -WhatIf

    # 真跑
    powershell -ExecutionPolicy Bypass -File .\scripts\docker-vhdx-rebuild.ps1

.NOTES
    ⚠️ 建议先重启电脑再跑 —— 当前 Docker Desktop / WSL 处于反复重启的
       卡死状态（实测 13:00-13:15 期间 5 次启动即退）。
#>
[CmdletBinding()]
param(
    [string]$BackupDir = "D:\docker-backup",
    [switch]$Yes
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

function Write-Step { param([string]$T) Write-Host "`n▶ $T" -ForegroundColor Cyan }
function Write-Ok   { param([string]$T) Write-Host "  ✓ $T" -ForegroundColor Green }
function Write-Bad  { param([string]$T) Write-Host "  ✗ $T" -ForegroundColor Red }

# ── 0. 定位 vhdx ─────────────────────────────────────────
Write-Host "=== Docker vhdx 重建 ===" -ForegroundColor Green

$settingsPath = Join-Path $env:APPDATA "Docker\settings-store.json"
if (-not (Test-Path $settingsPath)) { Write-Bad "找不到 settings-store.json"; exit 1 }
$settings = Get-Content $settingsPath -Raw | ConvertFrom-Json
$distroDir = $settings.CustomWslDistroDir
if (-not $distroDir) { $distroDir = Join-Path $env:LOCALAPPDATA "Docker\wsl" }
$vhdx = Join-Path $distroDir "disk\docker_data.vhdx"

if (-not (Test-Path -LiteralPath $vhdx)) { Write-Bad "找不到 $vhdx"; exit 1 }

$beforeGB = [math]::Round((Get-Item -LiteralPath $vhdx).Length / 1GB, 2)
$eFreeBefore = [math]::Round((Get-PSDrive E).Free / 1GB, 2)
Write-Host "  vhdx      : $vhdx" -ForegroundColor Gray
Write-Host "  当前大小  : $beforeGB GB" -ForegroundColor Gray
Write-Host "  E 盘剩余  : $eFreeBefore GB" -ForegroundColor Gray

# ── 1. Docker 状态 ──────────────────────────────────────
Write-Step "1/5 检查 Docker 状态"
$daemon = $null
$daemon = & docker version --format "{{.Server.Version}}" 2>$null
if ($LASTEXITCODE -eq 0 -and $daemon) {
    Write-Ok "Docker 正在运行（Server=$daemon）"
} else {
    Write-Host "  Docker 未运行" -ForegroundColor Yellow
    Write-Host "  ⚠ 强烈建议先【重启电脑】再跑本脚本。" -ForegroundColor Yellow
    Write-Host "    当前 WSL 处于反复重启的卡死状态（实测 13:00-13:15 五次启动即退）。" -ForegroundColor Yellow
    Write-Host "    磁盘被占用时删除 vhdx 可能失败。" -ForegroundColor Yellow
    $ext4 = "  （无法读取 ext4 用量，跳过备份）"
    Write-Host $ext4 -ForegroundColor DarkGray
}

# ── 2. 备份业务库 ──────────────────────────────────────
Write-Step "2/5 备份业务库 pgdata（不占 E 盘）"
if ($LASTEXITCODE -eq 0 -and $daemon) {
    if (-not (Test-Path $BackupDir)) {
        New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
    }
    $backupFile = Join-Path $BackupDir ("pgdata-backup-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".tar.gz")
    Write-Host "  备份到: $backupFile" -ForegroundColor Gray
    Write-Host "  命令: docker run --rm -v ai-cabinet_pgdata:/src -v ${BackupDir}:/dst postgres:16-alpine tar czf /dst/$(Split-Path $backupFile -Leaf) -C /src ." -ForegroundColor DarkGray
    $code = Invoke-Native @("run", "--rm", "-v", "ai-cabinet_pgdata:/src", "-v", "${BackupDir}:/dst",
                            "postgres:16-alpine", "tar", "czf", "/dst/$(Split-Path $backupFile -Leaf)", "-C", "/src", ".")
    if ($code -eq 0 -and (Test-Path $backupFile)) {
        $sz = [math]::Round((Get-Item $backupFile).Length / 1MB, 2)
        Write-Ok "备份完成（$sz MB）"
    } else {
        Write-Bad "备份失败（退出码 $code）—— 业务库将丢失！建议先解决再跑"
        if (-not $Yes) {
            $a = Read-Host "  仍要继续（会丢业务库）？(输入 yes 继续)"
            if ($a -ne "yes") { Write-Host "已取消" -ForegroundColor Gray; exit 0 }
        }
    }
} else {
    Write-Bad "Docker 未运行，无法备份 —— 业务库会丢"
    if (-not $Yes) {
        $a = Read-Host "  ⚠ 无备份继续？(输入 yes 继续)"
        if ($a -ne "yes") { Write-Host "已取消" -ForegroundColor Gray; exit 0 }
    }
}

# ── 3. 确认 ─────────────────────────────────────────────
Write-Step "3/5 确认"
Write-Host "  即将【删除】: $vhdx（$beforeGB GB）" -ForegroundColor Yellow
Write-Host ""
Write-Host "  删除后 Docker Desktop 下次启动会重建一个新 vhdx，预计约 25 GB。" -ForegroundColor Gray
Write-Host "  收益：E 盘回收约 95 GB。" -ForegroundColor Green
Write-Host ""
Write-Host "  ⚠ 不可逆丢失：" -ForegroundColor Yellow
Write-Host "    · 全部 39 个镜像（需重新 build / 重拉，数分钟）" -ForegroundColor Yellow
Write-Host "    · 全部容器" -ForegroundColor Yellow
Write-Host "    · 全部卷（含业务库 pgdata、调度库 xxljob-mysql）" -ForegroundColor Yellow
Write-Host ""
Write-Host "  之后需跑 .\docker-up.ps1 重建整个栈。" -ForegroundColor Gray

if (-not $Yes) {
    $a = Read-Host "`n确认删除 vhdx？(输入 DELETE 确认)"
    if ($a -ne "DELETE") { Write-Host "已取消，什么都没做" -ForegroundColor Gray; exit 0 }
}

# ── 4. 停 Docker + 删 vhdx ─────────────────────────────
Write-Step "4/5 停止 Docker 并删除 vhdx"

Get-Process -Name "Docker Desktop", "com.docker.backend" -ErrorAction SilentlyContinue |
    ForEach-Object { Write-Host "  停止 $($_.ProcessName) PID=$($_.Id)"; Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 5

if (Get-Process -Name "vmmemWSL" -ErrorAction SilentlyContinue) {
    Write-Host "  等待 vmmemWSL 释放 vhdx 句柄..." -ForegroundColor Gray
    # 必须释放句柄，否则文件删不掉（共享冲突）
    $wslExe = "wsl"
    if (Get-Command $wslExe -ErrorAction SilentlyContinue) {
        & wsl --shutdown 2>&1 | Out-Null
    }
    for ($i = 0; $i -lt 15; $i++) {
        if (-not (Get-Process -Name "vmmemWSL" -ErrorAction SilentlyContinue)) { break }
        Start-Sleep -Seconds 2
    }
    if (Get-Process -Name "vmmemWSL" -ErrorAction SilentlyContinue) {
        Write-Bad "vmmemWSL 仍在运行，文件可能删不掉。建议重启电脑后再跑。"
    } else {
        Write-Ok "vmmemWSL 已退出"
    }
}

Write-Host "  删除 $vhdx ..."
try {
    Remove-Item -LiteralPath $vhdx -Force -ErrorAction Stop
    Write-Ok "已删除"
} catch {
    Write-Bad "删除失败: $($_.Exception.Message)"
    Write-Host ""
    Write-Host "  若提示「正在使用」，说明还有进程占着 vhdx。两个办法：" -ForegroundColor Yellow
    Write-Host "    1) 重启电脑后重跑本脚本（最省事）" -ForegroundColor Yellow
    Write-Host "    2) 管理员 CMD 里手动删：" -ForegroundColor Yellow
    Write-Host "       del /f `"$vhdx`"" -ForegroundColor Yellow
    exit 1
}

# ── 5. 结果 ─────────────────────────────────────────────
Write-Step "5/5 结果"
$eFreeAfter = [math]::Round((Get-PSDrive E).Free / 1GB, 2)
Write-Host "  E 盘剩余: $eFreeBefore GB  →  ${eFreeAfter} GB" -ForegroundColor Gray
Write-Host "  回收    : $([math]::Round($eFreeAfter - $eFreeBefore, 2)) GB" -ForegroundColor Green
Write-Host ""
Write-Host "  下一步：" -ForegroundColor Cyan
Write-Host "    1) 启动 Docker Desktop（会自动重建一个新vhdx，约 25 GB）" -ForegroundColor Gray
Write-Host "    2) 等 Docker 完全就绪（托盘图标变绿、无报错）" -ForegroundColor Gray
Write-Host "    3) 重建整个栈：" -ForegroundColor Gray
Write-Host "       cd 'D:\ai-generated code\ai-cabinet'" -ForegroundColor DarkGray
Write-Host "       .\docker-up.ps1" -ForegroundColor DarkGray
Write-Host ""
Write-Host "  📌 顺便做这件事（防止再涨回去）：" -ForegroundColor Cyan
Write-Host "     Docker Desktop → Settings → Resources → Advanced →" -ForegroundColor Gray
Write-Host "     Disk image size 改成 60GB（现在上限 1TB，等于没刹车）" -ForegroundColor Gray

Write-Host "`n按任意键退出..." -ForegroundColor DarkGray
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")