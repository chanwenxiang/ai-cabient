# Fix java:S6809 self-invocation of @Transactional methods
$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not (Test-Path "$root/services/trade-service")) { $root = Split-Path $PSScriptRoot -Parent }

$envFile = Get-Content "$root/infra/.env"
$token = ($envFile | Where-Object { $_ -match '^SONAR_TOKEN=' }) -replace '^SONAR_TOKEN=',''
$auth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${token}:"))
$headers = @{ Authorization = "Basic $auth" }
$uri = "http://localhost:19002/api/issues/search?componentKeys=ai-cabinet-dev&rules=java:S6809&statuses=OPEN&branch=dev&ps=500"
$r = Invoke-RestMethod -Uri $uri -Headers $headers

function Get-ClassName([string]$content) {
    if ($content -match 'public\s+class\s+(\w+)') { return $Matches[1] }
    throw "Cannot find class name"
}

function Ensure-SelfInfrastructure([string[]]$lines, [string]$className) {
    $text = $lines -join "`n"
    if ($text -match "private\s+final\s+$className\s+self\s*;") {
        return $lines
    }

    $result = [System.Collections.Generic.List[string]]::new()
    $lazyAdded = $false
    $fieldAdded = $false
    $inConstructor = $false
    $ctorParenDepth = 0
    $ctorClosed = $false

    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]

        if (-not $lazyAdded -and $line -match '^import org\.springframework\.stereotype\.') {
            $result.Add('import org.springframework.context.annotation.Lazy;')
            $lazyAdded = $true
        }

        if (-not $fieldAdded -and $line -match '^\s+private\s+final\s+' -and $line -notmatch '\s+self\s*;') {
            $result.Add($line)
            if ($i + 1 -lt $lines.Count -and $lines[$i + 1] -notmatch '^\s+private\s+final\s+') {
                $result.Add("    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */")
                $result.Add("    private final $className self;")
                $fieldAdded = $true
            }
            continue
        }

        if ($line -match "public\s+$className\s*\(") {
            $inConstructor = $true
            $ctorParenDepth = ($line.ToCharArray() | Where-Object { $_ -eq '(' }).Count - ($line.ToCharArray() | Where-Object { $_ -eq ')' }).Count
            if ($ctorParenDepth -le 0 -and $line -match '\)\s*\{') {
                $line = $line -replace '\)\s*\{', ", @Lazy $className self) {"
                $inConstructor = $false
                $ctorClosed = $true
            }
            $result.Add($line)
            continue
        }

        if ($inConstructor) {
            $ctorParenDepth += ($line.ToCharArray() | Where-Object { $_ -eq '(' }).Count
            $ctorParenDepth -= ($line.ToCharArray() | Where-Object { $_ -eq ')' }).Count
            if ($ctorParenDepth -le 0 -and $line -match '\)\s*\{') {
                $line = $line -replace '\)\s*\{', ", @Lazy $className self) {"
                $inConstructor = $false
                $ctorClosed = $true
            }
            $result.Add($line)
            continue
        }

        if ($ctorClosed -and $line -match '^\s+\}\s*$' -and $result[-1] -match 'this\.\w+\s*=') {
            $result.Add('        this.self = self;')
            $ctorClosed = $false
            $result.Add($line)
            continue
        }

        if ($ctorClosed -and $line -match '^\s+this\.\w+\s*=') {
            $result.Add($line)
            if ($i + 1 -lt $lines.Count -and $lines[$i + 1] -match '^\s+\}\s*$') {
                $result.Add('        this.self = self;')
                $ctorClosed = $false
            }
            continue
        }

        $result.Add($line)
    }

    if (-not $fieldAdded) {
        throw "Failed to add self field for $className"
    }
    return ,$result.ToArray()
}

function Fix-TransactionalCall([string]$line) {
    if ($line -match '\bself\.') { return $line }

    $keywords = 'if|for|while|switch|catch|new|throw|return|else|try|do|synchronized|assert|super|this|class|instanceof'
    $fixed = $line

    # return foo(
    $fixed = [regex]::Replace($fixed, '(?<![.\w])return\s+(\w+)\s*\(', {
        param($m)
        if ($m.Groups[1].Value -match "^($keywords)$") { return $m.Value }
        return "return self.$($m.Groups[1].Value)("
    })

    # if (!foo( or if (foo(
    $fixed = [regex]::Replace($fixed, '(?<![.\w])if\s*\(\s*!(\w+)\s*\(', {
        param($m)
        if ($m.Groups[1].Value -match "^($keywords)$") { return $m.Value }
        return "if (!self.$($m.Groups[1].Value)("
    })
    $fixed = [regex]::Replace($fixed, '(?<![.\w])if\s*\(\s*(\w+)\s*\(', {
        param($m)
        if ($m.Groups[1].Value -match "^($keywords)$") { return $m.Value }
        return "if (self.$($m.Groups[1].Value)("
    })

    # assignment / args: = foo( or , foo( or ( foo(
    $fixed = [regex]::Replace($fixed, '(?<![.\w])(=|\(|,\s*)(\w+)\s*\(', {
        param($m)
        if ($m.Groups[2].Value -match "^($keywords)$") { return $m.Value }
        return "$($m.Groups[1].Value)self.$($m.Groups[2].Value)("
    })

    # statement start: spaces + foo(
    $fixed = [regex]::Replace($fixed, '^(\s+)(\w+)\s*\(', {
        param($m)
        if ($m.Groups[2].Value -match "^($keywords)$") { return $m.Value }
        return "$($m.Groups[1].Value)self.$($m.Groups[2].Value)("
    })

    # for (... foo(
    $fixed = [regex]::Replace($fixed, 'for\s*\([^)]*?\b(\w+)\s*\(', {
        param($m)
        if ($m.Groups[1].Value -match "^($keywords)$") { return $m.Value }
        return $m.Value -replace "\b$($m.Groups[1].Value)\s*\(", "self.$($m.Groups[1].Value)("
    })

    return $fixed
}

$byFile = $r.issues | Group-Object { ($_.component -split ':')[-1] -replace '.*service/','' -replace '.*scheduler/','' }
$changed = @()

foreach ($group in $byFile) {
    $shortName = $group.Name
    $paths = @(
        "$root/services/trade-service/src/main/java/com/aicabinet/trade/service/$shortName",
        "$root/services/trade-service/src/main/java/com/aicabinet/trade/scheduler/$shortName",
        "$root/services/trade-service/src/main/java/com/aicabinet/trade/payment/$shortName"
    )
    $path = $paths | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $path) {
        Write-Warning "Skip missing: $shortName"
        continue
    }

    $lines = [System.IO.File]::ReadAllLines($path)
    $className = Get-ClassName ($lines -join "`n")
    $needsInfra = ($lines -join "`n") -notmatch "private\s+final\s+$className\s+self\s*;"
    if ($needsInfra) {
        $lines = Ensure-SelfInfrastructure $lines $className
    }

    $issueLines = $group.Group | ForEach-Object { [int]$_.line } | Sort-Object -Unique
    $modified = $false
    foreach ($ln in $issueLines) {
        $idx = $ln - 1
        if ($idx -lt 0 -or $idx -ge $lines.Count) { continue }
        $old = $lines[$idx]
        $new = Fix-TransactionalCall $old
        if ($new -ne $old) {
            $lines[$idx] = $new
            $modified = $true
        }
    }

    if ($needsInfra -or $modified) {
        [System.IO.File]::WriteAllLines($path, $lines)
        $changed += $path
        Write-Host "Fixed: $shortName (infra=$needsInfra, lines=$($issueLines -join ','))"
    }
}

Write-Host "`nChanged $($changed.Count) files:"
$changed | ForEach-Object { Write-Host "  $_" }
