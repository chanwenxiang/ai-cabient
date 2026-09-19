# 可观测性接收端一键启停：Loki(日志聚合) + promtail(采集) + Tempo(链路追踪)
#
# 用法（在 infra/ 目录下，或直接给完整路径）：
#   infra/observability.ps1 on            # 启动并等待健康
#   infra/observability.ps1 off           # 停掉这三个容器（**保留**数据卷）
#   infra/observability.ps1 status        # 状态 + 内存占用
#   infra/observability.ps1 logs loki     # 跟某一路日志
#
# 🔴 为什么需要这个脚本：应用侧的「系统配置」只能控制**应用发不发数据**，
#    控制不了**容器在不在跑** —— 系统配置存在 PG 里，它拉不起容器。
#    所以「可观测开关」= 本脚本管容器 ＋ 运营台「功能开关」管数据，两层缺一不可。
#
# 🔴 为什么默认不启动：本机 Docker VM 只有 3.82GB，Loki+Tempo 常驻会与业务容器抢内存
#    （O8 压测那次 1000 VU 已把 Docker 引擎打崩、需人工重启）。需要看日志/链路时再开。

param(
    [Parameter(Position = 0)]
    [ValidateSet("on", "off", "status", "logs")]
    [string]$Action = "status",

    [Parameter(Position = 1)]
    [string]$Service = "loki"
)

$InfraDir = $PSScriptRoot
Set-Location $InfraDir

$ComposeArgs = @(
    "-f", "docker-compose.full.yml",
    "-f", "docker-compose.observability.yml",
    "--profile", "observability"
)

# 本 overlay 只负责这三个；off/logs 都只针对它们，绝不 down 整个项目
# （docker compose down 会把 full.yml 里的业务容器一起拆掉）。
$ObservabilityServices = @("loki", "promtail", "tempo")

# 本次新增的内存上限合计（与 infra/docker-compose.observability.yml 保持一致）
$AddedMemLimitMb = 384 + 128 + 320

# Windows PowerShell 5.1 会把 native 程序的 stderr 当**终止错误**：docker compose 的
# 正常进度是走 stderr 的，一旦脚本设了 $ErrorActionPreference='Stop'，就会「活儿干完了
# 却 exit 1」（假红）。所以原生调用一律走这个助手：临时降 EAP，成败只认 $LASTEXITCODE。
#
# 同时过滤掉镜像拉取的逐层进度（首次 on 会刷 2500+ 行，把真正的错误淹掉 —— 实测过）。
$Script:PullNoise = 'Downloading|Download complete|Extracting|Waiting|Verifying Checksum|' +
    'Pulling fs layer|Pull complete|Downloading fs layer|Already exists|Pulling|Digest:'

function Invoke-Native {
    param([scriptblock]$Body)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $Body 2>&1 | ForEach-Object {
            $line = [string]$_
            if ($line -match $Script:PullNoise) { return }
            Write-Host $line
        }
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Get-DockerMemTotalMb {
    $raw = docker info --format '{{.MemTotal}}' 2>$null
    if (-not $raw) { return 0 }
    return [math]::Round([double]$raw / 1MB, 0)
}

function Show-MemWarning {
    $total = Get-DockerMemTotalMb
    if ($total -le 0) { return }
    Write-Host ("Docker VM 内存 = {0} MB；本次新增上限约 {1} MB（loki 384 + promtail 128 + tempo 320）" -f $total, $AddedMemLimitMb)
    if ($total -lt 6144) {
        Write-Host "⚠️ 可用内存偏紧：如与压测/大构建同时进行，可能触发 OOM。看完请及时 `off`。" -ForegroundColor Yellow
    }
}

if (-not (Test-Path ".env")) {
    Copy-Item ".env.example" ".env"
    Write-Host "Created infra/.env from .env.example"
}

if ($Action -eq "status") {
    Write-Host "==> 可观测性容器状态" -ForegroundColor Cyan
    Invoke-Native { docker compose @ComposeArgs ps @ObservabilityServices } | Out-Null
    Write-Host ""
    Show-MemWarning
    Write-Host ""
    Write-Host "==> 端口（宿主）" -ForegroundColor Cyan
    Write-Host "  Loki    http://127.0.0.1:13100/ready"
    Write-Host "  Tempo   http://127.0.0.1:13200/ready   （OTLP HTTP 14318 / gRPC 14317）"
    Write-Host "  Grafana http://localhost:13000/  数据源 Loki / Tempo"
    exit 0
}

if ($Action -eq "off") {
    Write-Host "==> 停止（并移除）可观测性容器，保留数据卷" -ForegroundColor Cyan
    $code = Invoke-Native { docker compose @ComposeArgs rm -sf @ObservabilityServices }
    if ($code -ne 0) {
        Write-Host "off 失败，退出码 $code" -ForegroundColor Red
        exit $code
    }
    Write-Host "已停止。数据卷 loki_data / tempo_data 仍保留，下次 on 可直接续用。" -ForegroundColor Green
    exit 0
}

if ($Action -eq "logs") {
    Invoke-Native { docker compose @ComposeArgs logs -f --tail 50 $Service } | Out-Null
    exit 0
}

# ---- on ----
Write-Host "==> 启动 Loki + promtail + Tempo" -ForegroundColor Cyan
Show-MemWarning

$code = Invoke-Native { docker compose @ComposeArgs up -d @ObservabilityServices }
if ($code -ne 0) {
    Write-Host "启动失败，退出码 $code" -ForegroundColor Red
    Invoke-Native { docker compose @ComposeArgs logs --tail 60 @ObservabilityServices } | Out-Null
    exit $code
}

# 等健康（只在 on 时等，且**按容器状态**判，不用墙钟当判据）
$deadline = (Get-Date).AddSeconds(120)
$pending = [System.Collections.ArrayList]@($ObservabilityServices)
while ($pending.Count -gt 0 -and (Get-Date) -lt $deadline) {
    foreach ($svc in @($pending)) {
        $health = (docker compose @ComposeArgs ps $svc --format "{{.Health}}" 2>$null | Select-Object -First 1)
        if ($health -eq "healthy") {
            Write-Host ("    {0} healthy" -f $svc) -ForegroundColor Green
            $pending.Remove($svc) | Out-Null
        } else {
            $state = (docker compose @ComposeArgs ps $svc --format "{{.State}}" 2>$null | Select-Object -First 1)
            if ($state -eq "exited" -or $state -eq "dead") {
                Write-Host ("{0} 在变健康前就退出了" -f $svc) -ForegroundColor Red
                Invoke-Native { docker compose @ComposeArgs logs --tail 60 $svc } | Out-Null
                exit 1
            }
        }
    }
    if ($pending.Count -gt 0) { Start-Sleep -Seconds 5 }
}

if ($pending.Count -gt 0) {
    Write-Host ("超时未健康：{0}（promtail 无 healthcheck，不看它）" -f ($pending -join ", ")) -ForegroundColor Yellow
}

Write-Host ""
Write-Host "==> 自检" -ForegroundColor Cyan
Write-Host "  loki ready: $(try { (Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 http://127.0.0.1:13100/ready).StatusCode } catch { 'FAILED' })"
Write-Host "  tempo ready: $(try { (Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 http://127.0.0.1:13200/ready).StatusCode } catch { 'FAILED' })"
Write-Host ""
Write-Host "下一步：Grafana(Loki/Tempo 数据源已自动加载) → 应用侧运行期开关见运营台「功能开关」" -ForegroundColor Cyan

# 🔴 必须显式 exit 0：不写的话 $LASTEXITCODE 会留着脚本内部最后一条原生命令的值，
# 调用方（含 CI / 人肉判断）会读到一个没有意义的非 0 值（实测拿到过 -1）——
# 这正是「信号在骗读者」，与「假绿/假红」同类。
exit 0
