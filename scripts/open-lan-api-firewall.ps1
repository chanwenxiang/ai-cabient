#Requires -RunAsAdministrator
# 放行本机网关/交易服务端口，供手机真机调试访问电脑局域网 IP。
# 用法：右键「以管理员身份运行」PowerShell，执行：
#   cd <repo>
#   .\scripts\open-lan-api-firewall.ps1

$ErrorActionPreference = 'Stop'
$rules = @(
  @{ Name = 'AICabinet-Gateway-80'; Port = 80 },
  @{ Name = 'AICabinet-Trade-18080'; Port = 18080 }
)

foreach ($r in $rules) {
  $existing = Get-NetFirewallRule -DisplayName $r.Name -ErrorAction SilentlyContinue
  if ($existing) {
    Set-NetFirewallRule -DisplayName $r.Name -Enabled True -Action Allow -Profile Any
    Write-Host "updated $($r.Name) (TCP $($r.Port))"
  } else {
    New-NetFirewallRule -DisplayName $r.Name -Direction Inbound -Protocol TCP -LocalPort $r.Port -Action Allow -Profile Any | Out-Null
    Write-Host "added $($r.Name) (TCP $($r.Port))"
  }
}

Write-Host ''
Write-Host 'Done. On phone browser open: http://<PC-LAN-IP>/api/v2/auth/server-boot'
Write-Host 'If that fails, check same WiFi (not guest) and Docker gateway is up.'
