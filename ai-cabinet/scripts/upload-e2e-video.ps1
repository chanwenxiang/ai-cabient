# Upload E2E shopping session video to local MinIO (host port 9000).
param(
    [Parameter(Mandatory = $true)]
    [string]$SessionId,
    [string]$MinioHost = "host.docker.internal",
    [int]$MinioPort = 9000,
    [string]$AccessKey = "minioadmin",
    [string]$SecretKey = "minioadmin",
    [string]$Bucket = "cabinet-videos"
)

$ErrorActionPreference = "Stop"

$sample = & "$PSScriptRoot\ensure-sample-video.ps1"
$objectKey = "sim/$SessionId.mp4"

docker run --rm `
    -v "${sample}:/sample.mp4:ro" `
    --entrypoint /bin/sh `
    minio/mc `
    -c "mc alias set local http://${MinioHost}:${MinioPort} ${AccessKey} ${SecretKey} && mc cp /sample.mp4 local/${Bucket}/${objectKey}"

Write-Host "    uploaded minio://${Bucket}/${objectKey}"
