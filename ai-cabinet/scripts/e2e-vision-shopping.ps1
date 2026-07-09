# Shopping E2E with real media in MinIO (Step 4)
# Requires trade + device + vision with YOLO loaded.
# bus.jpg -> usually DISPUTED (no bottle SKU); bottle.jpg -> COMPLETED if MOCK_ENABLED=false.

param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$VisionUrl = "http://localhost:8082",
    [string]$Phone = "13800138000",
    [string]$Code = "123456",
    [string]$DeviceId = "CAB-001",
    [string]$InternalApiKey = "dev-internal-key-change-me",
    [string]$SampleImage = "",
    [string]$MinioEndpoint = "http://localhost:9000",
    [string]$MinioBucket = "cabinet-videos"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Python = Join-Path $Root "vision-service\.venv\Scripts\python.exe"

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

function Resolve-SampleImage {
    if ($SampleImage -and (Test-Path $SampleImage)) { return (Resolve-Path $SampleImage).Path }
    foreach ($p in @(
        (Join-Path $Root "testdata\bottle.jpg"),
        (Join-Path $Root "testdata\bus.jpg")
    )) {
        if (Test-Path $p) { return $p }
    }
    throw "No sample image. Add testdata/bottle.jpg or pass -SampleImage"
}

function Upload-MinioObject {
    param([string]$LocalPath, [string]$ObjectKey)
    $code = @"
from minio import Minio
from pathlib import Path
import sys
endpoint = sys.argv[1]
bucket = sys.argv[2]
key = sys.argv[3]
local = Path(sys.argv[4])
host = endpoint.replace('http://','').replace('https://','')
secure = endpoint.startswith('https')
client = Minio(host, access_key='minioadmin', secret_key='minioadmin', secure=secure)
if not client.bucket_exists(bucket):
    client.make_bucket(bucket)
ext = local.suffix.lower()
ctype = {
    '.jpg':'image/jpeg','.jpeg':'image/jpeg','.png':'image/png',
    '.mp4':'video/mp4','.webm':'video/webm','.mov':'video/quicktime'
}.get(ext,'application/octet-stream')
client.fput_object(bucket, key, str(local), content_type=ctype)
print(f'minio://{bucket}/{key}')
"@
    & $Python -c $code $MinioEndpoint $MinioBucket $ObjectKey $LocalPath
    if ($LASTEXITCODE -ne 0) { throw "MinIO upload failed" }
}

function Convert-ImageToMp4 {
    param([string]$ImagePath, [string]$OutputPath)
    $imgDir = Split-Path -Parent $ImagePath
    $imgName = Split-Path -Leaf $ImagePath
    $outDir = Split-Path -Parent $OutputPath
    $outName = Split-Path -Leaf $OutputPath
    docker run --rm `
        -v "${imgDir}:/in:ro" `
        -v "${outDir}:/out" `
        jrottenberg/ffmpeg:4.1-alpine `
        -y -loop 1 -i "/in/$imgName" -c:v libx264 -pix_fmt yuv420p -movflags +faststart -t 3 "/out/$outName" | Out-Null
    if (-not (Test-Path $OutputPath)) { throw "Failed to convert image to mp4: $OutputPath" }
}

Write-Host "==> 1. Login"
Invoke-Api -Method POST -Path "/api/v2/auth/sms-code?phoneNumber=$Phone" | Out-Null
$login = Invoke-Api -Method POST -Path "/api/v2/auth/login" -Body @{
    phoneNumber = $Phone
    code        = $Code
}
$auth = @{ Authorization = "Bearer $($login.token)" }
Write-Host "    userId=$($login.userId)"

Write-Host "==> 2. Create session"
$session = Invoke-Api -Method POST -Path "/api/v2/sessions" -Headers $auth -Body @{
    deviceId = $DeviceId
}
$sessionId = $session.sessionId
Write-Host "    sessionId=$sessionId state=$($session.state)"

$sample = Resolve-SampleImage
Write-Host "==> 3. Convert sample to MP4 and upload to MinIO ($sample)"
$mp4Path = Join-Path $env:TEMP "aicabinet-$sessionId.mp4"
Convert-ImageToMp4 -ImagePath $sample -OutputPath $mp4Path
$objectKey = "sim/$sessionId.mp4"
$videoUri = (Upload-MinioObject -LocalPath $mp4Path -ObjectKey $objectKey).Trim()
Remove-Item $mp4Path -Force -ErrorAction SilentlyContinue
Write-Host "    videoUri=$videoUri"

Write-Host "==> 4. Simulate door open"
Invoke-Internal -Path "/internal/v1/sessions/door-event" -Body @{
    sessionId = $sessionId
    deviceId  = $DeviceId
    doorState = "OPEN"
    timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
}

Write-Host "==> 5. Simulate door close + MinIO video"
Invoke-Internal -Path "/internal/v1/sessions/door-event" -Body @{
    sessionId    = $sessionId
    deviceId     = $DeviceId
    doorState    = "CLOSED"
    timestamp    = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    videoUri     = $videoUri
    uploadStatus = "UPLOADED"
}

Write-Host "==> 6. Wait for terminal state"
$finalState = $null
for ($i = 0; $i -lt 25; $i++) {
    Start-Sleep -Seconds 1
    $s = Invoke-Api -Method GET -Path "/api/v2/sessions/$sessionId" -Headers $auth
    $finalState = $s.state
    if ($finalState -in @("COMPLETED", "DISPUTED", "FAILED")) { break }
}
Write-Host "    final state=$finalState"

if ($finalState -eq "COMPLETED") {
    $order = Invoke-Api -Method GET -Path "/api/v2/sessions/$sessionId/order" -Headers $auth
    Write-Host "    orderId=$($order.orderId) total=$($order.totalAmountCents) status=$($order.status)"
    if ($order.status -ne "PAID") { throw "Expected order PAID, got $($order.status)" }
    Write-Host ""
    Write-Host "OK vision E2E passed (COMPLETED + order)"
    exit 0
}

if ($finalState -eq "DISPUTED") {
    Write-Host ""
    Write-Host "OK vision E2E passed (DISPUTED — YOLO ran but no mapped SKU or low confidence)"
    Write-Host "Use a bottle/cup photo (testdata/bottle.jpg) and MOCK_ENABLED=false for auto-settle."
    exit 0
}

throw "Unexpected session state=$finalState (expected COMPLETED or DISPUTED)"
