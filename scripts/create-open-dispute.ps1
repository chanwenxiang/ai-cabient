# Create one OPEN recognition dispute for CAB-001 (leave unresolved for UI UAT).
# Usage:
#   .\scripts\create-open-dispute.ps1
#   $env:E2E_BASE_URL='http://127.0.0.1:18080'; .\scripts\create-open-dispute.ps1
#   .\scripts\create-open-dispute.ps1 -Force     # 无视已有记录，强制新造一条
# Output JSON: .tmp/open-dispute.json  { sessionId, ticketId, reviewCode }
#
# 幂等语义（默认）：若 .tmp/open-dispute.json 记录的那条工单**仍是 OPEN**，直接复用它并
# 刷新文件，不再走一遍 MQTT 造单（快、且不制造多余数据）。只有记录缺失/已结案时才新造。
# 存在理由：该脚本产出的是「待结案工单」这种**一次性消耗品**——UI UAT 的 D-A04 会把它免单结案，
# 于是第二次跑 UAT 必然「种子失效」。旧行为是每次无脑新造（跑一次 UAT 就得手动重跑种子），
# 现在把「保证记录里的工单当前有效」做成脚本自身的职责。
param(
    [string]$BaseUrl = "",
    [string]$DeviceId = "330449777078",
    [string]$ConsumerPhone = "13800138000",
    [string]$ConsumerPassword = "123456",
    [string]$OperatorPhone = "13900000001",
    [string]$OperatorPassword = "123456",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "e2e-lib.ps1")
$BaseUrl = Resolve-E2eBaseUrl $BaseUrl
$VisionUrl = Get-E2eVisionUrl
$VisionApiKey = Get-E2eVisionApiKey
$outFile = Join-Path $RepoRoot ".tmp\open-dispute.json"
New-Item -ItemType Directory -Force -Path (Split-Path $outFile) | Out-Null

if (-not (Test-ServiceHealth -Url "$BaseUrl/actuator/health")) {
    throw "trade-service not healthy at $BaseUrl"
}

$e2eLock = Enter-E2eLock -Owner "create-open-dispute"
$reused = $false
try {
    $opsLogin = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
        phoneNumber = $OperatorPhone; password = $OperatorPassword
    }
    $ops = @{ Authorization = "Bearer $($opsLogin.token)" }

    # —— 幂等分支：记录里的工单若仍 OPEN，就直接复用 ——
    if (-not $Force -and (Test-Path $outFile)) {
        $rec = $null
        try { $rec = (Get-Content -Raw -Path $outFile) | ConvertFrom-Json } catch { $rec = $null }
        if ($rec -and $rec.ticketId -and $rec.sessionId) {
            $stillOpen = $null
            try {
                $probe = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
                    -Path "/api/v2/ops/disputes?status=OPEN&sessionId=$($rec.sessionId)&page=0&size=5" -Headers $ops
                $stillOpen = @($probe.items) |
                    Where-Object { [string]$_.ticketId -eq [string]$rec.ticketId } |
                    Select-Object -First 1
            } catch { $stillOpen = $null }
            if ($stillOpen) {
                $reusedPayload = [ordered]@{
                    sessionId  = [string]$rec.sessionId
                    ticketId   = [string]$rec.ticketId
                    reviewCode = [string]$stillOpen.reviewCode
                    deviceId   = [string]$rec.deviceId
                    adminUrl   = "http://localhost/admin/disputes?status=OPEN&ticketId=$($rec.ticketId)&sessionId=$($rec.sessionId)"
                }
                [System.IO.File]::WriteAllText($outFile, ($reusedPayload | ConvertTo-Json -Compress), [System.Text.UTF8Encoding]::new($false))
                Write-Host "OPEN dispute reused (still OPEN): ticket=$($rec.ticketId) session=$($rec.sessionId)"
                Write-Host "Wrote $outFile"
                Write-Host "Admin UI: $($reusedPayload.adminUrl)"
                $reused = $true
            }
        }
    }

    if (-not $reused) {
        Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
        Set-E2eConsumerBalance -BalanceCents 20000 | Out-Null
        docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -c `
            "UPDATE device_info SET sales_locked=false WHERE device_id='$DeviceId';" | Out-Null

        Set-E2eVisionForceNeedReview -Enabled $true -VisionUrl $VisionUrl -VisionApiKey $VisionApiKey | Out-Null
        try {
            & (Join-Path $PSScriptRoot "set-simulator-cart.ps1") -Items @("SKU-DEMO-001:1") -ShoppingSeconds 8 -NoRecreate | Out-Null
            $login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/password-login" -Body @{
                phoneNumber = $ConsumerPhone; password = $ConsumerPassword
            }
            $auth = @{ Authorization = "Bearer $($login.token)" }
            $mqtt = Invoke-E2eMqttShopping -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $auth `
                -RepoRoot $RepoRoot -KeepSimulator
            if ($mqtt.FinalState -ne "DISPUTED") {
                throw "expected DISPUTED, got $($mqtt.FinalState)"
            }
            $sessionId = $mqtt.SessionId
        } finally {
            try {
                Set-E2eVisionForceNeedReview -Enabled $false -VisionUrl $VisionUrl -VisionApiKey $VisionApiKey | Out-Null
            } catch { }
        }

        $disp = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
            -Path "/api/v2/ops/disputes?status=OPEN&sessionId=$sessionId&page=0&size=5" -Headers $ops
        $ticket = @($disp.items) | Select-Object -First 1
        if (-not $ticket) { throw "no OPEN dispute for session=$sessionId" }

        $payload = [ordered]@{
            sessionId  = $sessionId
            ticketId   = [string]$ticket.ticketId
            reviewCode = [string]$ticket.reviewCode
            deviceId   = $DeviceId
            adminUrl   = "http://localhost/admin/disputes?status=OPEN&ticketId=$($ticket.ticketId)&sessionId=$sessionId"
        }
        [System.IO.File]::WriteAllText($outFile, ($payload | ConvertTo-Json -Compress), [System.Text.UTF8Encoding]::new($false))
        Write-Host "OPEN dispute ready: ticket=$($payload.ticketId) session=$sessionId"
        Write-Host "Wrote $outFile"
        Write-Host "Admin UI: $($payload.adminUrl)"
    }
}
finally {
    Exit-E2eLock $e2eLock
}
