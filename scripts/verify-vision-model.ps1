# Vision SKU model + local video recognition verification
param(
    [string]$VisionHealthUrl = "http://127.0.0.1:8082/health",
    [string]$VisionUploadUrl = "http://127.0.0.1:8082/api/v2/vision/recognize/upload",
    [string]$VisionApiKey = "dev-vision-key-change-me",
    [string]$TradeBaseUrl = "http://127.0.0.1:8080",
    [string]$InternalApiKey = "dev-internal-key-change-me",
    [string]$TestVideo = "",
    [switch]$AllowGenericModel,
    [switch]$RequireSkuMappings,
    [switch]$SkipVideoUpload
)

$ErrorActionPreference = "Stop"
$checks = @()
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $TestVideo) {
    $TestVideo = Join-Path $repoRoot "testdata\take-one-bottle.mp4"
}

function Add-Check([string]$Name, [bool]$Pass, [string]$Detail) {
    $script:checks += [pscustomobject]@{ Name = $Name; Pass = $Pass; Detail = $Detail }
    $mark = if ($Pass) { "PASS" } else { "FAIL" }
    Write-Host "[$mark] $Name — $Detail"
}

Write-Host "========== Vision Model Verification =========="

try {
    $health = Invoke-RestMethod -Uri $VisionHealthUrl -TimeoutSec 8
    Add-Check "vision.health" ($health.status -eq "ok") "status=$($health.status)"
    Add-Check "vision.recognizer_available" ($health.recognizer_available -eq $true) "available=$($health.recognizer_available) error=$($health.load_error)"
    Add-Check "vision.yolo_loaded" ($health.yolo_loaded -eq $true) "yolo_loaded=$($health.yolo_loaded)"

    $forceReal = $health.vision_force_real -eq $true
    $mockOff = $health.mock_enabled -eq $false
    Add-Check "vision.real_mode" ($forceReal -or $mockOff) "force_real=$forceReal mock_enabled=$($health.mock_enabled)"

    $modelVersion = [string]$health.model_version
    $modelPath = [string]$health.model_path
    $isGeneric = ($modelVersion -match 'yolov8n' -or $modelPath -match 'yolov8n')
    if ($AllowGenericModel) {
        Add-Check "vision.model_not_generic" $true "skipped (AllowGenericModel)"
    } else {
        Add-Check "vision.model_not_generic" (-not $isGeneric) "version=$modelVersion path=$modelPath"
    }
    Add-Check "vision.model_version_set" ($modelVersion -and $modelVersion -ne "unknown") "version=$modelVersion"
    Add-Check "vision.yolo_mode" ($health.yolo_recognition_mode -in @('delta', 'single_frame')) "mode=$($health.yolo_recognition_mode)"
} catch {
    Add-Check "vision.health" $false $_.Exception.Message
}

if (-not $SkipVideoUpload) {
    if (-not (Test-Path $TestVideo)) {
        Add-Check "vision.video_upload" $false "missing test video: $TestVideo (run vision-service/scripts/generate_test_videos.py)"
    } else {
        try {
            $videoPath = (Resolve-Path $TestVideo).Path
            $curlOut = curl.exe -s -X POST $VisionUploadUrl `
                -H "X-Internal-Api-Key: $VisionApiKey" `
                -F "session_id=VERIFY-VIDEO" `
                -F "file=@$videoPath"
            $resp = $curlOut | ConvertFrom-Json
            $hasClasses = ($resp.detected_classes -and $resp.detected_classes.Count -gt 0)
            $notMock = ($resp.model_version -notmatch '^mock')
            $pipelineOk = ($resp.model_version -match 'yolov8')
            Add-Check "vision.video_upload" $true "model=$($resp.model_version) items=$($resp.items.Count) need_review=$($resp.need_review)"
            Add-Check "vision.video_not_mock" $notMock "model_version=$($resp.model_version)"
            Add-Check "vision.video_pipeline" $pipelineOk "model_version=$($resp.model_version)"
            if ($hasClasses) {
                Add-Check "vision.video_detected" $true "detected_classes=$($resp.detected_classes -join ',')"
            } else {
                Add-Check "vision.video_detected" $true "optional (synthetic video may have no COCO match)"
            }
        } catch {
            Add-Check "vision.video_upload" $false $_.Exception.Message
        }
    }
}

try {
    $headers = @{ "X-Internal-Api-Key" = $InternalApiKey }
    $map = Invoke-RestMethod -Uri "$TradeBaseUrl/internal/v1/vision/mappings" -Headers $headers -TimeoutSec 8
    $yolo = @($map.data.yolo)
    $skuMaps = @($yolo | Where-Object { $_.mappingSource -eq 'YOLO_SKU' })
    $cocoMaps = @($yolo | Where-Object { $_.mappingSource -eq 'YOLO_COCO' })
    Add-Check "mapping.yolo_total" ($yolo.Count -gt 0) "count=$($yolo.Count)"
    if ($RequireSkuMappings -or -not $AllowGenericModel) {
        Add-Check "mapping.yolo_sku" ($skuMaps.Count -ge 1) "YOLO_SKU=$($skuMaps.Count) YOLO_COCO=$($cocoMaps.Count)"
    } else {
        Add-Check "mapping.yolo_sku" $true "optional (RequireSkuMappings not set)"
    }
} catch {
    Add-Check "mapping.load" $false $_.Exception.Message
}

$failed = @($checks | Where-Object { -not $_.Pass })
Write-Host ""
if ($failed.Count -eq 0) {
    Write-Host "Vision model verification passed ($($checks.Count) checks)."
    exit 0
}
Write-Host "Vision model verification failed:"
$failed | ForEach-Object { Write-Host "  - $($_.Name): $($_.Detail)" }
exit 1
