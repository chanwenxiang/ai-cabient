# 加载 vision-service 真实识别环境变量（不影响 trade mock）
$Root = Split-Path $PSScriptRoot -Parent
$EnvFile = Join-Path $Root "infra\.env.vision-dev"
$Example = Join-Path $Root "infra\.env.vision-dev.example"
if ($args.Count -ge 1) {
    $EnvFile = $args[0]
}

if (-not (Test-Path $EnvFile)) {
    if (Test-Path $Example) {
        Copy-Item $Example $EnvFile
        Write-Host "Created $EnvFile from example"
    } else {
        Write-Error "Missing env file: $EnvFile (and no .env.vision-dev.example)"
        exit 1
    }
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
