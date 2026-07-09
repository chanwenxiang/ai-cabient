# Create infra/.env.staging from template if missing
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Infra = Join-Path $Root "infra"
$target = Join-Path $Infra ".env.staging"
$example = Join-Path $Infra ".env.staging.example"

if (-not (Test-Path $example)) {
    Write-Host "Missing $example" -ForegroundColor Red
    exit 1
}
if (Test-Path $target) {
    Write-Host "Already exists: $target"
} else {
    Copy-Item $example $target
    Write-Host "Created $target from example"
}
Write-Host ""
Write-Host "Next:"
Write-Host "  .\scripts\verify-step5.ps1 -CheckEnv -EnvFile infra\.env.staging"
Write-Host "  .\scripts\verify-step5.ps1 -Staging"
