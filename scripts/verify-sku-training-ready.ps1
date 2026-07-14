# SKU 训练就绪检查（阶段 C — 需标注数据集后才能训练）

param(
    [string]$DatasetRoot = (Join-Path (Split-Path $PSScriptRoot -Parent) "vision-service\datasets\cabinet-skus-v1")
)

$ErrorActionPreference = "Stop"
$checks = @()

function Add-Check([string]$Name, [bool]$Pass, [string]$Detail) {
    $script:checks += [pscustomobject]@{ Name = $Name; Pass = $Pass; Detail = $Detail }
    $mark = if ($Pass) { "PASS" } else { "FAIL" }
    Write-Host "[$mark] $Name — $Detail"
}

Write-Host "========== SKU Training Readiness =========="

$dataYaml = Join-Path (Split-Path $PSScriptRoot -Parent) "vision-service\training\data.yaml"
Add-Check "training.data_yaml" (Test-Path $dataYaml) $dataYaml

$trainScript = Join-Path (Split-Path $PSScriptRoot -Parent) "vision-service\training\train_sku_yolo.py"
Add-Check "training.script" (Test-Path $trainScript) $trainScript

$imagesTrain = Join-Path $DatasetRoot "images\train"
$labelsTrain = Join-Path $DatasetRoot "labels\train"
$imageCount = 0
$labelCount = 0
if (Test-Path $imagesTrain) {
    $imageCount = @(Get-ChildItem $imagesTrain -Include *.jpg,*.jpeg,*.png -Recurse -ErrorAction SilentlyContinue).Count
}
if (Test-Path $labelsTrain) {
    $labelCount = @(Get-ChildItem $labelsTrain -Include *.txt -Recurse -ErrorAction SilentlyContinue).Count
}
Add-Check "dataset.images_train" ($imageCount -ge 50) "count=$imageCount (need >=50 for meaningful train)"
Add-Check "dataset.labels_train" ($labelCount -ge 50) "count=$labelCount"

$failed = @($checks | Where-Object { -not $_.Pass })
Write-Host ""
if ($failed.Count -eq 0) {
    Write-Host "SKU training readiness OK. Run: python vision-service/training/train_sku_yolo.py --version cabinet-skus-v1.0.0"
    exit 0
}
Write-Host "SKU training not ready (expected until dataset is labeled). See vision-service/datasets/README.md"
$failed | ForEach-Object { Write-Host "  - $($_.Name): $($_.Detail)" }
exit 1
