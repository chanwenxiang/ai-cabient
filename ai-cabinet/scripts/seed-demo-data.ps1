# Load or ensure demo business context from trade-service DB
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$InternalApiKey = "dev-internal-key-change-me",
    [switch]$Ensure
)

$ErrorActionPreference = "Stop"

$path = if ($Ensure) { "/internal/v1/demo/ensure" } else { "/internal/v1/demo/context" }
$method = if ($Ensure) { "POST" } else { "GET" }

$ctx = $null
try {
    $resp = Invoke-RestMethod -Method $method -Uri "$BaseUrl$path" -Headers @{
        "X-Internal-Api-Key" = $InternalApiKey
    }
    if ($resp.code -eq 0) {
        $ctx = $resp.data
    }
} catch {
    Write-Host "WARN: demo context API unavailable ($($_.Exception.Message)), using defaults"
}

if (-not $ctx) {
    $ctx = [PSCustomObject]@{
        deviceId              = "CAB-001"
        consumerPhone         = "13800138000"
        consumerUserId        = 10001
        fallbackSkuId         = "SKU-DEMO-001"
        skuCount              = 0
        deviceInventoryLines  = 0
        warehouseLotCount     = 0
    }
}
Write-Host "Demo context (from database):"
Write-Host "  deviceId       = $($ctx.deviceId)"
Write-Host "  consumerPhone  = $($ctx.consumerPhone)"
Write-Host "  fallbackSkuId  = $($ctx.fallbackSkuId)"
Write-Host "  skuCount       = $($ctx.skuCount)"
Write-Host "  invLines       = $($ctx.deviceInventoryLines)"
Write-Host "  warehouseLots  = $($ctx.warehouseLotCount)"

$global:DemoDeviceId = $ctx.deviceId
$global:DemoConsumerPhone = $ctx.consumerPhone
$global:DemoFallbackSkuId = $ctx.fallbackSkuId

return $ctx
