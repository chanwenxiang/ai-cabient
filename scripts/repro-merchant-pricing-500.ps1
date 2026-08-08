# 并发复测：商户财务改价接口在权限矩阵测试中偶现 500（预期 403 / 200）。
# 复现方式：合法商户与无 merchant:pricing:edit 权限的运营账号并发 PATCH 同一 SKU 价格，
# 统计返回状态码分布，观察是否有 500（疑似并发/锁竞争）。
# 用法：.\scripts\repro-merchant-pricing-500.ps1 [-BaseUrl http://localhost:18080] [-Concurrency 30]
param(
    [string]$BaseUrl = "",
    [string]$MerchantPhone = "13800138001",
    [string]$MerchantPassword = "123456",
    [string]$OpsPhone = "13900000003",
    [string]$OpsPassword = "123456",
    [int]$Concurrency = 30
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($BaseUrl)) { $BaseUrl = "http://localhost:18080" }
$BaseUrl = $BaseUrl.TrimEnd('/')

function Get-LoginToken {
    param([string]$Phone, [string]$Password)
    $body = @{ phoneNumber = $Phone; password = $Password } | ConvertTo-Json -Compress
    $login = Invoke-RestMethod -Uri "$BaseUrl/api/v2/auth/merchant-password-login" -Method Post `
        -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec 20
    if (-not $login.data.token) { throw "login failed for $Phone" }
    return $login.data.token
}

$merchantHeaders = @{ Authorization = "Bearer $(Get-LoginToken $MerchantPhone $MerchantPassword)" }
$opsHeaders = @{ Authorization = "Bearer $(Get-LoginToken $OpsPhone $OpsPassword)" }
Write-Host "logins ok: merchant=$MerchantPhone ops=$OpsPhone"

# 取商户定价列表中的第一个 sku/device 作为并发压测目标
$pricing = Invoke-RestMethod -Uri "$BaseUrl/api/v2/merchant/pricing/skus" `
    -Headers $merchantHeaders -Method Get -TimeoutSec 20
if (-not $pricing.data -or $pricing.data.Count -eq 0) {
    throw "merchant $MerchantPhone has no pricing rows; cannot repro"
}
$skuId = $pricing.data[0].skuId
$deviceId = $pricing.data[0].deviceId
Write-Host "target sku=$skuId device=$deviceId concurrency=$Concurrency"

$jobs = @()
for ($i = 0; $i -lt $Concurrency; $i++) {
    $price = 666 + ($i % 3)
    $jobs += Start-Job -ArgumentList $BaseUrl, $merchantHeaders, $opsHeaders, $skuId, $deviceId, $price -ScriptBlock {
        param($b, $mh, $oh, $sku, $dev, $priceCents)
        $body = @{ deviceId = $dev; priceCents = $priceCents } | ConvertTo-Json -Compress
        $res = @{ merchant = 0; ops = 0 }
        try {
            $r = Invoke-WebRequest -Uri "$b/api/v2/merchant/pricing/skus/$sku" -Method PATCH `
                -Headers $mh -ContentType 'application/json; charset=utf-8' -Body $body -UseBasicParsing -TimeoutSec 20
            $res.merchant = [int]$r.StatusCode
        } catch { $res.merchant = [int]$_.Exception.Response.StatusCode }
        try {
            $r2 = Invoke-WebRequest -Uri "$b/api/v2/merchant/pricing/skus/$sku" -Method PATCH `
                -Headers $oh -ContentType 'application/json; charset=utf-8' -Body $body -UseBasicParsing -TimeoutSec 20
            $res.ops = [int]$r2.StatusCode
        } catch { $res.ops = [int]$_.Exception.Response.StatusCode }
        return $res
    }
}

$merchantSum = @{}
$opsSum = @{}
foreach ($j in $jobs) {
    $r = Receive-Job -Job $j -Wait
    Remove-Job -Job $j -Force
    $merchantSum[[string]$r.merchant] = 1 + $merchantSum[[string]$r.merchant]
    $opsSum[[string]$r.ops] = 1 + $opsSum[[string]$r.ops]
}

Write-Host "merchant PATCH status: $($merchantSum | ConvertTo-Json -Compress)"
Write-Host "ops(unauthorized) PATCH status: $($opsSum | ConvertTo-Json -Compress)"
$has500 = $merchantSum['500'] -gt 0 -or $opsSum['500'] -gt 0
if ($has500) {
    Write-Host "REPRODUCED: 500 observed under concurrency"
    exit 1
}
Write-Host "OK: no 500 observed (merchant 200 / unauthorized 403 expected)"
