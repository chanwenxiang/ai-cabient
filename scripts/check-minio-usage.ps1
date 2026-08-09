# MinIO 容量巡检：按用途前缀统计对象存储占用，超阈值时给出告警提示。
# 用法：
#   .\scripts\check-minio-usage.ps1                        # 默认阈值：Warn 50GB / Fail 200GB
#   .\scripts\check-minio-usage.ps1 -WarnGB 20 -FailGB 80
param(
    [string]$MinioContainer = "",
    [string]$Bucket = "",
    [double]$WarnGB = 50,
    [double]$FailGB = 200,
    [string]$McImage = "minio/mc:latest"
)

$ErrorActionPreference = "Stop"

if (-not $MinioContainer) {
    $MinioContainer = docker ps --filter "label=com.docker.compose.service=minio" --format "{{.Names}}" 2>$null |
        Select-Object -First 1
}
if (-not $MinioContainer) { $MinioContainer = "ai-cabinet-minio-1" }

$network = (docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' $MinioContainer 2>$null).Trim()
if (-not $network) { throw "无法识别 MinIO 容器网络：$MinioContainer" }

$user = $env:MINIO_ROOT_USER
if (-not $user) { $user = "minioadmin" }
$pass = $env:MINIO_ROOT_PASSWORD
if (-not $pass) { $pass = "minioadmin" }
if (-not $Bucket) {
    $Bucket = $env:MINIO_BUCKET
    if (-not $Bucket) { $Bucket = "cabinet-videos" }
}

function ConvertTo-GiB {
    param([string]$Text)
    if (-not $Text) { return 0.0 }
    $m = [regex]::Match($Text, '([\d.]+)\s*(B|KiB|MiB|GiB|TiB|kB|MB|GB|TB)')
    if (-not $m.Success) { return 0.0 }
    $value = [double]$m.Groups[1].Value
    switch ($m.Groups[2].Value) {
        "B"  { return $value / 1GB }
        "kB" { return $value * 1KB / 1GB }
        "MB" { return $value * 1MB / 1GB }
        "GB" { return $value }
        "TB" { return $value * 1TB / 1GB }
        "KiB"{ return $value * 1KB / 1GB }
        "MiB"{ return $value * 1MB / 1GB }
        "GiB"{ return $value }
        "TiB"{ return $value * 1TB / 1GB }
        default { return 0.0 }
    }
}

$prefixes = @("videos", "sim", "archive", "sku-images", "dispute-evidence", "replenishment-evidence")
$script = "mc alias set local http://minio:9000 '$user' '$pass' >/dev/null 2>&1; " +
    "for p in $($prefixes -join ' '); do printf '%s ' `$p; mc du --recursive local/$Bucket/`$p 2>/dev/null | tail -1; done; " +
    "printf 'TOTAL '; mc du --recursive local/$Bucket 2>/dev/null | tail -1"

$raw = docker run --rm --network $network --entrypoint sh $McImage -c $script
$rows = @()
$totalGiB = 0.0
foreach ($line in $raw) {
    if ($line -notmatch '^\s*(videos|sim|archive|sku-images|dispute-evidence|replenishment-evidence|TOTAL)\s') { continue }
    $parts = $line.Trim() -split '\s+', 2
    $name = $parts[0]
    $sizeText = if ($parts.Count -gt 1) { $parts[1] } else { "" }
    $giB = ConvertTo-GiB $sizeText
    if ($name -eq "TOTAL") { $totalGiB = $giB } else { $rows += [pscustomobject]@{ Prefix = $name; SizeGiB = [math]::Round($giB, 3) } }
}

Write-Host "==> MinIO 容量巡检（bucket=$Bucket, network=$network）"
$rows | Sort-Object SizeGiB -Descending | Format-Table Prefix, SizeGiB -AutoSize
Write-Host ("总占用: {0:N3} GB" -f $totalGiB)
if ($totalGiB -gt $FailGB) {
    Write-Host "[FAIL] 容量超过 $FailGB GB，请检查 ILM 生命周期与孤儿文件" -ForegroundColor Red
    exit 2
}
if ($totalGiB -gt $WarnGB) {
    Write-Host "[WARN] 容量超过 $WarnGB GB，建议关注录像保留策略" -ForegroundColor Yellow
    exit 1
}
Write-Host "OK：容量在阈值内"
