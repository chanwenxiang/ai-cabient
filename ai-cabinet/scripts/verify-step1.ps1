# Step 1: Full-stack Docker + E2E verification
# Usage: .\scripts\verify-step1.ps1 [-Build] [-SkipE2e]

param(
    [switch]$Build,
    [switch]$SkipE2e
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Infra = Join-Path $Root "infra"

function Test-DockerRunning {
    docker info *> $null
    return $LASTEXITCODE -eq 0
}

Write-Host "==> Step 1: Docker Compose + E2E"
Write-Host ""

if (-not (Test-DockerRunning)) {
    Write-Host "Docker is not running. Start Docker Desktop and retry:" -ForegroundColor Yellow
    Write-Host "  .\scripts\verify-step1.ps1 -Build"
    Write-Host ""
    Write-Host "See docs/ROADMAP.md"
    exit 1
}

Set-Location $Infra
$envFile = Join-Path $Infra ".env"
if (-not (Test-Path $envFile)) {
    Copy-Item (Join-Path $Infra ".env.example") $envFile
    Write-Host "Created infra/.env"
}

$compose = @(
    "compose", "-f", "docker-compose.yml", "-f", "docker-compose.apps.yml",
    "--profile", "apps"
)

Write-Host "==> Starting Compose$(if ($Build) { ' (--build)' })..."
$upArgs = @("up", "-d")
if ($Build) { $upArgs += "--build" }
docker @compose @upArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "==> Waiting for trade-service healthy (max 3 min)..."
$deadline = (Get-Date).AddMinutes(3)
$healthy = $false
while ((Get-Date) -lt $deadline) {
    $lines = docker @compose ps --format "{{.Service}}`t{{.Health}}" 2>$null
    foreach ($line in $lines) {
        if ($line -match "^trade-service`thealthy$") {
            $healthy = $true
            break
        }
    }
    if ($healthy) { break }
    Start-Sleep -Seconds 5
}

if (-not $healthy) {
    Write-Host "trade-service not healthy within 3 minutes. Check logs:" -ForegroundColor Yellow
    Write-Host "  cd infra"
    Write-Host "  docker compose -f docker-compose.yml -f docker-compose.apps.yml --profile apps logs -f trade-service"
    exit 1
}
Write-Host "    trade-service healthy"

Write-Host ""
Write-Host "==> HTTP probe..."
try {
    $r = Invoke-WebRequest -Uri "http://localhost:8080/actuator/health" -UseBasicParsing -TimeoutSec 10
    Write-Host "    actuator: $($r.StatusCode)"
} catch {
    Write-Host "    trying gateway http://localhost/admin/index.html"
    try {
        Invoke-WebRequest -Uri "http://localhost/admin/index.html" -UseBasicParsing -TimeoutSec 10 | Out-Null
        Write-Host "    admin OK (gateway :80)"
    } catch {
        Write-Host "    HTTP probe failed - check gateway / trade ports" -ForegroundColor Yellow
    }
}

if ($SkipE2e) {
    Write-Host ""
    Write-Host "Skipped E2E (-SkipE2e). Run manually:"
    Write-Host "  .\scripts\e2e-recharge.ps1"
    Write-Host "  .\scripts\e2e-shopping.ps1"
    exit 0
}

Set-Location $Root
Write-Host ""
Write-Host "==> E2E recharge..."
& (Join-Path $Root "scripts\e2e-recharge.ps1")
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "==> E2E shopping..."
& (Join-Path $Root "scripts\e2e-shopping.ps1")
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Step 1 complete. Admin: http://localhost/admin/index.html"
Write-Host "Next: docs/ROADMAP.md (Step 2 or Step 3)"
