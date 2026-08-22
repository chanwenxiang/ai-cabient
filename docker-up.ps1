param([switch]$NoBuild)
$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$Infra = Join-Path $Root "infra"
$EnvFile = Join-Path $Infra ".env"
if (-not (Test-Path $EnvFile)) { Copy-Item (Join-Path $Infra ".env.example") $EnvFile }
# Stop the legacy infrastructure-only compose project so host ports can be reused.
& docker compose --env-file $EnvFile -f (Join-Path $Infra "docker-compose.yml") down --remove-orphans
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$args = @("compose", "--env-file", $EnvFile, "-f", (Join-Path $Infra "docker-compose.full.yml"), "up", "-d")
if (-not $NoBuild) { $args += "--build" }
& docker @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

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
if ($health.status -ne "UP") { docker compose --env-file $EnvFile -f (Join-Path $Infra "docker-compose.full.yml") ps; throw "trade-service did not become healthy" }

Write-Host "AI Cabinet full stack is ready" -ForegroundColor Green
Write-Host "Admin:    http://localhost/admin/index.html"
Write-Host "API:      http://localhost:18080  (trade; gateway http://localhost)"
Write-Host "XXL-JOB:  http://localhost:18090/xxl-job-admin  (admin / 123456)"
Write-Host "Grafana:  http://localhost:13000"
Write-Host "MinIO:    http://localhost:9001"
