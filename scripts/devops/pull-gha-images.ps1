# 预拉 GHA self-hosted runner 用到的 Docker 镜像（避免 workflow 内 pull 超时）
# 用法：.\scripts\devops\pull-gha-images.ps1
$ErrorActionPreference = "Stop"
$images = @(
  "maven:3.9-eclipse-temurin-17",
  "sonarsource/sonar-scanner-cli:11.1"
)
foreach ($img in $images) {
  Write-Host "Pulling $img ..." -ForegroundColor Cyan
  docker pull $img
}
Write-Host "OK: GHA images ready" -ForegroundColor Green
