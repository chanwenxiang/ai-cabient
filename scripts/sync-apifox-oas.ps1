# Export OpenAPI from local trade-service; optionally import into Apifox.
# Usage:
#   .\scripts\sync-apifox-oas.ps1
#   $env:APIFOX_ACCESS_TOKEN = '...'; $env:APIFOX_PROJECT_ID = '8780097'; .\scripts\sync-apifox-oas.ps1
param(
    [string]$OpenApiUrl = "http://127.0.0.1:18080/v3/api-docs",
    [string]$ProjectId = "",
    [string]$AccessToken = "",
    [string]$OutFile = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $repoRoot ".tmp"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
if (-not $OutFile) {
    $OutFile = Join-Path $outDir "live-openapi.json"
}

Write-Host "==> Fetch $OpenApiUrl"
try {
    $raw = Invoke-WebRequest -Uri $OpenApiUrl -TimeoutSec 60 -UseBasicParsing
} catch {
    Write-Host 'Cannot fetch OpenAPI. Is trade-service running?'
    Write-Host 'IDEA mode: -OpenApiUrl http://127.0.0.1:8080/v3/api-docs'
    throw
}

$spec = $raw.Content | ConvertFrom-Json
$pathCount = @($spec.paths.PSObject.Properties).Count
[System.IO.File]::WriteAllText($OutFile, $raw.Content, [System.Text.UTF8Encoding]::new($false))
Write-Host "Saved $OutFile ($pathCount paths, title: $($spec.info.title))"

if ($pathCount -eq 0) {
    Write-Warning 'paths is empty; use trade-service :18080 or :8080, not gateway /v3/api-docs'
}

$ProjectId = if ($ProjectId) { $ProjectId } elseif ($env:APIFOX_PROJECT_ID) { $env:APIFOX_PROJECT_ID } else { "8780097" }
$AccessToken = if ($AccessToken) { $AccessToken } else { $env:APIFOX_ACCESS_TOKEN }

if (-not $AccessToken) {
    Write-Host ""
    Write-Host "Apifox import skipped: set APIFOX_ACCESS_TOKEN (optional APIFOX_PROJECT_ID, default $ProjectId)."
    Write-Host "Manual: Apifox -> Import -> OpenAPI -> $OutFile"
    Write-Host "Cursor MCP: user-apifox read_project_oas / refresh_project_oas (import in Apifox first)"
    exit 0
}

$importBody = @{
    input = $raw.Content
    options = @{
        endpointOverwriteBehavior = "OVERWRITE_EXISTING"
        schemaOverwriteBehavior = "OVERWRITE_EXISTING"
    }
} | ConvertTo-Json -Depth 6 -Compress

$headers = @{
    Authorization = "Bearer $AccessToken"
    "X-Apifox-Api-Version" = "2024-03-28"
    "Content-Type" = "application/json; charset=utf-8"
}

$importUrl = "https://api.apifox.com/v1/projects/$ProjectId/import-openapi?locale=zh-CN"
Write-Host "==> Import to Apifox project $ProjectId"
try {
    $resp = Invoke-RestMethod -Method POST -Uri $importUrl -Headers $headers -Body $importBody -TimeoutSec 180
    $resp | ConvertTo-Json -Depth 8
    Write-Host "Apifox import OK."
} catch {
    $msg = $_.Exception.Message
    Write-Host "Apifox import failed: $msg"
    if ($_.ErrorDetails.Message) { Write-Host $_.ErrorDetails.Message }
    exit 1
}
