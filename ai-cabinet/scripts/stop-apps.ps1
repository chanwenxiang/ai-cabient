# Stop Step 1 app containers so ports 8080/8081/8082 are free for IDEA local run.
# Infra (postgres, emqx, minio, ...) keeps running.

$ErrorActionPreference = "Stop"
$Infra = Join-Path (Split-Path -Parent $PSScriptRoot) "infra"
Set-Location $Infra

$compose = @(
    "compose", "-f", "docker-compose.yml", "-f", "docker-compose.apps.yml",
    "--profile", "apps"
)

Write-Host "==> Stopping app containers (trade / device / vision)..."
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
docker @compose stop trade-service device-service vision-service 2>&1 | Out-Null
$ErrorActionPreference = $prevEap
Write-Host "    done (ports 8080/8081/8082 free for IDEA)"
