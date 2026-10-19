# Generates infra/gateway/grafana.htpasswd locally (AU-146, 2026-10-19 decision).
#
# Why: the file used to be COMMITTED with a real admin bcrypt hash at cost=05.
# Even after a cost bump the old hash lives in git history forever => the credential
# must be rotated and the file must never be tracked again. Gate:
#   node scripts/check-no-committed-htpasswd.mjs
#
# Sources, in order of preference:
#   1. GRAFANA_BASIC_AUTH_USER / GRAFANA_BASIC_AUTH_PASSWORD from the environment
#   2. same keys from infra/.env
#   3. no password found => a 24-char random password is generated, PERSISTED to
#      infra/.env, and its location is printed (the password itself is not echoed).
#
# Hash: bcrypt cost 10 via a LOCALLY PRESENT docker httpd:alpine image (no auto-pull:
# offline hosts must not hang/timeout); falls back to openssl apr1 (Git for Windows
# ships openssl). apr1 is accepted by nginx auth_basic but weaker than bcrypt.
#
# Usage:  powershell -File scripts/devops/gen-grafana-htpasswd.ps1
#         (from repo root; -NoProfile recommended)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$envFile  = Join-Path $repoRoot 'infra\.env'
$outFile  = Join-Path $repoRoot 'infra\gateway\grafana.htpasswd'

function Read-DotEnv([string]$path) {
    $map = @{}
    if (Test-Path $path) {
        Get-Content $path | ForEach-Object {
            $line = $_.Trim()
            if ($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
                $map[$Matches[1]] = $Matches[2].Trim()
            }
        }
    }
    return $map
}

$dotenv = Read-DotEnv $envFile

# Username precedence: existing htpasswd file (rotate password without renaming the
# account) > GRAFANA_BASIC_AUTH_USER (env or .env) > 'opsadmin'.
$user = $null
if (Test-Path $outFile) {
    $first = (Get-Content $outFile -TotalCount 1)
    if ($first -match '^([^:]+):') { $user = $Matches[1] }
}
if (-not $user) { $user = $env:GRAFANA_BASIC_AUTH_USER }
if (-not $user) { $user = $dotenv['GRAFANA_BASIC_AUTH_USER'] }
if (-not $user) { $user = 'opsadmin' }

$password = $env:GRAFANA_BASIC_AUTH_PASSWORD
$passwordSource = 'environment'
if (-not $password) {
    $password = $dotenv['GRAFANA_BASIC_AUTH_PASSWORD']
    $passwordSource = 'infra/.env'
}

if (-not $password -or $password -eq 'change-me-strong-password') {
    # Generate a strong random password (24 chars, unambiguous alphabet) and persist it.
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789'
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = New-Object byte[] 24
    $rng.GetBytes($bytes)
    $sb = New-Object System.Text.StringBuilder
    foreach ($b in $bytes) { [void]$sb.Append($alphabet[$b % $alphabet.Length]) }
    $password = $sb.ToString()
    $passwordSource = 'generated'

    $key = 'GRAFANA_BASIC_AUTH_PASSWORD'
    if (Test-Path $envFile) {
        $content = Get-Content $envFile -Raw
        $pattern = "(?m)^$key=.*$"
        if ($content -match $pattern) {
            $content = $content -replace $pattern, "$key=$password"
            Set-Content -Path $envFile -Value $content -NoNewline -Encoding UTF8
        } else {
            Add-Content -Path $envFile -Value "`n$key=$password" -Encoding UTF8
        }
    } else {
        Set-Content -Path $envFile -Value "$key=$password" -Encoding UTF8
    }
    Write-Host "Generated a new random password and stored it in infra/.env ($key)."
    Write-Host 'The password itself is NOT printed here - read infra/.env if you need it.'
}

# --- hash ---
$hashLine = $null

# Prefer bcrypt cost 10 via a locally present httpd image (never auto-pull: offline
# hosts must fail fast into the openssl fallback instead of hanging on a registry pull).
$dockerOk = $false
try {
    docker image inspect httpd:alpine *> $null
    $dockerOk = ($LASTEXITCODE -eq 0)
} catch { $dockerOk = $false }

if ($dockerOk) {
    $out = @($password) | docker run --rm -i httpd:alpine htpasswd -B -C 10 -ni $user 2>$null
    if ($LASTEXITCODE -eq 0 -and $out) {
        $line = ($out | Where-Object { $_ -match ':\$2[aby]\$' } | Select-Object -First 1)
        if ($line) { $hashLine = $line; Write-Host 'Hashed with bcrypt cost 10 (docker httpd:alpine).' }
    }
}

if (-not $hashLine) {
    # Fallback: openssl apr1. Try direct binary first, then via Git Bash.
    $apr1 = $null
    $opensslCmd = Get-Command openssl -ErrorAction SilentlyContinue
    if ($opensslCmd) {
        # NOTE: `openssl passwd` needs the password as an ARGUMENT (no -stdin without a tty).
        $apr1 = (& $opensslCmd.Source passwd -apr1 $password 2>$null | Select-Object -First 1)
    }
    if (-not ($apr1 -match '^\$apr1\$')) {
        $escaped = $password -replace "'", "'\\''"
        $apr1 = bash -c "openssl passwd -apr1 '$escaped'" 2>$null
    }
    if ($apr1 -match '^\$apr1\$(\S+)') {
        $hashLine = "${user}:$($apr1.Trim())"
        Write-Warning 'bcrypt unavailable (no local httpd image) - used openssl apr1 (weaker). Pull httpd:alpine once, then re-run, for bcrypt cost 10.'
    } else {
        Write-Error 'Cannot hash password: need a local docker httpd:alpine image (htpasswd -B -C 10) or openssl. Generate manually with: htpasswd -Bc grafana.htpasswd <user>'
        exit 1
    }
}

$dir = Split-Path $outFile -Parent
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
Set-Content -Path $outFile -Value $hashLine -NoNewline -Encoding ASCII
Write-Host "Wrote $outFile (user=$user, source=$passwordSource)."
Write-Host 'This file is gitignored (AU-146). Never commit it.'
