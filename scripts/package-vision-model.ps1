# ============================================================
# ⛔ 已退役（2026-10-11）：旧云识别/录像链专用脚本。
#    识别只认将邑端侧；vision-service 与 /internal/v1/sessions/video* 已删除，
#    本脚本运行必失败。见 docs/JIANGYI_ONLY_FINAL_VERDICT_2026-10-11.md §3 批6/收尾B。
# ============================================================
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
