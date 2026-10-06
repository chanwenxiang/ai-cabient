# 生成生产环境 EMQX 认证种子文件 docker/emqx/auth-bootstrap.production.csv。
#
# 背景：EMQX 5 未配置认证器时默认放行匿名连接；docker-compose.production.yml 通过
# 内置数据库认证 + bootstrap 文件关闭匿名。bootstrap CSV 属敏感文件，已加入 .gitignore。
#
# S1（设备独立凭据）：-IncludeDevicesFromDb 会把 trade 库 device_mqtt_credential 表的
# ACTIVE 行（username=deviceId，运营台「设备详情→MQTT 凭据」签发）追加为 N 行设备账号；
# ACL（aicabinet-acl.conf）按 ${username} 命名空间放行，伪造他柜即被拒。
# 共享 aicabinet-device 账号在生产 bootstrap 中【不再输出】（吊销语义），仅 dev 栈保留。
#
# 用法（在 infra/ 下、docker compose up 之前执行一次；换口令后重跑并重建 emqx 容器）：
#   pwsh ../scripts/gen-emqx-auth-bootstrap.ps1 -EnvFile .env.production
#   pwsh ../scripts/gen-emqx-auth-bootstrap.ps1 -EnvFile .env.production -IncludeDevicesFromDb
#
# 参数优先级：命令行参数 > 环境变量文件(.env*) > 交互输入。
param(
    [string]$BackendUser = "aicabinet-backend",
    [string]$DeviceUser = "aicabinet-device",
    [string]$BackendPassword,
    [string]$DevicePassword,
    [string]$EnvFile = ".env.production",
    [string]$OutFile = "docker/emqx/auth-bootstrap.production.csv",
    # S1：从 trade 库 device_mqtt_credential 读 ACTIVE 设备凭据（逐设备一行）
    [switch]$IncludeDevicesFromDb,
    # 共享设备账号不再写进 bootstrap（S1 吊销语义）；此开关仅为回退保留
    [switch]$KeepSharedDevice
)

$ErrorActionPreference = "Stop"

function Read-DotEnv([string]$Path) {
    if (-not (Test-Path $Path)) { return @{} }
    $map = @{}
    foreach ($line in Get-Content $Path) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#") -or -not $trimmed.Contains("=")) { continue }
        $idx = $trimmed.IndexOf("=")
        $map[$trimmed.Substring(0, $idx).Trim()] = $trimmed.Substring($idx + 1).Trim()
    }
    return $map
}

# 脚本在 scripts/ 下运行时，相对路径按仓库根的 infra/ 解析
if (-not (Test-Path $EnvFile) -and (Test-Path (Join-Path "infra" $EnvFile))) {
    $EnvFile = Join-Path "infra" $EnvFile
}
if (-not (Test-Path $OutFile) -and (Test-Path (Join-Path "infra" $OutFile))) {
    $OutFile = Join-Path "infra" $OutFile
}

$env = Read-DotEnv $EnvFile
if (-not $BackendPassword) { $BackendPassword = $env["MQTT_PASSWORD"] }
if (-not $DevicePassword) { $DevicePassword = $env["MQTT_DEVICE_PASSWORD"] }
if (-not $BackendPassword) { $BackendPassword = Read-Host "MQTT 后端账号($BackendUser)口令" }
if ($KeepSharedDevice -and -not $DevicePassword) { $DevicePassword = Read-Host "MQTT 设备共享账号($DeviceUser)口令" }

if ($BackendPassword.Length -lt 16) { throw "后端 MQTT 口令太弱（<16 字符），拒绝生成" }
if ($KeepSharedDevice) {
    if ($DevicePassword.Length -lt 16) { throw "设备共享 MQTT 口令太弱（<16 字符），拒绝生成" }
    if ($BackendPassword -eq $DevicePassword) { throw "后端与设备口令不得相同" }
}

# S1：每设备独立凭据。来源=trade 库 device_mqtt_credential 表 ACTIVE 行（运营台签发/吊销），
# username=deviceId；明文 secret 仅签发响应展示一次，本表另存一份供 bootstrap 导出（敏感级同 CSV）。
$deviceRows = @()
if ($IncludeDevicesFromDb) {
    Write-Host "==> 读取 device_mqtt_credential ACTIVE 行（trade 容器内 psql，密钥不出库）..."
    $rows = docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -t -A -F ',' -c `
        "SELECT device_id, mqtt_secret FROM device_mqtt_credential WHERE status='ACTIVE' ORDER BY device_id" 2>$null
    foreach ($row in $rows) {
        if ([string]::IsNullOrWhiteSpace($row)) { continue }
        $parts = $row -split ',', 2
        if ($parts.Count -lt 2 -or [string]::IsNullOrWhiteSpace($parts[0])) { continue }
        $deviceRows += "$($parts[0]),$($parts[1]),false"
        Write-Host "    + 设备 $($parts[0])"
    }
    Write-Host "    设备凭据行数：$($deviceRows.Count)"
}

# bootstrap_type=plain：EMQX 首次启动导入后立即哈希落库。
# 注意 CSV 首行必须是表头（EMQX 按列名解析：user_id,password,is_superuser）。
$csv = "user_id,password,is_superuser`n$BackendUser,$BackendPassword,false`n"
if ($KeepSharedDevice) {
    $csv += "$DeviceUser,$DevicePassword,false`n"
}
foreach ($dr in $deviceRows) { $csv += "$dr`n" }

$dir = Split-Path $OutFile -Parent
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
[System.IO.File]::WriteAllText((Resolve-Path (Split-Path $OutFile -Parent)).Path + "\" + (Split-Path $OutFile -Leaf), $csv)
Write-Host "已生成 $OutFile（backend=$BackendUser 设备行=$($deviceRows.Count) 共享设备=$(if ($KeepSharedDevice) { '含' } else { '不含（S1）' })）"
Write-Host "提醒：该文件含明文口令，已被 .gitignore 忽略；后端口令需写入 .env.production 的 MQTT_PASSWORD。"
