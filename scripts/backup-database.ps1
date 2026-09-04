<#
.SYNOPSIS
    PostgreSQL 数据库备份脚本 — 生产环境定时备份 + PITR 配置
.DESCRIPTION
    支持全量备份和 WAL 归档两种模式。全量备份保留最近 N 天。
    口令只从 DATABASE_URL 或 PGUSER/PGPASSWORD 读取，禁止硬编码弱口令（B-9）。
.PARAMETER Mode
    full | wal | restore
.PARAMETER OutputDir
    备份文件输出目录
.PARAMETER RetentionDays
    全量备份保留天数 (默认 30)
.PARAMETER DbUrl
    数据库连接串 (默认从环境变量 DATABASE_URL 读取)
.PARAMETER BackupFile
    restore 模式输入文件
#>

param(
    [Parameter(Mandatory = $false)]
    [ValidateSet('full', 'wal', 'restore')]
    [string]$Mode = 'full',

    [Parameter(Mandatory = $false)]
    [string]$OutputDir = "C:\backups\aicabinet",

    [Parameter(Mandatory = $false)]
    [int]$RetentionDays = 30,

    [Parameter(Mandatory = $false)]
    [string]$DbUrl = $env:DATABASE_URL,

    [Parameter(Mandatory = $false)]
    [string]$BackupFile
)

$ErrorActionPreference = 'Stop'
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'

function Convert-JdbcToPgUrl([string]$JdbcUrl, [string]$User, [string]$Password) {
    if ($JdbcUrl -notmatch 'jdbc:postgresql://([^:/]+):?(\d*)/(\w+)') {
        return $null
    }
    $hostName = $Matches[1]
    $port = if ($Matches[2]) { $Matches[2] } else { '5432' }
    $dbName = $Matches[3]
    if ([string]::IsNullOrWhiteSpace($User) -or [string]::IsNullOrWhiteSpace($Password)) {
        throw "从 application.yml 拼连接串时须设置 PGUSER 与 PGPASSWORD（B-9）"
    }
    $encUser = [Uri]::EscapeDataString($User)
    $encPass = [Uri]::EscapeDataString($Password)
    return "postgresql://${encUser}:${encPass}@${hostName}:${port}/${dbName}"
}

function Get-PgConnectionParts([string]$Url) {
    if ($Url -notmatch '^postgres(?:ql)?://([^:/@]+):([^@]+)@([^:/]+):?(\d*)/([^?\s]+)') {
        throw "无法解析 DATABASE_URL，请使用 postgresql://user:pass@host:port/db"
    }
    return @{
        User     = [Uri]::UnescapeDataString($Matches[1])
        Password = [Uri]::UnescapeDataString($Matches[2])
        Host     = $Matches[3]
        Port     = if ($Matches[4]) { $Matches[4] } else { '5432' }
        Database = $Matches[5]
    }
}

if (-not $DbUrl) {
    $pgUser = $env:PGUSER
    $pgPass = $env:PGPASSWORD
    $pgHost = if ($env:PGHOST) { $env:PGHOST } else { 'localhost' }
    $pgPort = if ($env:PGPORT) { $env:PGPORT } else { '15433' }
    $pgDb = if ($env:PGDATABASE) { $env:PGDATABASE } else { 'aicabinet' }
    if ($pgUser -and $pgPass) {
        $DbUrl = "postgresql://$([Uri]::EscapeDataString($pgUser)):$([Uri]::EscapeDataString($pgPass))@${pgHost}:${pgPort}/${pgDb}"
    }
}

if (-not $DbUrl) {
    $ymlPath = Join-Path $PSScriptRoot '..\services\trade-service\src\main\resources\application.yml'
    if (Test-Path $ymlPath) {
        $yml = Get-Content $ymlPath -Raw
        if ($yml -match 'url:\s*(jdbc:postgresql://[^\s]+)') {
            $DbUrl = Convert-JdbcToPgUrl $Matches[1] $env:PGUSER $env:PGPASSWORD
        }
    }
}

if (-not $DbUrl) {
    throw "未配置 DATABASE_URL（或 PGUSER+PGPASSWORD）。禁止使用硬编码弱口令备份（B-9）。"
}

$parts = Get-PgConnectionParts $DbUrl
$env:PGPASSWORD = $parts.Password

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

switch ($Mode) {
    'full' {
        $file = Join-Path $OutputDir "aicabinet-full-${timestamp}.sql.gz"
        Write-Host ">>> 开始全量备份: $file"

        $dumpArgs = @(
            "-h", $parts.Host,
            "-p", $parts.Port,
            "-U", $parts.User,
            "-d", $parts.Database,
            "--format=custom",
            "--compress=9",
            "--no-owner",
            "--verbose",
            "--file", $file
        )

        try {
            & "pg_dump" @dumpArgs 2>&1 | Tee-Object -FilePath "$OutputDir\backup-${timestamp}.log"
            Write-Host "<<< 全量备份完成: $( (Get-Item $file).Length / 1MB ) MB"
        }
        catch {
            Write-Warning "pg_dump 失败: $_"
            Write-Warning "请确保安装了 PostgreSQL 客户端工具集 (postgresql-client)"
        }

        $cutoff = (Get-Date).AddDays(-$RetentionDays)
        Get-ChildItem -Path $OutputDir -Filter "aicabinet-full-*.sql.gz" | Where-Object {
            $_.CreationTime -lt $cutoff
        } | ForEach-Object {
            Remove-Item $_.FullName -Force
            Write-Host "已清理过期备份: $($_.Name)"
        }
    }

    'wal' {
        Write-Host ">>> WAL 归档模式需要在 postgresql.conf 中配置:"
        Write-Host "    archive_mode = on"
        Write-Host "    archive_command = 'cp %p $OutputDir/wal/%f'"
        Write-Host "    或者使用 pg_receivewal 实时归档"
    }

    'restore' {
        if (-not $BackupFile) {
            $BackupFile = Get-ChildItem -Path $OutputDir -Filter "aicabinet-full-*.sql.gz" |
                Sort-Object CreationTime -Descending | Select-Object -First 1 -ExpandProperty FullName
        }
        if (-not $BackupFile -or -not (Test-Path $BackupFile)) {
            Write-Error "备份文件不存在: $BackupFile"
            exit 1
        }
        Write-Host ">>> 恢复备份: $BackupFile"
        Write-Host ("    命令: pg_restore --clean --if-exists -h {0} -p {1} -U {2} -d {3} {4}" -f `
            $parts.Host, $parts.Port, $parts.User, $parts.Database, $BackupFile)
        Write-Host "    请手动执行上述命令，确认前不会自动恢复"
    }
}

Write-Host ""
Write-Host "===== 备份报告 ====="
Write-Host "模式:        $Mode"
Write-Host "输出目录:    $OutputDir"
Write-Host "保留天数:    $RetentionDays"
Write-Host "现有备份数:  $(@(Get-ChildItem -Path $OutputDir -Filter "aicabinet-full-*.sql.gz" 2>$null).Count)"
$totalSize = (Get-ChildItem -Path $OutputDir -Filter "*.sql.gz" 2>$null | Measure-Object -Property Length -Sum).Sum
if ($totalSize) {
    Write-Host "总备份大小:  $( [math]::Round($totalSize / 1GB, 2) ) GB"
}
Write-Host "==================="
Write-Host ""
Write-Host "建议 cron 调度 (Linux):"
Write-Host "  0 3 * * * $PSScriptRoot/backup-database.ps1 -Mode full -OutputDir /backups/aicabinet"
Write-Host ""
Write-Host "建议 Windows Task Scheduler:"
Write-Host "  powershell.exe -File $PSScriptRoot\backup-database.ps1 -Mode full"
Write-Host ""
Write-Host "PITR 配置详见: https://www.postgresql.org/docs/16/continuous-archiving.html"
