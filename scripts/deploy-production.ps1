# Production deployment checklist runner (does NOT deploy — validates env + docs)
# Usage: .\scripts\deploy-production.ps1

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

Write-Host "========================================"
Write-Host " Production Pre-Deploy Checklist"
Write-Host "========================================"

$envProd = Join-Path $Root "infra\.env.production"
$envExample = Join-Path $Root "infra\.env.production.example"

if (-not (Test-Path $envProd)) {
    if (Test-Path $envExample) {
        Write-Host "WARN: infra/.env.production not found. Copy from .env.production.example" -ForegroundColor Yellow
    } else {
        Write-Host "WARN: create infra/.env.production from docs/PRODUCTION.md" -ForegroundColor Yellow
    }
}

& (Join-Path $Root "scripts\check-env.ps1") -CheckEnv -Prod
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "==> Vision model gate (prod template; use -AllowGenericModel if stack not up yet)"
$visionArgs = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $Root "scripts\verify-vision-model.ps1"), "-AllowGenericModel")
& powershell @visionArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Manual steps (see docs/VISION_SKU_MODEL.md + docs/PRODUCTION.md):"
Write-Host "  [ ] 训练/导出 cabinet-skus-v*.pt 至 vision-service/models 或 infra/models/production"
Write-Host "  [ ] 运营后台维护 YOLO_SKU 映射，停用 YOLO_COCO 用于真实扣款"
Write-Host "  [ ] TLS / 域名 / 微信小程序 downloadFile & request 合法域名"
Write-Host "  [ ] PostgreSQL 备份与 Flyway 迁移 (含 V58 sku_vision_mapping)"
Write-Host "  [ ] WECHAT_* 商户与小程序密钥"
Write-Host "  [ ] SMS_WEBHOOK_URL 生产短信网关"
Write-Host "  [ ] MINIO/OSS 视频桶 CORS 与生命周期"
Write-Host "  [ ] docker compose -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.production.yml --env-file .env.production --profile apps up -d --build"
Write-Host ""
Write-Host "Checklist script OK — fix warnings above before go-live." -ForegroundColor Green
