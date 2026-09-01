# 上传演示购物录像到 MinIO，并在指定会话上挂载 video_uri（容器重建后恢复 demo 用）。
# 用法：
#   .\scripts\seed-demo-shopping-video.ps1
#   .\scripts\seed-demo-shopping-video.ps1 -SessionId 1788233611382431271
param(
    [string]$MinioContainer = "",
    [string]$PostgresContainer = "",
    [string]$Bucket = "cabinet-videos",
    [string]$ObjectKey = "demo/sample-shopping.mp4",
    [string]$SessionId = "",
    [string]$McImage = "minio/mc:latest"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$localFile = Join-Path $repoRoot "testdata\sample-shopping.mp4"
if (-not (Test-Path $localFile)) {
    throw "找不到本地样例视频：$localFile"
}

if (-not $MinioContainer) {
    $MinioContainer = docker ps --filter "label=com.docker.compose.service=minio" --format "{{.Names}}" 2>$null |
        Select-Object -First 1
}
if (-not $MinioContainer) { $MinioContainer = "ai-cabinet-minio-1" }

$network = (docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' $MinioContainer 2>$null).Trim()
if (-not $network) { throw "无法识别 MinIO 容器网络：$MinioContainer" }

$user = if ($env:MINIO_ROOT_USER) { $env:MINIO_ROOT_USER } else { "minioadmin" }
$pass = if ($env:MINIO_ROOT_PASSWORD) { $env:MINIO_ROOT_PASSWORD } else { "minioadmin" }
$videoUri = "minio://$Bucket/$ObjectKey"

Write-Host "==> Upload $localFile -> $Bucket/$ObjectKey"
docker run --rm `
    --network $network `
    -v "${localFile}:/upload.mp4:ro" `
    --entrypoint sh `
    $McImage `
    -c "mc alias set local http://minio:9000 '$user' '$pass' >/dev/null 2>&1 && mc mb --ignore-existing local/$Bucket && mc cp /upload.mp4 local/$Bucket/$ObjectKey"

Write-Host "==> MinIO object ready: $videoUri"

if ($SessionId) {
    if (-not $PostgresContainer) {
        $PostgresContainer = docker ps --filter "label=com.docker.compose.service=postgres" --format "{{.Names}}" 2>$null |
            Select-Object -First 1
    }
    if (-not $PostgresContainer) { $PostgresContainer = "ai-cabinet-postgres-1" }
    $sql = "UPDATE shopping_session SET video_uri = '$videoUri' WHERE session_id = '$SessionId';"
    Write-Host "==> Bind session $SessionId"
    docker exec $PostgresContainer psql -U aicabinet -d aicabinet -c $sql | Out-Host
    Write-Host "Done. Open order detail in consumer/merchant H5 and tap view shopping video."
} else {
    Write-Host "Tip: pass -SessionId to bind video_uri on a shopping_session row."
    Write-Host "Example: .\scripts\seed-demo-shopping-video.ps1 -SessionId 1788233611382431271"
}
