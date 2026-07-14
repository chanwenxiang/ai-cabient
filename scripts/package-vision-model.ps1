# Copy trained SKU model weights into deployment directories
param(
    [string]$Version = "cabinet-skus-v1.0.0",
    [string]$SourceDir = "",
    [switch]$SkipManifest
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$src = if ($SourceDir) { $SourceDir } else { Join-Path $Root "vision-service\models\$Version.pt" }
if (-not (Test-Path $src)) {
    Write-Error "Model not found: $src (train with vision-service/training/train_sku_yolo.py first)"
}

$targets = @(
    (Join-Path $Root "vision-service\models\$Version.pt"),
    (Join-Path $Root "infra\models\production\$Version.pt")
)

foreach ($dest in $targets | Select-Object -Unique) {
    $dir = Split-Path $dest -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    Copy-Item -Force $src $dest
    Write-Host "copied -> $dest"
}

if (-not $SkipManifest) {
    $manifest = Join-Path $Root "vision-service\models\$Version.manifest.json"
    if (Test-Path $manifest) {
        Copy-Item -Force $manifest (Join-Path $Root "infra\models\production\$Version.manifest.json")
        Write-Host "copied manifest"
    }
}

Write-Host "OK package-vision-model $Version"
