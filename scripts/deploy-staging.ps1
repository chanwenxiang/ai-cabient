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

# 环境变量先验后起栈：缺密钥时 compose up 会拉起一堆半死容器，排障成本远高于先 fail-fast。
Set-Location $Root
& (Join-Path $Root "scripts\check-env.ps1") -CheckEnv -EnvFile "infra\.env.staging"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Set-Location $Infra

Write-Host "==> Staging deploy (compose + SMS mock)"
docker compose -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.staging.yml --env-file .env.staging --profile apps up -d --build
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "==> Wait for trade-service..."
$deadline = (Get-Date).AddMinutes(3)
$up = $false
while ((Get-Date) -lt $deadline) {
    try {
        $h = Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -TimeoutSec 5
        if ($h.status -eq "UP") { $up = $true; break }
    } catch {}
    Start-Sleep 5
}
if (-not $up) {
    Write-Error "trade-service 健康检查 3 分钟内未 UP —— staging 部署判失败（原来会静默继续跑 e2e）"
    exit 1
}

Set-Location $Root

if (-not $SkipE2e) {
    & (Join-Path $Root "scripts\e2e-shopping.ps1") -BaseUrl "http://localhost:8080"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host ""
Write-Host "Staging deploy complete." -ForegroundColor Green
Write-Host "  Admin:  http://localhost/admin/index.html"
Write-Host "  SMS mock log: docker logs infra-sms-webhook-mock-1"
