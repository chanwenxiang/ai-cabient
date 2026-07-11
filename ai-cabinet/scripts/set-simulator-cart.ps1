param(
    [Parameter(Mandatory = $true)]
    [string[]]$Items,
    [int]$ShoppingSeconds = 20
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$ComposeFile = Join-Path $Root 'infra\docker-compose.full.yml'
$EnvFile = Join-Path $Root 'infra\.env'

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

Write-Host 'Simulator cart:'
$deltas | ForEach-Object {
    Write-Host "  $($_.skuId) x $(-$_.delta)"
}
Write-Host "Shopping duration: $ShoppingSeconds seconds"

docker compose --env-file $EnvFile -f $ComposeFile up -d --force-recreate device-simulator
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host 'Device simulator updated. The next shopping session will settle these items.'
