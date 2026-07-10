# Start full-stack Docker Compose (infra + app services).

param(
    [switch]$Prod,
    [switch]$Build,
    [switch]$Down,
    [switch]$Smoke,
    [int]$WaitSeconds = 240
)

$ErrorActionPreference = "Stop"
$InfraDir = $PSScriptRoot
$Root = Split-Path -Parent $InfraDir
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

function Get-DotEnvValue {
    param([string]$Name)
    $envPath = Join-Path $InfraDir ".env"
    if (-not (Test-Path $envPath)) { return "" }
    $line = Get-Content $envPath | Where-Object { $_ -match "^\s*$Name\s*=" } | Select-Object -First 1
    if (-not $line) { return "" }
    return ($line -replace "^\s*$Name\s*=\s*", "").Trim().Trim('"').Trim("'")
}

function Wait-ServiceHealthy {
    param([string]$Service, [int]$TimeoutSeconds)
    Write-Host "==> Waiting for $Service to be healthy..."
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $health = docker compose @composeArgs ps $Service --format "{{.Health}}" 2>$null
        if ($health -eq "healthy") {
            Write-Host "    $Service healthy"
            return
        }
        $state = docker compose @composeArgs ps $Service --format "{{.State}}" 2>$null
        if ($state -eq "exited" -or $state -eq "dead") {
            Write-Host "$Service exited before becoming healthy." -ForegroundColor Red
            docker compose @composeArgs logs --tail 80 $Service
            exit 1
        }
        Start-Sleep -Seconds 5
    }
    Write-Host "$Service not healthy after $TimeoutSeconds seconds." -ForegroundColor Red
    docker compose @composeArgs ps
    docker compose @composeArgs logs --tail 80 $Service
    exit 1
}

if ($Prod) {
    Write-Host "Production mode: ensure infra/.env has strong secrets and mock disabled."
}

$upArgs = @("up", "-d")
if ($Build) { $upArgs += "--build" }

docker compose @composeArgs @upArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if ($Smoke) {
    Wait-ServiceHealthy "trade-service" $WaitSeconds
    Wait-ServiceHealthy "device-service" $WaitSeconds
    Wait-ServiceHealthy "vision-service" $WaitSeconds

    $internalKey = $env:INTERNAL_API_KEY
    if (-not $internalKey) { $internalKey = Get-DotEnvValue "INTERNAL_API_KEY" }
    if (-not $internalKey) {
        Write-Host "INTERNAL_API_KEY is missing from environment and infra/.env." -ForegroundColor Red
        exit 1
    }

    Write-Host ""
    Write-Host "==> Running production runtime smoke..."
    & (Join-Path $Root "scripts\verify-production-readiness.ps1") `
        -BaseUrl "http://localhost:8080" `
        -DeviceUrl "http://localhost:8081" `
        -SkipBuild `
        -SkipTests `
        -SkipAdminBuild
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    Write-Host ""
    Write-Host "==> Running API smoke..."
    & (Join-Path $Root "scripts\run-api-tests.ps1") -BaseUrl "http://localhost:8080"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host ""
Write-Host "Stack starting. Useful URLs:"
Write-Host "  Admin:  http://localhost/admin/index.html"
Write-Host "  API:    http://localhost:8080"
Write-Host "  Grafana http://localhost:3000"
Write-Host ""
Write-Host "Verify: ..\scripts\verify-local.ps1"
Write-Host "E2E:    ..\scripts\e2e-shopping.ps1"
Write-Host "Smoke:  .\up.ps1 -Build -Smoke"
