# Compare refund restoreInventory true vs false
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "e2e-lib.ps1")
$BaseUrl = Resolve-E2eBaseUrl ""
$EnvFile = Join-Path $Root "infra\.env.sandbox"
if (-not (Test-Path $EnvFile)) { $EnvFile = Join-Path $Root "infra\.env" }

$lock = Enter-E2eLock -Owner "refund-restore-compare"
try {
    $demo = & (Join-Path $PSScriptRoot "seed-demo-data.ps1") -BaseUrl $BaseUrl -InternalApiKey "dev-internal-key-change-me" -Ensure
    $DeviceId = $demo.deviceId
    $Phone = $demo.consumerPhone
    $login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/password-login" -Body @{
        phoneNumber = $Phone; password = "123456"
    }
    $auth = @{ Authorization = ("Bearer " + $login.token) }
    Set-E2eConsumerBalance -BalanceCents 50000 -Phone $Phone | Out-Null
    $opsLogin = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
        phoneNumber = "13900000001"; password = "123456"
    }
    $ops = @{ Authorization = ("Bearer " + $opsLogin.token) }
    $inv = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path ("/api/v2/ops/admin/inventory?deviceId=" + $DeviceId) -Headers $ops
    $sku = ($inv | Where-Object { $_.quantity -ge 3 } | Sort-Object quantity -Descending | Select-Object -First 1).skuId
    if (-not $sku) { throw "no sku with qty>=3" }

    function Get-Qty([string]$SkuId) {
        $m = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path ("/api/v2/ops/admin/inventory?deviceId=" + $DeviceId) -Headers $ops
        return [int](($m | Where-Object { $_.skuId -eq $SkuId }).quantity)
    }
    function Invoke-TakeOne([string]$SkuId) {
        & (Join-Path $PSScriptRoot "set-simulator-cart.ps1") -Items @(($SkuId + ":1")) -ShoppingSeconds 6 -EnvFile $EnvFile | Out-Null
        Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
        $mqtt = Invoke-E2eMqttShopping -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $auth `
            -RepoRoot $Root -InternalApiKey "dev-internal-key-change-me" -KeepSimulator
        Start-Sleep -Seconds 2
        $orders = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
            -Path ("/api/v2/orders?page=0" + [char]38 + "size=10") -Headers $auth
        $hit = @($orders.items) | Where-Object { $_.sessionId -eq $mqtt.SessionId } | Select-Object -First 1
        if (-not $hit) { throw ("no order for session " + $mqtt.SessionId) }
        return $hit
    }

    Write-Host ("sku=" + $sku)
    $q0 = Get-Qty $sku
    $o1 = Invoke-TakeOne $sku
    $q1 = Get-Qty $sku
    $rf1 = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
        -Path ("/api/v2/ops/admin/orders/" + $o1.orderId + "/refund") -Headers $ops -Body @{
        reason            = "quality issue kept goods no restock"
        restoreInventory  = $false
    }
    $q2 = Get-Qty $sku
    Write-Host ("NO_RESTORE qty {0}->{1}->{2} restored={3}" -f $q0, $q1, $q2, $rf1.inventoryRestored)

    $o2 = Invoke-TakeOne $sku
    $q3 = Get-Qty $sku
    $rf2 = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
        -Path ("/api/v2/ops/admin/orders/" + $o2.orderId + "/refund") -Headers $ops -Body @{
        reason           = "misrecognition empty hand restock"
        restoreInventory = $true
    }
    $q4 = Get-Qty $sku
    Write-Host ("RESTORE mid={0} after={1} restored={2}" -f $q3, $q4, $rf2.inventoryRestored)

    $pass1 = ($q1 -eq ($q0 - 1)) -and ($q2 -eq $q1) -and ($rf1.inventoryRestored -eq $false)
    $pass2 = ($q4 -eq ($q3 + 1)) -and ($rf2.inventoryRestored -eq $true)
    Write-Host ("PASS_NO_RESTORE=" + $pass1 + " PASS_RESTORE=" + $pass2)
    if (-not ($pass1 -and $pass2)) { exit 1 }
} finally {
    Exit-E2eLock $lock
}
