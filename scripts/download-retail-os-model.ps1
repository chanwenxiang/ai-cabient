# 下载 Retail-OS / ShelfVision 76 类 YOLOv8m 权重 → vision-service/models/retail-os-v2.0.0.pt
# 官方 Google Drive（需可访问 drive.google.com，必要时 VPN）:
#   https://drive.google.com/drive/folders/1kBSzd2xSj-QzwDbvjVIPaYZ6VpFTt6lI
# 在文件夹内选择 Experiment 8「YOLOv8m Stratified + Oversample」的 best.pt

param(
    [string]$Dest = (Join-Path (Split-Path $PSScriptRoot -Parent) "vision-service\models\retail-os-v2.0.0.pt"),
    [switch]$UseHfShelfFallback
)

$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
$DownloadDir = Join-Path $Root "vision-service\models\retail-os-download"
$DriveFolderId = "1kBSzd2xSj-QzwDbvjVIPaYZ6VpFTt6lI"

function Install-Gdown {
    python -c "import gdown" 2>$null
    if ($LASTEXITCODE -ne 0) { pip install gdown -q }
}

function Try-GoogleDrive {
    Install-Gdown
    New-Item -ItemType Directory -Force -Path $DownloadDir | Out-Null
    Write-Host "Downloading Retail-OS weights from Google Drive..."
    python -c @"
import gdown
from pathlib import Path
out = Path(r'$DownloadDir')
gdown.download_folder(id='$DriveFolderId', output=str(out), quiet=False)
"@
    $candidates = Get-ChildItem $DownloadDir -Recurse -Filter "best.pt" -ErrorAction SilentlyContinue |
        Sort-Object Length -Descending
    if ($candidates) { return $candidates[0].FullName }
    return $null
}

function Try-HfShelfFallback {
    Write-Host "Google Drive unavailable — trying HF mirror (single-class shelf detector, NOT 76-class)..."
    $env:HF_ENDPOINT = "https://hf-mirror.com"
    python -c @"
import os, shutil
os.environ['HF_ENDPOINT'] = 'https://hf-mirror.com'
from huggingface_hub import hf_hub_download
src = hf_hub_download('foduucom/product-detection-in-shelf-yolov8', 'best.pt', endpoint='https://hf-mirror.com')
shutil.copy2(src, r'$Dest')
print('HF fallback saved', r'$Dest')
"@
}

$srcPath = $null
try {
    $srcPath = Try-GoogleDrive
} catch {
    Write-Warning $_.Exception.Message
}

if ($srcPath) {
    Copy-Item -Force $srcPath $Dest
    Write-Host "OK: Retail-OS weights -> $Dest ($([math]::Round((Get-Item $Dest).Length/1MB,1)) MB)"
    exit 0
}

if ($UseHfShelfFallback) {
    Try-HfShelfFallback
    Write-Warning "Using HF shelf single-class fallback. For true 76-class Retail-OS, download best.pt manually from Google Drive."
    exit 0
}

Write-Host ""
Write-Host "Retail-OS 76-class weights NOT downloaded (Google Drive timeout)."
Write-Host "Manual steps:"
Write-Host "  1. Open: https://drive.google.com/drive/folders/$DriveFolderId"
Write-Host "  2. Download YOLOv8m Stratified+Oversample / best.pt"
Write-Host "  3. Copy to: $Dest"
Write-Host ""
Write-Host "Or run with -UseHfShelfFallback for interim single-class shelf model."
exit 1
