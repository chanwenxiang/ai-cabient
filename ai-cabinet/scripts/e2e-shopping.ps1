# Shopping E2E (dev mock mode)
# Requires trade + device + vision with mock-enabled=true

param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Phone = "13800138000",
    [string]$Code = "123456",
    [string]$DeviceId = "CAB-001",
    [string]$InternalApiKey = "dev-internal-key-change-me"
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

function Invoke-Internal {
    param([string]$Path, $Body)
    $headers = @{ "X-Internal-Api-Key" = $InternalApiKey }
    Invoke-Api -Method POST -Path $Path -Headers $headers -Body $Body
}

Write-Host "==> 1. Login"
Invoke-Api -Method POST -Path "/api/v2/auth/sms-code?phoneNumber=$Phone" | Out-Null
$login = Invoke-Api -Method POST -Path "/api/v2/auth/login" -Body @{
    phoneNumber = $Phone
    code        = $Code
}
$auth = @{ Authorization = "Bearer $($login.token)" }
Write-Host "    userId=$($login.userId)"

Write-Host "==> 2. Balance before"
$before = Invoke-Api -Method GET -Path "/api/v2/account" -Headers $auth
Write-Host "    balanceCents=$($before.balanceCents)"

Write-Host "==> 3. Create session"
$session = Invoke-Api -Method POST -Path "/api/v2/sessions" -Headers $auth -Body @{
    deviceId = $DeviceId
}
$sessionId = $session.sessionId
Write-Host "    sessionId=$sessionId state=$($session.state)"

Write-Host "==> 4. Simulate door open"
Invoke-Internal -Path "/internal/v1/sessions/door-event" -Body @{
    sessionId = $sessionId
    deviceId  = $DeviceId
    doorState = "OPEN"
    timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
}

Write-Host "==> 5. Simulate door close + video"
$videoUri = "minio://cabinet-videos/sim/$sessionId.mp4"
Invoke-Internal -Path "/internal/v1/sessions/door-event" -Body @{
    sessionId    = $sessionId
    deviceId     = $DeviceId
    doorState    = "CLOSED"
    timestamp    = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    videoUri     = $videoUri
    uploadStatus = "UPLOADED"
}

Write-Host "==> 6. Wait for order"
$order = $null
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 1
    try {
        $order = Invoke-Api -Method GET -Path "/api/v2/sessions/$sessionId/order" -Headers $auth
        if ($null -ne $order -and $order.orderId) { break }
    } catch {
        # settlement may still be running
    }
}
if ($null -eq $order -or -not $order.orderId) {
    $state = (Invoke-Api -Method GET -Path "/api/v2/sessions/$sessionId" -Headers $auth).state
    throw "Order not ready after 20s, session state=$state"
}
Write-Host "    orderId=$($order.orderId) total=$($order.totalAmountCents) status=$($order.status)"

Write-Host "==> 7. Balance after"
$after = Invoke-Api -Method GET -Path "/api/v2/account" -Headers $auth
Write-Host "    balanceCents=$($after.balanceCents)"

$spent = $before.balanceCents - $after.balanceCents
if ($order.status -ne "PAID") {
    throw "Expected order PAID, got $($order.status)"
}
if ($spent -ne $order.totalAmountCents) {
    throw "Balance spent $spent != order total $($order.totalAmountCents)"
}

Write-Host ""
Write-Host "OK shopping E2E passed"
exit 0
