# 重建带 Java/Maven/Sonar Scanner 的 self-hosted runner 镜像
# 用法：.\scripts\devops\pull-gha-images.ps1
$ErrorActionPreference = "Stop"
$Root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Push-Location (Join-Path $Root "infra")
try {
  docker compose -p ai-cabinet -f docker-compose.full.yml -f docker-compose.devops.yml --profile devops build github-runner
  docker compose -p ai-cabinet -f docker-compose.full.yml -f docker-compose.devops.yml --profile devops up -d --force-recreate github-runner
  Write-Host "OK: github-runner rebuilt and restarted" -ForegroundColor Green
} finally {
  Pop-Location
}
