# Rename SonarQube main branch to match repo default (dev).
# Usage:
#   $env:SONAR_ADMIN_USER = "admin"
#   $env:SONAR_ADMIN_PASSWORD = "<your sonar admin password>"
#   .\scripts\devops\sonar-set-main-branch.ps1
# Or:
#   .\scripts\devops\sonar-set-main-branch.ps1 -Password "xxx" -NewName "dev"
param(
  [string]$HostUrl = $(if ($env:SONAR_HOST_URL) { $env:SONAR_HOST_URL } else { "http://localhost:19002" }),
  [string]$ProjectKey = "ai-cabinet-dev",
  [string]$NewName = "dev",
  [string]$User = $(if ($env:SONAR_ADMIN_USER) { $env:SONAR_ADMIN_USER } else { "admin" }),
  [string]$Password = $env:SONAR_ADMIN_PASSWORD
)

$ErrorActionPreference = "Stop"
if (-not $Password) {
  Write-Error "Set SONAR_ADMIN_PASSWORD or pass -Password. Project analysis tokens (sqp_*) cannot rename branches."
}

$pair = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${User}:${Password}"))
$headers = @{ Authorization = "Basic $pair" }

Write-Host "Renaming main branch -> $NewName on $ProjectKey ..."
# CE 仅一条「主分支」；API 用 name=新名（非 newName），见 Sonar 社区帖与 Web API 文档
Invoke-RestMethod -Method POST `
  "$HostUrl/api/project_branches/rename?project=$ProjectKey&name=$NewName" `
  -Headers $headers | Out-Null

$branches = Invoke-RestMethod "$HostUrl/api/project_branches/list?project=$ProjectKey" -Headers $headers
$branches.branches | ForEach-Object {
  Write-Host ("  {0} isMain={1} qg={2}" -f $_.name, $_.isMain, $_.status.qualityGateStatus)
}
Write-Host "Done. Open $HostUrl/dashboard?id=$ProjectKey"
