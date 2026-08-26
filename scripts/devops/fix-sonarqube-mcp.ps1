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

$utf8 = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText($McpPath, ($mcp | ConvertTo-Json -Depth 12), $utf8)

# 同步用户 settings 里的连接（供 SonarLint 提示 Migrate 到密钥库）
$appData = if ($env:APPDATA) { $env:APPDATA } else { Join-Path $userHome "AppData\Roaming" }
$settingsPath = if ($appData) { Join-Path $appData "Cursor\User\settings.json" } else { $null }
if ($settingsPath -and (Test-Path -LiteralPath $settingsPath)) {
  $settings = Get-Content $settingsPath -Raw -Encoding UTF8
  if ($settings[0] -eq [char]0xFEFF) { $settings = $settings.Substring(1) }
  $obj = $settings | ConvertFrom-Json
  $conn = @(
    [pscustomobject]@{
      connectionId = "ai-cabinet-local"
      serverUrl = "http://localhost:19002"
      token = $token
    }
  )
  $obj | Add-Member -NotePropertyName "sonarlint.connectedMode.connections.sonarqube" -NotePropertyValue $conn -Force
  [System.IO.File]::WriteAllText($settingsPath, ($obj | ConvertTo-Json -Depth 12), $utf8)
}

Write-Host "OK: wrote SonarQube MCP token (len=$($token.Length)) -> $McpPath" -ForegroundColor Green
Write-Host "Next: Ctrl+Shift+P -> Developer: Reload Window" -ForegroundColor Cyan
Write-Host "If SonarLint asks to Migrate tokens to secure storage -> click Migrate" -ForegroundColor Cyan
Write-Host "Do NOT click SonarLint sidebar 'Configure SonarQube MCP' again (it overwrites token with null)." -ForegroundColor Yellow
