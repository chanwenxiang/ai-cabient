$ErrorActionPreference = "Stop"
$Infra = Join-Path $PSScriptRoot "infra"
& docker compose --env-file (Join-Path $Infra ".env") -f (Join-Path $Infra "docker-compose.full.yml") down
exit $LASTEXITCODE
