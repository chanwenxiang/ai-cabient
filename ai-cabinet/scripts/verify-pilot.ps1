# Pilot launch verification: Step1 core + extended + Phase C
# Usage: .\scripts\verify-pilot.ps1 [-Build]

param(
    [switch]$Build
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot

Write-Host "========================================"
Write-Host " AI Cabinet Pilot Verification"
Write-Host "========================================"
Write-Host ""

$step1Args = @()
if ($Build) { $step1Args += "-Build" }

& (Join-Path $Root "scripts\verify-step1.ps1") @step1Args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "==> Extended E2E..."
& (Join-Path $Root "scripts\run-extended-e2e.ps1")
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "==> Miniapp API smoke..."
& (Join-Path $Root "scripts\run-miniapp-api-smoke.ps1")
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "==> Phase C finance E2E..."
& (Join-Path $Root "scripts\e2e-phase-c.ps1")
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "========================================"
Write-Host " Pilot verification PASSED" -ForegroundColor Green
Write-Host " Admin: http://localhost/admin/index.html"
Write-Host " Staging: .\scripts\deploy-staging.ps1"
Write-Host "========================================"
