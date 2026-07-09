# Generate a short test MP4 for E2E shopping video playback (requires Docker).
$ErrorActionPreference = "Stop"

$root = Split-Path $PSScriptRoot -Parent
$outDir = Join-Path $root "testdata"
$out = Join-Path $outDir "sample-shopping.mp4"

if ((Test-Path $out) -and ((Get-Item $out).Length -gt 20000)) {
    Write-Output $out
    exit 0
}

New-Item -ItemType Directory -Force -Path $outDir | Out-Null

Write-Host "Generating sample MP4 via ffmpeg container..."
docker run --rm `
    -v "${outDir}:/out" `
    jrottenberg/ffmpeg:4.1-alpine `
    -y -f lavfi -i "testsrc=duration=3:size=640x360:rate=30" `
    -c:v libx264 -pix_fmt yuv420p -movflags +faststart -t 3 /out/sample-shopping.mp4 | Out-Null

if (-not (Test-Path $out)) {
    throw "Failed to generate $out"
}
Write-Output $out
