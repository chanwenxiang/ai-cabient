param(
  [switch]$SkipAdminUi
)

$ErrorActionPreference = "Stop"
$Root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Set-Location $Root

$adminSkip = if ($SkipAdminUi) { "-Pskip-admin-ui" } else { "" }

Write-Host "==> Maven verify (trade-service)..." -ForegroundColor Cyan
$mvnArgs = @(
  "verify", "-DskipITs", "-pl", "services/trade-service", "-am",
  "-Dspring.datasource.url=jdbc:postgresql://localhost:15433/aicabinet",
  "-Dspring.datasource.username=aicabinet",
  "-Dspring.datasource.password=aicabinet"
)
if ($adminSkip) { $mvnArgs += $adminSkip.Split(" ") }
& mvn @mvnArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "==> device-service compile..." -ForegroundColor Cyan
& mvn compile -pl services/device-service -am
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "==> vision-service pytest..." -ForegroundColor Cyan
python -m pip install --upgrade pip --quiet
pip install pytest --quiet
$env:PYTHONPATH = "vision-service"
& python -m pytest -q vision-service/tests/test_delta_recognizer.py
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Tests passed." -ForegroundColor Green
