# 训练开门柜 SKU 模型 cabinet-skus-v1.0.0.pt
param(
    [int]$PerClass = 80,
    [int]$Epochs = 80
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Vs = Join-Path $Root "vision-service"

Write-Host "=== collect dataset from catalog ==="
Push-Location $Vs
python scripts/collect_sku_dataset.py --per-class $PerClass --minio
if ($LASTEXITCODE -ne 0) { Pop-Location; exit $LASTEXITCODE }

Write-Host "=== optional pretrain download ==="
python scripts/download_holoselecta_pretrained.py
Pop-Location

Write-Host "=== train YOLO ==="
Push-Location (Join-Path $Vs "training")
python train_sku_yolo.py --data data.yaml --epochs $Epochs --name cabinet-skus-v1
Pop-Location

$Src = Join-Path $Vs "training/runs/detect/cabinet-skus-v1/weights/best.pt"
$Dst = Join-Path $Vs "models/cabinet-skus-v1.0.0.pt"
if (Test-Path $Src) {
    Copy-Item $Src $Dst -Force
    Write-Host "model copied to $Dst"
} else {
    Write-Warning "best.pt not found at $Src — check training logs"
}

Write-Host "=== verify ==="
& (Join-Path $Root "scripts/verify-vision-model.ps1") -ModelPath $Dst
