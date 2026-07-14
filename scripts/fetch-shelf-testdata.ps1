# 从网上下载真实柜内/货架图，并生成 delta 测试视频 take-one-shelf.mp4
param(
    [string]$OutDir = "",
    [switch]$SkipDownload
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
if (-not $OutDir) { $OutDir = Join-Path $Root "testdata" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Test-ImageFile([string]$Path) {
    if (-not (Test-Path $Path)) { return $false }
    $b = [IO.File]::ReadAllBytes($Path)
    if ($b.Length -lt 1024) { return $false }
    $jpeg = ($b[0] -eq 0xFF -and $b[1] -eq 0xD8)
    $png = ($b[0] -eq 0x89 -and $b[1] -eq 0x50)
    return ($jpeg -or $png)
}

$shelfFull = Join-Path $OutDir "shelf-full.jpg"
$shelfSparse = Join-Path $OutDir "shelf-sparse.jpg"
$deltaVideo = Join-Path $OutDir "take-one-shelf.mp4"

if (-not $SkipDownload) {
    $sources = @(
        @{
            Url  = "https://images.pexels.com/photos/264636/pexels-photo-264636.jpeg?auto=compress&cs=tinysrgb&w=960"
            Out  = $shelfFull
            Name = "pexels vending machine shelf"
        },
        @{
            Url  = "https://upload.wikimedia.org/wikipedia/commons/thumb/4/4c/Soft_drink_shelf.jpg/960px-Soft_drink_shelf.jpg"
            Out  = $shelfFull
            Name = "wikimedia soft drink shelf"
        },
        @{
            Url  = "https://upload.wikimedia.org/wikipedia/commons/thumb/8/8f/Smart_fridge_interior.jpg/960px-Smart_fridge_interior.jpg"
            Out  = $shelfSparse
            Name = "wikimedia smart fridge interior"
        },
        @{
            Url  = "https://upload.wikimedia.org/wikipedia/commons/thumb/9/9d/Vending_machine_in_Japan.jpg/960px-Vending_machine_in_Japan.jpg"
            Out  = (Join-Path $OutDir "shelf-vending.jpg")
            Name = "wikimedia vending machine"
        }
    )

    function Invoke-DockerShelfDownload([string]$Url, [string]$DestLeaf) {
        $py = @"
import urllib.request, pathlib, cv2, numpy as np
url = '$Url'
dest = pathlib.Path('/testdata/$DestLeaf')
req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
data = urllib.request.urlopen(req, timeout=30).read()
arr = np.frombuffer(data, np.uint8)
img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
if img is None:
    raise SystemExit('decode failed')
cv2.imwrite(str(dest), cv2.resize(img, (960, 720)))
print(dest)
"@
        docker exec ai-cabinet-vision-service-1 python -c $py 2>$null | Out-Null
        return (Test-ImageFile (Join-Path $OutDir $DestLeaf))
    }

    foreach ($src in $sources) {
        if ((Test-Path $src.Out) -and (Test-ImageFile $src.Out)) {
            Write-Host "skip (exists): $($src.Out)"
            continue
        }
        Write-Host "download: $($src.Name)"
        try {
            curl.exe -fsSL --max-time 60 -o $src.Out $src.Url
            if (-not (Test-ImageFile $src.Out)) {
                Remove-Item $src.Out -Force -ErrorAction SilentlyContinue
                Write-Warning "invalid image from host curl: $($src.Url)"
                $leaf = Split-Path -Leaf $src.Out
                if (Invoke-DockerShelfDownload -Url $src.Url -DestLeaf $leaf) {
                    Write-Host "  -> docker saved $($src.Out)"
                }
            } else {
                Write-Host "  -> $($src.Out) ($((Get-Item $src.Out).Length) bytes)"
            }
        } catch {
            Write-Warning "host download failed: $($src.Url) — trying docker..."
            $leaf = Split-Path -Leaf $src.Out
            if (Invoke-DockerShelfDownload -Url $src.Url -DestLeaf $leaf) {
                Write-Host "  -> docker saved $($src.Out)"
            } else {
                Write-Warning "docker download also failed: $($src.Name)"
            }
        }
    }
}

if (-not (Test-ImageFile $shelfFull)) {
    Write-Host "fallback: generate synthetic shelf-full.jpg"
    python -c @"
import cv2, numpy as np
from pathlib import Path
p = Path(r'$shelfFull')
img = np.full((720, 960, 3), 40, dtype=np.uint8)
for row in range(3):
    for col in range(5):
        x, y = 40 + col * 180, 80 + row * 200
        cv2.rectangle(img, (x, y), (x+120, y+160), (30 + col*15, 80, 180), -1)
        cv2.putText(img, f'S{row}{col}', (x+20, y+90), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255,255,255), 2)
cv2.imwrite(str(p), img)
print('wrote', p)
"@
}

if (-not (Test-ImageFile $shelfSparse)) {
    if (Test-ImageFile $shelfFull) {
        Copy-Item $shelfFull $shelfSparse -Force
    } else {
        throw "No valid shelf images available under $OutDir"
    }
}

Write-Host "generate delta video: $deltaVideo"
python -c @"
import cv2, numpy as np
from pathlib import Path

def load(p):
    img = cv2.imread(str(p))
    if img is None:
        raise SystemExit(f'cannot read {p}')
    return cv2.resize(img, (960, 720))

full = load(Path(r'$shelfFull'))
sparse = load(Path(r'$shelfSparse'))
blank = np.full_like(full, 32)
out = Path(r'$deltaVideo')
writer = cv2.VideoWriter(str(out), cv2.VideoWriter_fourcc(*'mp4v'), 5.0, (960, 720))
open_frames = [full] * 4
close_frames = [sparse, sparse, blank, blank]
for f in open_frames + close_frames:
    writer.write(f)
writer.release()
print('wrote', out, 'frames', len(open_frames) + len(close_frames))
"@

Write-Host "probe vision (optional)..."
$visionUrl = "http://localhost:18082/api/v2/vision/recognize/upload"
try {
    $probe = curl.exe -s -X POST $visionUrl `
        -H "X-Internal-Api-Key: dev-vision-key-change-me" `
        -F "session_id=SHELF-PROBE" `
        -F "file=@$deltaVideo"
    $json = $probe | ConvertFrom-Json
    Write-Host "  model=$($json.model_version) items=$($json.items.Count) detected=$($json.detected_classes -join ',')"
} catch {
    Write-Warning "vision probe skipped: $_"
}

Write-Host "Done. Use -VideoFile take-one-shelf.mp4 for vision+gravity E2E."
