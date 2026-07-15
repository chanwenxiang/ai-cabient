# Admin UI page load check (requires logged-in token via API)
param([string]$BaseUrl = "http://localhost:8080")

$ErrorActionPreference = "Stop"
$pages = @(
    "dashboard", "devices", "sessions", "orders", "recharges", "skus", "users",
    "disputes", "vision-mappings", "upload-queue", "sla", "ota", "risk",
    "reconciliation", "replenishment", "merchants", "rbac", "audit", "recent", "reports"
)

$login = Invoke-RestMethod -Method POST -Uri "$BaseUrl/api/v2/auth/admin-login" `
    -ContentType "application/json" -Body '{"phoneNumber":"13900000001","code":"123456"}'
$token = $login.data.token
$passed = 0
$failed = 0

Write-Host "==> Admin UI static + API page checks"
$html = Invoke-WebRequest -Uri "$BaseUrl/admin/index.html" -UseBasicParsing
if ($html.StatusCode -eq 200) { Write-Host "[PASS] admin index.html loaded"; $passed++ } else { $failed++ }

foreach ($page in $pages) {
    try {
        switch ($page) {
            "dashboard" { Invoke-RestMethod -Uri "$BaseUrl/api/v2/ops/admin/stats" -Headers @{Authorization="Bearer $token"} | Out-Null }
            "devices" { Invoke-RestMethod -Uri "$BaseUrl/api/v2/ops/admin/devices" -Headers @{Authorization="Bearer $token"} | Out-Null }
            "sessions" { Invoke-RestMethod -Uri "$BaseUrl/api/v2/ops/admin/sessions?page=0&size=5" -Headers @{Authorization="Bearer $token"} | Out-Null }
            "orders" { Invoke-RestMethod -Uri "$BaseUrl/api/v2/ops/admin/orders?page=0&size=5" -Headers @{Authorization="Bearer $token"} | Out-Null }
            "recharges" { Invoke-RestMethod -Uri "$BaseUrl/api/v2/ops/admin/recharges?page=0&size=5" -Headers @{Authorization="Bearer $token"} | Out-Null }
            "skus" { Invoke-RestMethod -Uri "$BaseUrl/api/v2/ops/admin/skus" -Headers @{Authorization="Bearer $token"} | Out-Null }
            "users" { Invoke-RestMethod -Uri "$BaseUrl/api/v2/ops/admin/users?page=0&size=5" -Headers @{Authorization="Bearer $token"} | Out-Null }
            "disputes" { Invoke-RestMethod -Uri "$BaseUrl/api/v2/ops/disputes?page=0&size=5" -Headers @{Authorization="Bearer $token"} | Out-Null }
            "vision-mappings" { Invoke-RestMethod -Uri "$BaseUrl/api/v2/ops/admin/vision-mappings" -Headers @{Authorization="Bearer $token"} | Out-Null }
            "upload-queue" { Invoke-RestMethod -Uri "$BaseUrl/api/v2/ops/admin/sessions?page=0&size=5&state=WAITING_UPLOAD" -Headers @{Authorization="Bearer $token"} | Out-Null }
            "sla" { Invoke-RestMethod -Uri "$BaseUrl/api/v2/ops/admin/sla" -Headers @{Authorization="Bearer $token"} | Out-Null }
            "ota" { Invoke-RestMethod -Uri "$BaseUrl/api/v2/ops/admin/ota/releases" -Headers @{Authorization="Bearer $token"} | Out-Null }
            "risk" { Invoke-RestMethod -Uri "$BaseUrl/api/v2/ops/admin/risk/events" -Headers @{Authorization="Bearer $token"} | Out-Null }
            "reconciliation" { Invoke-RestMethod -Uri "$BaseUrl/api/v2/ops/admin/reconciliation" -Headers @{Authorization="Bearer $token"} | Out-Null }
            "replenishment" { Invoke-RestMethod -Uri "$BaseUrl/api/v2/ops/admin/inventory" -Headers @{Authorization="Bearer $token"} | Out-Null }
            "merchants" { Invoke-RestMethod -Uri "$BaseUrl/api/v2/ops/admin/merchants" -Headers @{Authorization="Bearer $token"} | Out-Null }
            "rbac" { Invoke-RestMethod -Uri "$BaseUrl/api/v2/ops/admin/rbac/roles" -Headers @{Authorization="Bearer $token"} | Out-Null }
            "audit" { Invoke-RestMethod -Uri "$BaseUrl/api/v2/ops/admin/audit-logs?page=0&size=5" -Headers @{Authorization="Bearer $token"} | Out-Null }
            "recent" { Invoke-RestMethod -Uri "$BaseUrl/api/v2/ops/admin/audit-logs/recent?size=5" -Headers @{Authorization="Bearer $token"} | Out-Null }
            "reports" { Invoke-RestMethod -Uri "$BaseUrl/api/v2/ops/admin/reports/devices" -Headers @{Authorization="Bearer $token"} | Out-Null }
        }
        Write-Host "[PASS] TC-ADM-UI page data: $page"
        $passed++
    } catch {
        Write-Host "[FAIL] TC-ADM-UI page data: $page - $($_.Exception.Message)" -ForegroundColor Red
        $failed++
    }
}

Write-Host "PASS: $passed FAIL: $failed"
if ($failed -gt 0) { exit 1 }
