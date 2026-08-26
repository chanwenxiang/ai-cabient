# 启动 DevOps 栈（SonarQube + GHA self-hosted runner）
param(
    [switch]$WithRunner
)

$ErrorActionPreference = "Stop"
$Root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$Infra = Join-Path $Root "infra"

Push-Location $Infra
try {
    $services = @("sonarqube-db", "sonarqube")
    if ($WithRunner) {
        $services += "github-runner"
    }

    Write-Host "Starting DevOps profile (SonarQube$(if ($WithRunner) { ' + GitHub Runner' }))..."
    docker compose -f docker-compose.full.yml -f docker-compose.devops.yml --profile devops up -d @services

    Write-Host "Reloading Prometheus alert rules..."
    docker compose -f docker-compose.full.yml exec -T prometheus kill -HUP 1 2>$null
    if ($LASTEXITCODE -ne 0) {
        docker compose -f docker-compose.full.yml restart prometheus
    }

    Write-Host ""
    Write-Host "DevOps URLs:"
    Write-Host "  SonarQube:  http://localhost:19002  (admin / admin, first login change pwd)"
    Write-Host "  GitHub Actions: https://github.com/chanwenxiang/ai-cabient/actions/workflows/sonar.yml"
    Write-Host "  Prometheus: http://localhost:9090/alerts"
    Write-Host "  Grafana:    http://localhost/devops/grafana/"
    if (-not $WithRunner) {
        Write-Host ""
        Write-Host "Tip: register self-hosted runner with scripts/devops/register-github-runner.ps1, then re-run -WithRunner"
    }
}
finally {
    Pop-Location
}
