#Requires -Version 5.1
<#
.SYNOPSIS
    Docker 空间 GC —— 回收构建缓存、历史镜像、孤儿卷。

.DESCRIPTION
    本脚本只做「可重建」的清理，不碰任何运行中容器，不碰主业务库卷。

    清理三档（默认只跑安全档）：
      -Safe    仅 buildx 缓存 + Exited 容器 + 匿名孤儿卷        （零风险）
      -Normal   Safe + ai-cabinet 历史 sha tag 镜像 + 缓存卷     （默认，需 -Normal）
      -Aggressive  Normal + 手工备份 tag + 外来大镜像            （需 -Aggressive）

    ⚠️ 本脚本【不会】让 vhdx 变小。vhdx 只涨不缩，必须另跑 compact，见
       scripts/docker-compact-vhdx.ps1

.PARAMETER Level
    Safe | Normal | Aggressive，默认 Normal

.PARAMETER DryRun
    只打印将执行的操作，不实际执行。

.EXAMPLE
    .\scripts\docker-gc.ps1 -DryRun
    .\scripts\docker-gc.ps1 -Level Safe
    .\scripts\docker-gc.ps1 -Level Normal

.NOTES
    背景：docker-up.ps1 用 git short SHA 作镜像 tag、build.ps1 每次产生新层、
    docker-down.ps1 不带 -v —— 三者叠加会让 Docker 数据盘持续膨胀。
    本脚本是这三条的兜底。
#>
[CmdletBinding()]
param(
    [ValidateSet("Safe", "Normal", "Aggressive")]
    [string]$Level = "Normal",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

# PS 5.1 + native exe：stderr 的正常进度会被当终止错误 ⇒ 临时降 EAP + [ref] 回 $LASTEXITCODE
function Invoke-Docker {
    # ⚠️ 参数名绝不能叫 $Args —— 那是 PowerShell 自动变量，@Args splat 会拿到
    # 整个函数的位置参数列表而不是我们传的数组（曾导致 docker 收到 "System.Object[]"）。
    # 成败判 $LASTEXITCODE，不判脚本退出码（docker 往 stderr 写正常进度会让 $ErrorActionPreference 假红）。
    param([string[]]$DockerArgList)
    if ($null -eq $DockerArgList -or $DockerArgList.Count -eq 0) {
        Write-Host "  ✗ Invoke-Docker: 参数为空" -ForegroundColor Red
        return 1
    }
    $previousEap = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
    & docker @DockerArgList 2>&1 | ForEach-Object { Write-Host "  $_" }
        $code = $LASTEXITCODE
        return $code
    } finally {
        $ErrorActionPreference = $previousEap
    }
}

function Invoke-Checked {
    param([string]$What, [string[]]$DockerArgList)
    Write-Host "▶ $What" -ForegroundColor Cyan
    $code = Invoke-Docker -DockerArgList $DockerArgList
    if ($code -ne 0) {
        Write-Host "  ✗ 退出码 $code —— 该步未生效" -ForegroundColor Yellow
    }
    return $code
}

function Get-DockerRootUsedGB {
    $out = & docker system df --format "{{.Type}}|{{.Size}}" 2>$null
    $total = 0.0
    foreach ($line in $out) {
        $parts = $line -split '\|'
        if ($parts.Count -ge 2) {
            $v = $parts[1] -replace '[^0-9.]', ''
            if ($v) { $total += [double]$v }
        }
    }
    return [math]::Round($total, 2)
}

Write-Host "=== Docker GC（Level=$Level, DryRun=$($DryRun.IsPresent))===" -ForegroundColor Green

$before = Get-DockerRootUsedGB
Write-Host "清理前 Docker 占用合计: ${before} GB`n" -ForegroundColor Gray

# ── 保护清单：任何档位都不删 ──────────────────────────────────
$PROTECTED_VOLUMES = @(
    "ai-cabinet_pgdata"           # 主业务库，postgres 正在使用
)
$PROTECTED_IMAGES = @(
    "ai-cabinet/trade-service:local",
    "ai-cabinet/vision-service:local",
    "ai-cabinet/device-service:local",
    "ai-cabinet/github-runner:2.321.0"
)

# compose 引用的镜像（钉版本或 digest），删了要重拉
$COMPOSE_IMAGES = @(
    "postgres:16-alpine", "redis:7-alpine", "emqx/emqx:5.8.6", "nginx:alpine",
    "mysql:8.0", "minio/minio", "minio/mc", "redpandadata/redpanda:v24.1.1",
    "xuxueli/xxl-job-admin:3.4.2", "prom/prometheus:v2.51.0", "prom/alertmanager:v0.28.1",
    "grafana/grafana:10.4.0", "grafana/loki:2.9.8", "grafana/promtail:2.9.8",
    "grafana/tempo:2.4.1", "python:3.12-slim", "ai-cabinet/device-simulator:latest",
    "ai-cabinet/device-service:latest"
)

# ── Step 1: buildx 构建缓存（所有档位都做，零风险）────────────
Write-Host "【1/4】buildx 构建缓存" -ForegroundColor Cyan
if ($DryRun) {
    Write-Host "  [dry-run] docker builder prune -af" -ForegroundColor DarkGray
} else {
    Invoke-Checked "docker builder prune -af" @("builder", "prune", "-af") | Out-Null
}

# ── Step 2: Exited 容器（所有档位都做）────────────────────────
Write-Host "`n【2/4】已退出的容器" -ForegroundColor Cyan
if ($DryRun) {
    $exited = & docker ps -aq -f status=exited 2>$null
    Write-Host "  [dry-run] 将删除 $($exited.Count) 个 Exited 容器（保留全部 Running）" -ForegroundColor DarkGray
} else {
    Invoke-Checked "docker container prune -f" @("container", "prune", "-f") | Out-Null
}

# ── Step 3: 匿名孤儿卷（所有档位都做）────────────────────────
# 只删 64 位 hex 名的匿名卷；具名卷一律保留（可能是有状态数据）
Write-Host "`n【3/4】匿名孤儿卷" -ForegroundColor Cyan
$anonVols = @(& docker volume ls -qf dangling=true 2>$null | Where-Object { $_ -match '^[0-9a-f]{64}$' })
if ($anonVols.Count -eq 0) {
    Write-Host "  无匿名孤儿卷" -ForegroundColor Gray
} elseif ($DryRun) {
    Write-Host "  [dry-run] 将删除 $($anonVols.Count) 个匿名卷" -ForegroundColor DarkGray
} else {
    Write-Host "  删除 $($anonVols.Count) 个匿名卷..." -ForegroundColor Gray
    # 分批删，避免单条命令过长（Windows 命令行 8191 字符限制）
    $batchSize = 20
    for ($i = 0; $i -lt $anonVols.Count; $i += $batchSize) {
        $batch = $anonVols[$i..([math]::Min($i + $batchSize - 1, $anonVols.Count - 1))]
        Invoke-Docker -DockerArgList (@("volume", "rm") + $batch) | Out-Null
    }
}

# ── Step 4: 镜像（分档）────────────────────────────────────
Write-Host "`n【4/4】镜像清理" -ForegroundColor Cyan

if ($Level -eq "Safe") {
    Write-Host "  Level=Safe —— 跳过镜像清理（历史 tag 保留）" -ForegroundColor Gray
} else {
    # 收集 ai-cabinet/* 的 sha tag 镜像（tag 形如 7 位 hex，或明确的 local/latest）
    $allTags = @(& docker images --filter "reference=ai-cabinet/*" --format "{{.Repository}}:{{.Tag}}" 2>$null)

    $protected = @($PROTECTED_IMAGES + $COMPOSE_IMAGES)

    if ($Level -eq "Normal") {
        # 只删：纯 sha tag（7-40 位 hex），保留 local/latest/pre-*/g9tmp 等手工 tag
        $pattern = '^ai-cabinet/[a-z-]+:[0-9a-f]{7,40}$'
        $targets = @($allTags | Where-Object { $_ -match $pattern -and $protected -notcontains $_ })
    } else {
        # Aggressive：删全部 ai-cabinet/* 除保护清单
        $targets = @($allTags | Where-Object { $protected -notcontains $_ })
    }

    if ($targets.Count -eq 0) {
        Write-Host "  无匹配镜像" -ForegroundColor Gray
    } else {
        Write-Host "  Level=$Level ⇒ 待删 $($targets.Count) 个镜像：" -ForegroundColor Gray
        $targets | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
        if ($DryRun) {
            Write-Host "  [dry-run] 跳过实际删除" -ForegroundColor DarkGray
        } else {
            foreach ($t in $targets) {
                if ($protected -contains $t) { continue }   # 二次防线
                Invoke-Docker -DockerArgList @("image", "rm", $t) | Out-Null
            }
        }
    }
}

# ── 汇总 ─────────────────────────────────────────────────
Write-Host "`n=== 结果 ===" -ForegroundColor Green
$after = Get-DockerRootUsedGB
Write-Host "清理前: ${before} GB" -ForegroundColor Gray
Write-Host "清理后: ${after} GB" -ForegroundColor Gray
Write-Host "回收  : $([math]::Round($before - $after, 2)) GB（Docker 统计口径）" -ForegroundColor Green

Write-Host "`n剩余占用明细：" -ForegroundColor Gray
& docker system df 2>$null | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }

Write-Host "`n⚠️  vhdx 文件本身不会变小。" -ForegroundColor Yellow
Write-Host "   Docker 在 ext4 上删文件不 punch hole，docker_data.vhdx 保持原大小。" -ForegroundColor Yellow
Write-Host "   需要缩容请另跑： .\scripts\docker-compact-vhdx.ps1（会停 Docker）" -ForegroundColor Yellow
