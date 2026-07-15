# Staging deployment helper
# Usage: .\scripts\deploy-staging.ps1 [-SkipE2e]

param([switch]$SkipE2e)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Infra = Join-Path $Root "infra"
Set-Location $Infra

$envFile = Join-Path $Infra ".env.staging"
if (-not (Test-Path $envFile)) {
    Copy-Item (Join-Path $Infra ".env.staging.example") $envFile
    Write-Host "Created $envFile — edit secrets before production-like staging."
}

Write-Host "==> Staging deploy (compose + SMS mock)"
docker compose -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.staging.yml --env-file .env.staging --profile apps up -d --build
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "==> Wait for trade-service..."
$deadline = (Get-Date).AddMinutes(3)
while ((Get-Date) -lt $deadline) {
    try {
        $h = Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -TimeoutSec 5
        if ($h.status -eq "UP") { break }
    } catch {}
    Start-Sleep 5
}

Set-Location $Root
& (Join-Path $Root "scripts\verify-step5.ps1") -CheckEnv -EnvFile "infra\.env.staging"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if (-not $SkipE2e) {
    & (Join-Path $Root "scripts\verify-step5.ps1") -Staging
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host ""
Write-Host "Staging deploy complete." -ForegroundColor Green
Write-Host "  Admin:  http://localhost/admin/index.html"
Write-Host "  SMS mock log: docker logs infra-sms-webhook-mock-1"
