# 加载 vision-service 真实识别环境变量（不影响 trade mock）
$EnvFile = Join-Path (Split-Path $PSScriptRoot -Parent) "infra\.env.vision-dev"
if ($args.Count -ge 1) {
    $EnvFile = $args[0]
}

if (-not (Test-Path $EnvFile)) {
    Write-Error "Missing env file: $EnvFile"
}
Get-Content $EnvFile | ForEach-Object {
    if ($_ -match '^\s*#' -or $_ -match '^\s*$') { return }
    if ($_ -match '^([^=]+)=(.*)$') {
        $name = $matches[1].Trim()
        $value = $matches[2].Trim()
        Set-Item -Path "env:$name" -Value $value
        Write-Host "set $name"
    }
}
Write-Host "Vision dev env loaded from $EnvFile"
