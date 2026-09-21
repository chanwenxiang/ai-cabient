# G9 验证：编译 + 只跑 PayScoreContractCancelTest（-am 带上 common-core）
# 判据：退出码 + surefire 报告里 <testcase> 元素个数（不看 "Tests run:" 行）
$ErrorActionPreference = 'Continue'
$repo = 'C:\Users\cwx\OneDrive\Desktop\demo\ai-cabinet'
Set-Location $repo
$mvn = 'D:\devTools\apache-maven-3.9.11\bin\mvn.cmd'
$log = Join-Path $repo '.tmp\g9-verify.log'
$exitFile = Join-Path $repo '.tmp\g9-verify.exit'
$casesFile = Join-Path $repo '.tmp\g9-verify-cases.txt'

# ① 先删掉陈旧报告，否则「产物存在」会变成假判据
$reportDir = Join-Path $repo 'services\trade-service\target\surefire-reports'
$classesDir = Join-Path $repo 'services\trade-service\target\classes'
Remove-Item $reportDir -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $classesDir -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $exitFile -Force -ErrorAction SilentlyContinue
Remove-Item $casesFile -Force -ErrorAction SilentlyContinue

$mvnArgs = @(
  '-B',
  '-pl', 'services/trade-service',
  '-am',
  'clean', 'test',
  '-Dtest=PayScoreContractCancelTest',
  '-Dsurefire.failIfNoSpecifiedTests=false'
)

$out = & $mvn @mvnArgs 2>&1 | Out-String
$out | Out-File -FilePath $log -Encoding utf8
$code = $LASTEXITCODE
"EXITCODE=$code" | Out-File -FilePath $exitFile -Encoding ASCII

$lines = @()
if (Test-Path $reportDir) {
  Get-ChildItem $reportDir -Filter 'TEST-*.xml' | ForEach-Object {
    $n = (Select-String -Path $_.FullName -Pattern '<testcase ' -AllMatches).Matches.Count
    $lines += "$($_.Name) testcase=$n"
  }
} else {
  $lines += "surefire-reports DIR MISSING (no tests executed)"
}
$cls = Join-Path $repo 'services\trade-service\target\classes\com\aicabinet\trade\service\PayScoreService$ContractCancelResult.class'
if (Test-Path $cls) { $lines += 'ContractCancelResult.class EXISTS' } else { $lines += 'ContractCancelResult.class MISSING' }
$lines | Out-File -FilePath $casesFile -Encoding utf8
