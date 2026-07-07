# Quick port check for local dev
# Usage: .\scripts\check-ports.ps1

$ports = @(
    @{ Port = 8080; Name = "trade-service" },
    @{ Port = 8081; Name = "device-service" },
    @{ Port = 8082; Name = "vision-service" },
    @{ Port = 15433; Name = "postgres" },
    @{ Port = 9000; Name = "minio" },
    @{ Port = 11883; Name = "emqx-mqtt" }
)

Write-Host "Local port status:"
foreach ($p in $ports) {
    $listen = Get-NetTCPConnection -LocalPort $p.Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listen) {
        Write-Host ("  {0,5}  UP   {1} (pid {2})" -f $p.Port, $p.Name, $listen.OwningProcess)
    } else {
        Write-Host ("  {0,5}  DOWN {1}" -f $p.Port, $p.Name)
    }
}
