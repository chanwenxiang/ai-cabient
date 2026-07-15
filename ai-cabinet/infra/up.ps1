# 启动全栈 Docker Compose（基础设施 + 应用）
param(
    [switch]$Prod,
    [switch]$Build,
    [switch]$Down
)

$ErrorActionPreference = "Stop"
$InfraDir = $PSScriptRoot
Set-Location $InfraDir

$composeArgs = @(
    "-f", "docker-compose.yml",
    "-f", "docker-compose.apps.yml",
    "--profile", "apps"
)

if (-not (Test-Path ".env")) {
    Copy-Item ".env.example" ".env"
    Write-Host "Created infra/.env from .env.example"
}

if ($Down) {
    docker compose @composeArgs down
    exit 0
}

if ($Prod) {
    Write-Host "Production mode: ensure infra/.env has strong secrets and mock disabled."
}

$upArgs = @("up", "-d")
if ($Build) { $upArgs += "--build" }

docker compose @composeArgs @upArgs

Write-Host ""
Write-Host "Stack starting. Useful URLs:"
Write-Host "  Admin:  http://localhost/admin/index.html"
Write-Host "  API:    http://localhost:8080"
Write-Host "  Grafana http://localhost:3000"
Write-Host ""
Write-Host "Verify: ..\scripts\verify-step1.ps1"
Write-Host "E2E:    ..\scripts\e2e-recharge.ps1"
Write-Host "        ..\scripts\e2e-shopping.ps1"
