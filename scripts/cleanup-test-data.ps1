# Cleanup E2E/browser test artifacts: resolve open exceptions, cancel blocking sessions, restore consumer balance.
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$DeviceId = "CAB-001",
    [string]$ConsumerPhone = "13800138000",
    [int]$RestoreBalanceCents = 11300,
    [string]$OperatorPhone = "13900000001",
    [string]$OperatorPassword = "123456",
    [string]$PostgresContainer = "ai-cabinet-postgres-1",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "e2e-lib.ps1")

Write-Host "========== Cleanup Test Data =========="

if (-not $DryRun) {
    Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId -PostgresContainer $PostgresContainer | Out-Null
}

$opsAuth = $null
if (-not $DryRun) {
    try {
        $login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
            phoneNumber = $OperatorPhone
            password    = $OperatorPassword
        }
        $opsAuth = @{ Authorization = "Bearer $($login.token)" }
    } catch {
        Write-Warning "Operator login failed: $_"
    }
}

$openDisputes = docker exec $PostgresContainer psql -U aicabinet -d aicabinet -t -A -c `
    "SELECT ticket_id || '|' || COALESCE(session_id,'') FROM dispute_ticket WHERE status='OPEN';" 2>&1
$disputeRows = @($openDisputes -split "`n" | Where-Object { $_.Trim() })
Write-Host "Open disputes: $($disputeRows.Count)"

if ($disputeRows.Count -gt 0 -and -not $DryRun -and $null -ne $opsAuth) {
    foreach ($row in $disputeRows) {
        $parts = $row.Trim() -split '\|', 2
        if ($parts.Count -lt 1) { continue }
        $ticketId = $parts[0]
        $sid = if ($parts.Count -ge 2) { $parts[1] } else { '' }
        Write-Host "  -> waive dispute $ticketId (session=$sid)"
        try {
            Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
                -Path "/api/v2/ops/disputes/$ticketId/resolve" -Headers $opsAuth -Body @{
                    resolutionType = 'WAIVE'
                    items          = @()
                } | Out-Null
        } catch {
            Write-Warning "  failed to waive dispute $ticketId : $_"
        }
    }
}

$openExceptions = docker exec $PostgresContainer psql -U aicabinet -d aicabinet -t -A -c `
    "SELECT exception_id || '|' || exception_type || '|' || COALESCE(session_id,'') FROM ops_exception WHERE status IN ('OPEN','PROCESSING');" 2>&1
$rows = @($openExceptions -split "`n" | Where-Object { $_.Trim() })
Write-Host "Open exceptions: $($rows.Count)"

if ($rows.Count -gt 0 -and -not $DryRun) {
    try {
        if ($null -eq $opsAuth) {
            throw "operator auth unavailable"
        }
        $auth = $opsAuth
        foreach ($row in $rows) {
            $parts = $row.Trim() -split '\|', 3
            if ($parts.Count -lt 2) { continue }
            $exId = $parts[0]
            $exType = $parts[1]
            $sid = if ($parts.Count -ge 3) { $parts[2] } else { '' }
            Write-Host "  -> resolve $exId ($exType session=$sid)"
            try {
                if ($sid -and $exType -in @('BALANCE_INSUFFICIENT', 'RECOGNITION_FAILED', 'RECOGNITION_UNAVAILABLE', 'SETTLEMENT_FAILED')) {
                    Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
                        -Path "/api/v2/ops/admin/exceptions/$exId/manual-resolve" -Headers $auth -Body @{
                            resolutionType = 'WAIVE'
                            items          = @()
                            reason         = 'E2E cleanup waive'
                            idempotencyKey = "cleanup-waive-$exId"
                        } | Out-Null
                } else {
                    Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
                        -Path "/api/v2/ops/admin/exceptions/$exId/resolve" -Headers $auth -Body @{
                            resolution = 'E2E cleanup auto-resolve'
                        } | Out-Null
                }
            } catch {
                Write-Warning "  failed to resolve $exId : $_"
            }
        }
    } catch {
        Write-Warning "Operator login failed; falling back to SQL resolve: $_"
        $sql = @"
UPDATE ops_exception SET status='RESOLVED', resolution='E2E cleanup', resolved_at=NOW(), updated_at=NOW()
WHERE status IN ('OPEN','PROCESSING');
UPDATE shopping_session SET state='CANCELLED', updated_at=NOW()
WHERE state IN ('DISPUTED','RECOGNIZING','SETTLING','SHOPPING','OPENING','CREATED')
  AND device_id = '$DeviceId';
"@
        docker exec $PostgresContainer psql -U aicabinet -d aicabinet -c $sql | Out-Null
    }
}

if (-not $DryRun) {
    Set-E2eConsumerBalance -BalanceCents $RestoreBalanceCents -Phone $ConsumerPhone -PostgresContainer $PostgresContainer | Out-Null
    Write-Host "Restored consumer $ConsumerPhone balance to $RestoreBalanceCents cents"
}

$summary = docker exec $PostgresContainer psql -U aicabinet -d aicabinet -c @"
SELECT (SELECT COUNT(*) FROM dispute_ticket WHERE status='OPEN') AS open_disputes,
       (SELECT COUNT(*) FROM ops_exception WHERE status IN ('OPEN','PROCESSING')) AS open_exceptions,
       (SELECT balance_cents FROM user_account ua JOIN user_info ui ON ua.user_id=ui.user_id WHERE ui.phone_number='$ConsumerPhone') AS consumer_balance;
"@
Write-Host $summary
Write-Host "OK cleanup complete"
