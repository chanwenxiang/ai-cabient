# 生成生产环境 EMQX 认证种子文件 docker/emqx/auth-bootstrap.production.csv。
#
# 背景：EMQX 5 未配置认证器时默认放行匿名连接；docker-compose.production.yml 通过
# 内置数据库认证 + bootstrap 文件关闭匿名。bootstrap CSV 属敏感文件，已加入 .gitignore。
#
# 用法（在 infra/ 下、docker compose up 之前执行一次；换口令后重跑并重建 emqx 容器）：
#   pwsh ../scripts/gen-emqx-auth-bootstrap.ps1 -EnvFile .env.production
#
# 参数优先级：命令行参数 > 环境变量文件(.env*) > 交互输入。
param(
    [string]$BackendUser = "aicabinet-backend",
    [string]$DeviceUser = "aicabinet-device",
    [string]$BackendPassword,
    [string]$DevicePassword,
    [string]$EnvFile = ".env.production",
    [string]$OutFile = "docker/emqx/auth-bootstrap.production.csv"
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
if (-not $DevicePassword) { $DevicePassword = Read-Host "MQTT 设备共享账号($DeviceUser)口令" }

if ($BackendPassword.Length -lt 16) { throw "后端 MQTT 口令太弱（<16 字符），拒绝生成" }
if ($DevicePassword.Length -lt 16) { throw "设备 MQTT 口令太弱（<16 字符），拒绝生成" }
if ($BackendPassword -eq $DevicePassword) { throw "后端与设备口令不得相同" }

# bootstrap_type=plain：EMQX 首次启动导入后立即哈希落库。
# 注意 CSV 首行必须是表头（EMQX 按列名解析：user_id,password,is_superuser）。
$csv = "user_id,password,is_superuser`n$BackendUser,$BackendPassword,false`n$DeviceUser,$DevicePassword,false`n"
$dir = Split-Path $OutFile -Parent
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
[System.IO.File]::WriteAllText((Resolve-Path (Split-Path $OutFile -Parent)).Path + "\" + (Split-Path $OutFile -Leaf), $csv)
Write-Host "已生成 $OutFile（backend=$BackendUser device=$DeviceUser）"
Write-Host "提醒：该文件含明文口令，已被 .gitignore 忽略；口令同时需写入 .env.production 的 MQTT_PASSWORD / MQTT_DEVICE_PASSWORD。"
