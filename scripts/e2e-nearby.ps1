# Nearby cabinets API smoke
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "e2e-lib.ps1")
$BaseUrl = Resolve-E2eBaseUrl ""
$InternalKey = if ($env:INTERNAL_API_KEY) { $env:INTERNAL_API_KEY } else { "dev-internal-key-change-me" }

$demo = & (Join-Path $PSScriptRoot "seed-demo-data.ps1") -BaseUrl $BaseUrl -InternalApiKey $InternalKey -Ensure
$Phone = $demo.consumerPhone
$login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/password-login" -Body @{
    phoneNumber = $Phone; password = "123456"
}
$auth = @{ Authorization = ("Bearer " + $login.token) }

# ensure CAB-001 has coords (keep existing if present)
$coords = docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -t -A -F "|" -c "SELECT COALESCE(latitude::text,''),COALESCE(longitude::text,'') FROM device_info WHERE device_id='CAB-001';"
$lat = 31.2304; $lng = 121.4737
if ($coords -match '^([^|]+)\|([^|]+)$') {
    $la = $Matches[1].Trim(); $lo = $Matches[2].Trim()
    if ($la -and $lo) { $lat = [double]$la; $lng = [double]$lo }
} else {
    docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -c "UPDATE device_info SET latitude=31.2304, longitude=121.4737, address=COALESCE(NULLIF(address,''),'上海市黄浦区演示点位') WHERE device_id='CAB-001' AND (latitude IS NULL OR longitude IS NULL);" | Out-Null
}

$list = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
    -Path ("/api/v2/devices/nearby?lat=" + $lat + "&lng=" + $lng + "&radiusKm=5&limit=10") -Headers $auth
$hit = @($list) | Where-Object { $_.deviceId -eq "CAB-001" } | Select-Object -First 1
Write-Host ("nearby count=" + @($list).Count + " cab001=" + ($null -ne $hit) + " dist=" + $hit.distanceMeters + " from=(" + $lat + "," + $lng + ")")
$pass = ($null -ne $hit) -and ($hit.distanceMeters -ge 0)
Write-Host ("PASS_NEARBY=" + $pass)
if (-not $pass) { exit 1 }
