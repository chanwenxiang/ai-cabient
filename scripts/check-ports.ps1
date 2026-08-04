# Quick port check for local / Docker Desktop host mappings
# Usage: .\scripts\check-ports.ps1
#
# Defaults match compose / .env.example Hyper-V-safe ports.
# Windows may override EMQX to 12883 via infra/.env — both are checked.

$ports = @(
    @{ Port = 80; Name = "gateway (http)" },
    @{ Port = 18080; Name = "trade-service (compose host)" },
    @{ Port = 8080; Name = "trade-service (local JVM)" },
    @{ Port = 18081; Name = "device-service (compose host)" },
    @{ Port = 8081; Name = "device-service (local JVM)" },
    @{ Port = 18082; Name = "vision-service (compose host)" },
    @{ Port = 8082; Name = "vision-service (local JVM)" },
    @{ Port = 15433; Name = "postgres" },
    @{ Port = 19000; Name = "minio API (win-ports)" },
    @{ Port = 9000; Name = "minio API (default)" },
    @{ Port = 11883; Name = "emqx-mqtt (default Hyper-V-safe)" },
    @{ Port = 12883; Name = "emqx-mqtt (local override, e.g. .env)" },
    @{ Port = 28083; Name = "emqx-dashboard" }
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

Write-Host ""
Write-Host "EMQX note: compose default host port is 11883 (see infra/.env.example EMQX_MQTT_PORT)."
Write-Host "           Some Windows setups use 12883 when 11883 is reserved — set EMQX_MQTT_PORT in infra/.env."
Write-Host "           Container-internal broker remains tcp://emqx:1883."
