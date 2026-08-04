# 启动沙箱模拟全栈（真实 YOLO + 可选支付宝沙箱）
param(
    [string]$EnvFile = "",
    [switch]$Build
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
if (-not $EnvFile) {
    $EnvFile = Join-Path $Root "infra\.env.sandbox"
    if (-not (Test-Path $EnvFile)) {
        Write-Host "Copy infra\.env.sandbox.example to infra\.env.sandbox and fill ALIPAY_* first."
        $EnvFile = Join-Path $Root "infra\.env.sandbox.example"
    }
}
$ComposeFile = Join-Path $Root "infra\docker-compose.full.yml"
Push-Location (Join-Path $Root "infra")
try {
    if ($Build) {
        docker compose --env-file $EnvFile -f docker-compose.full.yml build vision-service trade-service
    }
    docker compose --env-file $EnvFile -f docker-compose.full.yml up -d
    Write-Host "Sandbox stack started. Vision: http://localhost:18082/health  Trade: http://localhost:18080"
} finally {
    Pop-Location
}
