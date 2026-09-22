param(
    [switch]$NoBuild,
    [switch]$DevOps,
    # App stack only (default). DevOps = prometheus/grafana + optional sonarqube profile.
    [switch]$WithMonitoring
)
$ErrorActionPreference = "Stop"

# ── 原生命令调用助手 ──────────────────────────────────────────────────────────────
# 🔴 PowerShell 5.1 在 $ErrorActionPreference='Stop' 下，会把 **native exe 写到 stderr 的每一行**
#    包成 NativeCommandError 并**立即终止脚本**。而 `docker compose up -d` 恰恰把 build 进度
#    （`#5 [trade-service] ...`）与容器状态（`Container xxx Starting`）写到 stderr ⇒
#    **容器其实已经起来了，脚本却在中途抛错退出**（2026-09-19 实测：退出码 1，但 12/12 容器
#    已重建、`/actuator/health` = UP —— 典型假红，会把「成功」读成「失败」）。
# 修法：调用原生命令期间临时把 EAP 降为 Continue，成败**一律以 $LASTEXITCODE 为准**。
# 输出不做任何包装（不接管道），因此调用者的 `*>` / `2>&1` 重定向照常生效。
# 退出码经 [ref] 带回，避免「函数没有 return 就没有输出」的歧义。
function Invoke-NativeCommand {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$ArgumentList,
        [Parameter(Mandatory)][ref]$ExitCode
    )
    $previousEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $FilePath @ArgumentList
        $ExitCode.Value = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousEap
    }
}

$Root = $PSScriptRoot
$Infra = Join-Path $Root "infra"
$EnvFile = Join-Path $Infra ".env"
if (-not (Test-Path $EnvFile)) { Copy-Item (Join-Path $Infra ".env.example") $EnvFile }

# 钉镜像标签：未设置 IMAGE_TAG 时用当前 git short SHA，避免默认 latest 漂移
if (-not $env:IMAGE_TAG -or $env:IMAGE_TAG.Trim() -eq "") {
  try {
    $sha = (& git -C $Root rev-parse --short HEAD 2>$null)
    if ($LASTEXITCODE -eq 0 -and $sha) {
      $env:IMAGE_TAG = $sha.Trim()
    } else {
      $env:IMAGE_TAG = "local"
    }
  } catch {
    $env:IMAGE_TAG = "local"
  }
}
Write-Host "IMAGE_TAG=$($env:IMAGE_TAG)"

$composeFiles = @(
  "-f", (Join-Path $Infra "docker-compose.full.yml")
)
# Windows Hyper-V often reserves 9000/9092 ranges; win-ports remaps MinIO/Redpanda/trade host ports.
if ($env:OS -match 'Windows' -or $IsWindows) {
  $composeFiles += @("-f", (Join-Path $Infra "docker-compose.win-ports.yml"))
}
if ($DevOps) {
  $composeFiles += @("-f", (Join-Path $Infra "docker-compose.devops.yml"), "--profile", "devops")
}

$appServices = @(
  "postgres", "redis", "emqx", "minio", "minio-init", "redpanda",
  "vision-service", "xxl-job-mysql", "xxl-job-admin",
  "trade-service", "device-service", "device-simulator", "gateway"
)
if ($WithMonitoring -or $DevOps) {
  $appServices += @("prometheus", "grafana")
}

$composeArgs = @("compose", "--env-file", $EnvFile) + $composeFiles + @("up", "-d") + $appServices
if (-not $NoBuild) { $composeArgs += "--build" }

# 生成 admin 运行时配置（高德 JS API key）——务必在起 gateway **之前**：
# gateway 把 services/.../static/admin 直接 bind-mount 给 nginx，该 json 就靠这条路径生效。
# 产物里只有一句 `fetch(BASE_URL + 'runtime-config.json')`，所以 key 不参与构建、不影响 CI
# 的 admin-artifacts 逐字节比对（该 json 是 .gitignore 的）。缺 key 时脚本写 `{}`，页面降级
# Leaflet —— 属正常降级，因此这里失败只告警、不中断启动。
$genScript = Join-Path $Root "scripts/gen-admin-runtime-config.mjs"
if (Test-Path $genScript) {
  $genExit = 0
  try {
    Invoke-NativeCommand -FilePath "node" -ArgumentList @($genScript) -ExitCode ([ref]$genExit)
    if ($genExit -ne 0) {
      Write-Host "gen-admin-runtime-config exited with $genExit (大屏将降级 Leaflet)" -ForegroundColor Yellow
    }
  } catch {
    Write-Host "gen-admin-runtime-config skipped: $($_.Exception.Message)" -ForegroundColor Yellow
  }
}

$composeExit = 0
Invoke-NativeCommand -FilePath docker -ArgumentList $composeArgs -ExitCode ([ref]$composeExit)
if ($composeExit -ne 0) {
  Write-Host "docker compose up exited with $composeExit" -ForegroundColor Red
  exit $composeExit
}

# Keep devops tooling stopped unless explicitly requested（容器不存在时忽略）
if (-not $DevOps) {
  foreach ($name in @('ai-cabinet-sonarqube-1', 'ai-cabinet-sonarqube-db-1', 'ai-cabinet-github-runner-1')) {
    cmd /c "docker stop $name >nul 2>nul"
  }
}
if (-not $WithMonitoring -and -not $DevOps) {
  foreach ($name in @('ai-cabinet-prometheus-1', 'ai-cabinet-grafana-1')) {
    cmd /c "docker stop $name >nul 2>nul"
  }
}

Write-Host "Waiting for trade-service..."
$deadline = (Get-Date).AddMinutes(8)
$healthUrls = @(
    "http://localhost:18080/actuator/health",
    "http://localhost:8080/actuator/health"
)
do {
    $health = $null
    foreach ($url in $healthUrls) {
        try {
            $health = Invoke-RestMethod $url -TimeoutSec 3
            if ($health.status -eq "UP") { break }
        } catch { $health = $null }
    }
    if ($health.status -eq "UP") { break }
    Start-Sleep -Seconds 3
} while ((Get-Date) -lt $deadline)
if ($health.status -ne "UP") {
  $psExit = 0
  Invoke-NativeCommand -FilePath docker -ExitCode ([ref]$psExit) `
    -ArgumentList (@("compose", "--env-file", $EnvFile) + $composeFiles + @("ps"))
  throw "trade-service did not become healthy (docker compose ps exit=$psExit)"
}

Write-Host "AI Cabinet Docker app stack is ready (devops skipped unless -DevOps/-WithMonitoring)" -ForegroundColor Green
Write-Host "Admin:    http://localhost/admin/index.html"
Write-Host "API:      http://localhost:18080  (gateway http://localhost)"
Write-Host "XXL-JOB:  http://localhost:18090/xxl-job-admin  (admin / 123456)"
Write-Host "MinIO:    http://localhost:19000 (API) / http://localhost:19001 (console) on Windows win-ports"
if ($WithMonitoring -or $DevOps) {
  Write-Host "Grafana:  http://localhost/devops/grafana/"
}
if ($DevOps) {
  Write-Host "SonarQube: http://localhost:19002"
}
