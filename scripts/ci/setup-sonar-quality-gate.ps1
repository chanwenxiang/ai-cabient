param(
  [string]$SonarHostUrl = $env:SONAR_HOST_URL,
  [string]$SonarToken = $env:SONAR_TOKEN
)

$ErrorActionPreference = "Stop"
$Root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Set-Location $Root

if (-not $SonarHostUrl) { $SonarHostUrl = "http://localhost:19002" }
if (-not $SonarToken) {
  Write-Error "SONAR_TOKEN is required."
}

$headers = @{ Authorization = "Bearer $SonarToken" }
$base = $SonarHostUrl.TrimEnd('/')

function Post-Form([string]$Path, [hashtable]$Form) {
  Invoke-RestMethod -Method Post -Uri ($base + $Path) -Headers $headers -Body $Form -TimeoutSec 30 | Out-Null
}

Write-Host "==> Ensure quality gate 'AI Cabinet'" -ForegroundColor Cyan
$list = Invoke-RestMethod "$base/api/qualitygates/list" -Headers $headers
$exists = $list.qualitygates | Where-Object { $_.name -eq 'AI Cabinet' }
if (-not $exists) {
  Post-Form "/api/qualitygates/create" @{ name = "AI Cabinet" }
}

$show = Invoke-RestMethod "$base/api/qualitygates/show?name=AI%20Cabinet" -Headers $headers
foreach ($c in @($show.conditions)) {
  if ($c.id) { Post-Form "/api/qualitygates/delete_condition" @{ id = $c.id } }
}

$conds = @(
  @{ gateName = "AI Cabinet"; metric = "new_vulnerabilities"; op = "GT"; error = "0" },
  @{ gateName = "AI Cabinet"; metric = "new_blocker_violations"; op = "GT"; error = "0" },
  @{ gateName = "AI Cabinet"; metric = "new_duplicated_lines_density"; op = "GT"; error = "3" },
  # 全仓覆盖率（含无单测的前端行，整体约 10–15%）
  @{ gateName = "AI Cabinet"; metric = "coverage"; op = "LT"; error = "10" }
)
foreach ($c in $conds) { Post-Form "/api/qualitygates/create_condition" $c }

foreach ($project in @("ai-cabinet-dev", "ai-cabinet-main")) {
  try {
    Post-Form "/api/qualitygates/select" @{ projectKey = $project; gateName = "AI Cabinet" }
    Write-Host "bound $project"
  } catch {
    Write-Host "skip bind $project : $($_.Exception.Message)"
  }
}

Write-Host "Quality gate ready: AI Cabinet (vuln=0, blocker=0, dup<=3%, coverage>=10%)" -ForegroundColor Green
