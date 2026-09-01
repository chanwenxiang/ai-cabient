param(
    [switch]$NoBuild,
    [switch]$DevOps,
    # App stack only (default). DevOps = prometheus/grafana + optional sonarqube profile.
    [switch]$WithMonitoring
)
$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$Infra = Join-Path $Root "infra"
$EnvFile = Join-Path $Infra ".env"
if (-not (Test-Path $EnvFile)) { Copy-Item (Join-Path $Infra ".env.example") $EnvFile }

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

& docker @composeArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Keep devops tooling stopped unless explicitly requested
if (-not $DevOps) {
  docker stop ai-cabinet-sonarqube-1 ai-cabinet-sonarqube-db-1 ai-cabinet-github-runner-1 2>$null | Out-Null
}
if (-not $WithMonitoring -and -not $DevOps) {
  docker stop ai-cabinet-prometheus-1 ai-cabinet-grafana-1 2>$null | Out-Null
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
  docker compose --env-file $EnvFile @composeFiles ps
  throw "trade-service did not become healthy"
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
