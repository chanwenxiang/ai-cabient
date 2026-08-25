# 启动 DevOps 栈并校验 Prometheus 业务告警规则
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$Root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$Infra = Join-Path $Root "infra"

Push-Location $Infra
try {
    if (-not $SkipBuild) {
        Write-Host "Building Jenkins image..."
        docker compose -f docker-compose.full.yml -f docker-compose.devops.yml --profile devops build jenkins
    }

    Write-Host "Starting DevOps profile (SonarQube + Jenkins)..."
    docker compose -f docker-compose.full.yml -f docker-compose.devops.yml --profile devops up -d sonarqube-db sonarqube jenkins

    Write-Host "Reloading Prometheus alert rules..."
    docker compose -f docker-compose.full.yml exec -T prometheus kill -HUP 1 2>$null
    if ($LASTEXITCODE -ne 0) {
        docker compose -f docker-compose.full.yml restart prometheus
    }

    Write-Host ""
    Write-Host "DevOps URLs:"
    Write-Host "  Jenkins:    http://localhost:19081  (admin / changeme)"
    Write-Host "  SonarQube:  http://localhost:19002  (admin / admin, first login change pwd)"
    Write-Host "  Prometheus: http://localhost:9090/alerts"
    Write-Host "  Grafana:    http://localhost/devops/grafana/"
}
finally {
    Pop-Location
}
