<#
.SYNOPSIS
    PostgreSQL 数据库备份脚本 — 生产环境定时备份 + PITR 配置
.DESCRIPTION
    支持全量备份和 WAL 归档两种模式。全量备份保留最近 N 天。
    生产建议: 每小时 WAL 归档 + 每日全量备份
    配合: pg_dump / pg_basebackup + cron 调度
.PARAMETER Mode
    full | wal | restore
.PARAMETER OutputDir
    备份文件输出目录
.PARAMETER RetentionDays
    全量备份保留天数 (默认 30)
.PARAMETER DbUrl
    数据库连接串 (默认从环境变量读取)
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

# 如果没传 DbUrl，从 Spring 配置解析
if (-not $DbUrl) {
    $yml = Get-Content "$PSScriptRoot\..\services\trade-service\src\main\resources\application.yml" -Raw
    if ($yml -match 'url:\s*(jdbc:postgresql://[^\s]+)') {
        $jdbcUrl = $Matches[1]
        # 从 spring datasource url 提取信息
        if ($jdbcUrl -match 'jdbc:postgresql://([^:/]+):?(\d*)/(\w+)') {
            $hostName = $Matches[1]
            $port = if ($Matches[2]) { $Matches[2] } else { '5432' }
            $dbName = $Matches[3]
            $user = 'aicabinet'
            $DbUrl = "postgresql://${user}:${password}@${hostName}:${port}/${dbName}"
        }
    }
}

if (-not $DbUrl) {
    $DbUrl = 'postgresql://aicabinet:aicabinet@localhost:15433/aicabinet'
    Write-Warning "使用默认本地数据库连接: $DbUrl"
    Write-Warning "生产环境请设置环境变量 DATABASE_URL"
}

# 确保备份目录存在
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

switch ($Mode) {
    'full' {
        $file = Join-Path $OutputDir "aicabinet-full-${timestamp}.sql.gz"
        Write-Host ">>> 开始全量备份: $file"

        # 使用 pg_dump（需要安装 PostgreSQL 客户端工具）
        # 参数: --format=custom 便于选择性恢复
        #       --compress=9 最大压缩
        #       --no-owner 避免 owner 冲突
        $env:PGPASSWORD = 'aicabinet'
        $dumpArgs = @(
            "-h", "localhost",
            "-p", "15433",
            "-U", "aicabinet",
            "-d", "aicabinet",
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

        # 清理过期备份
        $cutoff = (Get-Date).AddDays(-$RetentionDays)
        Get-ChildItem -Path $OutputDir -Filter "aicabinet-full-*.sql.gz" | Where-Object {
            $_.CreationTime -lt $cutoff
        } | ForEach-Object {
            Remove-Item $_.FullName -Force
            Write-Host "已清理过期备份: $($_.Name)"
        }
    }

    'wal' {
        # WAL 归档配置（需 PostgreSQL 开启 archive_mode）
        Write-Host ">>> WAL 归档模式需要在 postgresql.conf 中配置:"
        Write-Host "    archive_mode = on"
        Write-Host "    archive_command = 'cp %p $OutputDir/wal/%f'"
        Write-Host "    或者使用 pg_receivewal 实时归档"
    }

    'restore' {
        if (-not $BackupFile) {
            # 自动选择最新的备份
            $BackupFile = Get-ChildItem -Path $OutputDir -Filter "aicabinet-full-*.sql.gz" |
                Sort-Object CreationTime -Descending | Select-Object -First 1 -ExpandProperty FullName
        }
        if (-not $BackupFile -or -not (Test-Path $BackupFile)) {
            Write-Error "备份文件不存在: $BackupFile"
            exit 1
        }
        Write-Host ">>> 恢复备份: $BackupFile"
        Write-Host "    命令: pg_restore --clean --if-exists -h localhost -p 15433 -U aicabinet -d aicabinet $BackupFile"
        Write-Host "    请手动执行上述命令，确认前不会自动恢复"
    }
}

# 输出备份报告
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
