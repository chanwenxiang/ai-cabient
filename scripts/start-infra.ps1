# Start infrastructure only (Step 2) - no trade/device/vision containers.

$ErrorActionPreference = "Stop"
$Infra = Join-Path (Split-Path -Parent $PSScriptRoot) "infra"
Set-Location $Infra

$envFile = Join-Path $Infra ".env"
if (-not (Test-Path $envFile)) {
    Copy-Item (Join-Path $Infra ".env.example") $envFile
    Write-Host "Created infra/.env"
}

Write-Host "==> Starting infra (postgres, emqx, minio, redis, gateway, ...)..."
docker compose -f docker-compose.yml up -d
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "==> Waiting for postgres..."
$deadline = (Get-Date).AddMinutes(2)
$ok = $false
while ((Get-Date) -lt $deadline) {
    $state = docker compose ps postgres --format "{{.Health}}" 2>$null
    if ($state -eq "healthy") { $ok = $true; break }
    # infra-only compose may have no healthcheck; fall back to pg_isready
    $pgContainer = docker compose ps postgres --format "{{.Name}}" 2>$null
    if ($pgContainer) {
        docker exec $pgContainer pg_isready -U aicabinet -d aicabinet 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $ok = $true; break }
    }
    Start-Sleep -Seconds 3
}
if (-not $ok) {
    Write-Host "postgres not healthy - check: docker compose logs postgres" -ForegroundColor Yellow
    exit 1
}
Write-Host "    postgres healthy"

Write-Host ""
Write-Host "==> Ensure MinIO bucket cabinet-videos..."
$network = docker network ls --format "{{.Name}}" | Where-Object { $_ -match "infra" -and $_ -match "default" } | Select-Object -First 1
if (-not $network) { $network = "infra_default" }
docker run --rm --network $network `
    -e MC_HOST_local="http://minioadmin:minioadmin@minio:9000" `
    minio/mc:latest `
    mb --ignore-existing local/cabinet-videos 2>$null | Out-Null
Write-Host "    minio bucket OK (network=$network)"

Write-Host ""
Write-Host "Infra ready. Next in IDEA:"
Write-Host "  1. Run TradeServiceApplication      (:8080)"
Write-Host "  2. Run DeviceServiceApplication     (:8081)"
Write-Host "  3. Run vision-service uvicorn         (:8082)"
Write-Host "  4. Run DeviceSimulator  args: CAB-001"
Write-Host ""
Write-Host "Admin via gateway: http://localhost/admin/index.html"
Write-Host "Or direct trade:   http://localhost:8080/admin/index.html"
