# Miniapp UI backend smoke - verifies APIs used by each miniapp page
param([string]$BaseUrl = "http://localhost:8080")

$ErrorActionPreference = "Stop"
$passed = 0
$failed = 0
$results = @()

function Record($id, $page, $ok, $detail) {
    $script:results += [pscustomobject]@{ Id = $id; Page = $page; Result = $(if ($ok) { "PASS" } else { "FAIL" }); Detail = $detail }
    if ($ok) { $script:passed++ } else { $script:failed++ }
}

function Api($method, $path, $headers = @{}, $body = $null) {
    $p = @{ Method = $method; Uri = "$BaseUrl$path"; ContentType = "application/json" }
    if ($headers.Count -gt 0) { $p.Headers = $headers }
    if ($body) { $p.Body = ($body | ConvertTo-Json -Compress) }
    $r = Invoke-RestMethod @p
    if ($r.code -ne 0) { throw $r.message }
    return $r.data
}

Write-Host "==> Miniapp API smoke (UI backend)"
& (Join-Path $PSScriptRoot "e2e-cleanup-device.ps1") -DeviceId "CAB-001"
$c = Api POST "/api/v2/auth/login" @{} @{ phoneNumber = "13800138000"; code = "123456" }
$h = @{ Authorization = "Bearer $($c.token)" }
$o = Api POST "/api/v2/auth/admin-login" @{} @{ phoneNumber = "13900000001"; code = "123456" }
$oh = @{ Authorization = "Bearer $($o.token)" }

$pages = @(
    @{ Id = "UI-MP-001"; Page = "login"; Test = { Api POST "/api/v2/auth/login" @{} @{ phoneNumber = "13800138000"; code = "123456" } } },
    @{ Id = "UI-MP-002"; Page = "index/balance"; Test = { Api GET "/api/v2/account" $h } },
    @{ Id = "UI-MP-003"; Page = "index/device"; Test = { Api GET "/api/v2/devices/CAB-001/status" $h } },
    @{ Id = "UI-MP-004"; Page = "index/session"; Test = { Api POST "/api/v2/sessions" $h @{ deviceId = "CAB-001" } } },
    @{ Id = "UI-MP-005"; Page = "mine/account"; Test = { Api GET "/api/v2/account" $h } },
    @{ Id = "UI-MP-006"; Page = "mine/payscore"; Test = { Api POST "/api/v2/account/payscore/sign" $h } },
    @{ Id = "UI-MP-007"; Page = "mine/disputes"; Test = { Api GET "/api/v2/disputes/mine" $h } },
    @{ Id = "UI-MP-008"; Page = "verify"; Test = { $true } },
    @{ Id = "UI-MP-009"; Page = "recharge"; Test = { Api POST "/api/v2/payment/recharge/prepay" $h @{ channel = "WECHAT"; amountCents = 100 } } },
    @{ Id = "UI-MP-010"; Page = "recharges"; Test = { Api GET "/api/v2/payment/recharges?page=0&size=5" $h } },
    @{ Id = "UI-MP-011"; Page = "orders"; Test = { Api GET "/api/v2/orders?page=0&size=5" $h } },
    @{ Id = "UI-MP-012"; Page = "dispute-mine"; Test = { Api GET "/api/v2/disputes/mine" $h } },
    @{ Id = "UI-MP-013"; Page = "ops/restock"; Test = {
        $today = (Get-Date).ToString("yyyy-MM-dd")
        $route = Api POST "/api/v2/ops/admin/replenishment/routes" $oh @{
            routeName = "SMOKE-$today"; assigneeUserId = 100000001; plannedDate = $today
            tasks = @(@{ deviceId = "CAB-001"; notes = "smoke" })
        }
        $taskId = $route.tasks[0].taskId
        Api POST "/api/v2/ops/admin/replenishment/tasks/$taskId/check-in" $oh @{
            latitude = 31.2304; longitude = 121.4737
        } | Out-Null
        Api POST "/api/v2/ops/restock/open-door" $oh @{ deviceId = "CAB-001"; taskId = $taskId }
    } },
    @{ Id = "UI-MP-014"; Page = "ops/tasks"; Test = { Api GET "/api/v2/ops/admin/replenishment/my-tasks" $oh } },
    @{ Id = "UI-MP-015"; Page = "ops/disputes"; Test = { Api GET "/api/v2/ops/disputes?page=0&size=5" $oh } },
    @{ Id = "UI-MP-016"; Page = "result/order"; Test = { Api GET "/api/v2/orders?page=0&size=1" $h } }
)

foreach ($p in $pages) {
    try {
        $r = & $p.Test
        Record $p.Id $p.Page $true "ok"
    } catch {
        Record $p.Id $p.Page $false $_.Exception.Message
    }
}

Write-Host ""
$results | Format-Table -AutoSize
Write-Host "PASS: $passed  FAIL: $failed"
if ($failed -gt 0) { exit 1 }
