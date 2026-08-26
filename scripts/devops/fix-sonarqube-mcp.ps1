# 修复 SonarLint「Configure MCP」把 Token 写成 null 的问题。
# 用法：.\scripts\devops\fix-sonarqube-mcp.ps1
# 然后 Cursor：Ctrl+Shift+P → Developer: Reload Window
$ErrorActionPreference = "Stop"
$Root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$EnvFile = Join-Path $Root "infra\.env"
$userHome = if ($env:USERPROFILE) { $env:USERPROFILE } elseif ($env:HOME) { $env:HOME } else { "C:\Users\$env:USERNAME" }
$McpPath = Join-Path $userHome ".cursor\mcp.json"

if (-not (Test-Path $EnvFile)) { throw "missing $EnvFile" }
if (-not (Test-Path $McpPath)) { throw "missing $McpPath" }

$token = $null
Get-Content $EnvFile | ForEach-Object {
  if ($_ -match '^\s*SONAR_TOKEN=(.+)$') { $token = $Matches[1].Trim().Trim('"').Trim("'") }
}
if (-not $token -or $token -eq "null") { throw "SONAR_TOKEN missing in infra/.env" }

$mcp = Get-Content $McpPath -Raw -Encoding UTF8 | ConvertFrom-Json
if (-not $mcp.mcpServers) { $mcp | Add-Member -NotePropertyName mcpServers -NotePropertyValue ([pscustomobject]@{}) -Force }

$mcp.mcpServers | Add-Member -NotePropertyName sonarqube -NotePropertyValue ([pscustomobject]@{
  command = "docker"
  args = @("run", "-i", "--rm", "--init", "--pull=always", "-e", "SONARQUBE_TOKEN", "-e", "SONARQUBE_URL", "mcp/sonarqube")
  env = [pscustomobject]@{
    SONARQUBE_TOKEN = $token
    # 容器访问宿主机 Sonar（勿用 localhost）
    SONARQUBE_URL = "http://host.docker.internal:19002"
  }
}) -Force

# SonarLint Connected Mode 勿写入 settings：Cursor 密钥库常失败 → verify token 死循环
if ($mcp.mcpServers.sonarqube.env.PSObject.Properties.Name -contains 'SONARQUBE_IDE_PORT') {
  $mcp.mcpServers.sonarqube.env.PSObject.Properties.Remove('SONARQUBE_IDE_PORT')
}

$utf8 = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText($McpPath, ($mcp | ConvertTo-Json -Depth 12), $utf8)

Write-Host "OK: wrote SonarQube MCP token (len=$($token.Length)) -> $McpPath" -ForegroundColor Green
Write-Host "IDE 波浪线用 SonarLint 本地分析；若仍弹 token 错误请运行:" -ForegroundColor Cyan
Write-Host "  .\scripts\devops\reset-sonarlint-standalone.ps1" -ForegroundColor Cyan
Write-Host "Do NOT click SonarLint sidebar 'Configure SonarQube MCP' (overwrites mcp.json / token)." -ForegroundColor Yellow
