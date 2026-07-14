# 从项目根目录 ai-cabinet/ 构建镜像

param(
    [string]$Tag = "latest"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Set-Location $Root

Write-Host "Building from $Root"

docker build -f infra/docker/trade-service.Dockerfile -t "ai-cabinet/trade-service:$Tag" .
docker build -f infra/docker/device-service.Dockerfile -t "ai-cabinet/device-service:$Tag" .
docker build -f infra/docker/vision-service.Dockerfile -t "ai-cabinet/vision-service:$Tag" .

Write-Host "Done:"
Write-Host "  ai-cabinet/trade-service:$Tag"
Write-Host "  ai-cabinet/device-service:$Tag"
Write-Host "  ai-cabinet/vision-service:$Tag"
