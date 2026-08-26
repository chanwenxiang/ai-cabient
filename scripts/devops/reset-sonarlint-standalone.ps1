# 清掉 SonarLint Connected Mode（Cursor 密钥库常失败 → verify token 死循环）
# 保留：本地 automatic analysis（写代码波浪线）+ MCP（AI 查 Sonar，走 fix-sonarqube-mcp.ps1）
# 用法：.\scripts\devops\reset-sonarlint-standalone.ps1
# 然后：完全退出 Cursor → 重新打开（不要只 Reload）
$ErrorActionPreference = "Stop"

$userHome = if ($env:USERPROFILE) { $env:USERPROFILE } else { "C:\Users\$env:USERNAME" }
$appData = if ($env:APPDATA) { $env:APPDATA } else { Join-Path $userHome "AppData\Roaming" }
$settingsPath = Join-Path $appData "Cursor\User\settings.json"
$mcpPath = Join-Path $userHome ".cursor\mcp.json"
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$wsSettings = Join-Path $root ".vscode\settings.json"
$connectedMode = Join-Path $root ".sonarlint\connectedMode.json"

function Read-Json([string]$path) {
  $raw = Get-Content $path -Raw -Encoding UTF8
  if ($raw.Length -gt 0 -and $raw[0] -eq [char]0xFEFF) { $raw = $raw.Substring(1) }
  return $raw | ConvertFrom-Json
}

function Write-Json([string]$path, $obj) {
  $utf8 = New-Object System.Text.UTF8Encoding $false
  [System.IO.File]::WriteAllText($path, ($obj | ConvertTo-Json -Depth 30), $utf8)
}

# 1) User settings: remove ALL SonarLint server connections; keep standalone analysis
if (Test-Path -LiteralPath $settingsPath) {
  $obj = Read-Json $settingsPath
  foreach ($prop in @(
    'sonarlint.connectedMode.connections.sonarqube',
    'sonarlint.connectedMode.connections.sonarcloud',
    'sonarlint.connectedMode.servers'
  )) {
    if ($obj.PSObject.Properties.Name -contains $prop) {
      $obj.PSObject.Properties.Remove($prop)
      Write-Host "removed user $prop"
    }
  }
  $obj | Add-Member -NotePropertyName "sonarlint.automaticAnalysis" -NotePropertyValue $true -Force
  $obj | Add-Member -NotePropertyName "sonarlint.output.showVerboseLogs" -NotePropertyValue $true -Force
  Write-Json $settingsPath $obj
  Write-Host "user settings: standalone analysis ON, connections removed"
}

# 2) Workspace: remove project binding
if (Test-Path -LiteralPath $wsSettings) {
  $ws = Read-Json $wsSettings
  if ($ws.PSObject.Properties.Name -contains 'sonarlint.connectedMode.project') {
    $ws.PSObject.Properties.Remove('sonarlint.connectedMode.project')
    Write-Json $wsSettings $ws
    Write-Host "workspace: removed sonarlint.connectedMode.project"
  }
}

# 3) Repo connectedMode.json → disable (Connected Mode 会强制要 token)
if (Test-Path -LiteralPath $connectedMode) {
  Rename-Item -LiteralPath $connectedMode -NewName "connectedMode.json.disabled" -Force
  Write-Host "renamed .sonarlint/connectedMode.json"
}

# 4) Delete SonarLint secrets from Cursor global storage (broken encrypted tokens)
$globalDb = Join-Path $appData "Cursor\User\globalStorage\state.vscdb"
if (Test-Path -LiteralPath $globalDb) {
  python -c @"
import sqlite3
db=r'$globalDb'
con=sqlite3.connect(db)
cur=con.cursor()
n=0
for (k,) in list(cur.execute(\"SELECT key FROM ItemTable WHERE key LIKE 'secret://%' AND key LIKE '%sonarsource.sonarlint-vscode%'\")):
    cur.execute('DELETE FROM ItemTable WHERE key=?', (k,)); n+=1
    print('deleted secret', k)
con.commit()
print('secrets_removed', n)
"@
}

# 5) MCP: strip SonarLint-injected IDE port (leave token/url to fix-sonarqube-mcp if present)
if (Test-Path -LiteralPath $mcpPath) {
  $mcp = Read-Json $mcpPath
  if ($mcp.mcpServers -and $mcp.mcpServers.sonarqube -and $mcp.mcpServers.sonarqube.env) {
    $envObj = $mcp.mcpServers.sonarqube.env
    if ($envObj.PSObject.Properties.Name -contains 'SONARQUBE_IDE_PORT') {
      $envObj.PSObject.Properties.Remove('SONARQUBE_IDE_PORT')
      Write-Json $mcpPath $mcp
      Write-Host "mcp.json: removed SONARQUBE_IDE_PORT (SonarLint injection)"
    }
  }
}

Write-Host ""
Write-Host "OK: SonarLint reset to STANDALONE mode." -ForegroundColor Green
Write-Host "1) Fully QUIT Cursor (File -> Exit), not just Reload" -ForegroundColor Cyan
Write-Host "2) Reopen ai-cabinet; open a .java file and wait ~10s for squiggles" -ForegroundColor Cyan
Write-Host "3) Do NOT open SonarLint Connected Mode / Configure MCP in sidebar" -ForegroundColor Yellow
Write-Host "4) For AI Sonar: run .\scripts\devops\fix-sonarqube-mcp.ps1 (MCP only, no SonarLint connection)" -ForegroundColor Cyan
