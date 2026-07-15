# Step 4: Real vision pipeline (YOLO + MinIO + multi-camera fusion)
# Usage:
#   .\scripts\verify-step4.ps1                         # local vision on :8082
#   .\scripts\verify-step4.ps1 -WithE2e                # also run e2e-vision-shopping
#   .\scripts\verify-step4.ps1 -SampleImage C:\temp\bottle.jpg

param(
    [string]$VisionUrl = "http://localhost:8082",
    [string]$TradeUrl = "http://localhost:8080",
    [string]$SampleImage = "",
    [switch]$WithE2e,
    [switch]$SkipInfra
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Python = Join-Path $Root "vision-service\.venv\Scripts\python.exe"
$Step4Script = Join-Path $Root "vision-service\scripts\step4_check.py"

function Test-HttpOk {
    param([string]$Url, [int]$TimeoutSec = 5)
    try {
        $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSec
        return ($r.StatusCode -ge 200 -and $r.StatusCode -lt 400)
    } catch { return $false }
}

Write-Host "==> Step 4: Vision real pipeline verification"
Write-Host ""

if (-not $SkipInfra) {
    Write-Host "==> Checking infra (postgres/minio)..."
    & (Join-Path $Root "scripts\start-infra.ps1")
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} else {
    Write-Host "==> Skipping infra restart (-SkipInfra)"
}

Write-Host ""
Write-Host "==> Checking vision-service..."
if (-not (Test-HttpOk "$VisionUrl/health")) {
    Write-Host "vision-service not reachable at $VisionUrl" -ForegroundColor Red
    Write-Host "Start locally:"
    Write-Host "  cd vision-service"
    Write-Host "  .\.venv\Scripts\pip install -r requirements-ml.txt"
    Write-Host "  `$env:MOCK_ENABLED='false'"
    Write-Host "  .\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8082"
    exit 1
}

$health = Invoke-RestMethod -Uri "$VisionUrl/health" -TimeoutSec 10
Write-Host "    recognizer_available=$($health.recognizer_available) mock_enabled=$($health.mock_enabled)"
if (-not $health.recognizer_available) {
    Write-Host ""
    Write-Host "YOLO not loaded. Install ML deps and restart vision-service:" -ForegroundColor Yellow
    Write-Host "  cd vision-service"
    Write-Host "  .\.venv\Scripts\pip install -r requirements-ml.txt"
    Write-Host "  .\.venv\Scripts\python.exe scripts\setup_yolo.py"
    exit 1
}

if (-not (Test-Path $Python)) {
    Write-Host "Python venv not found: $Python" -ForegroundColor Red
    Write-Host "Run: cd vision-service; python -m venv .venv; .\.venv\Scripts\pip install -r requirements-base.txt -r requirements-ml.txt"
    exit 1
}

if (-not (Test-Path (Join-Path $Root "testdata\bus.jpg"))) {
    Write-Host "==> Preparing testdata/bus.jpg..."
    $src = Join-Path $Root "vision-service\.venv\Lib\site-packages\ultralytics\assets\bus.jpg"
    if (Test-Path $src) {
        New-Item -ItemType Directory -Force -Path (Join-Path $Root "testdata") | Out-Null
        Copy-Item $src (Join-Path $Root "testdata\bus.jpg") -Force
    }
}

Write-Host ""
Write-Host "==> Vision API checks (upload + minio + multi-camera)..."
$pyArgs = @($Step4Script, "--vision-url", $VisionUrl)
if ($SampleImage) { $pyArgs += @("--sample-image", $SampleImage) }
& $Python @pyArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if (-not $WithE2e) {
    Write-Host ""
    Write-Host "Step 4 vision API checks passed."
    Write-Host "Optional: .\scripts\verify-step4.ps1 -WithE2e -SampleImage path\to\bottle.jpg"
    Write-Host "Tip: set MOCK_ENABLED=false on vision-service for strict need_review behavior."
    exit 0
}

if (-not (Test-HttpOk "$TradeUrl/actuator/health")) {
    Write-Host ""
    Write-Host "trade-service not up on $TradeUrl; skip E2E (-WithE2e needs trade/device/vision)" -ForegroundColor Yellow
    exit 1
}

Write-Host ""
Write-Host "==> E2E shopping with real MinIO media..."
$e2eArgs = @{
    BaseUrl     = $TradeUrl
    VisionUrl   = $VisionUrl
}
if ($SampleImage) { $e2eArgs["SampleImage"] = $SampleImage }
& (Join-Path $Root "scripts\e2e-vision-shopping.ps1") @e2eArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Step 4 verification passed (API + E2E)."
exit 0
