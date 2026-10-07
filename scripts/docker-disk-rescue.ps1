#Requires -Version 5.1
<#
.SYNOPSIS
    管理员模式磁盘救援 —— 自动提权 + 压缩 Docker vhdx + 清空回收站。

.DESCRIPTION
    本脚本【不需要你手动开管理员 PowerShell】：双击后会自己弹 UAC 请求提权，
    提权成功后自动完成全部动作。

    为什么必须管理员（已实测取证）：
      - `fsutil fsinfo sectorinfo E:` → 「错误 5: 拒绝访问」
      - `diskpart` 在非管理员下无输出、退出码为空
      - 删除 `$RECYCLE.BIN` 里其他用户 SID（S-1-5-18 / S-1-5-21-...-1001）的条目会被拒
    compact vdisk 与 `wsl --manage --set-sparse` 都需要 SeShutdownPrivilege/SeManageVolumePrivilege，
    非管理员一律失败。

    执行内容：
      1. 清空回收站（E 盘 2.88GB 是白捡的）
      2. 停Docker Desktop（释放 vhdx 句柄）
      3. diskpart compact vdisk —— 把 ext4 已释放的空洞还给 NTFS
      4.复查前后大小

    ⚠️ 会中断 postgres / redis，Docker 重启后自动恢复。

.EXAMPLE
    右键本文件 → “使用 PowerShell 运行”
    或  powershell -ExecutionPolicy Bypass -File .\scripts\docker-disk-rescue.ps1

.NOTES
    背景实测数据（2026-10-07）：
      vhdx 文件          = 124.09 GB
      ext4 内部真实用量  = 21.9 GB      ⇒ 约 102 GB 是 ext4 已释放但 NTFS 没拿回的空洞
      E 盘剩余          = 7.8 GB       ⇒紧急
#>
[CmdletBinding()]
param(
    [switch]$NoRecycleBin
)

$ErrorActionPreference = "Continue"

# ── 自动提权 ─────────────────────────────────────────────
$isAdmin = ([Security.Principal.WindowsPrincipal] `
    [Security.Principal.WindowsIdentity]::GetCurrent()
).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdmin) {
    Write-Host "当前不是管理员，正在请求提权..." -ForegroundColor Yellow
    $psExe = (Get-Process -Id $PID).Path
    $argList = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$PSCommandPath`"")
    if ($NoRecycleBin) { $argList += "-NoRecycleBin" }
    try {
        Start-Process -FilePath $psExe -Verb RunAs -ArgumentList $argList
        Write-Host "已启动提权窗口。若有 UAC 弹窗，请点「是」。" -ForegroundColor Green
        Write-Host "本窗口即将关闭，请到新窗口看结果。" -ForegroundColor Gray
        exit 0
    } catch {
        Write-Host "提权失败：$($_.Exception.Message)" -ForegroundColor Red
        Write-Host "请手动右键 →「以管理员身份运行」" -ForegroundColor Yellow
        exit 1
    }
}

# ── 到这里已是管理员 ────────────────────────────────────
Write-Host "=== Docker 磁盘救援（管理员模式）===" -ForegroundColor Green
Write-Host "已确认：管理员权限OK`n" -ForegroundColor Gray

$vhdxPath = "E:\docker\images\DockerDesktopWSL\disk\docker_data.vhdx"
if (-not (Test-Path $vhdxPath)) {
    # 退回到从设置里读
    $s = Get-Content "$env:APPDATA\Docker\settings-store.json" -Raw | ConvertFrom-Json
    if ($s.CustomWslDistroDir) {
        $vhdxPath = Join-Path $s.CustomWslDistroDir "disk\docker_data.vhdx"
    }
}
if (-not (Test-Path $vhdxPath)) {
    Write-Host "找不到 docker_data.vhdx" -ForegroundColor Red
    exit 1
}

$beforeGB = [math]::Round((Get-Item $vhdxPath).Length / 1GB, 2)
$eFreeBefore = [math]::Round((Get-PSDrive E).Free / 1GB, 2)
Write-Host "vhdx: $vhdxPath" -ForegroundColor Gray
Write-Host "压缩前:$beforeGB GB" -ForegroundColor Gray
Write-Host "E 盘剩余(前): $eFreeBefore GB`n" -ForegroundColor Gray

# ── 1. 回收站 ──────────────────────────────────────────
Write-Host "【1/4】清空回收站" -ForegroundColor Cyan
if ($NoRecycleBin) {
    Write-Host "  已按 -NoRecycleBin 跳过" -ForegroundColor DarkGray
} else {
    foreach ($d in @("E:\`$RECYCLE.BIN", "C:\`$RECYCLE.BIN")) {
        if (Test-Path -LiteralPath $d) {
            $sz = (Get-ChildItem -LiteralPath $d -Recurse -File -Force -ErrorAction SilentlyContinue |
                   Measure-Object Length -Sum).Sum
            if ($sz -gt 0) {
                Write-Host "  清理 $d （$([math]::Round($sz/1GB,2)) GB）" -ForegroundColor Gray
                #管理员下才能删其他用户 SID 的条目
                Get-ChildItem -LiteralPath $d -Force -ErrorAction SilentlyContinue |
                    ForEach-Object {
                        Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction SilentlyContinue
                    }
            }
        }
    }
    $left = (Get-ChildItem 'E:\$RECYCLE.BIN' -Recurse -File -Force -ErrorAction SilentlyContinue |
             Measure-Object Length -Sum).Sum
    if ($left -lt 100MB) {
        Write-Host "  ✓ 回收站已清空" -ForegroundColor Green
    } else {
        Write-Host "  ⚠ 仍剩 $([math]::Round($left/1GB,2)) GB（可能有其他用户在用）" -ForegroundColor Yellow
    }
}

# ── 2. 停 Docker（只停 Docker，不 shutdown WSL）──────────────
# 🔴 顺序铁律：必须「先停 Docker → fstrim → wsl --shutdown → compact」。
#    反了就会白跑：ext4 已 fstrim 过的块才可能真空，
#    而 ext4 卸载（wsl --shutdown）之后再 fstrim 就没意义了。
#    首版脚本就在这里提前 shutdown，导致 fstrim 无从下手，只回收 4.75GB。
Write-Host "`n【2/4】停止 Docker Desktop（暂不 shutdown WSL，fstrim 还需要它）" -ForegroundColor Cyan
$running = @()
try { $running = @(& docker ps --format "{{.Names}}" 2>$null) } catch {}
if ($running.Count -gt 0) {
    Write-Host "  将中断以下容器：" -ForegroundColor Yellow
    $running | ForEach-Object { Write-Host "    $_" -ForegroundColor Yellow }
}

Get-Process -Name "Docker Desktop", "com.docker.backend" -ErrorAction SilentlyContinue |
    ForEach-Object { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 4
Write-Host "  ✓ Docker Desktop 已停" -ForegroundColor Green

# ── 2.5 fstrim（🔴 关键，缺这步 compact 几乎无效）────────────
# 为什么必须先做（2026-10-07 实测教训）：
#   ext4 只有在 fstrim 之后才知道哪些块「真空了」。没 fstrim 时 ext4 认为那些块还在用，
#   vhdx 就得为它们保留空间，NTFS 也只能实打实分配 —— 这不是空洞，compact 压不动。
#   实测教训：首版脚本跳过fstrim，124.09GB → 119.34GB，只回收 4.75GB。
#   fsutil file queryextents 证实 80.92GB 是「连续无空洞的已分配块」。
#   补上 fstrim + set-sparse 后才可能回收那80GB 量级。
Write-Host "`n【2.5/4】fstrim —— 让 ext4 交还空闲块（🔴 缺这步 compact 几乎无效）" -ForegroundColor Cyan

# 3a) fstrim：必须在 WSL 活着时做（需要挂载 ext4），所以先做这个，再 shutdown
$trimOK = $false
if (Get-Command wsl -ErrorAction SilentlyContinue) {
    # 注意：Docker 已停但 WSL 发行版可能仍在，此时 ext4 已卸载 ⇒ fstrim 会失败。
    # 因此正确顺序是：停 Docker → fstrim → wsl --shutdown → compact。
    # 若此处失败（Docker 已先停导致 ext4 卸载），脚本会提示改用「重启后单独跑 fstrim」。
    Write-Host "  尝试 fstrim（需要 ext4 仍挂载；若 Docker 已停导致失败，见文末提示）" -ForegroundColor Gray
    $distroName = "docker-desktop"
    # 部分版本发行版名为 docker-desktop-data，一并尝试
    foreach ($d in @($distroName, "docker-desktop-data")) {
        $r = & wsl -l -q 2>&1 | Out-String
        if ($r -notmatch [regex]::Escape($d)) { continue }
        Write-Host "  [1/2] wsl -d $d -u root -- fstrim -av" -ForegroundColor DarkGray
        $out = & wsl -d $d -u root -- /sbin/fstrim -av 2>&1 | Out-String
        Write-Host $out
        if ($LASTEXITCODE -eq 0) { $trimOK = $true; Write-Host "  ✓ fstrim 完成" -ForegroundColor Green; break }
    }
    if (-not $trimOK) {
        Write-Host "  ⚠ fstrim 未成功（常见：ext4 已卸载 / 发行版名不同）" -ForegroundColor Yellow
        Write-Host "    ext4 卸载时 fstrim 无从下手 —— 这正是上一步只回收 4.75GB 的原因。" -ForegroundColor DarkGray
        Write-Host "    【补救】见文末「补救方案」：需 Docker 运行中单独跑 fstrim。" -ForegroundColor DarkGray
    }

    # 3b) set-sparse：让 vhdx 真稀疏，后续空洞能真正还给 NTFS
    Write-Host "`n  [2/2] wsl --set-sparse（vhdx 稀疏化）" -ForegroundColor DarkGray
    $sp = & wsl --manage $distroName --set-sparse true 2>&1 | Out-String
    Write-Host $sp
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  ✓ set-sparse 完成（vhdx 变稀疏，空洞可还 NTFS）" -ForegroundColor Green
    } else {
        Write-Host "  ⚠ set-sparse 失败（需 WSL ≥ 2.0）" -ForegroundColor Yellow
    }

    # fstrim 必须在 shutdown 之前 —— 现在才 shutdown
    Write-Host "`n  fstrim 已处理，wsl --shutdown ..." -ForegroundColor DarkGray
    & wsl --shutdown 2>&1 | Out-Null
    Start-Sleep -Seconds 4
    for ($i = 0; $i -lt 15; $i++) {
        if (-not (Get-Process -Name "vmmemWSL" -ErrorAction SilentlyContinue)) { break }
        Start-Sleep -Seconds 2
    }
    if (Get-Process -Name "vmmemWSL" -ErrorAction SilentlyContinue) {
        Write-Host "  ⚠ vmmemWSL 仍在运行" -ForegroundColor Yellow
    } else {
        Write-Host "  ✓ vmmemWSL 已退出，可以 compact" -ForegroundColor Green
    }
} else {
    Write-Host "  ⚠ 找不到 wsl 命令，跳过 fstrim 与 set-sparse" -ForegroundColor Yellow
}

# ── 3. compact vdisk ──────────────────────────────────
Write-Host "`n【3/4】diskpart compact vdisk（核心步骤）" -ForegroundColor Cyan
Write-Host "  这一步会把 ext4 已释放的空洞真正还给 NTFS。" -ForegroundColor Gray

$dpScript = "$env:TEMP\docker-compact-dp.txt"
@"
select vdisk file="$vhdxPath"
attach vdisk readonly
compact vdisk
detach vdisk
exit
"@ | Out-File -FilePath $dpScript -Encoding ascii

$dpOut = & diskpart /s $dpScript 2>&1 | Out-String
Write-Host $dpOut

$compactOK = $false
if ($dpOut -match "磁盘已压缩|Disk compacted|successfully compacted|已完成压缩") { $compactOK = $true }
elseif ($dpOut -match "错误|Error|错误 5") { $compactOK = $false }

# ── 4. 复查 ───────────────────────────────────────────
Write-Host "`n【4/4】复查" -ForegroundColor Cyan
$afterGB = [math]::Round((Get-Item $vhdxPath).Length / 1GB, 2)
$attr = (Get-Item $vhdxPath).Attributes
$isSparse = ($attr -band [IO.FileAttributes]::SparseFile) -ne 0
$eFreeAfter = [math]::Round((Get-PSDrive E).Free / 1GB, 2)
$delta = [math]::Round($beforeGB - $afterGB, 2)

Write-Host "  vhdx 大小 : $beforeGB GB  →  ${afterGB} GB" -ForegroundColor Gray
Write-Host "  稀疏标志  : $(if($isSparse){'已启用'}else{'未启用'})" -ForegroundColor Gray
Write-Host "  E 盘剩余  : $eFreeBefore GB  →  ${eFreeAfter} GB" -ForegroundColor Gray

if ($delta -gt 0) {
    Write-Host "`n  ✅ 回收 $delta GB！E 盘从 $eFreeBefore GB 回到 $eFreeAfter GB" -ForegroundColor Green
} else {
    Write-Host "`n  ⚠ vhdx 大小没变。" -ForegroundColor Yellow
    Write-Host "    若 diskpart 报「错误 5」→ 仍是权限不足（请确认 UAC 点了「是」）" -ForegroundColor DarkGray
    Write-Host "    若报「磁盘已联机」→ vhdx 被占用，重启电脑后重跑本脚本" -ForegroundColor DarkGray
    Write-Host "    若报「不支持」→ 走备用方案：Docker Desktop 设置里把数据盘搬到 D 盘" -ForegroundColor DarkGray
}

Write-Host "`n  现在可以重启 Docker Desktop：" -ForegroundColor Gray
Write-Host "    Start-Process 'C:\Program Files\Docker\Docker\Docker Desktop.exe'" -ForegroundColor DarkGray
Write-Host "  postgres / redis 会自动恢复。" -ForegroundColor DarkGray

# ══════════════════════════════════════════════════════════
# 补救方案
# ══════════════════════════════════════════════════════════
if (-not $trimOK) {
    Write-Host ""
    Write-Host "════════════════════════════════════════════════════" -ForegroundColor Yellow
    Write-Host " fstrim 没成功 —— 80GB 量级还没回收，需要补一步" -ForegroundColor Yellow
    Write-Host "════════════════════════════════════════════════════" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "原因：ext4 已卸载时 fstrim 无从下手（必须挂着才能交还空闲块）。" -ForegroundColor Gray
    Write-Host ""
    Write-Host "【补救：两步】" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "第1步· Docker 运行时做 fstrim（ext4 挂着的唯一时机）" -ForegroundColor Cyan
    Write-Host "  用【管理员 PowerShell】执行：" -ForegroundColor Gray
    Write-Host ""
    Write-Host "    docker-up 启动栈（或启动 Docker Desktop），然后：" -ForegroundColor DarkGray
    Write-Host "    wsl -d docker-desktop -u root -- /sbin/fstrim -av" -ForegroundColor Yellow
    Write-Host "    wsl --manage docker-desktop --set-sparse true" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "第 2 步 · 再跑本脚本（compact）" -ForegroundColor Cyan
    Write-Host "    .\scripts\docker-disk-rescue.ps1" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "注意：第 1 步做完可以【立刻】跑第2 步，不需要先关 Docker" -ForegroundColor DarkGray
    Write-Host "      （本脚本会自己停 Docker 再 compact）" -ForegroundColor DarkGray
    Write-Host ""
    Write-Host "【或直接换方案】Docker Desktop → Settings → Resources → Advanced →" -ForegroundColor Gray
    Write-Host "  Disk image location 把数据盘搬到 D 盘（D 盘有 247.68 GB 空闲）" -ForegroundColor Gray
    Write-Host "  会重建数据盘，镜像容器卷清空，需重新 docker-up 构建。" -ForegroundColor DarkGray
    Write-Host "  你现在 Docker 里只剩 18GB 活跃内容，迁移代价最小。" -ForegroundColor DarkGray
}

# 不自动重启 Docker —— 让用户自己决定，避免误判
Write-Host "`n按任意键退出..." -ForegroundColor DarkGray
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")