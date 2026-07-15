# Recharge E2E (dev mock mode)
# Requires trade-service at http://localhost:8080 with mock-enabled=true

param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Phone = "13800138000",
    [string]$Code = "123456",
    [int]$AmountCents = 500
)

$ErrorActionPreference = "Stop"

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [hashtable]$Headers = @{},
        $Body = $null
    )
    $uri = "$BaseUrl$Path"
    $params = @{
        Method      = $Method
        Uri         = $uri
        ContentType = "application/json"
    }
    if ($Headers.Count -gt 0) { $params.Headers = $Headers }
    if ($null -ne $Body) { $params.Body = ($Body | ConvertTo-Json -Compress) }
    $resp = Invoke-RestMethod @params
    if ($resp.code -ne 0) {
        throw "API error: $($resp.message) (path=$Path)"
    }
    return $resp.data
}

Write-Host "==> 1. Send SMS code"
Invoke-Api -Method POST -Path "/api/v2/auth/sms-code?phoneNumber=$Phone" | Out-Null

Write-Host "==> 2. Login"
$login = Invoke-Api -Method POST -Path "/api/v2/auth/login" -Body @{
    phoneNumber = $Phone
    code        = $Code
}
$token = $login.token
$userId = $login.userId
Write-Host "    userId=$userId"

$auth = @{ Authorization = "Bearer $token" }

Write-Host "==> 3. Balance before"
$before = Invoke-Api -Method GET -Path "/api/v2/account" -Headers $auth
Write-Host "    balanceCents=$($before.balanceCents)"

Write-Host "==> 4. Create recharge prepay"
$prepay = Invoke-Api -Method POST -Path "/api/v2/payment/recharge/prepay" -Headers $auth -Body @{
    channel     = "WECHAT"
    amountCents = $AmountCents
}
$orderId = $prepay.debugInfo.orderId
Write-Host "    orderId=$orderId mode=$($prepay.debugInfo.mode)"

Write-Host "==> 5. Mock payment notify"
Invoke-Api -Method POST -Path "/api/v2/payment/wechat/notify/mock/$orderId" | Out-Null

Write-Host "==> 6. Query order"
$order = Invoke-Api -Method GET -Path "/api/v2/payment/recharge/$orderId" -Headers $auth
Write-Host "    status=$($order.status) amountCents=$($order.amountCents)"

Write-Host "==> 7. Balance after"
$after = Invoke-Api -Method GET -Path "/api/v2/account" -Headers $auth
Write-Host "    balanceCents=$($after.balanceCents)"

$delta = $after.balanceCents - $before.balanceCents
if ($order.status -ne "PAID") {
    throw "Expected PAID, got $($order.status)"
}
if ($delta -ne $AmountCents) {
    throw "Balance delta $delta != amount $AmountCents"
}

Write-Host ""
Write-Host "OK recharge E2E passed"
exit 0
