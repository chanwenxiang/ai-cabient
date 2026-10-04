# Shared SMS webhook login helpers for staging / Step 5 / Phase E2E

function Invoke-E2eApi {
    param(
        [string]$BaseUrl,
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

function Test-E2eSmsMockAvailable {
    param([string]$SmsMockUrl = "http://localhost:8099")
    try {
        Invoke-RestMethod -Uri "$SmsMockUrl/health" -TimeoutSec 2 | Out-Null
        return $true
    } catch {
        return $false
    }
}

function Get-E2eSmsCodeFromDb {
    param(
        [string]$BaseUrl = "http://localhost:8080",
        [string]$Phone,
        [string]$InternalApiKey = "dev-internal-key-change-me",
        [int]$Retries = 8,
        [int]$DelayMs = 500
    )
    $headers = @{ "X-Internal-Api-Key" = $InternalApiKey }
    for ($i = 0; $i -lt $Retries; $i++) {
        Start-Sleep -Milliseconds $DelayMs
        try {
            $data = Invoke-RestMethod -Method GET `
                -Uri "$BaseUrl/internal/v1/sms/latest-code?phoneNumber=$Phone" `
                -Headers $headers -TimeoutSec 5
            if ($data.code -eq 0 -and $data.data.code -match '^\d{6}$') {
                return $data.data.code
            }
        } catch { }
    }
    return $null
}

function Get-E2eSmsCodeFromMock {
    param(
        [string]$SmsMockUrl = "http://localhost:8099",
        [string]$Phone,
        [int]$Retries = 8,
        [int]$DelayMs = 500
    )
    for ($i = 0; $i -lt $Retries; $i++) {
        Start-Sleep -Milliseconds $DelayMs
        try {
            $last = Invoke-RestMethod -Uri "$SmsMockUrl/last" -TimeoutSec 5
            if ($last.phoneNumber -eq $Phone -and $last.code -match '^\d{6}$') {
                return $last.code
            }
        } catch { }
    }
    return $null
}

function Get-E2eSmsCode {
    param(
        [string]$BaseUrl = "http://localhost:8080",
        [string]$Phone,
        [string]$SmsMockUrl = "http://localhost:8099",
        [string]$InternalApiKey = "dev-internal-key-change-me"
    )
    $fromWebhook = Get-E2eSmsCodeFromMock -SmsMockUrl $SmsMockUrl -Phone $Phone
    if ($fromWebhook) { return $fromWebhook }

    $fromDb = Get-E2eSmsCodeFromDb -BaseUrl $BaseUrl -Phone $Phone -InternalApiKey $InternalApiKey
    if ($fromDb) { return $fromDb }

    throw "SMS code for $Phone not found (webhook $SmsMockUrl/last or DB internal API)"
}

function Invoke-E2eSmsLogin {
    param(
        [string]$BaseUrl = "http://localhost:8080",
        [string]$Phone,
        [string]$SmsMockUrl = "http://localhost:8099",
        [string]$InternalApiKey = "dev-internal-key-change-me",
        [string]$LoginPath = "/api/v2/auth/login"
    )
    Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/sms-code?phoneNumber=$Phone" | Out-Null
    $code = Get-E2eSmsCode -BaseUrl $BaseUrl -Phone $Phone -SmsMockUrl $SmsMockUrl -InternalApiKey $InternalApiKey
    return Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path $LoginPath -Body @{
        phoneNumber = $Phone
        code        = $code
    }
}

# dev：sms-webhook 不可用时回退 123456；staging：优先 webhook/DB 真码
function Invoke-E2eFlexibleLogin {
    param(
        [string]$BaseUrl = "http://localhost:8080",
        [string]$Phone,
        [string]$SmsMockUrl = "http://localhost:8099",
        [string]$InternalApiKey = "dev-internal-key-change-me",
        [string]$LoginPath = "/api/v2/auth/login",
        [string]$DevMockCode = "123456",
        [switch]$ForceDevMock
    )
    Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/sms-code?phoneNumber=$Phone" | Out-Null

    $code = $null
    if (-not $ForceDevMock) {
        if (Test-E2eSmsMockAvailable -SmsMockUrl $SmsMockUrl) {
            $code = Get-E2eSmsCodeFromMock -SmsMockUrl $SmsMockUrl -Phone $Phone -Retries 6
        }
        if (-not $code) {
            $code = Get-E2eSmsCodeFromDb -BaseUrl $BaseUrl -Phone $Phone -InternalApiKey $InternalApiKey -Retries 4
        }
    }
    if (-not $code) {
        $code = $DevMockCode
    }

    $login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path $LoginPath -Body @{
        phoneNumber = $Phone
        code        = $code
    }
    return @{
        Token  = $login.token
        UserId = $login.userId
        Auth   = @{ Authorization = "Bearer $($login.token)" }
        Login  = $login
        Code   = $code
    }
}

function Ensure-E2eConsumerBalance {
    param(
        [string]$BaseUrl,
        [string]$SmsMockUrl,
        [string]$InternalApiKey = "dev-internal-key-change-me",
        [string]$ConsumerPhone = "13800138000",
        [string]$OperatorPhone = "13900000001",
        [long]$ConsumerUserId = 10001,
        [int]$MinBalanceCents = 500,
        [int]$TopUpCents = 2000
    )
    $consumer = Invoke-E2eSmsLogin -BaseUrl $BaseUrl -Phone $ConsumerPhone -SmsMockUrl $SmsMockUrl `
        -InternalApiKey $InternalApiKey
    $auth = @{ Authorization = "Bearer $($consumer.token)" }
    $account = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/account" -Headers $auth
    if ($account.balanceCents -ge $MinBalanceCents) {
        return @{ Login = $consumer; Auth = $auth; BalanceCents = $account.balanceCents }
    }
    Write-Host "    Consumer balance low ($($account.balanceCents)), topping up via ops..."
    $operator = Invoke-E2eSmsLogin -BaseUrl $BaseUrl -Phone $OperatorPhone -SmsMockUrl $SmsMockUrl `
        -InternalApiKey $InternalApiKey -LoginPath "/api/v2/auth/admin-login"
    $opAuth = @{ Authorization = "Bearer $($operator.token)" }
    Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/ops/admin/users/$ConsumerUserId/balance" `
        -Headers $opAuth -Body @{ deltaCents = $TopUpCents } | Out-Null
    $account = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/account" -Headers $auth
    return @{ Login = $consumer; Auth = $auth; BalanceCents = $account.balanceCents }
}
