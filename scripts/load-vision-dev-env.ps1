# ============================================================
# ⛔ 已退役（2026-10-11）：旧云识别/录像链专用脚本。
#    识别只认将邑端侧；vision-service 与 /internal/v1/sessions/video* 已删除，
#    本脚本运行必失败。见 docs/JIANGYI_ONLY_FINAL_VERDICT_2026-10-11.md §3 批6/收尾B。
# ============================================================
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
