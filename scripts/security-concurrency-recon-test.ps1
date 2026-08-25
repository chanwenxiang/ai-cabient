# Security / concurrency / reconciliation / dirty-data inventory tests
$ErrorActionPreference = 'Continue'
$Base = 'http://localhost'
$Report = New-Object System.Collections.Generic.List[string]
function Log([string]$s) { [void]$Report.Add($s); Write-Host $s }

function Get-Captcha {
  $c = Invoke-RestMethod -Uri "$Base/api/v2/auth/captcha" -Method GET
  $id = $c.data.captchaId
  $code = docker exec ai-cabinet-redis-1 redis-cli GET "aicabinet:captcha:$id"
  if (-not $code) { throw "captcha redis miss: $id" }
  return @{ id = $id; code = "$code".Trim() }
}

function Admin-Login([string]$phone, [string]$password = '123456') {
  $cap = Get-Captcha
  $body = @{
    phoneNumber = $phone
    password    = $password
    captchaId   = $cap.id
    captchaCode = $cap.code
  } | ConvertTo-Json
  $r = Invoke-RestMethod -Uri "$Base/api/v2/auth/admin-password-login" -Method POST `
    -ContentType 'application/json' -Body $body
  return $r.data
}

function ApiCall([string]$method, [string]$path, [string]$token, $body = $null) {
  $headers = @{ Authorization = "Bearer $token" }
  $params = @{
    Uri             = "$Base$path"
    Method          = $method
    Headers         = $headers
    UseBasicParsing = $true
  }
  if ($null -ne $body) {
    $params.ContentType = 'application/json'
    $params.Body = ($body | ConvertTo-Json -Depth 6 -Compress)
  }
  try {
    $resp = Invoke-WebRequest @params
    $json = $null
    try { $json = $resp.Content | ConvertFrom-Json } catch {}
    return @{ status = [int]$resp.StatusCode; code = $json.code; msg = $json.message; data = $json.data; raw = $resp.Content }
  } catch {
    $ex = $_.Exception
    $status = 0
    $raw = ''
    if ($ex.Response) {
      $status = [int]$ex.Response.StatusCode
      try {
        $stream = $ex.Response.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream)
        $raw = $reader.ReadToEnd()
      } catch {}
    }
    $json = $null
    try { $json = $raw | ConvertFrom-Json } catch {}
    return @{ status = $status; code = $json.code; msg = $json.message; data = $json.data; raw = $raw }
  }
}

function Expect-Status([string]$name, $res, [int[]]$okStatuses) {
  if ($okStatuses -contains $res.status) {
    Log "PASS  $name -> HTTP $($res.status)"
  } else {
    Log "FAIL  $name -> HTTP $($res.status) code=$($res.code) msg=$($res.msg)"
  }
}

function Get-TotpCode([string]$secret) {
  $alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  $s = $secret.ToUpper().Replace('=', '').Replace(' ', '')
  $bits = 0; $value = 0
  $bytes = New-Object System.Collections.Generic.List[byte]
  foreach ($ch in $s.ToCharArray()) {
    $idx = $alphabet.IndexOf($ch)
    if ($idx -lt 0) { throw "bad base32" }
    $value = ($value -shl 5) -bor $idx
    $bits += 5
    if ($bits -ge 8) {
      $bytes.Add([byte](($value -shr ($bits - 8)) -band 0xFF))
      $bits -= 8
    }
  }
  $key = $bytes.ToArray()
  $counter = [int64]([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() / 30)
  $msg = [BitConverter]::GetBytes([System.Net.IPAddress]::HostToNetworkOrder($counter))
  $hmac = New-Object System.Security.Cryptography.HMACSHA1 (,$key)
  $hash = $hmac.ComputeHash($msg)
  $offset = $hash[$hash.Length - 1] -band 0x0F
  $bin = (($hash[$offset] -band 0x7F) -shl 24) -bor (($hash[$offset + 1] -band 0xFF) -shl 16) `
    -bor (($hash[$offset + 2] -band 0xFF) -shl 8) -bor ($hash[$offset + 3] -band 0xFF)
  $otp = $bin % 1000000
  return ('{0:D6}' -f $otp)
}

Log '========== 1) role login =========='
$roles = [ordered]@{
  admin   = '13900000001'
  finance = '13900000002'
  ops     = '13900000003'
  replen  = '13900000004'
  viewer  = '13900000005'
}
$tokens = @{}
foreach ($k in $roles.Keys) {
  try {
    $data = Admin-Login $roles[$k]
    $tokens[$k] = $data.token
    if ($data.twoFactorRequired) {
      Log "WARN  $k requires 2FA challenge"
    } else {
      Log "PASS  login $k ($($roles[$k]))"
    }
  } catch {
    Log "FAIL  login $k : $($_.Exception.Message)"
  }
}

Log ''
Log '========== 2) RBAC matrix =========='
Expect-Status 'viewer GET /orders' (ApiCall GET '/api/v2/ops/admin/orders?page=1&size=5' $tokens.viewer) @(200)
Expect-Status 'viewer POST /reconciliation/run' (ApiCall POST '/api/v2/ops/admin/reconciliation/run?date=2026-08-05&channel=WECHAT' $tokens.viewer $null) @(403)
Expect-Status 'viewer GET /reconciliation' (ApiCall GET '/api/v2/ops/admin/reconciliation' $tokens.viewer) @(403)
Expect-Status 'viewer GET /rbac/me/nav' (ApiCall GET '/api/v2/ops/admin/rbac/me/nav' $tokens.viewer) @(200)
Expect-Status 'viewer POST /repair-tickets' (ApiCall POST '/api/v2/ops/admin/repair-tickets' $tokens.viewer @{ deviceId = 'CAB-001'; title = 'rbac-probe'; faultType = 'OTHER'; priority = 'LOW'; remark = 'no-op' }) @(403)

Expect-Status 'finance GET /reconciliation' (ApiCall GET '/api/v2/ops/admin/reconciliation' $tokens.finance) @(200)
Expect-Status 'finance GET /orders' (ApiCall GET '/api/v2/ops/admin/orders?page=1&size=5' $tokens.finance) @(403)
Expect-Status 'finance GET /rbac/me/nav' (ApiCall GET '/api/v2/ops/admin/rbac/me/nav' $tokens.finance) @(200)

Expect-Status 'ops GET /orders' (ApiCall GET '/api/v2/ops/admin/orders?page=1&size=5' $tokens.ops) @(200)
Expect-Status 'ops GET /rbac/me/nav' (ApiCall GET '/api/v2/ops/admin/rbac/me/nav' $tokens.ops) @(200)
Expect-Status 'ops POST /reconciliation/run' (ApiCall POST '/api/v2/ops/admin/reconciliation/run?date=2026-08-05&channel=WECHAT' $tokens.ops $null) @(403)

Expect-Status 'replen GET /reconciliation' (ApiCall GET '/api/v2/ops/admin/reconciliation' $tokens.replen) @(403)
Expect-Status 'replen GET /orders' (ApiCall GET '/api/v2/ops/admin/orders?page=1&size=5' $tokens.replen) @(403)

Expect-Status 'admin GET /reconciliation' (ApiCall GET '/api/v2/ops/admin/reconciliation' $tokens.admin) @(200)
Expect-Status 'admin GET /consistency/failures' (ApiCall GET '/api/v2/ops/admin/consistency/failures' $tokens.admin) @(200)

try {
  $cLogin = Invoke-RestMethod -Uri "$Base/api/v2/auth/login" -Method POST -ContentType 'application/json' `
    -Body (@{ phoneNumber = '13800138000'; code = '123456' } | ConvertTo-Json)
  $ct = $cLogin.data.token
  Expect-Status 'consumer to ops /orders' (ApiCall GET '/api/v2/ops/admin/orders?page=1&size=1' $ct) @(401, 403)
} catch {
  Log "WARN  consumer login: $($_.Exception.Message)"
}

Log ''
Log '========== 3) 2FA (viewer, disable after) =========='
$st = ApiCall GET '/api/v2/ops/admin/rbac/me/two-factor/status' $tokens.viewer
Log "INFO  viewer 2FA enabled=$($st.data.enabled) HTTP=$($st.status)"
try {
  if (-not $st.data.enabled) {
    $en = ApiCall GET '/api/v2/ops/admin/rbac/me/two-factor/enroll' $tokens.viewer
    if ($en.status -eq 200 -and $en.data.secret) {
      $secret = [string]$en.data.secret
      $code = Get-TotpCode $secret
      $cf = ApiCall POST '/api/v2/ops/admin/rbac/me/two-factor/confirm' $tokens.viewer @{ code = $code }
      Expect-Status 'viewer 2FA confirm' $cf @(200)

      $login2 = Admin-Login '13900000005'
      Log "INFO  after-enable login keys: twoFactorRequired=$($login2.twoFactorRequired) tokenLen=$($login2.token.Length)"
      if ($login2.twoFactorRequired) {
        Log 'PASS  2FA login returns challenge flag'
        $chal = [string]$login2.token
        $biz = ApiCall GET '/api/v2/ops/admin/orders?page=1&size=1' $chal
        Expect-Status 'challenge token blocked from biz API' $biz @(401, 403)

        try {
          Invoke-RestMethod -Uri "$Base/api/v2/auth/admin-2fa/verify" -Method POST -ContentType 'application/json' `
            -Body (@{ challengeToken = $chal; code = '000000' } | ConvertTo-Json) | Out-Null
          Log 'FAIL  wrong TOTP should reject'
        } catch {
          Log 'PASS  wrong TOTP rejected'
        }

        $okCode = Get-TotpCode $secret
        $ver = Invoke-RestMethod -Uri "$Base/api/v2/auth/admin-2fa/verify" -Method POST -ContentType 'application/json' `
          -Body (@{ challengeToken = $chal; code = $okCode } | ConvertTo-Json)
        if ($ver.data.token -and -not $ver.data.twoFactorRequired) {
          Log 'PASS  2FA verify issues session token'
          $tokens.viewer = $ver.data.token
        } else {
          Log "FAIL  2FA verify unexpected: $($ver | ConvertTo-Json -Compress)"
        }
      } else {
        Log "FAIL  expected twoFactorRequired after enable; raw=$($login2 | ConvertTo-Json -Compress)"
      }

      $disCode = Get-TotpCode $secret
      $dis = ApiCall POST '/api/v2/ops/admin/rbac/me/two-factor/disable' $tokens.viewer @{ code = $disCode }
      Expect-Status 'viewer 2FA disable' $dis @(200)
    } else {
      Log "FAIL  enroll HTTP=$($en.status) msg=$($en.msg)"
    }
  } else {
    Log 'SKIP  viewer 2FA already enabled; skip enroll/disable'
  }
} catch {
  Log "FAIL  2FA flow: $($_.Exception.Message)"
}

Log ''
Log '========== 4) concurrency idempotency =========='
try {
  $cLogin = Invoke-RestMethod -Uri "$Base/api/v2/auth/login" -Method POST -ContentType 'application/json' `
    -Body (@{ phoneNumber = '13800138000'; code = '123456' } | ConvertTo-Json)
  $ct = $cLogin.data.token
  $idem = "CONC-TEST-$(Get-Date -Format 'yyyyMMddHHmmss')"
  $jobs = 1..5 | ForEach-Object {
    Start-Job -ScriptBlock {
      param($BaseUrl, $Token, $IdemKey)
      $body = @{ deviceId = 'CAB-001'; idempotencyKey = $IdemKey } | ConvertTo-Json
      try {
        $r = Invoke-WebRequest -Uri "$BaseUrl/api/v2/sessions" -Method POST `
          -Headers @{ Authorization = "Bearer $Token" } -ContentType 'application/json' -Body $body -UseBasicParsing
        return "OK|$($r.StatusCode)|$($r.Content)"
      } catch {
        $st = 0
        $raw = ''
        if ($_.Exception.Response) {
          $st = [int]$_.Exception.Response.StatusCode
          try {
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $raw = $reader.ReadToEnd()
          } catch {}
        }
        return "ERR|$st|$raw"
      }
    } -ArgumentList $Base, $ct, $idem
  }
  $results = $jobs | Wait-Job | Receive-Job
  $jobs | Remove-Job
  Log "INFO  idem=$idem"
  foreach ($line in $results) { Log "INFO  $line" }
  $oks = @($results | Where-Object { $_ -like 'OK|200|*' -or $_ -like 'OK|201|*' })
  $sids = New-Object System.Collections.Generic.List[string]
  foreach ($line in $oks) {
    if ($line -match '"sessionId"\s*:\s*"([^"]+)"') {
      [void]$sids.Add($Matches[1])
    }
  }
  $uniq = @($sids | Select-Object -Unique)
  if ($uniq.Count -le 1 -and $oks.Count -ge 1) {
    Log "PASS  concurrent idempotency shared sessionId=$($uniq -join ',')"
  } elseif ($uniq.Count -gt 1) {
    Log "FAIL  concurrent idempotency multiple sessionIds=$($uniq -join ',')"
  } else {
    Log "WARN  no clear success; okCount=$($oks.Count) (device may be busy/locked)"
  }
} catch {
  Log "FAIL  concurrency: $($_.Exception.Message)"
}

Log ''
Log '========== 5) reconciliation + consistency =========='
$recon = ApiCall GET '/api/v2/ops/admin/reconciliation' $tokens.admin
Expect-Status 'admin list reconciliation' $recon @(200)
$arr = @()
if ($recon.data -is [System.Array]) { $arr = @($recon.data) }
elseif ($recon.data.items) { $arr = @($recon.data.items) }
elseif ($recon.data.list) { $arr = @($recon.data.list) }
elseif ($recon.data) { $arr = @($recon.data) }
Log "INFO  reconciliation rows=$($arr.Count)"
if ($arr.Count -gt 0) {
  $sample = $arr[0] | ConvertTo-Json -Compress
  Log "INFO  sample=$sample"
}

$run = ApiCall POST '/api/v2/ops/admin/reconciliation/run?date=2026-08-15&channel=WECHAT' $tokens.admin $null
Log "INFO  recon run HTTP=$($run.status) code=$($run.code) msg=$($run.msg)"
if ($run.status -eq 200 -and $run.code -eq 0) { Log 'PASS  admin reconciliation/run' } else { Log 'FAIL  admin reconciliation/run' }

Expect-Status 'viewer POST consistency/run' (ApiCall POST '/api/v2/ops/admin/consistency/run' $tokens.viewer $null) @(403)
$cons = ApiCall POST '/api/v2/ops/admin/consistency/run' $tokens.admin $null
Log "INFO  consistency run HTTP=$($cons.status) code=$($cons.code) data=$($cons.data | ConvertTo-Json -Compress)"
$fails = ApiCall GET '/api/v2/ops/admin/consistency/failures' $tokens.admin
$failCount = 0
if ($fails.data -is [System.Array]) { $failCount = @($fails.data).Count }
Log "INFO  consistency open fails HTTP=$($fails.status) count=$failCount"

Log ''
Log '========== 6) dirty data inventory (read-only) =========='
$sql = @"
SELECT 'repair_open' AS kind, COUNT(*)::text AS n FROM repair_ticket WHERE status IN ('OPEN','PENDING','IN_PROGRESS')
UNION ALL
SELECT 'coupon_draft', COUNT(*)::text FROM coupon_definition WHERE status = 'DRAFT'
UNION ALL
SELECT 'coupon_test_name', COUNT(*)::text FROM coupon_definition WHERE coupon_name ILIKE '%test%' OR coupon_name ILIKE '%测试%' OR coupon_name ILIKE '%全量%'
UNION ALL
SELECT 'consistency_fail', COUNT(*)::text FROM data_consistency_record WHERE status = 'FAIL' AND fixed_at IS NULL
UNION ALL
SELECT 'coupon_issue_mismatch', COUNT(*)::text FROM coupon_definition c WHERE issued_count <> (SELECT COUNT(*) FROM user_coupon uc WHERE uc.coupon_def_id=c.coupon_def_id)
UNION ALL
SELECT 'orphan_order_no_lines', COUNT(*)::text FROM cabinet_order o WHERE NOT EXISTS (SELECT 1 FROM cabinet_order_line i WHERE i.order_id=o.order_id)
UNION ALL
SELECT 'sessions_stuck_opening', COUNT(*)::text FROM shopping_session WHERE state='OPENING' AND created_at < NOW() - INTERVAL '1 hour'
UNION ALL
SELECT 'recon_unmatched', COUNT(*)::text FROM payment_reconciliation WHERE unmatched_count > 0 OR status <> 'MATCHED';
"@
$pgName = (docker ps --format '{{.Names}}' | Select-String -Pattern 'postgres' | Select-Object -First 1)
if ($pgName) {
  $name = "$pgName".Trim()
  $out = docker exec $name psql -U aicabinet -d aicabinet -t -A -F '|' -c $sql 2>&1
  foreach ($line in $out) { Log "DATA  $line" }
} else {
  Log 'WARN  postgres container not found'
}

Log ''
Log '========== DONE =========='
$pass = @($Report | Where-Object { $_ -like 'PASS *' }).Count
$fail = @($Report | Where-Object { $_ -like 'FAIL *' }).Count
$warn = @($Report | Where-Object { $_ -like 'WARN *' }).Count
Log "SUMMARY pass=$pass fail=$fail warn=$warn"
$outPath = Join-Path $env:TEMP 'aicabinet-security-recon-test.txt'
$Report | Set-Content -Path $outPath -Encoding UTF8
Log "WROTE $outPath"
