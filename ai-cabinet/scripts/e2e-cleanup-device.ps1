# Cancel stale sessions that block device availability (E2E preflight)
param(
    [string]$DeviceId = "CAB-001",
    [string]$PostgresContainer = "infra-postgres-1"
)

$ErrorActionPreference = "Stop"

$states = @("CREATED", "OPENING", "SHOPPING", "RECOGNIZING", "WAITING_UPLOAD", "SETTLING")
$inList = ($states | ForEach-Object { "'$_'" }) -join ","
$sql = @"
UPDATE shopping_session
SET state = 'CANCELLED', updated_at = NOW()
WHERE device_id = '$DeviceId'
  AND state IN ($inList);

UPDATE replenishment_task
SET status = 'CANCELLED'
WHERE device_id = '$DeviceId'
  AND status = 'IN_PROGRESS';

UPDATE device_info
SET online_status = 'OFFLINE'
WHERE device_id = '$DeviceId';

DELETE FROM user_blacklist
WHERE user_id IN (SELECT user_id FROM user_info WHERE phone_number = '13800138000');

UPDATE shopping_session
SET created_at = created_at - INTERVAL '2 hours'
WHERE user_id IN (SELECT user_id FROM user_info WHERE phone_number = '13800138000')
  AND created_at > NOW() - INTERVAL '1 hour';
"@

$out = docker exec $PostgresContainer psql -U aicabinet -d aicabinet -c $sql 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Warning "e2e-cleanup-device: postgres cleanup failed: $out"
    exit 1
}
Write-Host "==> e2e-cleanup-device: cleared blocking sessions on $DeviceId"
Write-Host "    $out"
