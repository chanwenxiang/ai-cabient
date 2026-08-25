param(
  [string]$RepoUrl = $env:GITHUB_REPO_URL,
  [string]$RunnerToken = $env:GITHUB_RUNNER_TOKEN,
  [string]$RunnerName = "ai-cabinet-local"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$Infra = Join-Path $Root "infra"
$EnvFile = Join-Path $Infra ".env"

if (-not $RepoUrl) {
  Write-Error "Set GITHUB_REPO_URL, e.g. https://github.com/your-org/ai-cabinet"
}
if (-not $RunnerToken) {
  Write-Error "Set GITHUB_RUNNER_TOKEN from GitHub repo Settings → Actions → Runners → New self-hosted runner"
}

if (-not (Test-Path $EnvFile)) {
  Copy-Item (Join-Path $Infra ".env.example") $EnvFile
}

$lines = Get-Content $EnvFile -ErrorAction SilentlyContinue
$map = @{}
foreach ($line in $lines) {
  if ($line -match '^\s*([^#=]+)=(.*)$') {
    $map[$Matches[1].Trim()] = $Matches[2].Trim()
  }
}
$map["GITHUB_REPO_URL"] = $RepoUrl
$map["GITHUB_RUNNER_TOKEN"] = $RunnerToken
$map["GITHUB_RUNNER_NAME"] = $RunnerName

$map.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" } | Set-Content $EnvFile -Encoding UTF8

Write-Host "Updated infra/.env with runner settings. Starting github-runner..." -ForegroundColor Cyan
Set-Location $Infra
& docker compose -f docker-compose.full.yml -f docker-compose.devops.yml --profile devops up -d github-runner
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Runner container started. Verify in GitHub repo → Settings → Actions → Runners." -ForegroundColor Green
