# 已废弃：勿再单独启动 infra/docker-compose.yml（会在 Docker Desktop 里多出一个 infra 项目）。
# 统一使用仓库根目录全栈启动：
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Write-Host "start-infra.ps1 已废弃 → 请改用: .\docker-up.ps1" -ForegroundColor Yellow
& (Join-Path $Root "docker-up.ps1") @args
exit $LASTEXITCODE
