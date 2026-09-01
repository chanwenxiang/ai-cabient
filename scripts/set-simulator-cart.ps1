param(
    [Parameter(Mandatory = $true)]
    [string[]]$Items,
    [int]$ShoppingSeconds = 20,
    [string]$EnvFile = "",
    [string]$VideoFile = "",
    [switch]$NoRecreate,
    # IDEA / local DeviceSimulator mode: only export env vars; do not touch docker simulator
    [switch]$SkipDocker
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$ComposeFile = Join-Path $Root 'infra\docker-compose.full.yml'
$WinPortsFile = Join-Path $Root 'infra\docker-compose.win-ports.yml'
$composeFileArgs = @('-f', $ComposeFile)
if (($env:OS -match 'Windows' -or $IsWindows) -and (Test-Path $WinPortsFile)) {
    $composeFileArgs += @('-f', $WinPortsFile)
}
if (-not $EnvFile) {
    $EnvFile = Join-Path $Root 'infra\.env.sandbox'
    if (-not (Test-Path $EnvFile)) {
        $EnvFile = Join-Path $Root 'infra\.env'
    }
}

$slotNumber = 1
$deltas = foreach ($item in $Items) {
    if ($item -notmatch '^([^:]+):(\d+)$') {
        throw "Invalid item format: $item. Use SKU:quantity, for example SKU-WATER-001:2"
    }
    $skuId = $Matches[1].Trim().ToUpperInvariant()
    $quantity = [int]$Matches[2]
    if ($quantity -lt 1) {
        throw "Item quantity must be greater than zero: $item"
    }
    [ordered]@{
        skuId  = $skuId
        delta  = -$quantity
        slotId = "SIM-$slotNumber"
    }
    $slotNumber++
}

$env:AICABINET_SIM_GRAVITY_JSON = ConvertTo-Json @($deltas) -Compress
$env:AICABINET_SIM_SHOPPING_MS = [string]($ShoppingSeconds * 1000)
if ($VideoFile) {
    $env:AICABINET_SIM_VIDEO_FILE = $VideoFile
}

Write-Host 'Simulator cart:'
$deltas | ForEach-Object {
    Write-Host "  $($_.skuId) x $(-$_.delta)"
}
Write-Host "Shopping duration: $ShoppingSeconds seconds"

if ($SkipDocker -or $env:E2E_USE_DOCKER_SIMULATOR -eq '0') {
    Write-Host 'SkipDocker: env exported for local DeviceSimulator (no docker compose).'
    exit 0
}

# docker compose writes progress to stderr; do not treat that as a terminating error in PowerShell.
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
if ($NoRecreate) {
    docker compose -p ai-cabinet --env-file $EnvFile @composeFileArgs up -d --no-deps device-simulator 2>&1 | Out-Null
} else {
    docker compose -p ai-cabinet --env-file $EnvFile @composeFileArgs up -d --no-deps --force-recreate device-simulator 2>&1 | Out-Null
}
$composeExit = $LASTEXITCODE
$ErrorActionPreference = $prevEap
if ($composeExit -ne 0) {
    exit $composeExit
}

if (-not $NoRecreate) {
    Write-Host 'Waiting 12s for MQTT warmup after simulator recreate...'
    Start-Sleep -Seconds 12
}

Write-Host 'Device simulator updated. The next shopping session will settle these items.'
