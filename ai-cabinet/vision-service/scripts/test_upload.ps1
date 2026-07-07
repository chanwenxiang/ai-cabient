# 上传图片测试 YOLO 识别
# 用法: .\scripts\test_upload.ps1 C:\path\to\bottle.jpg

param(
    [Parameter(Mandatory = $true)]
    [string]$ImagePath
)

$apiKey = "dev-vision-key-change-me"
$url = "http://localhost:8082/api/v2/vision/recognize/upload"

if (-not (Test-Path $ImagePath)) {
    Write-Error "文件不存在: $ImagePath"
    exit 1
}

Write-Host "上传识别: $ImagePath"
curl.exe -s -X POST $url `
    -H "X-Internal-Api-Key: $apiKey" `
    -F "session_id=TEST-$(Get-Date -Format 'HHmmss')" `
    -F "file=@$ImagePath"
