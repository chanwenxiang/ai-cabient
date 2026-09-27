# Create disputed/unpaid shopping order for coupon redeem; do NOT settle.
param(
    [string]$BaseUrl = "http://127.0.0.1:18080",
    [string]$DeviceId = "",
    [string]$ConsumerToken = "",
    [string]$InternalApiKey = "dev-internal-key-change-me",
    [string]$MqttBroker = "tcp://localhost:11883"
)
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "e2e-lib.ps1")
$BaseUrl = Resolve-E2eBaseUrl $BaseUrl
$DeviceId = Resolve-E2eTestDevice -DeviceId $DeviceId -UnlockSales
$E2eSku = Resolve-E2eTestSku -DeviceId $DeviceId
if ([string]::IsNullOrWhiteSpace($ConsumerToken)) { throw "ConsumerToken required" }

Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
$auth = @{ Authorization = "Bearer $ConsumerToken" }

try {
    & (Join-Path $PSScriptRoot "set-simulator-cart.ps1") -Items @("${E2eSku}:1") -ShoppingSeconds 20 -NoRecreate
} catch {
    Write-Warning "set-simulator-cart: $_"
}

$mqtt = Invoke-E2eMqttShopping -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $auth `
    -RepoRoot (Split-Path -Parent $PSScriptRoot) -MqttBroker $MqttBroker -InternalApiKey $InternalApiKey
Write-Output "SESSION=$($mqtt.SessionId)"
Write-Output "STATE=$($mqtt.FinalState)"

Start-Sleep -Seconds 2
$orders = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/orders?page=0&size=10" -Headers $auth
$items = @()
if ($orders.items) { $items = @($orders.items) }
elseif ($orders -is [System.Array]) { $items = @($orders) }

$pick = $items | Where-Object { $_.status -in @("DISPUTED", "UNPAID", "PENDING", "CREATED") } | Select-Object -First 1
if (-not $pick -and $items.Count -gt 0) { $pick = $items[0] }
if ($pick) {
    Write-Output "ORDER=$($pick.orderId)"
    Write-Output "OSTATUS=$($pick.status)"
    Write-Output "AMOUNT=$($pick.payableCents)"
} else {
    Write-Output "ORDER="
    Write-Output "OSTATUS="
}
